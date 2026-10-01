package `in`.isro.sih26173.itantramessage.data.nearby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for stream framing.
 *
 * ## Why this class deserves the most tests in the project
 *
 * `EnvelopeCodec` is the easy half of the protocol: bytes go in, a message or `null` comes
 * out, and the boundaries are already known. `Reassembler` is the hard half, because it has
 * to *find* the boundaries in a stream that delivers arbitrary chunks -- and the chunking
 * is not under its control.
 *
 * That produces a property that is easy to state and easy to get subtly wrong:
 *
 * > For any splitting of a byte stream into chunks, feeding those chunks in order must
 * > yield exactly the frames that were written.
 *
 * Both transports violate chunk-boundary assumptions in production. RFCOMM hands over
 * whatever the socket buffered. BLE delivers one MTU per notification, so a 200-byte
 * message is nine separate callbacks with arbitrary split points. Any test that only feeds
 * whole frames at once would pass against an implementation that is broken for every real
 * radio.
 *
 * So most of this file is about *slicing*: every splitting of the same stream is fed to a
 * fresh reassembler and must produce the same frames.
 */
class ReassemblerTest {

    private fun payload(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ---- the round trip the sender and receiver both depend on ---------------------------

    @Test
    fun `a single frame in a single chunk`() {
        val r = Reassembler()
        val frames = r.feed(Reassembler.frame(bytes("hello")))

        assertEquals(1, frames.size)
        assertArrayEquals(bytes("hello"), frames[0])
    }

    @Test
    fun `several frames in one chunk are all returned`() {
        // The ordinary RFCOMM case: a socket read that happened to catch three messages.
        // Returning only the first would leave two messages sitting in the buffer until
        // more traffic arrived -- and on an idle link that may be never.
        val r = Reassembler()
        val chunk = Reassembler.frame(bytes("one")) +
            Reassembler.frame(bytes("two")) +
            Reassembler.frame(bytes("three"))

        val frames = r.feed(chunk)

        assertEquals(3, frames.size)
        assertArrayEquals(bytes("one"), frames[0])
        assertArrayEquals(bytes("two"), frames[1])
        assertArrayEquals(bytes("three"), frames[2])
    }

    @Test
    fun `one frame split across many chunks`() {
        // The ordinary BLE case. Each notification is one MTU; nothing about the split
        // points relates to the message.
        val r = Reassembler()
        val stream = Reassembler.frame(bytes("a message longer than one mtu "))

        val collected = mutableListOf<ByteArray>()
        // One byte at a time is the worst case a radio can produce, and the case a
        // frame-boundary bug hides behind.
        for (b in stream) {
            collected += r.feed(byteArrayOf(b))
        }

        assertEquals(1, collected.size)
        assertArrayEquals(bytes("a message longer than one mtu "), collected[0])
    }

    /**
     * Every possible way of cutting a stream into chunks must produce the same frames.
     *
     * ## Why "every" and not a sample
     *
     * An implementation can be correct for the chunk sizes it was tested with and wrong for
     * one it was not -- an off-by-one that loses a byte when a frame lands exactly on an
     * MTU boundary, say. A sampled test has a fair chance of missing that. So this
     * enumerates exhaustively rather than randomly.
     *
     * ## Why not simply "all splits" of the whole stream
     *
     * An earlier version of this test tried to and quietly did not. The stream was 39 bytes,
     * so there were 38 cut points and 2^38 chunkings -- and `1 shl 38` overflows an Int to
     * `1 shl 6`, so the loop ran 64 times and passed. The `chunkings > 100` guard added
     * afterwards is what caught it, which is that guard earning its place.
     *
     * So the coverage is split by what is actually enumerable:
     *
     *  - **All 2^12 chunkings** of a 13-byte stream: 4096 cases, exactly enumerable, and
     *    the one test that can honestly claim exhaustiveness over every cut pattern.
     *  - **All 2- and 3-chunk splits** of a longer, representative stream containing an
     *    empty frame, a frame larger than any BLE MTU, and a payload that includes the
     *    frame magic itself. O(n^2) and O(n^3), roughly 10k cases.
     */
    @Test
    fun `every cut pattern of a small stream yields the same frames`() {
        // Deliberately two frames. One is empty and one is not, which is the case most
        // likely to break a parser -- a zero-length body means the next frame starts
        // immediately after a header with nothing between the two. A third frame would
        // push this to 2^23 chunkings, which is minutes of test time for coverage the
        // three-way split test below already provides.
        val expectedFrames = listOf(bytes(""), bytes("b"))
        val stream = expectedFrames.fold(ByteArray(0)) { acc, f -> acc + Reassembler.frame(f) }

        // 13 bytes -> 12 cut points -> 4096 chunkings, every one of them.
        assertEquals(13, stream.size)
        assertEquals(12, stream.size - 1)

        var chunkings = 0
        for (mask in 0 until (1 shl (stream.size - 1))) {
            assertFramesEqual(expectedFrames, feedAllChunks(stream, mask), "chunking #$chunkings")
            chunkings++
        }
        assertEquals(4096, chunkings)
    }

    @Test
    fun `every two- and three-way split of a representative stream yields the same frames`() {
        val tricky = bytes("ITM1") + payload(0xFF, 0xFF)
        val expectedFrames = listOf(bytes(""), bytes("x".repeat(300)), tricky, bytes("tail"))
        val stream = expectedFrames.fold(ByteArray(0)) { acc, f -> acc + Reassembler.frame(f) }
        val n = stream.size

        fun check(chunks: List<ByteArray>, label: String) {
            val r = Reassembler()
            val actual = mutableListOf<ByteArray>()
            for (c in chunks) actual += r.feed(c)
            assertFramesEqual(expectedFrames, actual, label)
            assertEquals("$label: bytes should be fully consumed", 0L, r.droppedBytes)
        }

        check(listOf(stream), "whole stream")

        for (i in 1 until n) {
            check(listOf(stream.copyOfRange(0, i), stream.copyOfRange(i, n)), "split@$i")
            for (j in i + 1 until n) {
                check(
                    listOf(
                        stream.copyOfRange(0, i),
                        stream.copyOfRange(i, j),
                        stream.copyOfRange(j, n),
                    ),
                    "split@$i,$j",
                )
            }
        }
    }

    /** Split [stream] at every position where [mask] has a bit set. */
    private fun feedAllChunks(stream: ByteArray, mask: Int): List<ByteArray> {
        val r = Reassembler()
        val frames = mutableListOf<ByteArray>()
        var start = 0
        for (i in 0 until stream.size - 1) {
            if ((mask and (1 shl i)) != 0) {
                frames += r.feed(stream.copyOfRange(start, i + 1))
                start = i + 1
            }
        }
        frames += r.feed(stream.copyOfRange(start, stream.size))
        return frames
    }

    private fun assertFramesEqual(expected: List<ByteArray>, actual: List<ByteArray>, label: String) {
        assertEquals("$label: frame count", expected.size, actual.size)
        for (i in expected.indices) {
            assertArrayEquals("$label: frame $i", expected[i], actual[i])
        }
    }
    /**
     * A frame that arrives split exactly on its magic, and then again on its length.
     *
     * Not covered by random splits above, because the code has explicit branches for
     * "fewer than HEADER_BYTES buffered" and "header complete but body incomplete" and
     * those branches are where an off-by-one lives.
     */
    @Test
    fun `a frame split inside its header and inside its length field`() {
        val r = Reassembler()
        val frame = Reassembler.frame(bytes("body"))

        // 3 bytes: partial magic. Must not resynchronise -- that would discard real data.
        assertEquals(emptyList<ByteArray>(), r.feed(frame.copyOfRange(0, 3)))
        assertEquals("partial magic must not count as dropped", 0L, r.droppedBytes)

        // 1 byte: now the magic is complete but no length yet.
        assertEquals(emptyList<ByteArray>(), r.feed(frame.copyOfRange(3, 4)))
        assertEquals(0L, r.droppedBytes)

        // 1 byte: length complete, body incomplete.
        assertEquals(emptyList<ByteArray>(), r.feed(frame.copyOfRange(4, 5)))
        assertEquals(0L, r.droppedBytes)

        // The rest: the frame completes.
        val frames = r.feed(frame.copyOfRange(5, frame.size))
        assertEquals(1, frames.size)
        assertArrayEquals(bytes("body"), frames[0])
    }

    @Test
    fun `an empty payload frame is valid`() {
        // A zero-length payload is a legal frame. Nothing in the current protocol sends
        // one -- MessageType has a single entry, TEXT, and every message carries a body --
        // so this is not testing a path that exists yet. It is here because the moment an
        // acknowledgement is added, this is the branch it will take, and a parser that
        // treated "empty body" as "corrupt" would silently drop every one of them.
        //
        // The asymmetry is what makes this easy to get wrong: the *message* layer has a
        // minimum (a TEXT message needs a ciphertext) while the *framing* layer has none.
        // A rebuilder conflating the two would refuse the frame here and report a
        // protocol error, when the honest answer is "well-formed, carrying nothing".
        val r = Reassembler()
        val frames = r.feed(Reassembler.frame(ByteArray(0)))

        assertEquals(1, frames.size)
        assertEquals(0, frames[0].size)
    }

    // ---- resynchronisation ---------------------------------------------------------------

    /**
     * Garbage before a valid frame is skipped, and the frame after it is still found.
     *
     * This is the case the magic exists for. On BLE, notifications are not acknowledged at
     * the link layer, so bytes are lost silently and without telling either end. A
     * length-only parser would resynchronise onto a random offset and start reading
     * payload as header -- producing plausible-looking garbage forever. Requiring a magic
     * before trusting a length means the stream recovers at the next real boundary.
     */
    @Test
    fun `leading garbage is skipped and the next frame is found`() {
        val r = Reassembler()
        val stream = payload(0x00, 0xFF, 0x13, 0x37, 0x42) + Reassembler.frame(bytes("survivor"))

        val frames = r.feed(stream)

        assertEquals(1, frames.size)
        assertArrayEquals(bytes("survivor"), frames[0])
        assertEquals("the garbage should be counted as dropped", 5L, r.droppedBytes)
    }

    @Test
    fun `garbage between two frames does not lose the second`() {
        val r = Reassembler()
        val stream = Reassembler.frame(bytes("first")) +
            payload(0xAA, 0xBB, 0xCC) +
            Reassembler.frame(bytes("second"))

        val frames = r.feed(stream)

        assertEquals(2, frames.size)
        assertArrayEquals(bytes("first"), frames[0])
        assertArrayEquals(bytes("second"), frames[1])
    }

    @Test
    fun `resynchronisation happens one byte at a time so an interior frame survives`() {
        // A bulk skip is the tempting optimisation and it is wrong: the magic can start at
        // any offset, so skipping a guessed amount can throw away a real frame that began
        // in the middle of the garbage. This pins the byte-at-a-time behaviour.
        val r = Reassembler()
        // 8 bytes of garbage, then a frame. A "skip to the next 0x49" heuristic would work
        // here; a "skip a fixed 16" heuristic would lose the frame.
        val stream = payload(1, 2, 3, 4, 5, 6, 7, 8) + Reassembler.frame(bytes("kept"))

        val frames = r.feed(stream)

        assertEquals(1, frames.size)
        assertArrayEquals(bytes("kept"), frames[0])
    }

    @Test
    fun `a stream that is entirely garbage yields nothing and does not hang`() {
        // The loop inside feed() resynchronises by dropping a byte and continuing. If the
        // buffer-empties condition is wrong, this either returns early and desynchronises
        // or spins forever.
        val r = Reassembler()
        val frames = r.feed(ByteArray(4096) { 0x00 })

        assertEquals(0, frames.size)
        // 4091, not 4096. The loop stops as soon as fewer than HEADER_BYTES (6) remain,
        // because with 5 buffered zeros it cannot yet tell whether a magic is starting --
        // and those 5 bytes are still legitimately "not dropped", just undetermined. An
        // implementation that discarded them would be discarding real frame headers.
        assertEquals(4096 - 5, r.droppedBytes)
    }

    /**
     * A frame whose magic contains the header's own bytes must not be misread.
     *
     * `0x49 0x54 0x4D 0x31` -- "ITM1" -- can legitimately occur inside a payload. A
     * parser that trusted a length as soon as it saw *any* of those bytes would treat the
     * next two bytes as a length and cut the message in half. Requiring all four before
     * acting is what makes the stream self-synchronising rather than merely lucky.
     */
    @Test
    fun `magic bytes inside a payload do not split the frame`() {
        val r = Reassembler()
        // "ITM1" then bytes that would read as a length of 0xFFFF if misread.
        val tricky = bytes("ITM1") + payload(0xFF, 0xFF) + bytes("tail")

        val frames = r.feed(Reassembler.frame(tricky))

        assertEquals(1, frames.size)
        assertArrayEquals(tricky, frames[0])
    }

    // ---- the length ceiling -------------------------------------------------------------

    /**
     * A length beyond the ceiling drops that frame and resynchronises.
     *
     * [Reassembler.MAX_FRAME_BYTES] is 64 KB, far above any plausible message. It exists
     * so a corrupt or hostile length field cannot make this class buffer a gigabyte. The
     * important part is what happens next: the frame is *dropped and the stream recovers*,
     * not held. Holding it would be a memory leak that only appears under attack, and it
     * would take down a phone that is otherwise fine.
     */
    @Test
    fun `an over-ceiling length is dropped and the stream recovers`() {
        val r = Reassembler(maxFrameBytes = 1024)

        // A hand-built header claiming 60000 bytes, followed by a real frame.
        val hostile = byteArrayOf(0x49, 0x54, 0x4D, 0x31, 0xEA.toByte(), 0x60.toByte()) +
            ByteArray(16) { 0xEE.toByte() }
        val stream = hostile + Reassembler.frame(bytes("after"))

        val frames = r.feed(stream)

        assertEquals("the over-ceiling frame must not be delivered", 1, frames.size)
        assertArrayEquals("the following frame must still arrive", bytes("after"), frames[0])
        assertTrue("the dropped bytes should be accounted for", r.droppedBytes > 0)
    }

    @Test
    fun `the largest permitted frame is accepted`() {
        // The boundary, not just one side of it. An off-by-one that rejected exactly
        // maxFrameBytes would be invisible until a genuinely large message arrived.
        val r = Reassembler(maxFrameBytes = 1024)
        val big = ByteArray(1024) { (it % 251).toByte() }

        val frames = r.feed(Reassembler.frame(big))

        assertEquals(1, frames.size)
        assertArrayEquals(big, frames[0])
    }

    @Test
    fun `one byte over the ceiling is refused by frame()`() {
        // The sender side of the same limit. frame() is the only place a frame is produced,
        // so this is where an over-large message must fail -- loudly, at the point where
        // the caller can report it, rather than on the receiver as silent corruption.
        try {
            Reassembler.frame(ByteArray(Reassembler.MAX_FRAME_BYTES + 1))
            org.junit.Assert.fail("expected IllegalArgumentException for an over-ceiling frame")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `the frame header is exactly the documented shape`() {
        val frame = Reassembler.frame(bytes("xy"))

        assertEquals(6 + 2, frame.size)
        assertEquals('I'.code, frame[0].toInt())
        assertEquals('T'.code, frame[1].toInt())
        assertEquals('M'.code, frame[2].toInt())
        assertEquals('1'.code, frame[3].toInt())
        assertEquals(0, frame[4].toInt()) // length hi
        assertEquals(2, frame[5].toInt()) // length lo
    }

    @Test
    fun `a frame length is encoded big-endian`() {
        // A 300-byte payload puts a non-zero value in the high byte, which is the only
        // thing that distinguishes this from little-endian. One end reading one and the
        // other reading the other would produce a 300-byte frame described as 44 -- or
        // vice versa -- and every large message would fail with no useful error.
        val frame = Reassembler.frame(ByteArray(300))

        assertEquals(300, (frame[4].toInt() and 0xFF shl 8) or (frame[5].toInt() and 0xFF))
        assertEquals(1, frame[4].toInt())
        assertEquals(44, frame[5].toInt())
    }

    // ---- reset -------------------------------------------------------------------------

    @Test
    fun `reset discards a partial frame`() {
        // Called when a link is re-established. Without it, bytes from the previous
        // connection would be prepended to the first frame of the next one, corrupting a
        // message that was itself perfectly intact.
        val r = Reassembler()
        r.feed(Reassembler.frame(bytes("x")).copyOfRange(0, 4))
        r.reset()

        val frames = r.feed(Reassembler.frame(bytes("clean")))

        assertEquals(1, frames.size)
        assertArrayEquals(bytes("clean"), frames[0])
        assertEquals("reset should clear the diagnostic counter", 0L, r.droppedBytes)
    }

    /**
 * A mid-frame loss stalls the stream until the link is re-established.
 *
 * This documents a real limitation rather than asserting a behaviour the design does not
 * have. Once a length has been read, the parser is committed: it cannot know whether the
 * bytes that follow belong to this frame or are the beginning of the next one. So if a
 * link drops half-way through a message, the stale length swallows the following messages
 * as though they were its own body, and nothing further is delivered.
 *
 * Recovery is at the boundary, not here: `MessageRepository.attach()` installs a fresh
 * `Reassembler` on every successful connect. That is the correct place, because a link
 * change is the one event that guarantees the byte stream has restarted.
 *
 * ## Why not fix it in here
 *
 * The alternatives all cost something real. A checksum per frame detects the truncation and
 * lets the parser skip the bad body, at the cost of 4 bytes per message and a retry
 * protocol. A timeout would recover without either, but introduces a timing dependency --
 * a slow radio and a tight timeout means discarding good messages, which is the one thing
 * this app must not do.
 *
 * For a two-party chat over Bluetooth, where a lost link is visible to the user (the
 * conversation shows as disconnected) and reconnected deliberately, paying for a retry
 * protocol to speed up recovery of a case the user can already see and fix is not worth
 * it. Recorded in docs/LIMITATIONS.md so the trade is visible rather than discovered.
 */
    @Test
    fun `a partial frame then a full frame stalls until reset`() {
        val r = Reassembler()
        // Five bytes of a frame header: the magic and the high byte of a length.
        r.feed(Reassembler.frame(bytes("interrupted")).copyOfRange(0, 5))

        // The next real frame is absorbed as the body of the stale one.
        val frames = r.feed(Reassembler.frame(bytes("next")))

        assertEquals(
            "a committed length cannot be abandoned mid-frame",
            0,
            frames.size,
        )

        // Recovery is what a new link does.
        r.reset()
        val afterReset = r.feed(Reassembler.frame(bytes("next")))
        assertEquals(1, afterReset.size)
        assertArrayEquals(bytes("next"), afterReset[0])
    }

    // ---- buffer growth ------------------------------------------------------------------

    @Test
    fun `a frame larger than the initial buffer is reassembled`() {
        // The initial buffer is 2048 bytes, well above a typical message. A long message
        // must still work, and must still be delivered in one piece rather than truncated
        // to what fitted.
        val r = Reassembler()
        val big = ByteArray(9000) { (it % 256).toByte() }

        val frames = r.feed(Reassembler.frame(big))

        assertEquals(1, frames.size)
        assertEquals(big.size, frames[0].size)
        assertArrayEquals(big, frames[0])
    }

    @Test
    fun `a large frame arriving in MTU-sized chunks is reassembled`() {
        // The real BLE shape: the frame grows past the initial buffer *while* it is being
        // fed, so growth has to work on the accumulation path, not only on one big write.
        val r = Reassembler()
        val big = ByteArray(9000) { (it % 256).toByte() }
        val stream = Reassembler.frame(big)

        val collected = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + 244, stream.size)
            collected += r.feed(stream.copyOfRange(offset, end))
            offset = end
        }

        assertEquals(1, collected.size)
        assertArrayEquals(big, collected[0])
    }

    @Test
    fun `feeding nothing changes nothing`() {
        // A read timeout on a socket can legitimately deliver zero bytes. Treating that as
        // an error, or as a signal to resynchronise, would corrupt a partial frame that is
        // legitimately mid-flight.
        val r = Reassembler()
        r.feed(Reassembler.frame(bytes("partial")).copyOfRange(0, 8))

        assertEquals(emptyList<ByteArray>(), r.feed(ByteArray(0)))
        assertEquals(0L, r.droppedBytes)
    }
}