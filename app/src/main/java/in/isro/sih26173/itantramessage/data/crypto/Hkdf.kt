package `in`.isro.sih26173.itantramessage.data.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF, RFC 5869, over HMAC-SHA256.
 *
 * ## Why this is hand-written
 *
 * The platform has no HKDF the app can use. `KeyAgreement` produces a raw shared secret of
 * variable length with no domain separation in it, and using that raw output directly as an
 * AES key is the classic mistake: nothing binds the resulting key to this protocol, this
 * version, or this pair of devices, so the same ECDH output would be a valid message key for
 * every conversation on the radio and every future version of the app.
 *
 * HKDF is the standard repair and it is short enough to be worth owning. The two vectors in
 * `HkdfTest` are RFC 5869 test cases 1 and 3, checked against an independent implementation
 * before being written down.
 *
 * ## Why Android's `SecretKeyFactory("Pbkdf2WithHmacSha256")` is not used instead
 *
 * It cannot: PBKDF2 is for stretching a low-entropy secret, and a 256-bit ECDH secret needs no
 * stretching. Using it would add a slow, pointless KDF and still leave the derivation unbound
 * from the protocol without extra `info`-like plumbing that PBKDF2 has no clean equivalent for.
 *
 * ## Why this is its own file rather than a private method
 *
 * It is the one piece of the new key agreement that can be tested without a radio, a
 * Keystore, or a second phone. If this is wrong, every message on every link fails to decrypt
 * with no symptom that points here, so it gets real tests rather than being buried.
 *
 * ## Thread safety
 *
 * Every method is a pure function and creates its own [Mac]. No shared state, so there is
 * nothing to synchronise and nothing to make thread safety a question.
 */
object Hkdf {

    private const val MAC_ALGORITHM = "HmacSHA256"

    /** SHA-256 output length. Also the largest number of bytes HKDF-SHA256 can emit per block. */
    const val HASH_LEN_BYTES = 32

    /** 255 blocks is the ceiling RFC 5869 section 2.3 sets on L. */
    const val MAX_OUTPUT_BYTES = 255 * HASH_LEN_BYTES

    /**
     * HKDF-Extract: `PRK = HMAC(salt, IKM)`.
     *
     * An empty or absent salt is replaced with `HASH_LEN_BYTES` zero bytes. That is not a
     * convenience: RFC 5869 section 2.2 requires it, and it is what makes an unsalted
     * derivation well defined. Skipping it would silently produce a different key than every
     * other implementation, which is a bug that only shows up as an interop failure.
     */
    fun extract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).run {
            init(SecretKeySpec(salt.effectiveSalt(), MAC_ALGORITHM))
            doFinal(inputKeyMaterial)
        }

    /**
     * HKDF-Expand: `OKM = T(1) | T(2) | ...` where `T(0)` is empty and
     * `T(i) = HMAC(PRK, T(i-1) | info | i)`.
     *
     * @param outputLengthBytes how many bytes to emit. `0` is legal and yields an empty array.
     * @throws IllegalArgumentException if [outputLengthBytes] exceeds [MAX_OUTPUT_BYTES], which
     *   is the one case the format cannot express.
     */
    fun expand(
        pseudoRandomKey: ByteArray,
        info: ByteArray,
        outputLengthBytes: Int,
    ): ByteArray {
        require(outputLengthBytes in 0..MAX_OUTPUT_BYTES) {
            "HKDF-SHA256 cannot produce $outputLengthBytes bytes; the limit is $MAX_OUTPUT_BYTES"
        }

        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(pseudoRandomKey, MAC_ALGORITHM))

        val out = ByteArray(outputLengthBytes)
        // T(0) is the *empty* string, not a block of zeroes. RFC 5869 section 2.3 says so
        // literally, and getting it wrong is invisible until the output is compared with
        // another implementation: seeding 32 zero bytes here yields a plausible 32 bytes of
        // key that matches nothing, which on two phones means every message is unreadable
        // with no error anywhere. The RFC 5869 vectors in HkdfTest exist to catch precisely
        // this, and did.
        var previousBlock = ByteArray(0)
        var produced = 0
        var counter = 1

        while (produced < outputLengthBytes) {
            mac.reset()
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())

            val block = mac.doFinal()
            // The last block is truncated: emitting all 32 bytes when 4 were asked for would
            // hand back key material the caller did not request and cannot reason about.
            val take = minOf(block.size, outputLengthBytes - produced)
            block.copyInto(out, produced, 0, take)
            previousBlock = block

            produced += take
            counter++
        }
        return out
    }

    /** Extract-then-expand, the form the app actually calls. */
    fun derive(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLengthBytes: Int,
    ): ByteArray = expand(extract(salt, inputKeyMaterial), info, outputLengthBytes)

    /** RFC 5869 section 2.2: an absent salt is `HashLen` zero bytes. */
    private fun ByteArray.effectiveSalt(): ByteArray =
        if (isEmpty()) ByteArray(HASH_LEN_BYTES) else this
}