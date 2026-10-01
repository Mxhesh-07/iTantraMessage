package `in`.isro.sih26173.itantramessage.domain.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Tests for the wire codec.
 *
 * ## What these tests are actually protecting
 *
 * Not "the codec round-trips". A round-trip test passes just as happily when both the
 * encoder and the decoder share the same mistake. The tests below are mostly *negative*:
 * each takes a valid frame and corrupts one field, then asserts the frame is rejected.
 * That is the property the receive path depends on, because the receive path is fed bytes
 * from a radio by anyone in range, and the only safe response to malformed input is to
 * drop it.
 */
class EnvelopeCodecTest {

    private fun validEnvelope(
        messageId: String = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        senderId: String = "IT-1A2B3C",
        receiverId: String = "IT-4D5E6F",
        timestamp: Long = 1_726_000_000_000L,
        payload: String = "itm1.1.aXY=.Y2lwaGVy",
    ) = Envelope(messageId, senderId, receiverId, timestamp, MessageType.TEXT, payload)

    // ---- the happy path ---------------------------------------------------------------

    @Test
    fun `encode then decode preserves every field`() {
        val original = validEnvelope()

        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(original))

        assertNotEquals("decode should have produced a value", null, decoded)
        assertEquals(original.messageId, decoded!!.messageId)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.receiverId, decoded.receiverId)
        assertEquals(original.timestamp, decoded.timestamp)
        assertEquals(original.type, decoded.type)
        assertEquals(original.payload, decoded.payload)
    }

    /**
     * The MTU check depends on [Envelope.wireSize] agreeing with what encode actually emits.
     *
     * If these drift apart, the transport either splits a frame that does not need
     * splitting or writes one that exceeds the negotiated MTU and is silently dropped by
     * the peer's stack. Nothing else in the codebase would catch that.
     */
    @Test
    fun `wireSize agrees with the encoded length`() {
        for (payloadLength in listOf(0, 1, 20, 512, 4096)) {
            val envelope = validEnvelope(payload = "x".repeat(payloadLength))
            val encoded = EnvelopeCodec.encode(envelope)
            assertEquals(
                "payload of $payloadLength chars",
                envelope.wireSize,
                encoded.size,
            )
        }
    }

    @Test
    fun `payload bytes are UTF-8 on the wire`() {
        // A payload with characters outside ASCII. Base64 of an encrypted body is always
        // ASCII, so this test is really checking that the *length* field counts UTF-8
        // bytes rather than chars -- a String length for a multi-byte payload would
        // truncate the frame and the receiver would see a short payload.
        val envelope = validEnvelope(payload = "itm1.1.aXY=.éèê")
        val encoded = EnvelopeCodec.encode(envelope)
        assertEquals(envelope.wireSize, encoded.size)
        assertEquals(envelope.payload, EnvelopeCodec.decode(encoded)?.payload)
    }

    // ---- rejection: the property that matters ------------------------------------------

    @Test
    fun `a frame shorter than the header is rejected`() {
        val truncated = ByteArray(EnvelopeCodec.HEADER_BYTES - 1)
        assertNull(EnvelopeCodec.decode(truncated))
    }

    @Test
    fun `an empty frame is rejected`() {
        assertNull(EnvelopeCodec.decode(ByteArray(0)))
    }

    @Test
    fun `a wrong magic byte is rejected`() {
        val bytes = EnvelopeCodec.encode(validEnvelope())
        bytes[0] = 'X'.code.toByte()
        assertNull(EnvelopeCodec.decode(bytes))
    }

    @Test
    fun `an unknown version is rejected`() {
        // This is what the version tag is for: a future peer running a different protocol
        // must be refused, not misparsed.
        val bytes = EnvelopeCodec.encode(validEnvelope())
        bytes[1] = 99
        assertNull(EnvelopeCodec.decode(bytes))
    }

    @Test
    fun `an unknown message type is rejected`() {
        val bytes = EnvelopeCodec.encode(validEnvelope())
        bytes[2] = 42
        assertNull(EnvelopeCodec.decode(bytes))
    }

    @Test
    fun `a payload length longer than the bytes present is rejected`() {
        // The single most important rejection in this file. A hostile or corrupt peer can
        // claim any length; believing it means either allocating gigabytes or throwing.
        // The decoder must refuse *before* it reads a payload.
        val bytes = EnvelopeCodec.encode(validEnvelope())
        val lengthOffset = EnvelopeCodec.HEADER_BYTES - 2
        val absurd = 0x7FFF // 32767, far beyond this frame's actual payload
        bytes[lengthOffset] = (absurd ushr 8).toByte()
        bytes[lengthOffset + 1] = (absurd and 0xFF).toByte()

        assertNull(EnvelopeCodec.decode(bytes))
    }

    @Test
    fun `a payload length beyond the absolute ceiling is rejected`() {
        // Distinct from the previous test: that one over-declares within the frame, this
        // one declares a length larger than MAX_PAYLOAD_BYTES. Both must be refused, and
        // neither may attempt an allocation.
        val bytes = EnvelopeCodec.encode(validEnvelope())
        val lengthOffset = EnvelopeCodec.HEADER_BYTES - 2
        val tooBig = 0xFFFF
        bytes[lengthOffset] = (tooBig ushr 8).toByte()
        bytes[lengthOffset + 1] = (tooBig and 0xFF).toByte()

        assertNull(EnvelopeCodec.decode(bytes))
    }

    @Test
    fun `a truncated payload is rejected rather than returned short`() {
        val bytes = EnvelopeCodec.encode(validEnvelope(payload = "abcdefghij"))
        val truncated = bytes.copyOf(bytes.size - 4)
        assertNull(EnvelopeCodec.decode(truncated))
    }

    // ---- trailing data ----------------------------------------------------------------

    /**
     * A trailing byte is tolerated, not rejected.
     *
     * Deliberate, and this test exists to stop a future reader "fixing" it. A BLE
     * transport reassembles whatever the radio delivered, so a padding byte at the end of
     * the buffer is an ordinary outcome. Rejecting the frame would make a message
     * unrecoverable over the transport most likely to produce padding -- the message would
     * be lost for a reason that looks like corruption and is not.
     */
    @Test
    fun `a trailing padding byte does not prevent decoding`() {
        val original = validEnvelope()
        val bytes = EnvelopeCodec.encode(original)
        val padded = bytes.copyOf(bytes.size + 1)

        val decoded = EnvelopeCodec.decode(padded)

        assertNotEquals(null, decoded)
        assertEquals(original.payload, decoded!!.payload)
        assertEquals(original.messageId, decoded.messageId)
    }

    // ---- id encoding ------------------------------------------------------------------

    @Test
    fun `a UUID message id survives the round trip exactly`() {
        val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(messageId = id)))
        assertEquals(id, decoded?.messageId)
    }

    /**
     * Pins the regression that forced the message id field to be 16 bytes.
     *
     * These two ids differ *only* in bytes 8..11, the part of the UUID that the previous
     * 12-byte field dropped (it kept the high 8 bytes and the low 4). Under that layout
     * both ids decoded to the same value, so a receiver would classify the second message
     * as a duplicate of the first and silently never store it -- one message visible where
     * two were sent, with no error anywhere.
     *
     * The assertion is that each id comes back as *itself*, not merely that they differ:
     * a truncating codec satisfies "they differ" while still destroying both ids.
     */
    @Test
    fun `ids differing only in the high-half low dword survive distinctly`() {
        val a = "f47ac10b-58cc-4372-0000-0000b2c3d479"
        val b = "f47ac10b-58cc-4372-9a0c-0000b2c3d479"

        assertEquals(
            "id A must survive exactly",
            a,
            EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(messageId = a)))?.messageId,
        )
        assertEquals(
            "id B must survive exactly",
            b,
            EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(messageId = b)))?.messageId,
        )
    }

    @Test
    fun `ids differing only in the low half survive distinctly`() {
        // The half the old layout did keep, checked as well so that neither end of the
        // 16-byte field can quietly go back to being ignored.
        val a = "f47ac10b-58cc-4372-a567-0e0200000000"
        val b = "f47ac10b-58cc-4372-a567-0e02ffffffff"

        assertEquals(a, EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(messageId = a)))?.messageId)
        assertEquals(b, EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(messageId = b)))?.messageId)
    }

    /**
     * Reversed from an earlier version of this test, which asserted a non-UUID id was
     * accepted and zero-padded.
     *
     * It did "work" -- and silently changed the id, which is exactly the failure the
     * 16-byte field exists to remove. Packing an arbitrary string into 12 bytes means the
     * value that comes back out is not the value that went in, and since this id is the
     * deduplication key, a silently altered id can make two different messages look like
     * one.
     *
     * Rejecting loudly is the correct trade: every id in this app comes from
     * `UUID.randomUUID()`, so this guards the protocol's own assumption rather than
     * blocking a path the UI can reach.
     */
    @Test
    fun `a non-UUID message id is rejected loudly at the boundary`() {
        try {
            EnvelopeCodec.encode(validEnvelope(messageId = "custom-id-abc"))
            fail("expected IllegalArgumentException for a non-UUID message id")
        } catch (expected: IllegalArgumentException) {
            // Expected. The message must name the problem rather than being an empty
            // IllegalArgumentException, since this surfaces in a log during triage.
            assertTrue(
                "the exception should mention the id",
                expected.message?.contains("messageId") == true,
            )
        }
    }

    /**
     * HEADER_BYTES is the sum of the field widths in the table in [Envelope]'s comment.
     *
     * Written out here as arithmetic instead of reused from the codec, because a constant
     * that asserts its own value proves nothing. If someone adds a field to the format and
     * forgets this number, the transport starts splitting frames at the wrong offset --
     * a silent wire corruption rather than a compile error.
     */
    @Test
    fun `the header length equals the documented field widths`() {
        // 3 framing + 16 messageId + 9 senderId + 9 receiverId + 8 timestamp + 2 payloadLen
        val expected = 3 + 16 + 9 + 9 + 8 + 2
        assertEquals(expected, EnvelopeCodec.HEADER_BYTES)
    }

    // ---- device ids: the second silent-truncation bug -------------------------------------

    /**
     * Pins the second instance of the same bug, which was found by this same test class.
     *
     * The device id field was 6 bytes -- the width of the hex payload, not of the whole
     * `IT-%06X` string, which is 9 characters -- and the encoder did `id.take(6)`. So
     * `IT-1A2B3C` went on the wire as `IT-1A2` and `IT-1A2B3D` went as exactly the same
     * bytes. Two different phones were the same device as far as the protocol was
     * concerned.
     *
     * The assertion is that each id comes back as itself. A truncating codec passes a
     * "do they differ?" check, which is why this is written as round-trip equality.
     */
    @Test
    fun `device ids differing only in their last characters survive distinctly`() {
        val a = validEnvelope(senderId = "IT-1A2B3C")
        val b = validEnvelope(senderId = "IT-1A2B3D")

        val decodedA = EnvelopeCodec.decode(EnvelopeCodec.encode(a))
        val decodedB = EnvelopeCodec.decode(EnvelopeCodec.encode(b))

        assertEquals("IT-1A2B3C", decodedA?.senderId)
        assertEquals("IT-1A2B3D", decodedB?.senderId)
    }

    @Test
    fun `a real IT-XXXXXX id round-trips exactly`() {
        // The actual shape DeviceIdentity produces, including the prefix. This is the id
        // that appears in the database, on the wire, and in the UI, and all three must be
        // the same string.
        val id = "IT-8F3A21"
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(senderId = id)))
        assertEquals(id, decoded?.senderId)
    }

    @Test
    fun `every device id in the full id space is distinct`() {
        // 24 bits of entropy -- the whole space DeviceIdentity can produce -- encoded and
        // checked for collisions. 4096 ids is a sample, not the full 16.7 M, so this is a
        // smoke test rather than a proof; the round-trip tests above are what actually pin
        // the behaviour. Included because a sample that finds nothing still catches the
        // gross failure modes (all-zero padding, a dropped high byte, an off-by-one).
        val ids = (1 until 4096).map { String.format("IT-%06X", it) }.toSet()
        assertEquals("id format collided with itself", 4095, ids.size)

        val decoded = ids.map {
            EnvelopeCodec.decode(EnvelopeCodec.encode(validEnvelope(senderId = it)))?.senderId
        }.toSet()

        assertEquals("distinct device ids collapsed on the wire", ids.size, decoded.size)
        assertTrue("every decoded id must be one we sent", decoded.containsAll(ids))
    }

    @Test
    fun `an over-long device id is rejected rather than truncated`() {
        // The loud failure the truncation used to hide. A 10-character id does not fit the
        // 9-byte field, and passing the first 9 through would address a different device
        // than the one the sender meant.
        try {
            EnvelopeCodec.encode(validEnvelope(senderId = "IT-1A2B3CDE"))
            fail("expected IllegalArgumentException for an over-long device id")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the exception should mention the device id",
                expected.message?.contains("device id") == true,
            )
        }
    }

    // ---- size limits ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `encoding an over-large payload is rejected at the boundary`() {
        // encode() throws rather than truncating. A truncated frame would be a corrupt
        // frame on the wire, and the caller has to know its payload was too big so it can
        // tell the user rather than sending garbage.
        EnvelopeCodec.encode(validEnvelope(payload = "x".repeat(70_000)))
    }

    @Test
    fun `a payload just under the limit still encodes`() {
        val payload = "x".repeat(60_000)
        val bytes = EnvelopeCodec.encode(validEnvelope(payload = payload))
        assertEquals(payload, EnvelopeCodec.decode(bytes)?.payload)
    }

    // ---- byte-level determinism -------------------------------------------------------

    @Test
    fun `encoding is deterministic for identical input`() {
        // Two encodes of the same envelope must be byte-identical. The wire format has no
        // random nonce of its own -- the IV lives inside the encrypted payload -- so any
        // variation here would mean something non-deterministic had leaked into the
        // header, which would break any future content-addressed deduplication.
        val envelope = validEnvelope()

        assertArrayEquals(
            EnvelopeCodec.encode(envelope),
            EnvelopeCodec.encode(envelope),
        )
    }

    @Test
    fun `the header is big-endian`() {
        // Pinned because both ends must agree and neither reads the other's source. The
        // timestamp is the easiest field to check: 0x0102030405060708 in big-endian is
        // 01 02 03 ... and in little-endian is 08 07 06 ...
        val timestamp = 0x0102030405060708L
        val bytes = EnvelopeCodec.encode(validEnvelope(timestamp = timestamp))

        val timestampOffset = EnvelopeCodec.HEADER_BYTES - 10
        assertEquals(0x01, bytes[timestampOffset].toInt() and 0xFF)
        assertEquals(0x08, bytes[timestampOffset + 7].toInt() and 0xFF)
    }

    /**
 * Every field sits at the offset the table in [Envelope] claims.
 *
 * This replaces a "the header is mostly non-printable" assertion that had been standing in
 * for it. That assertion failed at 25 of 47 printable bytes, and it was wrong to fail:
 * `IT-1A2B3C` is ASCII, a UUID's bytes are printable about half the time, and padding is
 * NUL -- so roughly half a real header is printable no matter how it is built. Counting
 * printable bytes measures the field *values*, not the format.
 *
 * Offsets are the property that actually matters. A field written one byte early or late
 * corrupts every frame without any error: a receiver would read a valid-looking timestamp
 * from the middle of the id, or a length from inside the address, and the only symptom
 * would be messages that silently fail to decrypt. Nothing else in the codebase or the
 * compiler can see this, because both ends read the same constants.
 *
 * Each assertion below names the byte offset from the documented table, so a layout change
 * makes this test fail at the first field that moved rather than silently.
 */
    @Test
    fun `every field sits at its documented offset`() {
        val timestamp = 0x0102030405060708L
        val payload = "itm1.1.aXY="
        val envelope = validEnvelope(
            messageId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            senderId = "IT-1A2B3C",
            receiverId = "IT-4D5E6F",
            timestamp = timestamp,
            payload = payload,
        )
        val b = EnvelopeCodec.encode(envelope)

        fun hex(offset: Int) = b[offset].toInt() and 0xFF

        // offset 0: magic
        assertEquals("magic at 0", 0x49, hex(0))
        // offset 1: version
        assertEquals("version at 1", 1, hex(1))
        // offset 2: type ordinal. TEXT is the first enum entry.
        assertEquals("type at 2", MessageType.TEXT.ordinal, hex(2))

        // offset 3..18: the UUID, high 8 bytes then low 8, big-endian each.
        // msb = 3f 25 04 e0 4f 89 11 d3   (from "3f2504e0-4f89-11d3")
        // lsb = 9a 0c 03 05 e8 2c 33 01   (from "9a0c-0305e82c3301")
        assertEquals("uuid msb[0] at 3", 0x3f, hex(3))
        assertEquals("uuid msb[7] at 10", 0xd3, hex(10))
        assertEquals("uuid lsb[0] at 11", 0x9a, hex(11))
        assertEquals("uuid lsb[3] at 14", 0x05, hex(14))
        assertEquals("uuid lsb[7] at 18", 0x01, hex(18))

        // offset 19..27: senderId, 9 bytes, NUL padded
        assertEquals("senderId[0] at 19", 'I'.code, hex(19))
        assertEquals("senderId[1] at 20", 'T'.code, hex(20))
        assertEquals("senderId[2] at 21", '-'.code, hex(21))
        assertEquals("senderId[8] at 27", 'C'.code, hex(27))

        // offset 28..36: receiverId, 9 bytes, NUL padded
        assertEquals("receiverId[0] at 28", 'I'.code, hex(28))
        assertEquals("receiverId[8] at 36", 'F'.code, hex(36))

        // offset 37..44: timestamp, big-endian
        assertEquals("timestamp[0] at 37", 0x01, hex(37))
        assertEquals("timestamp[7] at 44", 0x08, hex(44))

        // offset 45..46: payload length, big-endian
        val expectedLength = payload.toByteArray(Charsets.UTF_8).size
        assertEquals("payloadLen hi at 45", (expectedLength ushr 8) and 0xFF, hex(45))
        assertEquals("payloadLen lo at 46", expectedLength and 0xFF, hex(46))

        // offset 47..: payload, verbatim
        assertEquals("payload starts at 47", 'i'.code, hex(47))
        assertEquals(
            "total frame length",
            EnvelopeCodec.HEADER_BYTES + expectedLength,
            b.size,
        )
    }

    /**
     * The encrypted payload is Base64, so it is printable text by design.
     *
     * Stated explicitly because the test it replaced implied the whole frame was binary.
     * It is not: the header is fixed-width binary, the payload is Base64. Both are
     * deliberate -- binary for the header so a receiver can walk it without parsing,
     * Base64 for the payload so ciphertext survives any transport that would mangle raw
     * bytes.
     */
    @Test
    fun `the payload is printable Base64`() {
        val base64Like = "itm1.1." + "A".repeat(96) + "=.Q2lwaGVy"
        val b = EnvelopeCodec.encode(validEnvelope(payload = base64Like))
        val payload = b.copyOfRange(EnvelopeCodec.HEADER_BYTES, b.size)

        val nonPrintable = payload.count { it.toInt() !in 0x20..0x7E }
        assertEquals("Base64 payload should have no non-printable bytes", 0, nonPrintable)
    }

    @Test
    fun `utf8 is used explicitly rather than the platform default charset`() {
        // If the codec used Charset.defaultCharset() the bytes would differ between a
        // phone and a CI machine with a different default, and the failure would look like
        // a protocol bug rather than an encoding one.
        val payload = "itm1.1.aXY=.Ünïcödé"
        val bytes = EnvelopeCodec.encode(validEnvelope(payload = payload))

        val expectedLength = EnvelopeCodec.HEADER_BYTES + payload.toByteArray(StandardCharsets.UTF_8).size
        assertEquals(expectedLength, bytes.size)
    }
}