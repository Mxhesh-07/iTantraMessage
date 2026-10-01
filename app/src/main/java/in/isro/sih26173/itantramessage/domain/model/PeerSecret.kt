package `in`.isro.sih26173.itantramessage.domain.model

import `in`.isro.sih26173.itantramessage.data.crypto.Hex
import `in`.isro.sih26173.itantramessage.data.crypto.IdentityKey

/**
 * The second handshake frame: one device's RSA-wrapped contribution to the session key.
 *
 * ## Why this cannot be part of [PeerHello]
 *
 * A contribution is encrypted **to the peer's public key**, so it cannot be built until the
 * peer's key has arrived. A hello is sent the moment the link opens, before anything has been
 * received. Putting both in one frame would mean the first hello always carried an empty
 * contribution and the peer had to wait for a second one anyway -- so this is the second one,
 * named, rather than an empty field the parser has to guess about.
 *
 * The consequence is a four-frame handshake, two frames each way:
 *
 *  1. both sides send `HELLO:<id>:<public key>` on attach;
 *  2. on receiving a hello, each side sends `SECRET:<id>:<wrapped contribution>`;
 *  3. on receiving a secret, each side unwraps it, combines both contributions and installs
 *     the session key.
 *
 * Four frames on a link that is already open, in an app that is not competing on latency, and
 * the alternative -- one frame carrying a contribution encrypted to a key the sender has not
 * seen -- is not possible rather than merely worse.
 *
 * ## Why this is not an [Envelope]
 *
 * Same reasons as [PeerHello], and one more that is specific to this frame: its payload is
 * *already* ciphertext, encrypted to a key that has not been agreed yet. There is no session
 * key to encrypt it with, so it cannot go through the uniformly encrypted message path, and
 * forcing it to would mean a frame type whose payload is encrypted but not with the session key
 * -- a distinction a future reader would have to rediscover.
 *
 * ## Both fields are untrusted
 *
 * [decode] validates the device id with the same regex [PeerHello] uses, and bounds the wrapped
 * bytes. The bound matters more here than in the hello: this value is handed to a `Cipher`
 * backed by the device's private key. A peer must not be able to hand over a megabyte of
 * ciphertext and call it a contribution.
 *
 * The ciphertext itself is not checked for structure -- `Cipher` does that, and
 * [IdentityKey.unwrap] reports failure rather than throwing. What is checked is that an
 * oversized or non-hex value never reaches it.
 */
object PeerSecret {

    /**
     * Discriminator for a wrapped-contribution frame.
     *
     * ASCII `S`, chosen because an envelope's first byte is always `I` (`0x49`) and a hello's
     * is `H`. Asserted against both in `PeerSecretTest`, because a collision here would mean a
     * wrapped key silently parsed as an id or as a message.
     */
    const val PREFIX = "SECRET:"

    /** Between the device id and the wrapped contribution. Not a hex digit and not legal in an id. */
    const val SEPARATOR = ":"

    /**
     * Longest accepted wrapped contribution, in bytes.
     *
     * A 2048-bit RSA ciphertext is exactly 256 bytes. Matches
     * [IdentityKey.MAX_WRAPPED_BYTES] and is asserted against it in `PeerSecretTest`, so the
     * parser and the unwrap step cannot disagree about what counts as a contribution.
     */
    const val MAX_WRAPPED_BYTES = IdentityKey.MAX_WRAPPED_BYTES

    /** Same regex as [PeerHello.DEVICE_ID], shared so the two frames cannot drift. */
    val DEVICE_ID = PeerHello.DEVICE_ID

    private val HEX_ONLY = Regex("""[0-9a-f]*""")

    /**
     * One device's wrapped contribution, with the id of the device that wrapped it.
     *
     * The id is redundant with the hello that was already received, and is included anyway so
     * that a secret frame can be checked against the peer the link is actually talking to
     * rather than trusted on its own. A frame naming a third device is refused, not ignored:
     * accepting it would put a contribution into the combine step from someone who is not the
     * conversation's other half.
     */
    data class Contribution(
        val deviceId: String,
        val wrappedHex: String,
    )

    /**
     * Frame bytes carrying [contribution] for [deviceId].
     *
     * @throws IllegalArgumentException if either field is malformed. This encodes the app's own
     *   view of itself, so a bad value is a programming error, not hostile input, and failing
     *   loudly at the call site beats transmitting something the peer would reject.
     */
    fun encode(deviceId: String, wrapped: ByteArray): ByteArray {
        require(isValid(deviceId)) { "refusing to wrap a contribution for malformed id '$deviceId'" }
        require(isValidWrapped(wrapped.size)) {
            "refusing to send a ${wrapped.size} byte contribution"
        }
        return (PREFIX + deviceId + SEPARATOR + Hex.encode(wrapped)).toByteArray(Charsets.UTF_8)
    }

    /** Whether [frame] is a wrapped-contribution frame, by its first [PREFIX] bytes. */
    fun isSecret(frame: ByteArray): Boolean {
        if (frame.size <= PREFIX.length) return false
        for (i in PREFIX.indices) {
            if (frame[i] != PREFIX[i].code.toByte()) return false
        }
        return true
    }

    /**
     * The contribution carried by [frame], or null if it is not a usable one.
     *
     * Null covers four cases on purpose, because all four have the same correct handling: ignore
     * it. Not a secret frame; no separator; a malformed id; a missing or malformed contribution.
     */
    fun decode(frame: ByteArray): Contribution? {
        if (!isSecret(frame)) return null
        val body = String(frame, PREFIX.length, frame.size - PREFIX.length, Charsets.UTF_8)

        val split = body.indexOf(SEPARATOR)
        if (split <= 0) return null

        val deviceId = body.substring(0, split)
        val wrappedHex = body.substring(split + SEPARATOR.length)

        if (!isValid(deviceId)) return null
        if (!isValidWrappedHex(wrappedHex)) return null

        return Contribution(deviceId = deviceId, wrappedHex = wrappedHex)
    }

    /** The one place the id shape is decided, shared with [PeerHello]. */
    fun isValid(deviceId: String): Boolean =
        deviceId.length <= PeerHello.MAX_DEVICE_ID_BYTES && DEVICE_ID.matches(deviceId)

    /**
     * The one place the contribution shape is decided: lowercase hex, even length, within the
     * byte cap, and not empty.
     *
     * Empty is refused because a zero-length contribution cannot be unwrapped into anything
     * and would only reach the private key as a failure.
     *
     * The hex check is here and not left to [Hex.decode] because `Hex` is strict about the
     * alphabet too, and this is the frame parser: the value it faces the radio with should not
     * be handed downstream on the assumption that a later layer is careful. An earlier version
     * of this method checked the size and forgot the alphabet, which `PeerSecretTest` caught.
     */
    fun isValidWrappedHex(wrappedHex: String): Boolean {
        if (wrappedHex.isEmpty()) return false
        if (wrappedHex.length % 2 != 0) return false
        if (!isValidWrapped(wrappedHex.length / 2)) return false
        return HEX_ONLY.matches(wrappedHex)
    }

    private fun isValidWrapped(byteCount: Int): Boolean =
        byteCount in 1..MAX_WRAPPED_BYTES
}