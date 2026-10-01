package `in`.isro.sih26173.itantramessage.data.crypto

/**
 * Hex, by hand, because the app needs a text encoding it can test.
 *
 * ## Why not `android.util.Base64` or `java.util.Base64`
 *
 * `android.util.Base64` is a stub in JVM unit tests. This module sets
 * `isReturnDefaultValues = true`, so it does not throw -- it returns `null`. That is worse than
 * throwing: an encrypt built on it produces `null` in a test and a real string on a phone, and
 * the round-trip test fails for a reason that has nothing to do with the crypto.
 *
 * `java.util.Base64` is real on the JVM and needs no stub, but it is API 26. This module
 * declares `minSdk 24` deliberately, so calling it would link on the two test phones and crash
 * on Android 7.
 *
 * ## Why hex rather than base64 anyway
 *
 * Size, and nothing else: hex costs 2x where base64 costs 1.33x. For a text messenger the
 * absolute saving is a few dozen bytes on a payload that is capped at 64 KB, and in exchange
 * the entire encoding is about thirty lines of table lookup with no dependency, no API-level
 * floor, and a test that can drive every byte value through it.
 *
 * ## Why the decoder is strict
 *
 * The decoder rejects anything that is not exactly two hex digits per byte. A lenient decoder
 * that skips whitespace or ignores stray characters would let a hostile peer craft two distinct
 * byte strings that decode to the same ciphertext, which is a decryption oracle.
 */
internal object Hex {

    private const val DIGITS = "0123456789abcdef"

    /**
     * @return lowercase hex, two characters per byte. Empty in, empty out.
     */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * @return the bytes, or null if [text] is not an even-length run of hex digits.
     *   `null` rather than an exception: every caller is a receive path.
     */
    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        if (text.isEmpty()) return ByteArray(0)

        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = nibble(text[i * 2]) ?: return null
            val lo = nibble(text[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** @return the value of one hex digit, or null if it is not one. */
    private fun nibble(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}