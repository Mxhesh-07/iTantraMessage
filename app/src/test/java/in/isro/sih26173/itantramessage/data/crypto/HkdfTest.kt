package `in`.isro.sih26173.itantramessage.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for HKDF and for the conversation key derivation.
 *
 * ## Why the RFC vectors are here
 *
 * The key agreement this supports is the step that makes two phones able to read each other at
 * all, and the failure mode is total and silent: a wrong HKDF produces two different keys, and
 * every message on every link renders as "Could not read that message". RFC 5869 test cases 1
 * and 3 are therefore checked here against the published values rather than against a value
 * this app's own implementation produced, which would only prove it is self-consistent.
 *
 * The vectors were confirmed by an independent implementation before being written down, so
 * these tests can fail if the Kotlin is wrong.
 */
class HkdfTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun hexOf(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    // ---- RFC 5869 ----------------------------------------------------------------------

    /**
     * RFC 5869 Appendix A, Test Case 1: non-empty salt and info.
     *
     * This is the case that catches an expand loop that drops the counter or repeats the
     * previous block.
     */
    @Test
    fun `rfc5869 test case 1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        assertArrayEquals(
            hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
            Hkdf.extract(salt, ikm),
        )
        assertArrayEquals(
            hex(
                "3cb25f25faacd57a90434f64d0362f2a" +
                    "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            ),
            Hkdf.derive(ikm, salt, info, 42),
        )
    }

    /**
     * RFC 5869 Appendix A, Test Case 3: **empty** salt and **empty** info.
     *
     * The case the app depends on, because an absent salt must become `HashLen` zero bytes.
     * Implementing the salt substitution wrong here yields a plausible key that matches no
     * other implementation.
     */
    @Test
    fun `rfc5869 test case 3 with empty salt and info`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")

        assertArrayEquals(
            hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"),
            Hkdf.extract(ByteArray(0), ikm),
        )
        assertArrayEquals(
            hex(
                "8da4e775a563c18f715f802a063c5a31" +
                    "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                    "9d201395faa4b61a96c8",
            ),
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42),
        )
    }

    /** An absent salt must be treated as zeros, not as an empty HMAC key. */
    @Test
    fun `an empty salt is not an empty HMAC key`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val asZeros = Hkdf.extract(ByteArray(Hkdf.HASH_LEN_BYTES), ikm)
        val asEmpty = Hkdf.extract(ByteArray(0), ikm)
        assertArrayEquals("an empty salt must expand to zeros", asZeros, asEmpty)
    }

    // ---- expand edge cases --------------------------------------------------------------

    @Test
    fun `asking for zero bytes yields nothing rather than throwing`() {
        val out = Hkdf.expand(ByteArray(32) { it.toByte() }, ByteArray(0), 0)
        assertEquals(0, out.size)
    }

    /**
     * A longer output must extend a shorter one, not replace it.
     *
     * `expand(64)` is `T(1)|T(2)` and `expand(32)` is `T(1)`, so the 32-byte result must be
     * the *prefix* of the 64-byte one. This is the property that catches a loop which
     * recomputes the first block from scratch, or which stops after one block because the
     * caller only ever wanted a key length.
     *
     * Note it is deliberately not `expand(32) + expand(32)`. L is the total output length, so
     * asking for 32 twice yields `T(1)|T(1)`, not `T(1)|T(2)`. Asserting the wrong version of
     * this would have failed against a correct implementation.
     */
    @Test
    fun `a longer output extends a shorter one`() {
        val prk = ByteArray(32) { (it * 7).toByte() }
        val info = "x".toByteArray()
        val short = Hkdf.expand(prk, info, 32)
        val long = Hkdf.expand(prk, info, 64)
        assertEquals(64, long.size)
        assertArrayEquals("the first block must be identical", short, long.copyOf(32))
        assertFalse(
            "the second block must not repeat the first",
            short.contentEquals(long.copyOfRange(32, 64)),
        )
    }

    @Test
    fun `a short final block is truncated to the requested length`() {
        val prk = ByteArray(32) { it.toByte() }
        val out = Hkdf.expand(prk, ByteArray(0), 5)
        assertEquals(5, out.size)
    }

    @Test
    fun `the maximum length is accepted and one more is refused`() {
        val prk = ByteArray(32) { it.toByte() }
        assertEquals(
            Hkdf.MAX_OUTPUT_BYTES,
            Hkdf.expand(prk, ByteArray(0), Hkdf.MAX_OUTPUT_BYTES).size,
        )
        var threw = false
        try {
            Hkdf.expand(prk, ByteArray(0), Hkdf.MAX_OUTPUT_BYTES + 1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an unrepresentable length must be refused", threw)
    }

    @Test
    fun `a negative length is refused rather than silently truncated`() {
        var threw = false
        try {
            Hkdf.expand(ByteArray(32), ByteArray(0), -1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    /** Different info must produce different output, or the conversation binding is fiction. */
    @Test
    fun `info changes the output`() {
        val prk = ByteArray(32) { it.toByte() }
        assertFalse(
            hexOf(Hkdf.expand(prk, "a".toByteArray(), 32)) ==
                hexOf(Hkdf.expand(prk, "b".toByteArray(), 32)),
        )
    }

    // ---- the conversation key -----------------------------------------------------------

    private fun secret(seed: Int): ByteArray =
        ByteArray(32) { ((it + seed) and 0xFF).toByte() }

    /**
     * The property the whole fix rests on.
     *
     * Two phones that ran ECDH must arrive at the same key. If this fails, every message is
     * unreadable in both directions and nothing in the app says why.
     */
    @Test
    fun `both sides derive the same key from the same secret and conversation`() {
        val conversation = "IT-AAAAAA|IT-BBBBBB"
        val ours = SessionKeys.derive(secret(1), conversation)
        val theirs = SessionKeys.derive(secret(1), conversation)
        assertNotNull(ours)
        assertNotNull(theirs)
        assertArrayEquals(ours!!.encoded, theirs!!.encoded)
    }

    /** Different conversations must not share a key, or a ciphertext moves between them. */
    @Test
    fun `a different conversation yields a different key`() {
        val a = SessionKeys.derive(secret(1), "IT-AAAAAA|IT-BBBBBB")!!
        val b = SessionKeys.derive(secret(1), "IT-AAAAAA|IT-CCCCCC")!!
        assertFalse(a.algorithm == b.algorithm && a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `a different shared secret yields a different key`() {
        val a = SessionKeys.derive(secret(1), "IT-AAAAAA|IT-BBBBBB")!!
        val b = SessionKeys.derive(secret(2), "IT-AAAAAA|IT-BBBBBB")!!
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `the key is AES 256`() {
        val key = SessionKeys.derive(secret(1), "IT-AAAAAA|IT-BBBBBB")!!
        assertEquals("AES", key.algorithm)
        assertEquals(SessionKeys.KEY_SIZE_BYTES, key.encoded.size)
        assertEquals(256, SessionKeys.KEY_SIZE_BITS)
    }

    /**
     * The key must not be made of zeroes.
     *
     * `SecretKeySpec` copies its input, so wiping the derivation output afterwards is safe.
     * If that ever stops being true the key here would silently become 32 zero bytes and every
     * message would fail to decrypt.
     */
    @Test
    fun `wiping the derivation output does not zero the key`() {
        val key = SessionKeys.derive(secret(9), "IT-AAAAAA|IT-BBBBBB")!!
        assertFalse("derived key is all zeroes", key.encoded.all { it == 0.toByte() })
    }

    /** The caller's ECDH output is wiped, because this is the only place it exists in clear. */
    @Test
    fun `the shared secret is wiped after derivation`() {
        val raw = secret(3)
        SessionKeys.derive(raw, "IT-AAAAAA|IT-BBBBBB")
        assertTrue("shared secret must be zeroed", raw.all { it == 0.toByte() })
    }

    /** An empty secret is refused instead of deriving a key from nothing. */
    @Test
    fun `an empty shared secret is refused`() {
        assertNull(SessionKeys.derive(ByteArray(0), "IT-AAAAAA|IT-BBBBBB"))
    }

    /** Derivation is deterministic, so a device can re-derive its key after a process death. */
    @Test
    fun `derivation is deterministic across calls`() {
        val first = SessionKeys.derive(secret(4), "IT-AAAAAA|IT-BBBBBB")!!.encoded
        val second = SessionKeys.derive(secret(4), "IT-AAAAAA|IT-BBBBBB")!!.encoded
        assertArrayEquals(first, second)
    }

    @Test
    fun `the salt is handed out as a copy so callers cannot corrupt the derivation`() {
        val first = SessionKeys.salt()
        first.fill(0)
        assertFalse("salt must not be a shared mutable array", SessionKeys.salt().all { it == 0.toByte() })
    }
}