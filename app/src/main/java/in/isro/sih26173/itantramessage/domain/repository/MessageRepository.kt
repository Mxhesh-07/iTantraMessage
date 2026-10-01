package `in`.isro.sih26173.itantramessage.domain.repository

import android.util.Log
import `in`.isro.sih26173.itantramessage.data.crypto.EncryptionManager
import `in`.isro.sih26173.itantramessage.data.database.DeliveryStatus
import `in`.isro.sih26173.itantramessage.data.database.MessageDao
import `in`.isro.sih26173.itantramessage.data.database.MessageEntity
import `in`.isro.sih26173.itantramessage.data.nearby.ByteLink
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyError
import `in`.isro.sih26173.itantramessage.data.nearby.Reassembler
import `in`.isro.sih26173.itantramessage.data.device.DeviceIdentity
import `in`.isro.sih26173.itantramessage.data.device.PeerIdentity
import `in`.isro.sih26173.itantramessage.domain.model.ConversationId
import `in`.isro.sih26173.itantramessage.domain.model.Envelope
import `in`.isro.sih26173.itantramessage.domain.model.EnvelopeCodec
import `in`.isro.sih26173.itantramessage.domain.model.MessageType
import `in`.isro.sih26173.itantramessage.domain.model.PeerHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The single place a message is composed, stored, sent, or accepted.
 *
 * ## Layering
 *
 * Everything above this class is UI and knows nothing about transports. Everything below
 * it knows nothing about UI. That is the entire point of the repository, and it is why
 * [attach] takes a [ByteLink] rather than a transport enum: the retry logic, the
 * encryption, the deduplication and the state machine are identical whichever radio
 * carried the bytes, so there is no reason for any of them to branch on transport.
 *
 * ## The queue is the source of truth for delivery
 *
 * A message is [PENDING] the moment the user taps Send, and it becomes [SENT] only when
 * a transport accepts the bytes. Nothing is held in memory waiting for a connection: the
 * database row *is* the queue. That is what makes the app survive a process death
 * mid-conversation, and it is why [observeConversation] shows a queued message as a real
 * message with a "Queued" chip rather than as a spinner that disappears on restart.
 */
class MessageRepository(
    private val dao: MessageDao,
    private val identity: DeviceIdentity,
    private val crypto: EncryptionManager,
    private val scope: CoroutineScope,
    private val peers: PeerIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * The current peer's device id, or null until it has identified itself.
     *
     * Exists because the conversation key cannot be built before this is known, and building
     * it from the MAC address instead produces a key that matches no row on either side --
     * messages that send successfully and then appear to vanish. See [PeerIdentity].
     *
     * Reset on [attach] rather than retained: the id belongs to the link that announced it,
     * and a new link is a new peer until it says otherwise.
     */
    private val _peerDeviceId = MutableStateFlow<String?>(null)
    val peerDeviceId: StateFlow<String?> = _peerDeviceId.asStateFlow()
    /**
     * The current link, or null when disconnected.
     *
     * Guarded by [linkMutex] because [attach], [detach] and the retry loop all touch it
     * from different coroutines. A plain `var` here would let the retry loop write to a
     * link the user has already disconnected from, which fails silently and leaves a
     * message stuck in SENDING until the next retry.
     */
    @Volatile private var link: ByteLink? = null
    private val linkMutex = Mutex()

    private var pumpJob: Job? = null
    private var receiveJob: Job? = null

    /**
     * The reassembler for the current link.
     *
     * Per-link, not a field reused across links: a partially received frame from a
     * dropped connection would otherwise be prepended to the first frame of the next one,
     * producing a corrupt message whose cause is untraceable.
     */
    private var reassembler = Reassembler()

    // ---- reads -----------------------------------------------------------------------

    fun observeConversation(conversationId: String, limit: Int = 200): Flow<List<MessageEntity>> =
        dao.observeConversation(conversationId, limit)

    fun observeQueue(): Flow<List<MessageEntity>> = dao.observeQueue()

    fun observeMessageCount(): Flow<Int> = dao.observeMessageCount()

    fun observePayloadBytes(): Flow<Long> = dao.observePayloadBytes().map { it ?: 0L }

    /**
     * Decrypt a stored message for display.
     *
     * Returns null when the row is unreadable, which is a real state and not an error: the
     * key can be gone while the rows remain, if the user cleared the Keystore entry. The UI
     * shows "Could not read that message" rather than an empty bubble, because an empty
     * bubble looks like the message was never sent.
     */
    suspend fun plaintextOf(entity: MessageEntity): String? {
        val stored = entity.ciphertext ?: return null
        // Locally composed messages are stored already encrypted, so they take the same
        // path as received ones. There is no plaintext branch here, and that is
        // deliberate: a second path is a second way to leak.
        return crypto.decryptToText(stored)
    }

    // ---- sending ---------------------------------------------------------------------

    /**
     * Store a message the user just composed, and try to send it.
     *
     * @return the stored row's message id, or `null` if the text was empty.
     *
     * Stored before any network or radio work is attempted, so a crash between the tap
     * and the send cannot lose the message. This ordering is the whole offline-first
     * guarantee: local persistence first, delivery second, best-effort.
     */
    suspend fun send(peerId: String, text: String): String? {
        val body = text.trim()
        if (body.isEmpty()) return null

        val messageId = UUID.randomUUID().toString()
        val conversationId = ConversationId.of(identity.id, peerId).value
        val now = clock()

        val entity = MessageEntity(
            messageId = messageId,
            conversationId = conversationId,
            senderId = identity.id,
            receiverId = peerId,
            ciphertext = crypto.encrypt(body),
            timestamp = now,
            messageType = MessageType.TEXT.name,
            status = DeliveryStatus.PENDING.name,
        )

        dao.insert(entity)

        // A pump now rather than a direct send: if there is no link, the row stays
        // PENDING and the next attach or retry cycle picks it up. The alternative, an
        // `if (link != null) send()`, would silently drop the message.
        requestPump()
        return messageId
    }

    /**
     * Attach a link and start sending and receiving on it.
     *
     * Called after a successful connect. Replaces any previous link, closing it first --
     * switching conversations must not leave the old socket open, because two open RFCOMM
     * sockets to the same radio is exactly the situation Android will refuse to schedule.
     */
    suspend fun attach(newLink: ByteLink) {
        linkMutex.withLock {
            link?.close()
            reassembler = Reassembler()
            link = newLink
            _peerDeviceId.value = null
        }

        startReceiving(newLink)
        announceSelf(newLink)
        requestPump()
    }

    /**
     * Tell the peer who we are.
     *
     * Sent on every attach, before any queued message is pumped. That order is load-bearing:
     * the peer cannot build a conversation key for an incoming message until it knows our id,
     * so a message arriving before our hello would be filed under a key the sender never uses
     * -- a message the user sent and can never see again.
     *
     * A refused write is logged and otherwise ignored. The peer may already know our id from
     * an earlier session, in which case this frame is redundant, and there is nothing to
     * recover here: [requestPump] runs again on the next cycle.
     */
    private suspend fun announceSelf(link: ByteLink) {
        val frame = Reassembler.frame(PeerHello.encode(identity.id))
        if (!link.send(frame)) {
            Log.w(TAG, "could not send identification on the new link")
        }
    }

    /** Detach and stop all link activity. Safe when nothing is attached. */
    suspend fun detach() {
        val old = linkMutex.withLock {
            val current = link
            link = null
            current
        }
        old?.close()
        _peerDeviceId.value = null
        receiveJob?.cancel()
        receiveJob = null
        pumpJob?.cancel()
        pumpJob = null
    }

    // ---- receiving -------------------------------------------------------------------

    /**
     * Read from [source] until it closes.
     *
     * The only place bytes become messages. It is deliberately strict: a frame that does
     * not decode, or that is not addressed to this device, or that is a duplicate, is
     * counted and dropped. None of those are fatal, because all of them are things a peer
     * on the same radio can send, and a peer that can crash this app is a denial of
     * service by anyone in range.
     */
    private fun startReceiving(source: ByteLink) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                source.incoming.collect { chunk ->
                    for (frame in reassembler.feed(chunk)) {
                        acceptFrame(frame)
                    }
                }
            } catch (t: Throwable) {
                // A closed channel is the normal end of a link, not a failure to report.
                if (t !is ClosedReceiveChannelException) {
                    Log.w(TAG, "receive loop ended: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Record the peer's announced device id against the address it arrived on.
     *
     * Both halves are needed and either can be missing. Without an announced id there is
     * nothing to remember; without a peer address there is no key to remember it under,
     * because Android hides the local adapter address from Android 12 and the address is the
     * only handle the two sides share at this point.
     *
     * In the second case the id is published on [peerDeviceId] but not persisted, so the
     * current conversation still works and the next session simply waits for a fresh
     * handshake. Persisting it under a placeholder would risk writing the peer's id under
     * *our own* address, which would make two different peers collide on one key.
     */
    private fun handleHello(frame: ByteArray) {
        val announced = PeerHello.decode(frame)
        if (announced == null) {
            Log.w(TAG, "ignored handshake with an unusable device id")
            return
        }

        val address = link?.peerAddress
        if (address == null) {
            Log.w(TAG, "handshake from $announced not persisted: peer address unavailable")
        } else {
            peers.remember(address, announced)
        }
        _peerDeviceId.value = announced
    }

    /**
     * Handle one complete frame.
     *
     * Split out from the collect loop so it can be tested directly against hostile input.
     */
    private suspend fun acceptFrame(frame: ByteArray) {
        // The handshake is checked first, before any envelope decode, because it is the only
        // frame that is not a message and it must never reach the message path -- an
        // unencrypted frame written to the database would defeat the storage-at-rest claim.
        // `PeerHello.isHello` keys on a byte the envelope magic cannot produce, so this
        // cannot shadow a real message.
        if (PeerHello.isHello(frame)) {
            handleHello(frame)
            return
        }

        val envelope = EnvelopeCodec.decode(frame)
        if (envelope == null) {
            Log.w(TAG, "dropped undecodable frame (${frame.size} B)")
            return
        }

        // Addressed to someone else. Not an error: both phones may be relaying, or a
        // message may arrive after a rotation. Storing it would put another device's
        // message in this user's history.
        if (envelope.receiverId != identity.id) {
            Log.d(TAG, "dropped frame not addressed to this device")
            return
        }

        // Deduplication by sender-assigned id. Checked before decryption because the
        // database constraint is what actually enforces it, and inserting first would
        // throw on a duplicate.
        if (dao.exists(envelope.messageId)) {
            Log.d(TAG, "dropped duplicate ${envelope.messageId}")
            return
        }

        val plaintext = crypto.decryptToText(envelope.payload)
        if (plaintext == null) {
            // Undecryptable: wrong key generation, tampered, or a peer running a different
            // scheme. Recorded as FAILED rather than dropped silently, so the sender's retry
            // does not loop forever against a message that can never be read.
            dao.insert(
                MessageEntity(
                    messageId = envelope.messageId,
                    conversationId = ConversationId.of(envelope.senderId, identity.id).value,
                    senderId = envelope.senderId,
                    receiverId = envelope.receiverId,
                    ciphertext = envelope.payload,
                    timestamp = envelope.timestamp,
                    messageType = envelope.type.name,
                    status = DeliveryStatus.FAILED.name,
                    lastError = "could not decrypt",
                ),
            )
            return
        }

        val inserted = dao.insertIgnoringDuplicates(
            MessageEntity(
                messageId = envelope.messageId,
                conversationId = ConversationId.of(envelope.senderId, identity.id).value,
                senderId = envelope.senderId,
                receiverId = identity.id,
                // Stored re-encrypted under this device's own key, not verbatim from the
                // wire. The wire format carries the sender's generation, which this device
                // cannot decrypt later once that generation is pruned.
                ciphertext = crypto.encrypt(plaintext),
                timestamp = envelope.timestamp,
                messageType = envelope.type.name,
                status = DeliveryStatus.DELIVERED.name,
            ),
        )

        if (inserted == -1L) {
            Log.d(TAG, "duplicate rejected by unique index")
        }
    }

    // ---- the retry pump ---------------------------------------------------------------

    /**
     * Ask the pump to run.
     *
     * Coalesced: many rapid sends, or a queue draining, produce one pump. The alternative
     * -- a coroutine per message -- would have several coroutines racing to send the same
     * oldest message, and the second write would duplicate it at the peer.
     */
    private fun requestPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch { pump() }
    }

    /**
     * Drain the queue.
     *
     * The loop shape is: take the oldest pending row, try once, and if it failed wait a
     * backoff before the next attempt. It never retries a message forever -- the retry
     * budget in the row is the stopping condition, which is what keeps an unreachable
     * peer from becoming a permanent battery drain.
     */
    private suspend fun pump() {
        while (coroutineContextIsActive()) {
            val current = link ?: return // Nothing to send on; the next attach() pumps.
            val next = dao.pendingForDelivery().firstOrNull() ?: return // Queue is empty.

            val sent = deliver(current, next)
            if (sent) {
                transition(next, DeliveryStatus.PENDING, DeliveryStatus.SENT)
                continue // Try the next message immediately.
            }

            // A failure. Count it, and if the budget is gone give up on this row.
            dao.recordFailure(next.messageId, DeliveryStatus.FAILED.name, "send rejected")
            if (next.retryCount + 1 >= MAX_RETRIES) {
                Log.w(TAG, "message ${next.messageId} exhausted $MAX_RETRIES retries")
                continue
            }

            // Backoff before trying again. Without this, a peer that is in range but not
            // accepting writes would be hammered at the retry rate for as long as the app
            // is open.
            delay(backoffMs(next.retryCount))
        }
    }

    /**
     * Write one message to [link].
     *
     * @return whether the transport accepted the bytes. `false` covers both "no link" and
     *   "the write was rejected"; the two are indistinguishable from here and are treated
     *   the same way, because the action is identical: retry later.
     */
    private suspend fun deliver(link: ByteLink, entity: MessageEntity): Boolean {
        val envelope = Envelope(
            messageId = entity.messageId,
            senderId = entity.senderId,
            receiverId = entity.receiverId,
            timestamp = entity.timestamp,
            type = MessageType.entries.firstOrNull { it.name == entity.messageType }
                ?: return false,
            payload = entity.ciphertext ?: return false,
        )

        val bytes = EnvelopeCodec.encode(envelope)
        if (bytes.size > MAX_WIRE_BYTES) {
            Log.w(TAG, "message ${entity.messageId} is ${bytes.size} B, over the limit")
            return false
        }

        transition(entity, DeliveryStatus.PENDING, DeliveryStatus.SENDING)
        return link.send(Reassembler.frame(bytes))
    }

    /**
     * Move a message to a new state, checking the transition is legal first.
     *
     * The DAO's WHERE clause is what makes this safe under concurrency; the check here is
     * for a clear log line when a caller asks for something the state machine forbids.
     */
    private suspend fun transition(
        entity: MessageEntity,
        from: DeliveryStatus,
        to: DeliveryStatus,
    ) {
        val current = DeliveryStatus.entries.firstOrNull { it.name == entity.status } ?: return
        if (!current.canTransitionTo(to)) {
            Log.w(TAG, "refused ${entity.status} -> $to for ${entity.messageId}")
            return
        }
        dao.transition(entity.messageId, from.name, to.name)
    }

    /**
     * Retry delay for attempt [retryCount].
     *
     * Exponential, capped. The cap matters: unbounded backoff would mean a message that
     * fails once an hour is effectively never delivered, and the user has no way to know
     * whether it is still trying.
     */
    private fun backoffMs(retryCount: Int): Long =
        (BASE_BACKOFF_MS shl retryCount.coerceAtMost(BACKOFF_SHIFT_CAP))
            .coerceAtMost(MAX_BACKOFF_MS)

    private fun coroutineContextIsActive(): Boolean = scope.isActive

    companion object {
        private const val TAG = "MessageRepository"

        /**
         * Total send attempts before a message is marked FAILED.
         *
         * Three, not thirty. A nearby peer that has not appeared within roughly half a
         * minute is not coming back for this message, and a queue that keeps trying is the
         * difference between an app that respects its battery budget and one that does not.
         * The user can still re-send manually; the app does not decide to do it for them.
         */
        const val MAX_RETRIES = 3

        /**
         * The wire size ceiling, checked before writing.
         *
         * Matches [EnvelopeCodec]'s own limit. Both exist because they guard different
         * sides: the codec guards against a hostile *received* length, this guards against
         * sending something absurd.
         */
        const val MAX_WIRE_BYTES = 64 * 1024

        private const val BASE_BACKOFF_MS = 500L
        private const val BACKOFF_SHIFT_CAP = 4 // 500ms << 4 = 8s
        private const val MAX_BACKOFF_MS = 8_000L
    }
}
