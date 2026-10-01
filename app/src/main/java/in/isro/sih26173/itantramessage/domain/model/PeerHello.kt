package `in`.isro.sih26173.itantramessage.domain.model

import `in`.isro.sih26173.itantramessage.data.crypto.IdentityKey

/**
 * The identification and key-agreement handshake: each side announces its device id **and its
 * public key** once per link.
 *
 * ## Why a handshake is unavoidable
 *
 * The conversation key is built from two device ids, because both ends must derive the same key
 * with no coordination -- neither phone gets to dictate what the conversation is called. But a
 * device id cannot be derived from anything both ends can see:
 *
 *  * Hashing the MAC address would remove the handshake, and is impossible anyway.
 *    `BluetoothAdapter.getAddress()` is a hidden API from Android 12, so a modern handset
 *    cannot read its own address, let alone hash it.
 *  * A locally-minted random id is private by construction. Nobody else knows it.
 *
 * So the id has to be *exchanged*. This is that exchange.
 *
 * ## Why the public key rides along with the id
 *
 * Because the id exchange and the key exchange are the same round trip. Separating them would
 * mean two frames and two waits to open a conversation, for no gain: at the moment the peer id
 * arrives the app already needs the peer's public key to derive anything, and sending both
 * together means one frame, one wait, and one place where the peer's claims about itself are
 * validated.
 *
 * The frame is about 600 bytes of ASCII rather than the nine it used to be: the RSA
 * `SubjectPublicKeyInfo` is 294 bytes and hex costs 2x. It still costs one write on a link that
 * is already open, and the alternative -- opening a conversation optimistically and discovering
 * afterwards that the peer cannot be keyed -- is the failure this class exists to prevent.
 *
 * The wrapped contribution that completes the handshake is a second frame, [PeerSecret], because
 * it is encrypted to the peer's key and so cannot exist before this one has been received.
 *
 * ## Why a hello without a public key is rejected outright
 *
 * It cannot produce a message key, so accepting it would mean a conversation that displays,
 * accepts typing, and then fails to deliver every message. Rejection is the only honest
 * answer. A peer running an older build of this app therefore cannot hold a conversation with
 * this one; that is recorded in `docs/LIMITATIONS.md` rather than left to be discovered.
 *
 * ## Why it is not an [Envelope]
 *
 * Three reasons, in order of how much they would hurt:
 *
 *  1. **There is no receiver.** An envelope's `receiverId` field is mandatory, and at hello
 *     time the sender has just met the peer and does not know its id -- which is the entire
 *     reason for the handshake. Encoding an empty receiver would mean weakening a validated
 *     fixed-width field to accommodate a second frame type.
 *  2. **There is no conversation.** The key is built *from* the ids, so a hello cannot be
 *     stored under one without the very information it is carrying.
 *  3. **It must never be encrypted.** The payload of an envelope is ciphertext under a shared
 *     key; at hello time no shared key exists yet. It could not possibly be encrypted.
 *
 * Making it an `Envelope` would mean a `MessageType` with no message behind it, a nullable
 * receiver, and an unencrypted path through code that is otherwise uniformly encrypted. A
 * separate frame type keeps the message path with exactly one job.
 *
 * ## Why the prefix cannot be confused with a message
 *
 * An envelope always begins with magic `0x49` (`I`) followed by version `0x01`, so its second
 * byte is always `0x01` and its first is never anything else. [PREFIX] starts with `H`
 * (`0x48`), which can therefore never begin a valid envelope.
 *
 * That is a property of the protocol, not of the current implementation, and it is asserted
 * in `PeerHelloTest` rather than left to inspection -- a future edit to the envelope magic
 * would otherwise silently make the two frame types ambiguous, and the symptom would be
 * dropped messages, not a crash.
 *
 * ## Both fields are untrusted
 *
 * This arrives from a peer and one part of it becomes a database lookup key.
 *
 * [decode] accepts only the exact id shape
 * [DeviceIdentity][in.isro.sih26173.itantramessage.data.device.DeviceIdentity] mints, and only
 * hex for the key, because `ConversationId` joins ids with `|` and parses by splitting on it:
 * an id containing `|` could otherwise make a conversation resolve to a peer that is not the
 * one connected.
 *
 * The public key is length-bounded and shape-checked here even though
 * [in.isro.sih26173.itantramessage.data.crypto.IdentityKey] checks it again. Redundant on
 * purpose: this is the frame parser, it is the thing facing the radio, and it should not hand
 * a 60 KB "key" to a `KeyFactory` just because a later layer happens to be careful.
 */
object PeerHello {

    /**
     * Discriminator for a handshake frame.
     *
     * ASCII `H`, chosen because an envelope's first byte is `I`. See the class docs.
     */
    const val PREFIX = "HELLO:"

    /** Between the device id and the public key. Not a hex digit and not legal in an id. */
    const val SEPARATOR = ":"

    /**
     * Longest accepted device id, in bytes.
     *
     * `IT-` plus six hex digits is nine bytes, so this admits exactly the valid form plus
     * room for one stray byte. The bound matters: without it a peer could send a 60 KB "id"
     * and have it copied into a preferences key.
     */
    const val MAX_DEVICE_ID_BYTES = 16

    /**
     * Longest accepted public key, in bytes.
     *
     * A 2048-bit RSA `SubjectPublicKeyInfo` is 294 bytes. The ceiling is declared from
     * [in.isro.sih26173.itantramessage.data.crypto.IdentityKey.MAX_PUBLIC_KEY_BYTES] rather than
     * repeated, so the parser and the transport step cannot drift apart into accepting different
     * key sizes -- which would be a rejection at the far end with no clue why. `PeerHelloTest`
     * pins it against real 2048- and 4096-bit key sizes instead of against a literal, so the
     * guarantee survives the key size being changed on one side only.
     */
    const val MAX_PUBLIC_KEY_BYTES = IdentityKey.MAX_PUBLIC_KEY_BYTES

    /**
     * The shape [in.isro.sih26173.itantramessage.data.device.DeviceIdentity] mints.
     *
     * Shared with [in.isro.sih26173.itantramessage.data.device.PeerIdentity] rather than
     * duplicated, so the handshake and the registry cannot drift into accepting different
     * formats -- which would reintroduce exactly the key mismatch this exists to fix.
     */
    val DEVICE_ID = Regex("""IT-[0-9A-F]{6}""")

    private val HEX_ONLY = Regex("""[0-9a-f]*""")

    /**
     * What a peer says about itself.
     *
     * [publicKeyHex] is the peer's X.509 `SubjectPublicKeyInfo`. It is public by construction
     * and safe on the radio; the private half stays in the peer's Keystore.
     */
    data class Announcement(
        val deviceId: String,
        val publicKeyHex: String,
    )

    /**
     * Frame bytes announcing [deviceId] and [publicKeyHex].
     *
     * @throws IllegalArgumentException if either is malformed. This encodes the app's own
     *   view of itself, so a bad value here is a programming error, not hostile input, and
     *   failing loudly at the call site beats sending something the peer would reject.
     */
    fun encode(deviceId: String, publicKeyHex: String): ByteArray {
        require(isValid(deviceId)) { "refusing to announce malformed device id '$deviceId'" }
        require(isValidPublicKey(publicKeyHex)) {
            "refusing to announce a public key of ${publicKeyHex.length / 2} bytes"
        }
        return (PREFIX + deviceId + SEPARATOR + publicKeyHex).toByteArray(Charsets.UTF_8)
    }

    /** Whether [frame] is a handshake, by its first [PREFIX] bytes. */
    fun isHello(frame: ByteArray): Boolean {
        if (frame.size <= PREFIX.length) return false
        for (i in PREFIX.indices) {
            if (frame[i] != PREFIX[i].code.toByte()) return false
        }
        return true
    }

    /**
     * The announcement carried by [frame], or null if it is not a usable handshake.
     *
     * Null covers four cases on purpose, because all four have the same correct handling:
     * ignore it. Not a hello; no separator; a malformed id; a missing or malformed public key.
     * Distinguishing them would only produce a log line nobody can act on.
     */
    fun decode(frame: ByteArray): Announcement? {
        if (!isHello(frame)) return null
        val body = String(frame, PREFIX.length, frame.size - PREFIX.length, Charsets.UTF_8)

        val split = body.indexOf(SEPARATOR)
        // No separator means an old-format hello with no key. Refused: see the class docs.
        if (split <= 0) return null

        val deviceId = body.substring(0, split)
        val publicKeyHex = body.substring(split + SEPARATOR.length)

        if (!isValid(deviceId)) return null
        if (!isValidPublicKey(publicKeyHex)) return null

        return Announcement(deviceId = deviceId, publicKeyHex = publicKeyHex)
    }

    /** Just the device id, for callers that do not need to agree a key. */
    fun decodeDeviceId(frame: ByteArray): String? = decode(frame)?.deviceId

    /** The one place the id shape is decided. */
    fun isValid(deviceId: String): Boolean =
        deviceId.length <= MAX_DEVICE_ID_BYTES && DEVICE_ID.matches(deviceId)

    /**
     * The one place the key shape is decided: lowercase hex, even length, within the byte cap.
     *
     * A real key is additionally required to parse as a P-256 point, but that needs a
     * `KeyFactory` and belongs in
     * [in.isro.sih26173.itantramessage.data.crypto.IdentityKey]. This is the cheap half: it
     * refuses anything that is not even a hex encoding before a single byte reaches the
     * crypto provider.
     */
    fun isValidPublicKey(publicKeyHex: String): Boolean {
        if (publicKeyHex.isEmpty()) return false
        if (publicKeyHex.length % 2 != 0) return false
        if (publicKeyHex.length > MAX_PUBLIC_KEY_BYTES * 2) return false
        return HEX_ONLY.matches(publicKeyHex)
    }
}