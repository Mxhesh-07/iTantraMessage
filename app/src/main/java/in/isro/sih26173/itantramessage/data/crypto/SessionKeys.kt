package `in`.isro.sih26173.itantramessage.data.crypto

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Turns an ECDH shared secret into the AES key that protects one conversation.
 *
 * ## The defect this replaces
 *
 * The app shipped a message key that was a **per-device random Keystore key**. It could read
 * its own messages and nothing else, ever: the sender encrypted with the key inside its own
 * secure hardware and the receiver asked its own Keystore for *its* key, so GCM
 * authentication could never succeed. That was found on hardware, as a message that arrived
 * and rendered as "Could not read that message". The unit tests could not find it, because
 * `encrypt`/`decrypt` were each perfectly correct in isolation -- there was simply no step
 * anywhere that made the two devices hold the same key.
 *
 * ## What this does instead
 *
 * Both devices run ECDH over the link, arrive at the same shared secret, and derive the same
 * AES key from it. Neither key is ever transmitted; only the public halves are, in the
 * identification handshake.
 *
 * ## Why the conversation is mixed in
 *
 * The ECDH output is bound to the *pair* of keys, not to a conversation, so the same 32 bytes
 * would otherwise be the message key for every conversation those two phones ever have. A
 * message is authenticated with GCM but not *named* with it, so a ciphertext from one
 * conversation would decrypt cleanly in another and the receiver could not tell which it was.
 * Putting the conversation id in HKDF's `info` makes that impossible without transmitting
 * anything extra.
 *
 * The conversation id is safe to bind because both devices compute it identically: it sorts
 * the two device ids before joining them, so it does not depend on who dialled. See
 * [in.isro.sih26173.itantramessage.domain.model.ConversationId].
 *
 * ## Why the salt is a constant
 *
 * A salt is not a secret. Its job is to make the extraction well defined and to stop one
 * derived key from being reusable across unrelated derivations of the same input. Publishing
 * it costs nothing and keeps the derivation reproducible from the source, which is worth more
 * than the defence it gives up against an attacker who already has the source.
 *
 * ## Why the secret is wiped here
 *
 * [derive] overwrites the caller's `sharedSecret` array. This is the only place the raw ECDH
 * output exists in the clear, and a spec that says "do not keep the secret" but leaves the
 * array in a coroutine stack for the garbage collector to deal with has not really said it.
 * The caller must not reuse the array, which is why this is documented rather than silent.
 */
object SessionKeys {

    /** AES-256. See [KEY_SIZE_BITS]. */
    const val KEY_SIZE_BYTES = 32

    const val KEY_SIZE_BITS = KEY_SIZE_BYTES * 8

    private val SALT = "itantra-message/v1/session-salt".toByteArray(Charsets.UTF_8)

    private const val INFO_PREFIX = "itantra-message/v1/session|"

    /**
     * Derive the message key for one conversation.
     *
     * @param sharedSecret raw ECDH output. **Overwritten with zeroes before returning.**
     * @param conversationId the conversation both devices computed identically.
     * @return a fresh AES key, or null if the secret is not a usable length.
     *
     * Returning null rather than throwing is deliberate: [sharedSecret] arrives straight off
     * the radio, and a peer that sent a key which produced a degenerate shared secret must
     * produce a refused connection, not a crash in the UI layer.
     */
    fun derive(sharedSecret: ByteArray, conversationId: String): SecretKey? {
        if (sharedSecret.isEmpty()) {
            sharedSecret.fill(0)
            return null
        }
        return try {
            val info = (INFO_PREFIX + conversationId).toByteArray(Charsets.UTF_8)
            val material = Hkdf.derive(sharedSecret, SALT, info, KEY_SIZE_BYTES)
            try {
                // SecretKeySpec copies its input, so wiping `material` afterwards does not
                // damage the key it just produced. Asserted by SessionKeysTest rather than
                // assumed, because getting that backwards would return a key of zeroes and
                // every message would fail to decrypt with no clue where.
                SecretKeySpec(material, "AES")
            } finally {
                material.fill(0)
            }
        } catch (_: Exception) {
            null
        } finally {
            sharedSecret.fill(0)
        }
    }

    /**
     * The exact input HKDF is given, exposed so a test can pin it.
     *
     * If this string ever changes, every stored message becomes unreadable, because the
     * conversation id alone no longer reproduces the key. That is why it is a function with a
     * test rather than an inline concatenation.
     */
    fun infoFor(conversationId: String): ByteArray =
        (INFO_PREFIX + conversationId).toByteArray(Charsets.UTF_8)

    /** The salt, exposed for the same reason. */
    fun salt(): ByteArray = SALT.copyOf()

    /**
     * A short, one-way tag for [key], for logs and the settings screen.
     *
     * ## Why this is safe to log when the key is not
     *
     * This is the first 4 bytes of SHA-256 over the key material. Recovering the key from it
     * would require searching 2^256 candidates, so it discloses nothing about a key that is
     * still 256 bits wide -- and it is not the key: no code path uses this value to
     * encrypt, derive, or verify anything.
     *
     * ## Why it exists
     *
     * Because the failure this whole change addresses was *silent*. Two phones derived two
     * different keys, everything looked healthy, and the only symptom was a message rendering
     * as "Could not read that message" with no log line anywhere saying the keys had differed.
     * Two devices reporting the same fingerprint is direct evidence that agreement worked,
     * which is otherwise only inferable by sending a message and watching it decrypt.
     */
    fun fingerprint(key: SecretKey): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val head = digest.copyOf(FINGERPRINT_BYTES)
        val out = StringBuilder(FINGERPRINT_BYTES * 2)
        for (b in head) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4])
            out.append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /** Bytes of SHA-256 output kept. 4 bytes is 8 hex characters: enough to compare, short
     * enough to read off a screen. */
    private const val FINGERPRINT_BYTES = 4

    private const val HEX_DIGITS = "0123456789abcdef"
}