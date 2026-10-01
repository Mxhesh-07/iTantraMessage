package `in`.isro.sih26173.itantramessage.data.crypto

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Turns the material both sides agreed during the handshake into the AES key that protects one
 * conversation.
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
 * Both devices arrive at the same 64 bytes of key material and derive the same AES key from it.
 * Neither private key is ever transmitted; only public halves and one RSA-wrapped contribution
 * each, in the handshake. See [IdentityKey] for why the transport is RSA and not ECDH, which is
 * a measurement about two specific handsets rather than a preference.
 *
 * [combine] is where the two sides' contributions are put together. It is deliberately a pure
 * function over bytes and device ids, with no Keystore and no Android types, because this is the
 * step whose entire job is to produce the same answer on two devices -- and that is precisely
 * the step that cannot be tested on one device and would be invisible if it were wrong.
 *
 * ## Why the conversation is mixed in
 *
 * The agreed material is bound to the *pair* of devices, not to a conversation, so the same 64
 * bytes would otherwise be the message key for every conversation those two phones ever have. A
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
 * [derive] overwrites the caller's `sharedSecret` array. This is the only place the combined key
 * material exists in the clear, and a spec that says "do not keep the secret" but leaves the
 * array in a coroutine stack for the garbage collector to deal with has not really said it.
 * The caller must not reuse the array, which is why this is documented rather than silent.
 *
 * The two *contributions* are not wiped here -- [combine] explains why the caller keeps them --
 * so each side's 32 bytes outlive this call until the link is torn down.
 */
object SessionKeys {

    /** AES-256. See [KEY_SIZE_BITS]. */
    const val KEY_SIZE_BYTES = 32

    const val KEY_SIZE_BITS = KEY_SIZE_BYTES * 8

    /** Bytes each side contributes before they are combined. See [combine]. */
    const val CONTRIBUTION_BYTES = 32

    private val SALT = "itantra-message/v1/session-salt".toByteArray(Charsets.UTF_8)

    private const val INFO_PREFIX = "itantra-message/v1/session|"

    /**
     * Put the two sides' contributions together, in an order both sides compute the same way.
     *
     * ## The problem this solves
     *
     * RSA key transport gives each device a wrapped copy of the *other* side's contribution, and
     * its own contribution never leaves the device. So each side holds two 32-byte values in an
     * order that depends on which side is looking: the phone that dialled holds its own first,
     * the phone that answered holds its own second. Concatenating in "my contribution first"
     * order would produce two different 64-byte strings and therefore two different keys -- the
     * same silent failure as no agreement at all, arrived at more slowly.
     *
     * The fix is to order by something both sides already agree on. The device id is in both
     * hellos, so comparing them gives a total order that does not depend on who dialled, who
     * answered, or what order frames arrived in.
     *
     * ## Why the ids are checked before they are compared
     *
     * Two cases, and both are refused rather than handled:
     *
     *  - **Equal ids**, which make the ordering ambiguous. The fallback would be "pick one",
     *    which is exactly the thing that must not happen. Two devices claiming the same id
     *    cannot hold a conversation anyway: [ConversationId] would collapse them into one, so
     *    messages would be attributed to a peer that does not exist.
     *  - **An empty id**, which sorts before every real one and would otherwise be accepted,
     *    producing a key for a conversation with a participant that does not exist. The shape
     *    of an id is checked at the frame boundary by `PeerHello`; this is the same rule at the
     *    byte boundary, because this function is reachable from more than one path.
     *
     * ## Why this does not wipe its inputs
     *
     * [derive] does wipe the secret it is handed, because a secret is handed straight to it and
     * never reused. These are different: the caller's own contribution is a live field that it
     * may still need, and wiping it here would mean the handshake could only complete on the
     * side that received a contribution first. The caller owns the wiping, and the class docs
     * of [IdentityKey.wrap] say the same thing about the same value.
     *
     * @return the two contributions concatenated in device-id order, or null if either
     *   contribution is empty or either id is empty or the two ids are equal.
     */
    fun combine(
        ourDeviceId: String,
        ourContribution: ByteArray,
        theirDeviceId: String,
        theirContribution: ByteArray,
    ): ByteArray? {
        if (ourContribution.isEmpty() || theirContribution.isEmpty()) return null
        if (ourDeviceId.isEmpty() || theirDeviceId.isEmpty()) return null
        if (ourDeviceId == theirDeviceId) return null

        val weComeFirst = ourDeviceId < theirDeviceId
        val first = if (weComeFirst) ourContribution else theirContribution
        val second = if (weComeFirst) theirContribution else ourContribution
        return ByteArray(first.size + second.size).also {
            first.copyInto(it, 0)
            second.copyInto(it, first.size)
        }
    }

    /**
     * Derive the message key for one conversation.
     *
     * @param sharedSecret the 64 bytes from [combine]. **Overwritten with zeroes before
     *   returning.**
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