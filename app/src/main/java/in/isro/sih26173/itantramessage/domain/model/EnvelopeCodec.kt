package `in`.isro.sih26173.itantramessage.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary encoder/decoder for [Envelope].
 *
 * ## Every rejection is a `null`, never an exception
 *
 * This runs on bytes from a radio, which means every field is untrusted and a peer may
 * be running a different build of the app. So each parse step validates before it
 * allocates, and anything unexpected returns null. Throwing would force every caller to
 * wrap the call in a try/catch, and the one caller that forgot would crash the app on
 * malformed input -- which is a denial of service by anyone within radio range.
 *
 * ## Length validation happens before allocation
 *
 * [decode] checks that the declared payload length matches the bytes actually present
 * before allocating a buffer for it. Without that check, a 5-byte packet claiming a
 * 2 GB payload would either allocate 2 GB or throw -- the first is an OOM kill, the
 * second is the crash described above. Both are reachable by a hostile peer.
 */
object EnvelopeCodec {

    const val MAGIC = 0x49 // 'I'
    const val VERSION = 1.toByte()

    /**
     * The header length: 3 framing bytes + 16 messageId + 9 senderId + 9 receiverId
     * + 8 timestamp + 2 payloadLen.
     */
    const val HEADER_BYTES = 47

    /**
     * The message id occupies 16 bytes -- the full width of a UUID.
     *
     * Not 12. See the [Envelope] class comment: truncating the id means the receiver does
     * not get back the id the sender assigned, and two messages that collide in the
     * surviving bits are silently discarded as duplicates.
     */
    private const val ID_BYTES = 16

    /**
     * The device id occupies 9 bytes -- the exact length of `DeviceIdentity`'s `IT-%06X`
     * format, which is `IT-` plus six hex digits.
     *
     * This was 6, which is the width of the hex payload but not of the whole string. The
     * encoder then did `id.take(6)`, which kept `IT-1A2` and dropped the rest, so
     * `IT-1A2B3C` and `IT-1A2B3D` encoded to the *same* six bytes. Two distinct devices
     * were indistinguishable on the wire, the receiver's own-id check could not tell
     * them apart, and conversations with the wrong peer became indistinguishable.
     *
     * Nine bytes matches the format exactly rather than compressing it, so the string in
     * the database is byte-for-byte the string on the wire. Compressing would be smaller
     * -- but every transformation applied to an identifier is a chance to make two
     * different things look the same, and this file has already produced two of those.
     */
    private const val DEVICE_ID_BYTES = 9

    /** Refuse anything larger outright. See the class comment on why. */
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(envelope: Envelope): ByteArray {
        val payloadBytes = envelope.payload.toByteArray(Charsets.UTF_8)
        require(payloadBytes.size <= MAX_PAYLOAD_BYTES) {
            "payload ${payloadBytes.size} B exceeds the $MAX_PAYLOAD_BYTES B limit"
        }

        val buffer = ByteBuffer.allocate(HEADER_BYTES + payloadBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC.toByte())
        buffer.put(VERSION)
        buffer.put(envelope.type.ordinal.toByte())
        buffer.put(idToBytes(envelope.messageId))
        buffer.put(deviceIdToBytes(envelope.senderId))
        buffer.put(deviceIdToBytes(envelope.receiverId))
        buffer.putLong(envelope.timestamp)
        buffer.putShort(payloadBytes.size.toShort())
        buffer.put(payloadBytes)
        return buffer.array()
    }

    /**
     * Parse one envelope, or `null` if these bytes are not a valid one.
     *
     * A trailing byte is accepted rather than rejected, and that is deliberate: a BLE
     * transport delivering a stream reassembles whatever arrived, so a padding byte at the
     * end is a normal outcome of the radio, not corruption. Rejecting it would make the
     * message unrecoverable over exactly the transport most likely to add one.
     */
    fun decode(bytes: ByteArray): Envelope? {
        if (bytes.size < HEADER_BYTES) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        if (buffer.get().toInt() and 0xFF != MAGIC) return null

        val version = buffer.get()
        if (version != VERSION) return null

        val type = MessageType.fromOrdinal(buffer.get().toInt()) ?: return null

        // Read the three fixed-width id fields, each into its own array so the buffer
        // position advances by exactly the field width.
        val messageId = ByteArray(ID_BYTES).also { buffer.get(it) }.toIdString()

        val senderId = ByteArray(DEVICE_ID_BYTES).also { buffer.get(it) }.toDeviceIdString()
        val receiverId = ByteArray(DEVICE_ID_BYTES).also { buffer.get(it) }.toDeviceIdString()
        val timestamp = buffer.long
        val payloadLength = buffer.short.toInt() and 0xFFFF

        // The two checks that matter for hostile input, both before any allocation sized
        // by attacker-controlled data.
        if (payloadLength > MAX_PAYLOAD_BYTES) return null
        if (bytes.size < HEADER_BYTES + payloadLength) return null

        val payload = String(bytes, HEADER_BYTES, payloadLength, Charsets.UTF_8)

        return Envelope(
            messageId = messageId,
            senderId = senderId,
            receiverId = receiverId,
            timestamp = timestamp,
            type = type,
            payload = payload,
        )
    }

    /**
     * The 16 raw bytes of a message id.
     *
     * @throws IllegalArgumentException if [id] is not a UUID. A non-UUID id is rejected
     *   loudly rather than packed or truncated. The earlier design accepted an arbitrary
     *   string and zero-padded it, which meant the id that came back out was not the id
     *   that went in -- and since this id is the deduplication key, a silently altered id
     *   can make two different messages look like one. Every id in this app is generated by
     *   `UUID.randomUUID()`, so the throw is unreachable in normal operation and exists
     *   only to make a protocol mistake a build-time-visible failure rather than a data
     *   integrity one.
     */
    private fun idToBytes(id: String): ByteArray {
        // UUID.fromString's own message ("Invalid UUID string: xyz") does not say which
        // field was wrong. This exception can end up in a log during triage, so it names
        // the field and restates the requirement instead of leaving the reader to work
        // out which of the seven header values was at fault.
        val uuid = try {
            UUID.fromString(id)
        } catch (cause: IllegalArgumentException) {
            throw IllegalArgumentException(
                "messageId must be a UUID (36 chars, from UUID.randomUUID()), got \"$id\"",
                cause,
            )
        }

        val out = ByteArray(ID_BYTES)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0 until 8) out[i] = (msb ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb ushr (56 - 8 * i)).toByte()
        return out
    }

    /** Reassemble a 16-byte id as the canonical lowercase UUID string. */
    private fun ByteArray.toIdString(): String {
        val msb = ByteBuffer.wrap(this, 0, 8).long
        val lsb = ByteBuffer.wrap(this, 8, 8).long
        return UUID(msb, lsb).toString()
    }

    /**
     * The exact UTF-8 bytes of a device id.
     *
     * @throws IllegalArgumentException if the id is longer than [DEVICE_ID_BYTES]. The
     *   previous implementation used `id.take(DEVICE_ID_BYTES)`, which is the same class of
     *   bug as truncating a message id: it passes a shortened id through as though it were
     *   the real one, so two different devices share a wire representation and the
     *   receiver cannot tell them apart. Rejecting means a wrong id fails at the boundary
     *   instead of quietly addressing the wrong peer.
     */
    private fun deviceIdToBytes(id: String): ByteArray {
        val bytes = id.toByteArray(Charsets.UTF_8)
        require(bytes.size <= DEVICE_ID_BYTES) {
            "device id \"$id\" is ${bytes.size} bytes, over the $DEVICE_ID_BYTES byte field"
        }
        val out = ByteArray(DEVICE_ID_BYTES)
        bytes.copyInto(out)
        return out
    }

    private fun ByteArray.toDeviceIdString(): String =
        String(this, Charsets.UTF_8).trimEnd(0.toChar())
}
