package `in`.isro.sih26173.itantramessage.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Message encryption on an Android Keystore-held AES-256-GCM key.
 *
 * ## Why the key never exists in this process's memory as a byte array
 *
 * The key is generated *inside* the Keystore (API 23+ non-exportable, hardware-backed
 * where the device has a TEE or StrongBox) and is only ever referenced as a [SecretKey]
 * handle. `Cipher.init` is given that handle and the keystore performs the operation; the
 * raw key bytes are never returned to the app. That is the entire point of using the
 * Keystore instead of `SecretKeySpec(randomBytes, "AES")`, and it is why this class has no
 * constructor path that accepts key material.
 *
 * ## Why AES-GCM and not CBC
 *
 * GCM is authenticated: `doFinal` fails if the ciphertext was altered, so a tampered
 * message cannot be decrypted into plausible plaintext. CBC with a wrong key produces
 * garbage that occasionally parses; CBC without a MAC is unauthenticated. For a protocol
 * that accepts bytes from a nearby stranger, unauthenticated encryption is not an option.
 *
 * ## What this does NOT protect against
 *
 * A compromised or rooted device, or a debugger attached to this process, can read
 * whatever the user sees. This protects the key at rest and the wire, not a live attack on
 * the device. See docs/SECURITY.md for the full threat model.
 *
 * ## Key generations, and why they live in the alias name
 *
 * A peer encrypts under whatever key this device currently advertises, but a message can
 * sit in that peer's send queue for a long time. So a key carries a generation number, and
 * the receiver keeps old generations readable for a grace window after rotating. Without
 * the window, rotating silently corrupts every message already queued by a peer -- a
 * data-loss bug that only appears after an upgrade, which is the worst time to find it.
 *
 * The generation and the key's creation time are encoded in the *alias*, not in a side
 * file and not in keystore metadata:
 *
 * ```
 * itantra_msg.g<generation>.<createdAtMillis>
 * ```
 *
 * Two reasons. First, there is no supported way to attach an arbitrary integer to a
 * KeyStore entry on every API level in range, so metadata would need a side file -- and
 * side files are mutable by anything that can write app storage, which would let an
 * attacker claim a bogus generation and steer key selection. Second, listing aliases
 * gives generation *and* age in one read, which is exactly what [pruneRetired] needs, so
 * the grace window is enforced by the same mechanism that reports the active generation.
 * The only way to move the generation counter forward is to create a new alias, and
 * creating an alias requires the Keystore.
 */
class EncryptionManager(
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ---- public API ------------------------------------------------------------------

    /**
     * Encrypt [plaintext] for transport, returning the full envelope as Base64-free text.
     *
     * The envelope is a single self-delimiting string so the wire format has exactly one
     * field to carry and no out-of-band state:
     *
     * ```
     * itm1.<generation>.<iv-b64>.<ciphertext-b64>
     * ```
     *
     * `itm1` is a version tag. Without it a future format change would be undetectable on
     * the receiving side; with it, [decrypt] can refuse a payload it does not understand
     * instead of misparsing it. The generation travels with the ciphertext because a peer
     * cannot otherwise know which of this device's keys to try.
     *
     * The IV is generated per message inside [Cipher] and never reused. GCM's security
     * collapses entirely if the same (key, IV) pair ever encrypts two different
     * plaintexts, and `setRandomizedEncryptionRequired(true)` below is what makes the
     * Keystore refuse to hand out a caller-chosen IV.
     */
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No key yet on a first run: mint generation 1 inline rather than making every
        // caller check hasKey() first. The alias is looked up once per message, which is
        // the one unavoidable keystore read on this path.
        val alias = activeAlias()
        val active = alias?.let { keyStore.getKey(it, null) as? SecretKey } ?: generateKey()
        cipher.init(Cipher.ENCRYPT_MODE, active)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)

        return buildString {
            append(MAGIC).append('.')
            // The generation travels with the ciphertext so a peer knows which of this
            // device's keys to try. It is read from the alias that actually encrypted,
            // never from a second lookup: a rotation between the two would otherwise stamp
            // the wrong generation onto a message and make it undecryptable at the far end.
            append(generationOf(alias ?: activeAliasOrThrow())).append('.')
            append(Base64.encodeToString(iv, Base64.NO_WRAP)).append('.')
            append(Base64.encodeToString(ct, Base64.NO_WRAP))
        }
    }

    /**
     * The active alias, or an error.
     *
     * Reached only on the path where the alias was null and [generateKey] has just
     * created the first generation, which necessarily registered an alias. The error is
     * here so that if [generateKey] ever changes to stop registering one, this fails
     * loudly instead of writing a message no peer can ever decrypt.
     */
    private fun activeAliasOrThrow(): String =
        activeAlias() ?: error("no active key alias after generateKey()")

    /** Encrypt [text] as UTF-8. */
    fun encrypt(text: String): String = encrypt(text.toByteArray(Charsets.UTF_8))

    /**
     * Decrypt an envelope produced by [encrypt].
     *
     * @return the plaintext bytes, or `null` if the envelope is malformed, from an unknown
     *   version, or fails authentication. `null` rather than an exception because every
     *   caller is a receive path that must treat "this was not for us / was tampered with"
     *   as ordinary traffic rather than as a crash. See docs/SECURITY.md, input validation.
     */
    fun decrypt(envelope: String): ByteArray? {
        val parts = envelope.split('.')
        if (parts.size != 4) return null
        if (parts[0] != MAGIC) return null

        val generation = parts[1].toIntOrNull() ?: return null
        val iv = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return null
        val ct = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return null
        if (iv.isEmpty() || ct.isEmpty()) return null
        // GCM's tag is 16 bytes and is part of `ct`, so a ciphertext shorter than the tag
        // cannot be a valid envelope. Rejecting it here turns a provider-specific
        // IllegalArgumentException into the same null the caller already handles.
        if (ct.size < GCM_TAG_BITS / 8) return null

        val key = keyForGeneration(generation) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ct)
        } catch (_: Exception) {
            // Any failure here means "not decryptable": wrong key, tampered ciphertext, or
            // a provider quirk. Deliberately not logged with the ciphertext -- see the
            // no-plaintext-logging rule in docs/SECURITY.md.
            null
        }
    }

    /** Decrypt an envelope to text, or `null` if it does not decode as UTF-8. */
    fun decryptToText(envelope: String): String? =
        decrypt(envelope)?.toString(Charsets.UTF_8)

    /**
     * Whether an AES key exists yet.
     *
     * Checked before advertising, so a peer never advertises "I can encrypt" before it has
     * a key. The alternative is a connection that fails on the first message, which reads
     * to a user as data loss.
     */
    fun hasKey(): Boolean = runCatching { activeAlias() != null }.getOrDefault(false)

    /**
     * Make sure a key exists, creating the first generation if it does not.
     *
     * @return the generation now in use.
     * @throws IllegalStateException if the Keystore refuses to produce a readable alias
     *   afterwards, which means the key is unusable and [encrypt] would fail too.
     *
     * ## Why this method exists
     *
     * Found by running the release build on hardware, not by a test. Startup warm-up called
     * [keyFingerprint] and logged "generated encryption key, generation null" on a phone
     * whose Keystore was empty. [keyFingerprint] is a *reader* -- it returns null when there
     * is no key and never creates one -- so nothing was generated, the logged generation was
     * null because there genuinely was no key, and the log stated a falsehood.
     *
     * The app still worked, because [encrypt] mints generation 1 inline. What did not work
     * was the point of warm-up: moving a few milliseconds of Keystore cost, and the failure
     * risk on a locked device, off the user's first Send and onto idle startup. Instead the
     * cost stayed exactly where it was most unwelcome, and a log line claimed otherwise.
     *
     * Nothing in the unit suite could have caught this, because every path here needs the
     * real AndroidKeyStore provider. That is the gap `docs/TESTING.md` §5.1 records, and
     * this is the first concrete defect to come out of it.
     */
    fun ensureKey(): Int {
        if (!hasKey()) generateKey()
        return checkNotNull(currentGeneration()) {
            "Keystore produced a key that could not be read back"
        }
    }

    /**
     * A short public fingerprint of this device's key, for out-of-band verification.
     *
     * SHA-256 of the *public* verification key the Keystore exposes, truncated to 16 hex
     * characters. It is safe to show and to compare by reading it aloud: two devices
     * showing the same fingerprint hold the same key, so a connection prompt is not being
     * faked by a third device. It reveals nothing about the private key.
     *
     * A *check*, not an *authenticator* -- it cannot stop a MITM, it only lets a user
     * notice one after the fact.
     */
    fun keyFingerprint(): String? = runCatching {
        val alias = activeAlias() ?: return null
        val publicKey = keyStore.getCertificate(alias).publicKey.encoded
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
        digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Create a new key generation, keeping previous generations readable for the grace
     * window and deleting anything older.
     *
     * @return the new generation number.
     *
     * The previous key is *not* deleted here. It is deleted by [pruneRetired], called on
     * the next successful encrypt/decrypt, because deleting a key that a peer is still
     * using is exactly the data-loss case the window exists to prevent.
     */
    fun rotate(): Int {
        val existing = activeAlias()?.let { generationOf(it) } ?: 0
        val next = existing + 1
        generateKey(next)
        pruneRetired()
        return next
    }

    /**
     * The generation this device currently encrypts with, for the Settings screen.
     *
     * Reported rather than cached, so it cannot go stale if a key is deleted out from
     * under the app (which Android does on app-data clear, and on some OEM "optimise"
     * passes).
     */
    fun currentGeneration(): Int? = runCatching { activeAlias()?.let { generationOf(it) } }.getOrNull()

    // ---- internals -------------------------------------------------------------------

    private fun activeAlias(): String? =
        allGenerations().maxByOrNull { it.generation }?.alias

    /**
     * Every generation alias currently in the Keystore, newest first.
     *
     * This is the only place that parses aliases, so the encoding in [aliasFor] and the
     * reader here cannot drift apart.
     */
    private fun allGenerations(): List<Generation> = runCatching {
        keyStore.aliases()
            .toList()
            .filter { it.startsWith("$keyPrefix.g") }
            .mapNotNull { alias ->
                val rest = alias.removePrefix("$keyPrefix.g")
                val dot = rest.indexOf('.')
                if (dot < 0) return@mapNotNull null
                val generation = rest.substring(0, dot).toIntOrNull() ?: return@mapNotNull null
                val createdAt = rest.substring(dot + 1).toLongOrNull() ?: return@mapNotNull null
                Generation(alias, generation, createdAt)
            }
    }.getOrDefault(emptyList())

    /**
     * Delete generations older than the grace window.
     *
     * The active generation is never a candidate, so this cannot delete the key in use
     * even if the clock is wrong and every timestamp looks ancient. Anything inside the
     * window is kept: a peer may still hold a message encrypted under it.
     */
    private fun pruneRetired() {
        val active = activeAlias() ?: return
        val cutoff = clock() - RETIRED_KEY_GRACE_MS
        allGenerations()
            .filter { it.alias != active && it.createdAt < cutoff }
            .forEach { runCatching { keyStore.deleteEntry(it.alias) } }
    }

    private fun keyForGeneration(generation: Int): SecretKey? = runCatching {
        val alias = allGenerations().firstOrNull { it.generation == generation }?.alias
            ?: return null
        keyStore.getKey(alias, null) as? SecretKey
    }.getOrNull()

    private fun generateKey(generation: Int = (currentGeneration() ?: 0) + 1): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            aliasFor(generation, clock()),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // Required on API 23-27 for GCM keys, ignored later. Without it Cipher.init
            // throws on those devices ("Caller did not enable ..."). It is also what
            // forbids a caller-supplied IV, which is the GCM nonce-reuse failure mode.
            .setRandomizedEncryptionRequired(true)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun aliasFor(generation: Int, createdAt: Long) = "$keyPrefix.g$generation.$createdAt"

    private fun generationOf(alias: String): Int =
        alias.removePrefix("$keyPrefix.g").substringBefore('.').toInt()

    private data class Generation(val alias: String, val generation: Int, val createdAt: Long)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val MAGIC = "itm1"
        const val FINGERPRINT_BYTES = 8
        const val DEFAULT_KEY_PREFIX = "itantra_msg"

        /**
         * How long a retired generation stays readable. Seven days is chosen to cover the
         * longest realistic queue: a peer that goes away for a weekend and comes back must
         * still be able to read what it was sent. Beyond that, the undelivered messages
         * are stale enough that failing to decrypt them is the correct outcome, and an
         * unbounded set of readable keys is a liability.
         */
        const val RETIRED_KEY_GRACE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
