package `in`.isro.sih26173.itantramessage.data.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The message cipher, keyed by a key both phones derived.
 *
 * ## What this replaces
 *
 * `EncryptionManager` encrypts with this device's own random Keystore key, so it can read its
 * own stored rows and nobody else's -- including the rows a peer just sent it. That was the
 * reason a message sent from the realme arrived on the Samsung as "Could not read that
 * message": the bytes were on the wire and the handshake had already succeeded, but the
 * receiver had no key that could open them.
 *
 * [EncryptionManager] is kept for a different job, protecting the local database at rest, where
 * a per-device key is exactly right. This class covers the half that needs both phones to
 * agree.
 *
 * ## Wire format
 *
 * ```
 * s1<hex(iv)><hex(ct)>
 * ```
 *
 * The IV is a fixed [IV_BYTES] so the split between IV and ciphertext is unambiguous, and the
 * `s1` prefix distinguishes this from the `itm1.` envelope the at-rest layer uses. Being able
 * to tell them apart in a log is worth the two characters.
 *
 * ## Why the IV is fixed at [IV_BYTES]
 *
 * It is what `Cipher` generates for AES-GCM unless told otherwise, and GCM's security rests on
 * never reusing an IV under one key. A fixed length also means the decoder needs no length
 * field, so there is nothing for a hostile peer to lie about.
 *
 * ## Why this is testable and [EncryptionManager] is not
 *
 * Everything here is `javax.crypto`, which the JVM implements, so the whole cipher can be
 * tested without an Android Keystore -- including the property that actually matters, that a
 * ciphertext under one conversation's key does not decrypt under another's. That is the
 * regression test for the defect described above, and it is a pure unit test.
 */
class SessionCrypto(private val key: SecretKey) {

    /**
     * Encrypt [plaintext] under this conversation's key.
     *
     * @return the `s1...` envelope. Never throws for a usable key and non-empty plaintext.
     */
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks the IV. There is no path in this app that lets a caller choose
        // one, which is the only way GCM stays safe here.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return TAG + Hex.encode(iv) + Hex.encode(ciphertext)
    }

    /** Encrypt [text] as UTF-8. */
    fun encrypt(text: String): String = encrypt(text.toByteArray(Charsets.UTF_8))

    /**
     * Open an `s1...` envelope.
     *
     * @return the plaintext, or null if the envelope is malformed, is truncated, or fails
     *   authentication. Null rather than an exception because every caller is a receive path
     *   that must treat bad input from the radio as ordinary traffic.
     */
    fun decrypt(envelope: String): ByteArray? {
        if (!envelope.startsWith(TAG)) return null

        val body = envelope.substring(TAG.length)
        // IV plus the GCM tag is the smallest legal ciphertext. Anything shorter cannot
        // authenticate, and letting it reach the cipher turns a clear "malformed" into a
        // provider-specific exception.
        val minimumHexChars = (IV_BYTES + GCM_TAG_BYTES) * 2
        if (body.length < minimumHexChars) return null

        val bytes = Hex.decode(body) ?: return null
        if (bytes.size < IV_BYTES + GCM_TAG_BYTES) return null

        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            // Wrong key, tampered ciphertext, or a provider quirk. Never logged with the
            // ciphertext -- see the no-plaintext-logging rule in docs/SECURITY.md.
            null
        }
    }

    /**
     * Open an envelope and read it as UTF-8.
     *
     * @return null if the envelope does not decrypt *or* the plaintext is not valid UTF-8.
     *   Reporting both as unreadable is deliberate: a caller cannot tell a mangled encoding
     *   from a wrong key, and a message that is not valid text is not displayable anyway.
     */
    fun decryptToText(envelope: String): String? {
        val bytes = decrypt(envelope) ?: return null
        val text = String(bytes, Charsets.UTF_8)
        // Round-tripping proves the bytes really were UTF-8. Without this a message that
        // decrypted but did not decode would render as replacement characters, which looks
        // like corruption the user caused.
        if (text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return text
        return null
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "s1"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    }
}