package `in`.isro.sih26173.itantramessage.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The JCA provider name for the hardware-backed keystore.
 *
 * At file scope rather than in the companion because a primary constructor's default argument
 * cannot see a companion object of the class it belongs to -- the companion is not initialised
 * while the constructor signature is being resolved. Declaring it here is the whole reason the
 * class compiles.
 */
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

/**
 * Deliberately distinct from the AES alias prefix in `EncryptionManager`.
 *
 * File scope for the same reason as [KEYSTORE_PROVIDER].
 *
 * The suffix is `rsa` rather than the `ec` this used to carry, and that is not cosmetic: an
 * alias created by an older build holds an EC key pair under this name, and a class that found
 * one would try to use it as an RSA key. [ensureKeyPair] checks the type of what it finds, so a
 * stale entry is replaced rather than misused.
 */
private const val DEFAULT_IDENTITY_ALIAS = "itantra_msg.identity.rsa"

/**
 * This device's long-term identity, used to agree a message key with a peer.
 *
 * ## The defect this exists to fix
 *
 * The app shipped a message key that was a **per-device random Keystore key**. It could read its
 * own messages and nothing else, ever: the sender encrypted with the key inside its own secure
 * hardware and the receiver asked its own Keystore for *its* key, so GCM authentication could
 * never succeed. That was found on hardware, as a message that arrived and rendered as "Could
 * not read that message". The unit tests could not find it, because `encrypt`/`decrypt` were
 * each correct in isolation and the missing thing was not a function at all -- it was an
 * agreement.
 *
 * ## Why RSA transport, and not ECDH
 *
 * ECDH is the obvious choice and was the first implementation. It does not work on both target
 * handsets, and the reason is not a subtlety in the code. This was measured on the devices, by
 * trying each key type in the `AndroidKeyStore` on each handset and reporting what the keystore
 * accepted:
 *
 * | Keystore operation                          | Galaxy A14 5G, API 35 | realme Narzo 10A, API 30 |
 * |---------------------------------------------|-----------------------|---------------------------|
 * | EC key pair, `PURPOSE_AGREE_KEY`            | accepted              | **rejected**              |
 * | EC key pair, sign or verify                 | accepted              | accepted                  |
 * | RSA key pair, encrypt or decrypt            | accepted              | accepted                  |
 * | RSA private key decrypt, OAEP               | **rejected**          | **rejected**              |
 * | RSA private key decrypt, PKCS#1 v1.5        | accepted              | accepted                  |
 *
 * The EC rejection is explicit: `InvalidAlgorithmParameterException: Unknown purpose: 64`, and
 * 64 is `PURPOSE_AGREE_KEY`. The realme's MediaTek keystore does not implement EC key agreement
 * at all, so `KeyAgreement` can never be given a private key it will accept.
 *
 * RSA is the one asymmetric key the keystore accepts on both handsets, and only under PKCS#1
 * v1.5. RSA-OAEP -- the padding that should be preferred -- is rejected by both. So the transport
 * is RSA with PKCS#1 v1.5, and this file is the record of why.
 *
 * Two alternatives were considered and rejected on evidence rather than taste:
 *
 *  - **Software ECDH.** Verified to work on both handsets, and it is a better primitive. But the
 *    EC private key would then be an ordinary JCE object in the app's heap, not an exportable-
 *    never key in the TEE. That is a real loss on the one device where the Keystore refuses,
 *    and it would make the two handsets have different security postures for no gain the demo
 *    needs.
 *  - **A hybrid**, EC where the Keystore allows and RSA otherwise. It needs capability
 *    negotiation in the handshake, and it has a fatal practical problem: an EC-only phone and an
 *    RSA-only phone cannot talk. That is precisely the pair this app is supposed to demonstrate.
 *
 * ## What is and is not authenticated
 *
 * **This is not an authenticated key exchange.** Nothing here signs anything, so a device in
 * range could substitute its own key pair and read traffic. The app's answer is that the link is
 * RFCOMM to a **bonded** peer: the Bluetooth bond already authenticated the radio addresses
 * using a link key the platform keeps, so a frame arriving on this socket came from the phone
 * the user paired with. That is a real defence, and it is the *only* one this class provides.
 *
 * It is recorded here rather than left implicit because "the keys are in the Keystore" reads as
 * much stronger than it is. See `docs/SECURITY.md` for the threat model this implies.
 *
 * Consequences, stated plainly:
 *
 *  - no protection against an attacker who is already impersonating the peer at the Bluetooth
 *    layer, which is a stronger attacker than this scheme defends against;
 *  - **no forward secrecy.** A wrapped secret can be unwrapped again later by anyone holding
 *    this device's private key, so disclosure of that key exposes the traffic it protected.
 *  - **PKCS#1 v1.5 rather than OAEP**, because OAEP is rejected by both keystores (table above).
 *    v1.5 is the padding with a known padding-oracle attack against RSA decryption, so the
 *    honest statement is that this depends on the bond to keep non-peers off the socket: only a
 *    device the user has already paired can reach `unwrap`, and the only thing an oracle would
 *    disclose here is whether a session was established. Both are in `docs/SECURITY.md`.
 *  - no replay protection at this layer. Replay is handled where it matters, in
 *    `MessageRepository`, by the message id.
 *
 * ## Why the private half cannot be read
 *
 * The key pair is generated by the `AndroidKeyStore` provider, so the private key is generated
 * and used inside the TEE or StrongBox and is never returned to the process as bytes. Only the
 * public half is exportable, and only it is sent. That is what makes the public key safe to put
 * on the radio.
 *
 * ## Why one alias and no rotation
 *
 * The AES key in `EncryptionManager` carries generations because a queued message may outlive a
 * rotation. An identity key cannot be rotated the same way: changing it invalidates agreement
 * with the peer and would need a re-handshake, which is a protocol change rather than a
 * configuration one. There is exactly one alias and re-pinning the device is the rotation
 * story. Recorded as a limitation rather than silently omitted.
 *
 * ## Testability
 *
 * None of this file is unit tested, because `AndroidKeyStore` does not exist on the JVM and
 * Robolectric cannot be fetched offline. That is the same gap `docs/TESTING.md` records for
 * `EncryptionManager`, and here it is the part that decides whether messages can be read at all.
 *
 * What *is* tested is everything around it: the derivation and the two-secret combination
 * ([HkdfTest], [SessionKeysTest]), the cipher ([SessionCryptoTest]), and both handshake frame
 * codecs ([PeerHelloTest], [PeerSecretTest]). The keystore calls themselves were measured on the
 * two handsets, and the measurement is the table above.
 */
class IdentityKey(
    private val alias: String = DEFAULT_IDENTITY_ALIAS,
) {

    /**
     * In the class body rather than the constructor parameter list.
     *
     * Kotlin does not allow `by lazy` on a primary constructor parameter -- lazy needs a
     * property with somewhere to cache the value, and a constructor parameter has no storage of
     * its own. `EncryptionManager` does the same thing for the same reason.
     */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    private val random = SecureRandom()

    /** Whether a key pair already exists, without creating one. */
    fun hasKeyPair(): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    /**
     * Create the key pair if it is missing or is the wrong type.
     *
     * The type check is not paranoia. An older build of this app stored an **EC** pair under a
     * name that only the suffix changed, and `hasKeyPair()` answers a different question from
     * "is this entry usable for RSA transport". An entry of the wrong type is deleted and
     * recreated, which loses the peer's ability to reach the old key -- acceptable, because an
     * identity key with no agreed session is of no use to anyone.
     *
     * @return true if a usable pair is present afterwards. False means the Keystore refused, and
     *   the app can still read its own database but cannot take part in a conversation -- so the
     *   caller must say so rather than carry on.
     *
     * ## Why the failure is logged rather than swallowed
     *
     * This returns false on a realme Narzo 10A (API 30) while succeeding on a Samsung
     * Galaxy A14 (API 35), and `runCatching {}.getOrDefault(false)` reported that as the same
     * bare `false` a missing keystore would produce. The caller could only log "the keystore
     * refused", which names the symptom rather than the cause -- and it was the logging of the
     * real exception that turned "the old phone is different" into `Unknown purpose: 64`, which
     * is what the table in the class docs is built on.
     *
     * Keystore rejections are not uniform across vendors, so the reason has to be read rather
     * than predicted. Only the exception's class and message are logged, never a key or any key
     * material, and this is a `Log.w` on a failure path -- see the logging rules in
     * `docs/SECURITY.md`.
     */
    fun ensureKeyPair(): Boolean {
        if (hasKeyPair() && isTransportKey()) return true
        if (hasKeyPair()) {
            Log.w(TAG, "replacing a $alias entry that is not an RSA transport key")
            runCatching { keyStore.deleteEntry(alias) }
        }
        return try {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    // Declaring the padding is not optional. Without it the key does not
                    // authorise the padding and every `Cipher` initialisation fails with
                    // `InvalidKeyException: Keystore operation failed` -- which cost one build
                    // cycle before it was understood.
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    // No user authentication requirement: a peer must be able to read a
                    // message while the phone is locked, or a conversation silently stops
                    // working the moment the screen goes off. Stated in SECURITY.md because it
                    // is a deliberate trade, not an oversight.
                    .build(),
            )
            generator.generateKeyPair()
            true
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "could not create an RSA key pair in $KEYSTORE_PROVIDER: " +
                    "${t.javaClass.name}: ${t.message}",
            )
            false
        }
    }

    /**
     * This device's public key, hex encoded.
     *
     * @return the X.509 `SubjectPublicKeyInfo`, or null if no usable pair exists or the Keystore
     *   would not return it. Null is the normal "cannot identify" answer and callers must treat
     *   it as a refusal, not as an empty key.
     */
    fun publicKeyHex(): String? = runCatching {
        val certificate = keyStore.getCertificate(alias) ?: return null
        val encoded = certificate.publicKey.encoded ?: return null
        if (encoded.size > MAX_PUBLIC_KEY_BYTES) return null
        Hex.encode(encoded)
    }.getOrNull()

    /**
     * A fresh random contribution for one conversation's key.
     *
     * Both devices contribute one of these, which is what keeps the transport contributory: the
     * side that answers the handshake cannot choose the resulting session key on its own,
     * because the other side's contribution is mixed in as well.
     *
     * @return [CONTRIBUTION_BYTES] of entropy, or null if the entropy source refused.
     */
    fun newContribution(): ByteArray? = try {
        ByteArray(CONTRIBUTION_BYTES).also { random.nextBytes(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "could not draw random bytes: ${t.javaClass.simpleName}")
        null
    }

    /**
     * Encrypt [contribution] to a peer's public key, for transport.
     *
     * The peer's key is public by construction and arrives as hex off the radio, so it is
     * rebuilt from SPKI here exactly as it was sent -- which also means this method exercises
     * the same path a real peer does.
     *
     * [contribution] is **not** wiped. The caller keeps it: it is one half of the material that
     * [SessionKeys.combine] will need when the peer's contribution arrives, and wiping it here
     * would mean the handshake could only ever complete on the side that sent first.
     *
     * @return the wrapped bytes, or null for any failure: malformed hex, a key that is not RSA,
     *   a key too weak to be worth using, a padding the keystore refuses.
     */
    fun wrap(contribution: ByteArray, peerPublicKeyHex: String): ByteArray? {
        if (contribution.size != CONTRIBUTION_BYTES) {
            Log.w(TAG, "refusing to wrap ${contribution.size} bytes: expected $CONTRIBUTION_BYTES")
            return null
        }
        val peer = publicKeyFrom(peerPublicKeyHex) ?: return null
        if (!isAcceptableKey(peer)) {
            Log.w(TAG, "refusing a peer key of ${peer.algorithm} or the wrong size")
            return null
        }
        return try {
            // Plain JCE, deliberately not the keystore provider: the peer's public key is a
            // software object, and the encrypt direction never needs our private key.
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, peer)
                doFinal(contribution)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not wrap a contribution: ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /**
     * Recover the peer's contribution from [wrapped].
     *
     * This is the one place the private key is used, and it never leaves the Keystore: the
     * `Cipher` is initialised with the key handle and the plaintext comes straight back out.
     *
     * @return exactly [CONTRIBUTION_BYTES] of plaintext, or null. [wrapped] is wiped on every
     *   path, including the failing ones, because a ciphertext this device cannot unwrap has no
     *   further use and is not worth leaving around.
     */
    fun unwrap(wrapped: ByteArray): ByteArray? {
        return try {
            if (!ensureKeyPair()) return null
            if (wrapped.isEmpty() || wrapped.size > MAX_WRAPPED_BYTES) return null

            val privateKey = runCatching { keyStore.getKey(alias, null) }.getOrNull()
            if (privateKey !is PrivateKey) {
                Log.w(TAG, "the keystore returned ${privateKey?.javaClass?.name} instead of a key")
                return null
            }

            val plain = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, privateKey)
                doFinal(wrapped)
            }
            return if (plain.size == CONTRIBUTION_BYTES) {
                plain
            } else {
                // Cannot happen with a well-formed ciphertext, and must not be handed on: a
                // wrong-length contribution would produce a key that silently disagrees.
                Log.w(TAG, "unwrapped ${plain.size} bytes, expected $CONTRIBUTION_BYTES")
                plain.fill(0)
                null
            }
        } catch (t: Throwable) {
            // A padding failure lands here. That is the expected result for a ciphertext this
            // key cannot unwrap, which on a bonded link is only a peer that changed keys, so it
            // is logged at warn and treated as "no key".
            Log.w(TAG, "could not unwrap a contribution: ${t.javaClass.simpleName}")
            null
        } finally {
            wrapped.fill(0)
        }
    }

    /** Whether the stored entry is an RSA private key this class can actually unwrap with. */
    private fun isTransportKey(): Boolean {
        val key = runCatching { keyStore.getKey(alias, null) }.getOrNull()
        return key is PrivateKey && key.algorithm.equals(KEY_ALGORITHM, ignoreCase = true)
    }

    private fun publicKeyFrom(peerPublicKeyHex: String): java.security.PublicKey? {
        val bytes = Hex.decode(peerPublicKeyHex) ?: return null
        if (bytes.size > MAX_PUBLIC_KEY_BYTES) return null
        return runCatching {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()
    }

    /**
     * Whether a received public key is one this app will transport to.
     *
     * A peer chooses this key, and a small modulus is the obvious way to make a transport
     * worthless: an attacker who can substitute a 512-bit key only has to factor or brute force
     * something tiny. `Cipher` accepts a weak key happily, so it is refused here, at the
     * boundary, before anything is encrypted to it.
     */
    private fun isAcceptableKey(key: java.security.PublicKey): Boolean {
        if (!key.algorithm.equals(KEY_ALGORITHM, ignoreCase = true)) return false
        val rsa = key as? RSAPublicKey ?: return false
        return rsa.modulus.bitLength() in MIN_KEY_BITS..MAX_KEY_BITS
    }

    companion object {
        private const val TAG = "IdentityKey"

        private const val KEY_ALGORITHM = "RSA"

        /**
         * PKCS#1 v1.5, because OAEP is refused by the keystore on both handsets.
         *
         * The cost of that is recorded in the class docs rather than hidden here.
         */
        private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

        private const val KEY_SIZE_BITS = 2048

        /** Bytes each side contributes to the session key material. */
        const val CONTRIBUTION_BYTES = 32

        private const val MIN_KEY_BITS = 2048
        private const val MAX_KEY_BITS = 4096

        /**
         * A 2048-bit RSA `SubjectPublicKeyInfo` is 294 bytes.
         *
         * The ceiling leaves room for a 4096-bit key, which would be 550 bytes, and still
         * refuses to hand an arbitrary megabyte from the radio to the `KeyFactory`.
         */
        const val MAX_PUBLIC_KEY_BYTES = 640

        /**
         * Longest accepted wrapped contribution.
         *
         * A 2048-bit RSA ciphertext is exactly 256 bytes. 512 leaves room for 4096 and refuses
         * the rest.
         */
        const val MAX_WRAPPED_BYTES = 512
    }
}