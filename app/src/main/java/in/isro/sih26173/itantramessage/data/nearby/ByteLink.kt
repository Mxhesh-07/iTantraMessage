package `in`.isro.sih26173.itantramessage.data.nearby

/**
 * A bidirectional byte pipe to one peer.
 *
 * The transport-agnostic seam in this app. Everything above it -- the repository, the
 * queue, the ViewModel, the UI -- is written against this interface and nothing else, so
 * swapping RFCOMM for GATT is a matter of choosing a different implementation of the same
 * three methods. That is why the repository has no `when (transport)` in it.
 *
 * ## Why it is bytes and not messages
 *
 * The interface does not know what a message is. Framing, encryption and deduplication
 * all live above it, for a reason that is easy to get wrong: they have to behave
 * identically on every transport, and a transport that framed its own messages would
 * frame them differently. A BLE packet is 23-247 bytes and needs reassembly; an RFCOMM
 * stream is unbounded and needs none. [Reassembler] handles that difference once, for
 * both, rather than twice in two transports.
 */
interface ByteLink {

    /** Whether bytes can currently be written. False while disconnected. */
    val isOpen: Boolean

    /**
     * Bytes arriving from the peer.
     *
     * Never completes normally. A link that reaches end-of-stream is a link that failed,
     * and the collector above treats that as [NearbyError.TransportFailed] rather than
     * as a successful close.
     */
    val incoming: kotlinx.coroutines.flow.Flow<ByteArray>

    /**
     * Write [bytes] to the peer.
     *
     * @return whether the write was accepted. False means the link closed between the
     *   caller's check and this call, which is normal and must not throw -- the queue
     *   treats it as "peer unavailable, retry later".
     */
    suspend fun send(bytes: ByteArray): Boolean

    /** Close the link and release the radio. Must be safe to call twice. */
    fun close()

    /** A short description of the far end, for the UI. Never includes a stack trace. */
    val peerLabel: String

    /**
     * The peer's Bluetooth address, or null where the platform will not say.
     *
     * Added for exactly one purpose: attributing an incoming identification handshake to a
     * known radio. MessageRepository records \"this MAC announced this device id\", and
     * without the address it could not connect the two -- the handshake would arrive with
     * no idea which device sent it.
     *
     * Deliberately *not* used to identify the peer. A MAC is a radio address, not an
     * identity: the conversation key uses the exchanged device id precisely because the MAC
     * is unavailable on Android 12+ and cannot be derived from anything else. See
     * [in.isro.sih26173.itantramessage.data.device.PeerIdentity].
     */
    val peerAddress: String?
}

/**
 * Reassembles framed messages from an arbitrary byte stream.
 *
 * ## The problem
 *
 * The two transports deliver bytes with completely different granularity. RFCOMM hands
 * over whatever the socket buffered -- 40 bytes, 900 bytes, 12 bytes, in no relationship
 * to message boundaries. BLE hands over at most one MTU per notification, so a 200-byte
 * message arrives as nine separate callbacks. A transport cannot be allowed to guess
 * where a message ends, so it does not try; it forwards bytes, and this class finds the
 * boundaries.
 *
 * ## The framing
 *
 * A 4-byte header precedes every message:
 *
 * ```
 * 0x49 0x54 0x4D 0x31  "ITM1"  magic, 4 bytes
 * u16               payload length
 * ...               payload
 * ```
 *
 * The magic is what makes the stream self-synchronising. When bytes are lost -- and on
 * BLE they are, silently, because a notification is not acknowledged at the link layer
 * -- a length-based parser would resynchronise onto a random offset and then interpret
 * payload as header, producing garbage that looks like messages. Requiring a 4-byte magic
 * before trusting a length means a corrupted stream resynchronises at the next real
 * message boundary instead.
 *
 * ## Why the ceiling is not a hard maximum
 *
 * [MAX_FRAME_BYTES] is 64 KB, which is far above any plausible message. It exists to stop
 * a corrupt or hostile length field from making this class try to buffer a gigabyte. A
 * frame that exceeds it is *dropped and the stream resynchronises*, not held forever --
 * holding it would be a slow memory leak that only appears under attack.
 */
class Reassembler(
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
) {
    private var buffer = ByteArray(INITIAL_BUFFER)
    private var size = 0

    /**
     * Bytes discarded because the stream could not be trusted at that point: a byte that did
     * not match the frame magic, or a header whose length was beyond [maxFrameBytes].
     *
     * Does *not* count bytes of successfully delivered frames. Those leave the buffer
     * through [consume] rather than [discard], so a healthy link keeps this at zero and a
     * rising value is a real signal that the stream is losing sync.
     *
     * An earlier version incremented the same counter on both paths, so the figure tracked
     * the total byte count of all traffic -- roughly the payload length of every message
     * ever sent. Read as "bytes dropped", that number looks like catastrophic stream
     * corruption on a perfectly healthy link, which is worse than having no counter at all.
     */
    var droppedBytes: Long = 0L
        private set

    /**
     * Feed received bytes, returning every complete frame now available.
     *
     * A list rather than a single frame because one BLE notification can complete one
     * frame *and* start the next, and because a large read from an RFCOMM socket will
     * typically contain several messages back to back. Returning only the first would
     * leave messages sitting in the buffer until more traffic arrived, which on an
     * otherwise idle link means they never show up.
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        append(chunk)
        val frames = mutableListOf<ByteArray>()

        while (true) {
            if (size < HEADER_BYTES) break

            // Wait for the full magic before trusting anything, so a partial header is not
            // mistaken for a mismatch.
            if (buffer[0] != MAGIC_0 || buffer[1] != MAGIC_1 ||
                buffer[2] != MAGIC_2 || buffer[3] != MAGIC_3
            ) {
                // Resynchronise: drop one byte and look again. One byte at a time is
                // correct rather than merely conservative -- the magic could legitimately
                // start at any offset, and a bulk skip would discard a real frame that
                // happened to begin mid-chunk.
                discard(1)
                continue
            }

            val payloadLength = (buffer[4].toInt() and 0xFF shl 8) or (buffer[5].toInt() and 0xFF)
            if (payloadLength > maxFrameBytes) {
                // A length beyond the ceiling means the stream is not trustworthy from
                // here. Drop the magic and resynchronise, rather than trusting the
                // following bytes as a length.
                discard(HEADER_BYTES)
                continue
            }

            val total = HEADER_BYTES + payloadLength
            if (size < total) break // Frame is incomplete; wait for more.

            frames.add(buffer.copyOfRange(HEADER_BYTES, total))
            consume(total)
        }

        return frames
    }

    /** Reset, discarding any partial frame. Called when a link is re-established. */
    fun reset() {
        size = 0
        droppedBytes = 0
    }

    private fun append(chunk: ByteArray) {
        if (size + chunk.size > buffer.size) {
            var capacity = buffer.size
            // Grow to fit, but never past a bound that would let a hostile stream pin
            // memory. maxFrameBytes + header is the most that can legitimately be held.
            val ceiling = maxFrameBytes + HEADER_BYTES + chunk.size
            while (capacity < size + chunk.size && capacity < ceiling) {
                capacity = capacity shl 1
            }
            buffer = buffer.copyOf(capacity.coerceAtLeast(size + chunk.size))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
    }

/**
     * Remove [count] bytes from the front after they have been handed to the caller.
     *
     * Deliberately does *not* touch [droppedBytes]. These bytes were delivered, not lost.
     * The previous version incremented the counter here as well as in [discard], which
     * made the diagnostic count every successfully delivered frame as dropped -- so it
     * ended up close to the total byte count of all traffic, a number that looks like
     * catastrophic stream corruption while the stream is in fact perfectly healthy.
     */
    private fun consume(count: Int) {
        if (count >= size) {
            size = 0
            return
        }
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    /**
     * Remove [count] bytes from the front because they are unusable, counting them in
     * [droppedBytes].
     *
     * Only called on the resynchronisation paths: a byte that does not match the magic, and
     * a header whose length is beyond the ceiling.
     */
    private fun discard(count: Int) {
        if (count >= size) {
            droppedBytes += size
            size = 0
            return
        }
        buffer.copyInto(buffer, 0, count, size)
        size -= count
        droppedBytes += count
    }

    companion object {
        const val HEADER_BYTES = 6
        const val MAX_FRAME_BYTES = 64 * 1024
        private const val INITIAL_BUFFER = 2048

        private const val MAGIC_0 = 0x49.toByte() // 'I'
        private const val MAGIC_1 = 0x54.toByte() // 'T'
        private const val MAGIC_2 = 0x4D.toByte() // 'M'
        private const val MAGIC_3 = 0x31.toByte() // '1'

        /**
         * Wrap [payload] in a frame header.
         *
         * The single place a frame is ever produced, so the magic bytes and the length
         * encoding cannot disagree with [Reassembler].
         */
        fun frame(payload: ByteArray): ByteArray {
            val length = payload.size
            require(length <= MAX_FRAME_BYTES) {
                "payload $length B exceeds the $MAX_FRAME_BYTES B frame ceiling"
            }
            val out = ByteArray(HEADER_BYTES + length)
            out[0] = MAGIC_0
            out[1] = MAGIC_1
            out[2] = MAGIC_2
            out[3] = MAGIC_3
            out[4] = (length ushr 8).toByte()
            out[5] = length.toByte()
            payload.copyInto(out, HEADER_BYTES)
            return out
        }
    }
}
