package `in`.isro.sih26173.itantramessage.domain.repository

import android.util.Log
import `in`.isro.sih26173.itantramessage.data.crypto.EncryptionManager
import `in`.isro.sih26173.itantramessage.data.crypto.Hex
import `in`.isro.sih26173.itantramessage.data.crypto.IdentityKey
import `in`.isro.sih26173.itantramessage.data.crypto.SessionCrypto
import `in`.isro.sih26173.itantramessage.data.crypto.SessionKeys
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
import `in`.isro.sih26173.itantramessage.domain.model.PeerSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * ## Two layers of encryption, and why both exist
 *
 * A message body is encrypted twice, with two different keys for two different adversaries,
 * and mixing them up was the defect that made two phones unable to read each other at all.
 *
 *  * **[crypto]** -- this device's own Keystore key, generated locally. Protects the
 *    database at rest. Correct on its own terms: nobody else should read this phone's disk.
 *    It is the only key that can ever decrypt a *stored* row.
 *  * **[session]** -- a key agreed with the peer during the handshake, by RSA key transport.
     *   Protects
 *    the bytes on the wire, where the adversary is whoever is listening on the radio.
 *
 * The original code used [crypto] for both. That made a phone perfectly able to read its own
 * messages and completely unable to read anyone else's, because the sender encrypted with the
 * key inside its own secure hardware and the receiver asked its *own* Keystore for *its*
 * different key. On hardware the symptom was a message that arrived and rendered as "Could
 * not read that message" -- see `docs/TESTING.md`.
 *
 * So the wire is encrypted with the session key and the stored row is encrypted with the local
 * one, and there is no path anywhere that confuses them.
 */
class MessageRepository(
    private val dao: MessageDao,
    private val identity: DeviceIdentity,
    private val crypto: EncryptionManager,
    private val identityKeys: IdentityKey,
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
     * The key agreed with [peerDeviceId], or null when there is no keyed peer.
     *
     * Volatile rather than held under [linkLock]: it is written by the receive loop and read
     * by the pump, which is a memory-visibility question and not a mutual-exclusion one.
     * Taking the mutex would also mean holding it across a Keystore operation, and [attach]
     * needs that lock.
     *
     * Invariant, and the reason [peerDeviceId] needs no separate "is keyed" flag: this is
     * non-null **if and only if** [_peerDeviceId] is non-null. Both are set in [handleHello]
     * and cleared together in [attach] and [detach]. The UI gates on [peerDeviceId] alone,
     * which is safe only because of that pairing.
     */
    @Volatile private var session: SessionCrypto? = null

    /**
     * This side's random contribution to the session key, for the link currently attached.
     *
     * Held for the life of the link because the handshake needs it twice: once to wrap it to
     * the peer's public key, and once to combine it with the peer's contribution when that
     * arrives. Wiped whenever the link is replaced or dropped -- see [clearHandshake].
     *
     * @Volatile because [tryAgree] reads it from the frame handler and [attach] clears it.
     */
    @Volatile private var ourContribution: ByteArray? = null

    /** The peer's public key, from its hello. Null until a usable hello has been decoded. */
    @Volatile private var peerPublicKeyHex: String? = null

    /** The peer's contribution, unwrapped from its secret frame. Null until one is received. */
    @Volatile private var theirContribution: ByteArray? = null

    /**
     * Whether this side has already sent its wrapped contribution on this link.
     *
     * A hello arrives once per link, but nothing stops a peer sending a second. Without this
     * flag a second hello would wrap and send a second contribution, and the two sides would
     * then disagree about which of them counted -- an intermittent "works until the peer
     * re-identifies" failure, which is the hardest kind to attribute.
     */
    @Volatile private var contributionSent: Boolean = false

    /**
     * The agreed key for [conversationId], or null if there is none.
     *
     * Scoped rather than returned unconditionally because a session key for one conversation
     * must never encrypt for another: the messages would be written correctly, transmitted
     * correctly, and be unreadable at the far end with no clue why. Comparing the conversation
     * the caller wants against the conversation the handshake actually established is the
     * cheapest place to catch a mismatch.
     */
    private fun sessionFor(conversationId: String): SessionCrypto? {
        val peer = _peerDeviceId.value ?: return null
        val established = ConversationId.of(identity.id, peer).value
        return if (established == conversationId) session else null
    }

    /**
     * One live socket, with the receive machinery that belongs to it.
     *
     * Per-link rather than fields on the repository, and that is the fix for a defect this was
     * rewritten for. The receive loop, the reassembler and the link itself used to be single
     * instance fields, so a second connection replaced them and the first connection's partially
     * received frame could be prepended to the second's first frame.
 *
     * [open] is read by the send path to avoid writing to a socket whose far end is gone, and
 * cleared by the receive loop when it ends, so a dead link leaves the candidate list by itself
 * rather than by a timer or by being written to and failing.
 */
private class LiveLink(val id: Int, val link: ByteLink) {

    /**
     * Per link, not shared: a partially received frame from a dropped connection would
     * otherwise be prepended to the first frame of the next one, producing a corrupt message
     * whose cause is untraceable.
     */
    var reassembler = Reassembler()

    var job: Job? = null

    @Volatile var open = true
}

/**
     * The current link, or null when disconnected.
     *
     * Guarded by [linkLock] because [attach], [detach] and the receive loops all touch it from
     * different coroutines. A plain `var` here would let the retry loop write to a link the user
     * has already disconnected from, which fails silently and leaves a message stuck in SENDING
     * until the next retry.
     */
    @Volatile private var primary: LiveLink? = null

    /**
     * Superseded links that are still usable.
     *
     * This list exists because of a measured failure on two real handsets. Both phones run a
     * server *and* can dial, so if both are connected at the same time there are two sockets:
     * one the user opened, one the other phone opened. `attach` used to close the link it
     * replaced, and because each phone replaced the link the *other* one was using, both ended
     * up holding a socket whose far end the peer had already closed. Both phones showed a
     * connected peer with an agreed key, and neither could send: the symptom the user reported
     * as "still can't text".
     *
     * So a new link no longer supersedes a live one. Both are kept, each with its own receive
     * loop, and [deliverOnAnyLink] tries them in order. Two sockets cost more than one; the
     * alternative cost a working app.
     *
     * Bounded by [MAX_KEPT_LINKS], because this is a list that grows with however many times
     * the peer re-dials, and an unbounded list is a socket leak with extra steps.
     */
    private val kept = mutableListOf<LiveLink>()

    /**
     * Guards [primary] and [kept].
     *
     * A plain monitor rather than the coroutine [Mutex] this used to use. The work inside is
     * list bookkeeping and no suspension, and the receive loop has to be able to drop its own
     * link from a `finally` block without suspending -- which a Mutex cannot do without
     * `tryLock` and a second code path. A monitor is the simpler correct choice here.
     */
    private val linkLock = Any()

    private var nextLinkId = 0

    private var pumpJob: Job? = null

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
        var live: LiveLink
        var isPrimary: Boolean

        synchronized(linkLock) {
            live = LiveLink(nextLinkId++, newLink)
            val current = primary

            if (current == null || !current.open) {
                // Nothing usable to keep, so this link becomes the one everything keys off. The
                // old primary, if any, was already dead, so it is retired rather than closed --
                // its socket is gone and closing it again would be noise at best.
                if (current != null) retireLocked(current)
                kept.clear()
                primary = live
                isPrimary = true
                // Every handshake value belongs to the peer that announced it, and a new
                // primary is an unidentified peer until its hello arrives.
                clearHandshake()
            } else {
                // A second link to the same bonded peer, which is what happens when both phones
                // are connected at once. The existing primary is deliberately left alone: it is
                // the one the peer is more likely to still have open, and replacing it is the
                // defect described on [kept].
                if (kept.size >= MAX_KEPT_LINKS) retireLocked(kept.removeAt(0))
                kept.add(live)
                isPrimary = false
                Log.i(
                    TAG,
                    "link ${live.id} is additional; keeping link ${current.id} as the primary",
                )
            }
        }

        startReceiving(live)

        if (isPrimary) {
            announceSelf(live.link)
            requestPump()
        } else {
            // Deliberately not announcing on an additional link. The peer is already keyed from
            // the primary, and a second HELLO would restart the handshake and derive a *second*
            // session key -- which is exactly what happened on the hardware run that motivated
            // this change. The session key belongs to the conversation, not to the socket, so a
            // message sent here is readable by a peer already keyed from the other one.
            Log.d(TAG, "not re-announcing on additional link ${live.id}")
        }
    }

    /**
     * Live links to try, best first.
     *
     * A snapshot rather than the list itself: the caller iterates outside the monitor, and
     * [dropLink] removes entries from the receive loop's own coroutine.
     */
    private fun liveLinks(): List<LiveLink> = synchronized(linkLock) {
        buildList {
            primary?.let { if (it.open) add(it) }
            for (live in kept) if (live.open) add(live)
        }
    }

    /**
     * Take a link out of service: stop its receive loop and close the socket.
     *
     * Callers must hold [linkLock]. Marking it closed *first* means a concurrent
     * [deliverOnAnyLink] snapshot cannot pick it up between the two steps.
     */
    private fun retireLocked(live: LiveLink) {
        live.open = false
        live.job?.cancel()
        live.job = null
        runCatching { live.link.close() }
    }

    /**
     * A link's receive loop ended. Remove it, and let the send path stop offering it.
     *
     * This is what keeps a dead socket from being written to: without it, the dead link stays at
     * the front of [liveLinks] and every send fails on it until the user reconnects.
     */
    private fun dropLink(live: LiveLink) {
        val wasPrimary = synchronized(linkLock) {
            val was = primary === live
            if (was) primary = null
            kept.remove(live)
            was
        }
        live.open = false
        Log.i(
            TAG,
            if (wasPrimary) "link ${live.id} closed; no primary link remains"
            else "link ${live.id} closed",
        )
        // The next attach pumps, and a link that is still alive in [kept] can carry the queue
        // in the meantime, so the pump is asked to run rather than being left idle.
        if (liveLinks().isNotEmpty()) requestPump()
    }

    /**
     * Drop everything the handshake accumulated, wiping the bytes that are key material.
     *
     * Called when a new primary link appears, so it is not always under [linkLock] -- the
     * handshake fields are all @Volatile and none of them is a link.
     *
     * The wipe is the point of the method rather than a nicety. [ourContribution] and
     * [theirContribution] are the two halves of the session key input; leaving them on a
     * `ByteArray` that the collector will deal with eventually is the "do not keep the secret"
     * instruction that was not actually followed.
     */
    private fun clearHandshake() {
        _peerDeviceId.value = null
        session = null
        peerPublicKeyHex = null
        contributionSent = false
        ourContribution?.fill(0)
        ourContribution = null
        theirContribution?.fill(0)
        theirContribution = null
    }

    /**
     * Tell the peer who we are, and give it the public half of our identity key.
     *
     * Sent on every attach, before any queued message is pumped. That order is load-bearing:
     * the peer cannot build a conversation key for an incoming message until it knows our id
     * *and* our public key, so a message arriving before our hello would be a message the peer
     * cannot decrypt -- one the user sent and neither phone can ever read.
     *
     * This is frame one of four. Frame two, the wrapped contribution, is sent by
     * [sendContribution] once the peer's hello has arrived -- it cannot be sent sooner,
     * because it is encrypted to the peer's key.
     *
     * A refused write is logged and otherwise ignored. The peer may already know our id from
     * an earlier session, in which case this frame is redundant, and there is nothing to
     * recover here: [requestPump] runs again on the next cycle.
     */
    private suspend fun announceSelf(link: ByteLink) {
        val frame = withContext(Dispatchers.IO) {
            // On Dispatchers.IO because both of these are blocking Keystore calls and this
            // runs on the view model's Main dispatcher. Creating an RSA key pair can take
            // hundreds of milliseconds on a phone whose keystore is backed by a TEE, which on
            // the main thread is an ANR the user sees as the app hanging on tap.
            val publicKey = if (identityKeys.ensureKeyPair()) identityKeys.publicKeyHex() else null
            if (publicKey == null) {
                null
            } else {
                // runCatching because encode validates both fields, and this is the one place
                // where a throw would become a crash on a connect tap rather than a log line.
                runCatching { Reassembler.frame(PeerHello.encode(identity.id, publicKey)) }
                    .getOrNull()
            }
        }

        if (frame == null) {
            // Sent nothing rather than something unusable. A hello with no key would be
            // rejected by the peer's parser anyway, and "connected but cannot identify" is a
            // far more honest state to be in than a handshake that fails at the far end.
            Log.w(TAG, "no identity key available: cannot identify to the peer")
            return
        }

        if (!link.send(frame)) {
            Log.w(TAG, "could not send identification on the new link")
        }
    }

    /**
     * Send this side's contribution, wrapped to the peer's public key.
     *
     * Called when the peer's hello arrives, which is the earliest moment a contribution can
     * exist. Sent at most once per link; see [contributionSent].
     *
     * The contribution is generated here rather than at attach because a link that never
     * completes a handshake should not leave key material lying around, and because generating
     * it needs the Keystore, which is not free.
     */
    private suspend fun sendContribution(link: ByteLink, peerPublicKeyHex: String) {
        if (contributionSent) return

        val frame = withContext(Dispatchers.IO) {
            if (!identityKeys.ensureKeyPair()) return@withContext null
            val contribution = identityKeys.newContribution() ?: return@withContext null
            val wrapped = identityKeys.wrap(contribution, peerPublicKeyHex)
            if (wrapped == null) {
                contribution.fill(0)
                return@withContext null
            }
            val encoded = runCatching { Reassembler.frame(PeerSecret.encode(identity.id, wrapped)) }
                .getOrNull()
            // The ciphertext is not secret -- it is public by construction, and the peer needs
            // it -- but there is no reason to keep a second copy of it after the write.
            wrapped.fill(0)
            // Published only once the frame is ready to send, so a peer that sends its hello
            // twice cannot make this side send two different contributions.
            ourContribution = contribution
            encoded
        }

        if (frame == null) {
            Log.w(TAG, "could not wrap a contribution for the peer")
            return
        }

        contributionSent = true
        if (!link.send(frame)) {
            Log.w(TAG, "could not send our contribution on the link")
        }
    }

    /** Detach and stop all link activity. Safe when nothing is attached. */
    suspend fun detach() {
        synchronized(linkLock) {
            primary?.let { retireLocked(it) }
            primary = null
            kept.forEach { retireLocked(it) }
            kept.clear()
        }
        // Outside the lock deliberately: the handshake fields are all @Volatile and this does
        // not need to be atomic with the link swap, only ordered after it.
        clearHandshake()
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
    private fun startReceiving(live: LiveLink) {
        live.job = scope.launch {
            try {
                live.link.incoming.collect { chunk ->
                    for (frame in live.reassembler.feed(chunk)) {
                        acceptFrame(frame)
                    }
                }
            } catch (t: Throwable) {
                // A closed channel is the normal end of a link, not a failure to report.
                if (t !is ClosedReceiveChannelException) {
                    Log.w(
                        TAG,
                        "receive loop for link ${live.id} ended: ${t.javaClass.simpleName}",
                    )
                }
            } finally {
                // Always, including cancellation, so a link never lingers in the candidate list
                // after its socket is gone.
                dropLink(live)
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
     *
     * ## Agreeing the key is the part that used to be missing
     *
     * Recording the peer's id is not enough to read its messages, and that was the entire
     * defect: the app had no shared secret with anyone, so every message arrived unreadable.
     * The hello now also carries the peer's public key, and this answers it with our
     * contribution wrapped to that key. See [IdentityKey] for why that transport is RSA and not
     * ECDH -- it is a measurement about two specific handsets, not a preference.
     *
     * When agreement fails, the peer is deliberately left unidentified: [_peerDeviceId] stays
     * null so the UI reports an identification failure. Publishing the id anyway would open a
     * chat screen that accepts typing, queues the message, and cannot deliver it -- an
     * application that looks like it is working while being incapable of sending. A visible
     * failure is the honest outcome.
     */
    private suspend fun handleHello(frame: ByteArray) {
        val announced = PeerHello.decode(frame)
        if (announced == null) {
            Log.w(TAG, "ignored handshake with an unusable device id or public key")
            return
        }

        // Already keyed with this peer, so there is nothing to agree and re-agreeing would be
        // actively harmful. Both sides generate a fresh contribution per handshake, so a second
        // HELLO produces a *second* session key: the two phones would then hold different keys
        // and every message would fail to decrypt. That is not hypothetical -- it is what the
        // hardware run showed, where a second link sent a second hello and the fingerprint
        // changed from 5fd094db to b72b72d6 on both phones.
        //
        // The peer re-announcing on a second link is legitimate. Re-keying is not.
        if (session != null && _peerDeviceId.value == announced.deviceId) {
            Log.i(TAG, "already keyed with ${announced.deviceId}; not re-running the handshake")
            requestPump()
            return
        }

        val address = primary?.link?.peerAddress
        if (address == null) {
            Log.w(TAG, "handshake from ${announced.deviceId} not persisted: peer address unavailable")
        } else {
            peers.remember(address, announced.deviceId)
        }

        peerPublicKeyHex = announced.publicKeyHex
        _peerDeviceId.value = announced.deviceId

        // Now that the peer's key is known, our contribution can be encrypted to it. This is
        // the earliest a contribution can exist, which is why the handshake needs a second
        // frame rather than one larger one.
        primary?.link?.let { sendContribution(it, announced.publicKeyHex) }

        // The peer's contribution may already be here, if its secret frame somehow preceded
        // its hello. Checking costs one null comparison and removes the assumption.
        tryAgree()
    }

    /**
     * Handle a wrapped-contribution frame: unwrap it and, if the handshake is otherwise
     * complete, install the session key.
     *
     * ## Why the id in this frame is checked against the peer
     *
     * [PeerSecret.Contribution] names the device that wrapped it, and that name is not
     * decoration. A contribution from a device that is not this link's peer would be mixed into
     * the key material for a conversation it has nothing to do with, producing a key neither
     * phone holds -- and the symptom would be a message that neither end can read. Refusing is
     * cheap; attributing that later is not.
     */
    private fun handleSecret(frame: ByteArray) {
        val received = PeerSecret.decode(frame)
        if (received == null) {
            Log.w(TAG, "ignored a contribution frame that did not decode")
            return
        }

        val peer = _peerDeviceId.value
        if (peer == null) {
            // The contribution overtook its own hello, which an ordered link does not do. Rather
            // than buffer one frame's worth of state for a case that cannot arise, the peer is
            // left unidentified until its hello shows up.
            Log.w(TAG, "contribution from ${received.deviceId} arrived before its identification")
            return
        }
        if (received.deviceId != peer) {
            Log.w(TAG, "refused a contribution from ${received.deviceId} on a link to $peer")
            return
        }

        val wrapped = Hex.decode(received.wrappedHex)
        if (wrapped == null) {
            Log.w(TAG, "contribution from ${received.deviceId} was not valid hex")
            return
        }

        theirContribution?.fill(0)
        // unwrap wipes its argument on every path, so there is no copy of the ciphertext left
        // behind whether this succeeds or not.
        theirContribution = identityKeys.unwrap(wrapped)

        tryAgree()
    }

    /**
     * Derive and install the session key, if both contributions are in hand.
     *
     * Called from both halves of the handshake because either can arrive first: a hello with no
     * contribution yet, then a contribution, or the other way round if the peer dials faster than
     * this side answers. Returning early on a missing half is the whole reason this is a
     * separate function rather than the tail of [handleSecret].
     *
     * Every failure ends the same way -- no session, no peer, one log line. Splitting them in
     * the UI would offer a user a difference between "the other phone cannot do this" and
     * "something went wrong on the radio" that they cannot act on differently.
     */
    private fun tryAgree() {
        val peer = _peerDeviceId.value ?: return
        val ours = ourContribution ?: return
        val theirs = theirContribution ?: return

        val conversationId = ConversationId.of(identity.id, peer).value
        val combined = SessionKeys.combine(identity.id, ours, peer, theirs)
        if (combined == null) {
            Log.w(TAG, "could not combine contributions with $peer")
            failHandshake()
            return
        }

        val key = SessionKeys.derive(combined, conversationId)
        if (key == null) {
            Log.w(TAG, "could not derive a key for conversation $conversationId")
            failHandshake()
            return
        }

        session = SessionCrypto(key)

        // Logged on purpose. The defect this whole area exists to fix was silent: two devices
        // derived two different keys, nothing complained, and the only symptom was a message
        // rendering as "Could not read that message". A one-way fingerprint means the agreement
        // can be confirmed from two logcats without sending a message, and without logging
        // anything a reader could use. Two phones reporting the same value here is the evidence
        // that the keys match.
        Log.i(
            TAG,
            "agreed key for conversation $conversationId " +
                "with $peer, fingerprint " +
                (SessionKeys.fingerprint(key) ?: "unavailable"),
        )

        // The handshake is what makes queued messages sendable, so a newly keyed peer is itself
        // a reason to run the pump. Without this a message composed while the peer was still
        // identifying would sit in the queue until the next retry cycle noticed.
        requestPump()
    }

    /**
     * Abandon the handshake, keeping the link.
     *
     * Contributions are wiped rather than merely dropped: a half-finished key input is key
     * material with no further use on this link. [contributionSent] is left alone on purpose --
     * this side has already put a contribution on the wire and the peer may be combining it as
     * this runs, so the retry comes from a new link rather than from re-sending on this one,
     * which would leave the two sides holding different contributions again.
     */
    private fun failHandshake() {
        session = null
        _peerDeviceId.value = null
        peerPublicKeyHex = null
        ourContribution?.fill(0)
        ourContribution = null
        theirContribution?.fill(0)
        theirContribution = null
    }

    /**
     * Handle one complete frame.
     *
     * Split out from the collect loop so it can be tested directly against hostile input.
     */
    private suspend fun acceptFrame(frame: ByteArray) {
        // The handshake frames are checked first, before any envelope decode, because they are
        // the only frames that are not messages and they must never reach the message path -- an
        // unencrypted frame written to the database would defeat the storage-at-rest claim.
        // `PeerHello.isHello` and `PeerSecret.isSecret` key on bytes the envelope magic cannot
        // produce, so neither can shadow a real message.
        if (PeerHello.isHello(frame)) {
            handleHello(frame)
            return
        }

        if (PeerSecret.isSecret(frame)) {
            handleSecret(frame)
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

        val conversationId = ConversationId.of(envelope.senderId, identity.id).value

        // Checked before decrypting, and the reason is reported separately from a decryption
        // failure. "No key for this sender" and "the ciphertext was bad" look identical in the
        // UI -- both render as an unreadable message -- but they are different bugs, and
        // conflating them is how the missing key agreement stayed invisible for so long.
        val session = sessionFor(conversationId)
        if (session == null) {
            Log.w(TAG, "no agreed key for a frame from ${envelope.senderId}")
            recordUnreadable(envelope, conversationId, "no key for the sender")
            return
        }

        val plaintext = session.decryptToText(envelope.payload)
        if (plaintext == null) {
            // Undecryptable: tampered, or the two sides derived different keys, which is what
            // a mismatch in the handshake's `info` or salt would produce. Recorded as FAILED
            // rather than dropped silently, so the sender's retry does not loop forever against
            // a message that can never be read.
            recordUnreadable(envelope, conversationId, "could not decrypt")
            return
        }

        val inserted = dao.insertIgnoringDuplicates(
            MessageEntity(
                messageId = envelope.messageId,
                conversationId = conversationId,
                senderId = envelope.senderId,
                receiverId = identity.id,
                // Stored re-encrypted under this device's own key rather than verbatim from the
                // wire. The wire payload is under a *session* key that goes away with the
                // link; keeping it verbatim would mean history that cannot be read after the
                // next session, on this device or on any other.
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

    /**
     * Store a frame that arrived but cannot be shown, with the reason.
     *
     * The row is kept rather than dropped for two reasons. The user needs to see that
     * something arrived, so history does not silently lose messages; and the sender's retry
     * needs a terminal state to stop against, or a message that can never be read would be
     * retried until its budget ran out for no possible success.
     *
     * The payload is stored as it arrived. It is unreadable by this device either way, and
     * storing it keeps the row honest about what was actually received -- which matters when
     * the cause is a key mismatch, because the bytes are the only evidence.
     */
    private suspend fun recordUnreadable(
        envelope: Envelope,
        conversationId: String,
        reason: String,
    ) {
        dao.insert(
            MessageEntity(
                messageId = envelope.messageId,
                conversationId = conversationId,
                senderId = envelope.senderId,
                receiverId = envelope.receiverId,
                ciphertext = envelope.payload,
                timestamp = envelope.timestamp,
                messageType = envelope.type.name,
                status = DeliveryStatus.FAILED.name,
                lastError = reason,
            ),
        )
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
        // Rows already attempted during this pass.
        //
        // A backstop, not the mechanism: the state machine is supposed to move every row out
        // of `pendingForDelivery` within one pass. It did not, for a reason fixed above, and the
        // symptom was a tight resend loop that hammered the socket until the app was killed.
        // If a future change reintroduces any row that cannot leave the queue, this bounds the
        // damage to one attempt per row per pass instead of an unbounded spin.
        val attempted = mutableSetOf<String>()

        while (coroutineContextIsActive()) {
            if (liveLinks().isEmpty()) return // Nothing to send on; the next attach() pumps.

            val next = dao.pendingForDelivery()
                .firstOrNull { it.messageId !in attempted }
                ?: return // Nothing new to try; later messages wait for the next pump.
            attempted.add(next.messageId)

            // Three different reasons a row cannot go out right now, and they need different
            // handling. Conflating them is how a message sat in the queue forever on hardware:
            // a message addressed to a peer this device no longer has a conversation with can
            // never be sent, and holding it "until connected" waits for a connection that will
            // not come, because it would need that peer, not this one.
            when (val verdict = sendVerdict(next.conversationId)) {
                SendVerdict.NO_PEER_YET -> {
                    // Not connected yet. Returns without touching the row's retry counter,
                    // because "not connected yet" is not a failed send and counting it would
                    // burn a message's whole retry budget during the second or two the handshake
                    // takes. [handleHello] calls [requestPump] when the key lands.
                    Log.d(TAG, "holding ${next.messageId}: not connected to a peer yet")
                    return
                }

                SendVerdict.WRONG_PEER -> {
                    // A peer is connected, but not the one this message is addressed to. There
                    // is no route for it and no future where there will be: the conversation is
                    // keyed on the two device ids, so the message's peer is a device this phone
                    // is not talking to. Marked FAILED so the queue drains and the user can see
                    // why, rather than blocking every message behind it indefinitely.
                    Log.w(TAG, "message ${next.messageId} is for ${next.conversationId}, not the connected peer")
                    dao.recordFailure(next.messageId, DeliveryStatus.FAILED.name, "no route to that peer")
                    continue
                }

                SendVerdict.NO_KEY -> {
                    // Connected to the right peer, but the handshake has not finished. Same
                    // reasoning as NO_PEER_YET: this is not a failure.
                    Log.d(TAG, "holding ${next.messageId}: no agreed key for this conversation")
                    return
                }

                SendVerdict.READY -> Unit
            }

            val session = sessionFor(next.conversationId)!!
            val sent = deliverOnAnyLink(next, session)
            if (sent) {
                // [deliverOnAnyLink] has already moved the row to SENDING in the database.
                // `next` is a stale snapshot and still says PENDING, so the state this
                // transition must compare against is not readable from it -- [transition]
                // re-reads the row for exactly this reason.
                transition(next, DeliveryStatus.SENT)
                continue // Try the next message immediately.
            }

            // A failure. Count it, and only give up on the row once the budget is gone.
            //
            // `recordRetry` rather than `recordFailure`, and the difference is not cosmetic.
            // Marking the row FAILED on the first rejection makes it terminal, so
            // `pendingForDelivery` stops returning it and MAX_RETRIES never comes into play --
            // one refusal ended the retry budget entirely. The row goes back to PENDING
            // (a legal SENDING -> PENDING transition) so the next pass picks it up.
            if (next.retryCount + 1 >= MAX_RETRIES) {
                dao.recordFailure(next.messageId, DeliveryStatus.FAILED.name, "send rejected")
                Log.w(TAG, "message ${next.messageId} exhausted $MAX_RETRIES retries")
                continue
            }

            dao.recordRetry(next.messageId, "send rejected")

            // Backoff before trying again. Without this, a peer that is in range but not
            // accepting writes would be hammered at the retry rate for as long as the app
            // is open.
            delay(backoffMs(next.retryCount))
        }
    }

    /**
     * Why a row cannot be sent yet, or that it can.
     *
     * An enum rather than a nullable session because "no session" covers three situations that
     * need three different responses, and the pump's behaviour differs in each: wait for the
     * handshake, wait for a connection, or give up on the row permanently.
     */
    private enum class SendVerdict { READY, NO_PEER_YET, NO_KEY, WRONG_PEER }

    private fun sendVerdict(conversationId: String): SendVerdict {
        val peer = _peerDeviceId.value ?: return SendVerdict.NO_PEER_YET
        val established = ConversationId.of(identity.id, peer).value
        if (established != conversationId) return SendVerdict.WRONG_PEER
        return if (session != null) SendVerdict.READY else SendVerdict.NO_KEY
    }

    /**
     * Write one message, trying every live link until one accepts it.
     *
     * @param session the key agreed with this message's peer, already checked by [pump] to
     *   belong to this conversation.
     * @return whether any transport accepted the bytes. `false` covers every live link refusing
     *   it, which is treated as one failure and retried -- from the top of the list, so a link
     *   that has since died is simply not in it any more.
     *
     * ## Why this tries several links
     *
     * Both phones run a server and can dial, so two sockets can exist at once. On the hardware
     * run the peer had closed one of them without this side knowing, and writing only to the
     * newest meant every send failed on a dead socket while the older, live one went unused.
     * Trying them in order makes the app tolerant of which socket the peer actually kept.
     *
     * ## The payload is re-encrypted here, and that is the whole fix
     *
     * The stored row is under this device's Keystore key. Writing it to the wire verbatim
     * would send ciphertext only this phone can open -- which is exactly what the code used to
     * do, and why a message sent from the realme arrived on the Samsung and rendered as
     * "Could not read that message".
     *
     * So the row is opened with the local key and immediately re-encrypted under the key both
     * phones agreed. That costs one extra AES operation per outgoing message and nothing else:
     * no schema change, no plaintext held anywhere longer than this function, and no path that
     * leaves the message unprotected on the wire.
     *
     * The encoded frame is built once and reused across links. Re-encrypting per link would
     * produce different IVs and therefore different ciphertext for the same message, which is
     * wasteful and would make "which copy was sent" unanswerable if two links both accepted it.
     */
    private suspend fun deliverOnAnyLink(
        entity: MessageEntity,
        session: SessionCrypto,
    ): Boolean {
        val candidates = liveLinks()
        if (candidates.isEmpty()) return false

        // Opened with the *local* Keystore key, because that is how it was stored. Falling
        // through to the retry path when the row cannot be read is honest rather than
        // defensive: that happens after a keystore reset, and no amount of retrying fixes it.
        val body = plaintextOf(entity) ?: return false

        val envelope = Envelope(
            messageId = entity.messageId,
            senderId = entity.senderId,
            receiverId = entity.receiverId,
            timestamp = entity.timestamp,
            type = MessageType.entries.firstOrNull { it.name == entity.messageType }
                ?: return false,
            payload = session.encrypt(body),
        )

        val bytes = EnvelopeCodec.encode(envelope)
        if (bytes.size > MAX_WIRE_BYTES) {
            Log.w(TAG, "message ${entity.messageId} is ${bytes.size} B, over the limit")
            return false
        }

        val frame = Reassembler.frame(bytes)
        transition(entity, DeliveryStatus.SENDING)

        for (live in candidates) {
            if (live.link.send(frame)) {
                Log.d(TAG, "sent ${entity.messageId} on link ${live.id}")
                return true
            }
            Log.w(TAG, "link ${live.id} would not take ${entity.messageId}")
        }
        return false
    }

    /**
     * Move a message to a new state, checking the transition is legal first.
     *
     * The DAO's WHERE clause is what makes this safe under concurrency; the check here is
     * for a clear log line when a caller asks for something the state machine forbids.
     *
     * `from` is read from the database, not taken from the [MessageEntity] the caller passed
     * in. That distinction is the entire fix, and getting it wrong is invisible.
     *
     * The send path moves a row PENDING -> SENDING inside [deliverOnAnyLink] and then, back in
     * [pumpOnce], asks for PENDING -> SENT. Both calls receive the *same* `MessageEntity`
     * instance, whose `status` field is an immutable snapshot taken when the row was read --
     * the write inside `deliverOnAnyLink` updates the database but not that object. So a
     * `from` read from the entity is `PENDING` on both calls, the second call's
     * `WHERE status = 'PENDING'` matches nothing because the row is now `SENDING`, the UPDATE
     * affects zero rows, and the message stays on "Sending" for good.
     *
     * An earlier version of this comment claimed the bug was fixed because `from` was derived
     * from the row. It was not: it was derived from the row as it was *when the caller read
     * it*, which is the stale value. Observed on hardware on 2026-10-04, with one message
     * stuck on "Sending" while the same conversation delivered every other message normally.
     *
     * Reading the status back costs one indexed SELECT and makes a stale snapshot impossible
     * to act on. `transition` also logs when the UPDATE affects zero rows, so if this ever
     * regresses again it says so instead of failing silently.
     */
    private suspend fun transition(entity: MessageEntity, to: DeliveryStatus) {
        val current = dao.statusOf(entity.messageId)
        if (current == null) {
            Log.w(TAG, "no row for ${entity.messageId} when moving to $to")
            return
        }
        val from = DeliveryStatus.entries.firstOrNull { it.name == current }
        if (from == null) {
            Log.w(TAG, "row ${entity.messageId} has unreadable status '$current'")
            return
        }
        if (!from.canTransitionTo(to)) {
            Log.w(TAG, "refused $current -> $to for ${entity.messageId}")
            return
        }
        val changed = dao.transition(entity.messageId, from.name, to.name)
        if (changed == 0) {
            // Reachable only if another writer moved the row between the SELECT above and this
            // UPDATE. Worth a line: it means the compare-and-set lost a race, which is safe.
            Log.w(TAG, "lost race moving ${entity.messageId} $current -> $to")
        }
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
         * How many superseded links are kept alive alongside the primary.
         *
         * Two is the realistic case: the link the user opened, and the one the peer opened at
         * the same time. One spare beyond that absorbs a peer that re-dials while a previous
         * socket is still closing, without letting the list grow without bound.
         */
        const val MAX_KEPT_LINKS = 2

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
