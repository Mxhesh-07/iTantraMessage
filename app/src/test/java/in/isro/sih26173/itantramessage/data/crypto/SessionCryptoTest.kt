package `in`.isro.sih26173.itantramessage.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the hex encoding and the transport cipher.
 *
 * ## The test that matters most
 *
 * [a ciphertext does not decrypt under another conversation's key] is the regression test for
 * the defect that made this whole change necessary. The bug was not that encryption was weak
 * or misused; it was that each phone held a *different* key, so the sender's ciphertext could
 * never be opened by the receiver. That failure was invisible to every existing test because
 * encrypt and decrypt were each correct in isolation.
 *
 * So the property asserted here is not "AES-GCM works", it is "two phones that agreed on a key
 * can read each other, and two phones that did not cannot".
 */
class SessionCryptoTest {

    private fun keyFor(conversation: String, seed: Int = 1) =
        SessionKeys.derive(ByteArray(32) { ((it + seed) and 0xFF).toByte() }, conversation)!!

    private val conversationA = "IT-AAAAAA|IT-BBBBBB"
    private val conversationB = "IT-CCCCCC|IT-DDDDDD"

    // ---- hex ---------------------------------------------------------------------------

    @Test
    fun `hex round trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.decode(Hex.encode(all)))
    }

    @Test
    fun `hex is lowercase and two characters per byte`() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun `hex round trips an odd number of bytes`() {
        val odd = ByteArray(5) { (it * 40).toByte() }
        assertArrayEquals(odd, Hex.decode(Hex.encode(odd)))
    }

    @Test
    fun `empty hex round trips to empty`() {
        assertEquals("", Hex.encode(ByteArray(0)))
        assertEquals(0, Hex.decode("")!!.size)
    }

    @Test
    fun `hex accepts upper case but never emits it`() {
        assertArrayEquals(byteArrayOf(-1), Hex.decode("FF"))
        assertEquals("ff", Hex.encode(byteArrayOf(-1)))
    }

    @Test
    fun `hex refuses an odd length`() {
        assertNull(Hex.decode("abc"))
    }

    @Test
    fun `hex refuses anything that is not a hex digit`() {
        assertNull(Hex.decode("zz"))
        assertNull(Hex.decode("00 11"))
        assertNull(Hex.decode("00\n11"))
        // A lenient decoder would let two distinct strings collapse to one ciphertext.
        assertNull(Hex.decode("0x"))
    }

    // ---- round trips -------------------------------------------------------------------

    @Test
    fun `ascii round trips`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        assertEquals("hello", crypto.decryptToText(crypto.encrypt("hello")))
    }

    @Test
    fun `an empty message round trips`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        assertEquals("", crypto.decryptToText(crypto.encrypt("")))
    }

    /** Emoji, CJK and combining marks are exactly where a charset mistake shows up. */
    @Test
    fun `unicode round trips exactly, not approximately`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val samples = listOf(
            "namaste 🙏",
            "你好，世界",
            "مرحبا",
            "é combining: é",
            "tab\tnewline\nquote\"back\\slash",
        )
        for (sample in samples) {
            assertEquals(sample, crypto.decryptToText(crypto.encrypt(sample)))
        }
    }

    /** A long message spanning many GCM blocks must round trip, not truncate. */
    @Test
    fun `a multi block message round trips`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val long = "x".repeat(10_000)
        assertEquals(long, crypto.decryptToText(crypto.encrypt(long)))
    }

    @Test
    fun `binary round trips`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val bytes = ByteArray(64) { it.toByte() }
        assertArrayEquals(bytes, crypto.decrypt(crypto.encrypt(bytes)))
    }

    // ---- the regression ----------------------------------------------------------------

    /**
     * The defect, restated as a test.
     *
     * Before this change there was no shared key: each device generated its own. A message
     * produced under one conversation's key must not open under another's, and if it ever
     * does, conversation scoping has collapsed and every message in the app is one keystore
     * away from any other.
     */
    @Test
    fun `a ciphertext does not decrypt under another conversation's key`() {
        val sent = SessionCrypto(keyFor(conversationA)).encrypt("hello from the other phone")
        assertNull(SessionCrypto(keyFor(conversationB)).decryptToText(sent))
    }

    /** And the same conversation on the other phone does open it. */
    @Test
    fun `the same conversation key opens the message from either phone`() {
        val onPhoneOne = SessionCrypto(keyFor(conversationA, seed = 1))
        val onPhoneTwo = SessionCrypto(keyFor(conversationA, seed = 1))
        val sent = onPhoneOne.encrypt("hello")
        assertEquals("hello", onPhoneTwo.decryptToText(sent))
    }

    /**
     * A different ECDH secret is a different conversation key, even on the same pair of ids.
     *
     * This is the "keys never left the radio" property: a phone that captured the public halves
     * cannot turn them into this key without a private half.
     */
    @Test
    fun `a different shared secret does not decrypt`() {
        val sent = SessionCrypto(keyFor(conversationA, seed = 1)).encrypt("hello")
        assertNull(SessionCrypto(keyFor(conversationA, seed = 2)).decryptToText(sent))
    }

    // ---- IV hygiene --------------------------------------------------------------------

    /**
     * The IV must never repeat under one key.
     *
     * Reusing an IV under one GCM key destroys the confidentiality of both messages and leaks
     * their XOR. There is no test that can prove uniqueness forever, so this checks a wide
     * sample, and the code takes the IV from the provider rather than generating it.
     */
    @Test
    fun `the iv never repeats`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val seen = HashSet<String>()
        repeat(500) { i ->
            val body = crypto.encrypt("message $i").removePrefix("s1")
            // 12-byte IV is the first 24 hex characters.
            val iv = body.substring(0, 24)
            assertTrue("IV repeated at message $i", seen.add(iv))
        }
    }

    @Test
    fun `two encryptions of the same text differ`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }

    /** The plaintext must not be sitting in the envelope. */
    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val envelope = crypto.encrypt("PLAINTEXT_MARKER")
        assertFalse(envelope.contains("PLAINTEXT_MARKER"))
    }

    // ---- hostile and damaged input -----------------------------------------------------

    @Test
    fun `a tampered ciphertext is refused`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val envelope = crypto.encrypt("hello")
        val flipped = flipLastHexDigit(envelope)
        assertNull("GCM must reject a modified ciphertext", crypto.decryptToText(flipped))
    }

    @Test
    fun `a tampered iv is refused`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val envelope = crypto.encrypt("hello")
        val body = envelope.removePrefix("s1")
        val flipped = "s1" + flipLastHexDigit(body.substring(0, 24)) + body.substring(24)
        assertNull(crypto.decryptToText(flipped))
    }

    @Test
    fun `a truncated envelope is refused`() {
        val crypto = SessionCrypto(keyFor(conversationA))
        val envelope = crypto.encrypt("hello")
        assertNull(crypto.decryptToText(envelope.substring(0, envelope.length - 8)))
    }

    @Test
    fun `a wrong tag is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt("itm1.1.aabb.ccdd"))
    }

    @Test
    fun `a missing tag is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt("aabbccdd"))
    }

    /** Too short to hold an IV plus a GCM tag, so refused before the cipher is touched. */
    @Test
    fun `an envelope too short to be legal is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt("s1" + "00".repeat(20)))
    }

    @Test
    fun `non hex content in the envelope is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt("s1zzzz" + "00".repeat(30)))
    }

    @Test
    fun `an empty envelope is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt(""))
    }

    @Test
    fun `an odd length envelope is refused`() {
        assertNull(SessionCrypto(keyFor(conversationA)).decrypt("s1abc"))
    }

    /** Flips the final hex digit to a different valid digit, so only the bytes change. */
    private fun flipLastHexDigit(hex: String): String {
        val last = hex[hex.length - 1]
        val replacement = if (last == '0') '1' else '0'
        return hex.substring(0, hex.length - 1) + replacement
    }
}