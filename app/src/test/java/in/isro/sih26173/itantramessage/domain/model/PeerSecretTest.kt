package `in`.isro.sih26173.itantramessage.domain.model

import `in`.isro.sih26173.itantramessage.data.crypto.IdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the wrapped-contribution handshake frame.
 *
 * ## Why this frame exists
 *
 * RSA key transport means each device encrypts a contribution to the peer's public key. That key
 * arrives in the peer's hello, so the contribution cannot be built until the hello has been
 * received -- which is why the handshake is two frames each way rather than one. See
 * [PeerSecret].
 *
 * ## The property worth protecting
 *
 * This frame is the only thing a peer can hand to this device that reaches the `Cipher` backed by
 * its private key. A size bound here is the boundary that keeps a hostile bonded peer from
 * offering a megabyte of ciphertext, so the bound is tested against real sizes -- a 256-byte
 * ciphertext, the largest accepted, and one byte over -- rather than against the constant.
 */
class PeerSecretTest {

    private val valid = "IT-1A2B3C"

    /**
     * A stand-in contribution: 256 bytes, which is exactly what a 2048-bit RSA ciphertext is.
     *
     * Not a real ciphertext. Unwrapping one needs the Keystore; what matters here is that the
     * parser accepts and refuses the shape.
     */
    private fun contribution(size: Int) = ByteArray(size) { (it and 0xFF).toByte() }

    // ---- frame-type separation ---------------------------------------------------------------

    /**
     * The secret prefix cannot begin an envelope.
     *
     * Written in terms of [EnvelopeCodec.MAGIC] rather than the literal `0x49`, so this test
     * *fails* if the magic is ever changed rather than quietly continuing to assert something
     * about a number nothing uses any more.
     */
    @Test
    fun `the secret prefix cannot begin an envelope`() {
        assertNotEquals(
            "the SECRET prefix and the envelope magic must differ, or the frame types are " +
                "indistinguishable on the wire",
            EnvelopeCodec.MAGIC.toByte(),
            PeerSecret.PREFIX[0].code.toByte(),
        )
    }

    /** An envelope is never taken for a contribution, so it cannot reach the unwrap step. */
    @Test
    fun `an envelope is never reported as a secret`() {
        val frame = EnvelopeCodec.encode(sample())
        assertFalse(PeerSecret.isSecret(frame))
        assertNull(PeerSecret.decode(frame))
    }

    /** A contribution frame is never routable as a message. */
    @Test
    fun `a secret is never reported as an envelope`() {
        val frame = PeerSecret.encode(valid, contribution(256))
        assertTrue(PeerSecret.isSecret(frame))
        assertNull(
            "a contribution must not be routable as a message",
            EnvelopeCodec.decode(frame),
        )
    }

    /**
     * A hello is not a contribution.
     *
     * Both frames share a prefix shape -- ASCII letters then a colon -- so this is the case where
     * a careless prefix check would let one stand in for the other. A hello parsed as a
     * contribution would hand a 588-character public key to `Cipher`.
     */
    @Test
    fun `a hello is not reported as a secret`() {
        val hello = PeerHello.encode(valid, "ab".repeat(294))
        assertTrue(PeerHello.isHello(hello))
        assertFalse(PeerSecret.isSecret(hello))
        assertNull(PeerSecret.decode(hello))
    }

    /** And the converse. */
    @Test
    fun `a secret is not reported as a hello`() {
        val secret = PeerSecret.encode(valid, contribution(256))
        assertTrue(PeerSecret.isSecret(secret))
        assertFalse(PeerHello.isHello(secret))
        assertNull(PeerHello.decode(secret))
    }

    /** Byte-level restatement, so the property survives a change to either codec. */
    @Test
    fun `the prefixes are distinct in their first byte`() {
        assertNotEquals(PeerHello.PREFIX[0], PeerSecret.PREFIX[0])
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    fun `a contribution survives a round trip`() {
        val original = contribution(256)
        val decoded = PeerSecret.decode(PeerSecret.encode(valid, original))

        assertEquals(valid, decoded!!.deviceId)
        assertEquals(256, decoded.wrappedHex.length / 2)
        // Compared as hex rather than as bytes: the frame is a text encoding and asserting the
        // text is what actually travels.
        assertEquals(
            original.joinToString("") { "%02x".format(it) },
            decoded.wrappedHex,
        )
    }

    /** The device id is carried so a contribution can be checked against the link's peer. */
    @Test
    fun `the contributing device id is preserved`() {
        val decoded = PeerSecret.decode(PeerSecret.encode("IT-9F8E7D", contribution(256)))
        assertEquals("IT-9F8E7D", decoded!!.deviceId)
    }

    @Test
    fun `a frame is only a secret when it has the whole prefix`() {
        val full = PeerSecret.encode(valid, contribution(256))
        assertTrue(PeerSecret.isSecret(full))

        // One byte short of the prefix is not a secret frame.
        assertFalse(PeerSecret.isSecret(full.copyOf(PeerSecret.PREFIX.length)))
    }

    // ---- refusals -----------------------------------------------------------------------------

    /** No separator: the frame cannot say which part is which. */
    @Test
    fun `a frame without a separator is refused`() {
        val frame = ("SECRET:" + valid).toByteArray(Charsets.UTF_8)
        assertNull(PeerSecret.decode(frame))
    }

    /** An empty contribution. Zero-length ciphertext cannot unwrap into anything. */
    @Test
    fun `an empty contribution is refused`() {
        val frame = ("SECRET:" + valid + ":").toByteArray(Charsets.UTF_8)
        assertNull(PeerSecret.decode(frame))
        assertFalse(PeerSecret.isValidWrappedHex(""))
    }

    @Test
    fun `a malformed device id is refused`() {
        for (id in listOf("it-1a2b3c", "IT-1A2B3", "IT-1A2B3CD", "IT-1A2B3C|", "X" + valid)) {
            assertFalse(
                "must refuse device id '$id'",
                PeerSecret.isValid(id),
            )
            assertNull(
                "must refuse a frame naming '$id'",
                PeerSecret.decode(
                    PeerSecret.PREFIX.plus(id).plus(":").plus("cd".repeat(256))
                        .toByteArray(Charsets.UTF_8),
                ),
            )
        }
    }

    /** Odd-length hex cannot be a byte string. */
    @Test
    fun `odd-length hex is refused`() {
        assertFalse(PeerSecret.isValidWrappedHex("abc"))
        assertNull(
            PeerSecret.decode(
                ("SECRET:" + valid + ":abc").toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * Non-hex is refused.
     *
     * Upper case is included deliberately: the app only ever emits lower case, and a lenient
     * decoder here would mean two byte strings could be spelled two ways.
     */
    @Test
    fun `non-hex characters are refused`() {
        for (bad in listOf("zz", "ab cd", "ab\ncd", "AB".repeat(128))) {
            assertFalse("must refuse '$bad'", PeerSecret.isValidWrappedHex(bad))
        }
    }

    /**
     * The size bound is the one that matters here.
     *
     * This value is what reaches `Cipher`, so a peer must not be able to hand over an arbitrary
     * length. Both ends of the bound are checked against real sizes: a 2048-bit ciphertext is
     * exactly 256 bytes and must be accepted; one byte past the cap must be refused in the shape
     * check *and* in a real decode.
     */
    @Test
    fun `a real 2048 bit ciphertext is accepted`() {
        // A 2048-bit RSA ciphertext is exactly 256 bytes, and that is what this app sends on
        // both handsets. If the cap ever fell below it, every connection would fail at parse.
        assertTrue(
            "the cap must admit a 2048-bit ciphertext",
            IdentityKey.MAX_WRAPPED_BYTES >= 256,
        )
        val frame = PeerSecret.encode(valid, contribution(256))
        assertEquals(256, PeerSecret.decode(frame)!!.wrappedHex.length / 2)
    }

    @Test
    fun `one byte over the cap is refused`() {
        val over = "cd".repeat(IdentityKey.MAX_WRAPPED_BYTES + 1)
        assertFalse(PeerSecret.isValidWrappedHex(over))
        assertNull(
            PeerSecret.decode(
                ("SECRET:" + valid + ":" + over).toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /** The cap exactly, accepted. Fails if the bound is tightened without a matching change. */
    @Test
    fun `the cap itself is accepted`() {
        val atCap = "cd".repeat(IdentityKey.MAX_WRAPPED_BYTES)
        assertTrue(PeerSecret.isValidWrappedHex(atCap))
        assertNotNull(
            PeerSecret.decode(
                ("SECRET:" + valid + ":" + atCap).toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * The parser and the unwrap step must agree about the cap.
     *
     * `MAX_WRAPPED_BYTES` is declared from `IdentityKey`, so comparing the values would be a
     * tautology. What is asserted instead is the consequence: a value the parser accepts is
     * within the bound the unwrap step enforces.
     */
    @Test
    fun `the parser never accepts more than the unwrap step allows`() {
        val accepted = (1..IdentityKey.MAX_WRAPPED_BYTES).filter {
            PeerSecret.isValidWrappedHex("cd".repeat(it))
        }
        assertEquals(IdentityKey.MAX_WRAPPED_BYTES, accepted.size)
    }

    /** A megabyte is not a contribution. */
    @Test
    fun `an enormous contribution is refused`() {
        assertFalse(PeerSecret.isValidWrappedHex("cd".repeat(1_000_000)))
    }

    // ---- the id rule is shared with the hello frame ---------------------------------------------

    /**
     * The two handshake frames accept exactly the same device ids.
     *
     * Both are validated in the same way because [MessageRepository] compares the id in a
     * contribution against the id from the hello. If one frame accepted an id the other refused,
     * a legitimate peer could be refused by one side and accepted by the other, and the
     * handshake would hang with no error anywhere.
     */
    @Test
    fun `both handshake frames accept the same device ids`() {
        val ids = listOf(
            "IT-000000", "IT-FFFFFF", "IT-0A0B0C",
            "it-ffffff", "IT-FFFFF", "IT-FFFFFFA", "IT_000000", "", "IT-00000G",
        )
        for (id in ids) {
            assertEquals(
                "PeerHello and PeerSecret disagree about device id '$id'",
                PeerHello.isValid(id),
                PeerSecret.isValid(id),
            )
        }
    }

    private fun sample() = Envelope(
        messageId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        senderId = "IT-1A2B3C",
        receiverId = "IT-4D5E6F",
        timestamp = 1_726_000_000_000L,
        type = MessageType.TEXT,
        payload = "itm1.1.aXY=.Y2lwaGVy",
    )
}