package `in`.isro.sih26173.itantramessage.domain.model

import `in`.isro.sih26173.itantramessage.data.crypto.EncryptionManager

/**
 * The wire message, before encryption.
 *
 * ## Why this is a class and not the JSON in the brief
 *
 * The brief's example is a JSON object. It is a good description of the fields and a bad
 * choice of encoding, for reasons that are worth writing down because they will come up
 * again:
 *
 *  * **Size.** A 4-field message in JSON is ~180 bytes of which perhaps 90 are field
 *    names and punctuation that both ends already agreed on. Over BLE the default MTU is
 *    23 bytes, so 180 bytes is 8 radio round trips before the app has even started being
 *    useful. Over a constrained radio, on a 2 GB handset, that is the difference between
 *    a message arriving and not.
 *  * **Allocation.** Parsing JSON means an object graph per received message on the
 *    Binder thread pool, on a device where GC pressure is visible in frame times.
 *  * **Ambiguity.** JSON is forgiving. A peer sends a field this build does not know and
 *    a lenient parser silently drops it, so a message can arrive partially understood
 *    with no error anywhere.
 *
 * A fixed binary layout is smaller, allocates nothing to parse, and is strict by
 * construction: a wrong length or an unknown type is a rejection, not a guess. The field
 * order below is the wire order and must not be reordered without bumping [MAGIC].
 *
 * ```
 * offset  size  field
 *      0     1  magic       'I' (0x49)
 *      1     1  version     protocol version, currently 1
 *      2     1  type        MessageType ordinal
 *      3    16  messageId   UUID, the deduplication key
 *     19     9  senderId    DeviceIdentity.id
 *     28     9  receiverId  DeviceIdentity.id
 *     37     8  timestamp   epoch millis, big-endian
 *     45     2  payloadLen  bytes of encrypted payload that follow
 *     47     n  payload     UTF-8(Base64) of EncryptionManager.encrypt output
 * ```
 *
 * Fixed-width ids are the point of the layout: a receiver can skip to the payload offset
 * without parsing anything, and a message with a corrupt length is rejected before any
 * buffer is allocated for it.
 *
 * ## The messageId field is the full 16 bytes, and that is not incidental
 *
 * An earlier version of this layout allotted 12 bytes -- the UUID's high 8 bytes plus its
 * low 4 -- on the reasoning that 96 bits is plenty for a two-party chat. A round-trip
 * unit test showed that reasoning was wrong in a way that mattered.
 *
 * The receiver does not get back the id the sender assigned. It gets a *different* id with
 * the dropped 16 bits rendered as zeros:
 *
 * ```
 * sent:     f47ac10b-58cc-4372-a567-0e02b2c3d479
 * received: f47ac10b-58cc-4372-0000-0000b2c3d479
 * ```
 *
 * Two things break at once. First, any later operation that identifies a message by the id
 * the receiver stored -- an acknowledgement, a delivery receipt, a resend -- disagrees with
 * the sender's own record. Second, and much worse, two genuinely different messages from
 * the same sender that happen to agree in the surviving 96 bits produce the *same* id, so
 * the receiver's uniqueness constraint rejects the second as a duplicate and the message
 * is silently never stored. The user sees one message instead of two, with no error
 * anywhere.
 *
 * That is silent message loss, in the one component whose entire job is to guarantee that
 * no message is lost. Four bytes per message is not a price worth paying for it.
 */
data class Envelope(
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val type: MessageType,
    val payload: String,
) {
    /**
     * The total wire size in bytes, needed before writing.
     *
     * Computed from the field list rather than measured, so the transport can check
     * against an MTU before splitting. If this and [EnvelopeCodec.encode] ever disagree,
     * the MTU check is wrong, which is why the round-trip test in EnvelopeCodecTest
     * compares a decoded message against this value rather than a hand-written constant.
     */
    val wireSize: Int
        get() = EnvelopeCodec.HEADER_BYTES + payload.toByteArray(Charsets.UTF_8).size

    fun encrypt(manager: EncryptionManager): Envelope =
        copy(payload = manager.encrypt(payload))
}

/** What a message is. Only TEXT exists; the field is here so it can be added honestly. */
enum class MessageType {
    TEXT,
    ;

    companion object {
        fun fromOrdinal(value: Int): MessageType? = entries.getOrNull(value)
    }
}

/**
 * A conversation between this device and one peer.
 *
 * ## Why it is a single derived string, not two columns
 *
 * Both devices must independently compute the same conversation id without exchanging
 * it, or a message would be stored under different keys on the two sides and the history
 * would appear to have lost half its messages. Sorting the two device ids and joining
 * them is the standard trick: order-independent, so both ends agree, and it needs no
 * coordination or negotiation.
 *
 * A data class rather than a bare String so the two components cannot be swapped at a call
 * site. Every place that needs a conversation also needs to know which peer it is with, and
 * a single type carrying both makes that impossible to get backwards.
 */
data class ConversationId(val value: String, val peer: String) {
    companion object {
        fun of(selfId: String, peerId: String): ConversationId {
            val value = if (selfId <= peerId) "$selfId|$peerId" else "$peerId|$selfId"
            return ConversationId(value, peerId)
        }

        /**
         * Rebuild a conversation from a stored key.
         *
         * [selfId] is required because a key holds two ids and only one of them is this
         * device. Deriving the peer without it would be a coin toss.
         */
        fun parse(value: String, selfId: String): ConversationId? {
            val parts = value.split('|')
            if (parts.size != 2) return null

            // An empty part means a corrupted or hand-edited key. Without this check,
            // "IT-1A2B3C|" parses to a conversation whose peer is "", which then matches
            // no rows -- so the chat screen would open empty and look like the messages had
            // been deleted.
            if (parts.any { it.isEmpty() }) return null

            // Exactly one part must be this device.
            //
            // `firstOrNull { it != selfId }` is not enough. When selfId is neither part it
            // returns the *first* id, presenting an unrelated peer as though it were a real
            // conversation partner -- and a caller that passed the wrong selfId would get a
            // confidently wrong answer instead of a null it could handle. Requiring the
            // count to be exactly 1 rejects that, and also rejects a self-chat key
            // ("X|X"), which cannot occur from of() and would be meaningless if it did.
            if (parts.count { it == selfId } != 1) return null

            val peer = parts.first { it != selfId }
            return ConversationId(value, peer)
        }
    }
}
