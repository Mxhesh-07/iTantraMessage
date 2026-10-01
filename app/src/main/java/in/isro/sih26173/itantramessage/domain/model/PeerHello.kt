package `in`.isro.sih26173.itantramessage.domain.model

/**
 * The identification handshake: each side announces its device id once per link.
 *
 * ## Why a handshake is unavoidable
 *
 * A conversation key is built from two device ids, because both ends must derive the same
 * key with no coordination -- neither phone gets to dictate what the conversation is called.
 * But a device id cannot be derived from anything both ends can see:
 *
 *  * Hashing the MAC address would remove the handshake, and is impossible anyway.
 *    `BluetoothAdapter.getAddress()` is a hidden API from Android 12, so a modern handset
 *    cannot read its own address, let alone hash it.
 *  * A locally-minted random id is private by construction. Nobody else knows it.
 *
 * So the id has to be *exchanged*. This is the exchange, and it is nine bytes of ASCII on a
 * link that is already carrying messages.
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
 *     key; at hello time no shared key exists yet.
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
 * ## The payload is untrusted
 *
 * This arrives from a peer and becomes a database lookup key. [decode] accepts only the exact
 * shape [DeviceIdentity][in.isro.sih26173.itantramessage.data.device.DeviceIdentity] mints and
 * rejects everything else, because `ConversationId` joins ids with `|` and parses by splitting
 * on it: an id containing `|` could otherwise make a conversation resolve to a peer that is
 * not the one connected.
 */
object PeerHello {

    /**
     * Discriminator for a handshake frame.
     *
     * ASCII `H`, chosen because an envelope's first byte is `I`. See the class docs.
     */
    const val PREFIX = "HELLO:"

    /**
     * Longest accepted device id, in bytes.
     *
     * `IT-` plus six hex digits is nine bytes, so this admits exactly the valid form plus
     * room for one stray byte. The bound matters: without it a peer could send a 60 KB "id"
     * and have it copied into a preferences key.
     */
    const val MAX_DEVICE_ID_BYTES = 16

    /**
     * The shape [in.isro.sih26173.itantramessage.data.device.DeviceIdentity] mints.
     *
     * Shared with [in.isro.sih26173.itantramessage.data.device.PeerIdentity] rather than
     * duplicated, so the handshake and the registry cannot drift into accepting different
     * formats -- which would reintroduce exactly the key mismatch this exists to fix.
     */
    val DEVICE_ID = Regex("""IT-[0-9A-F]{6}""")

    /** Frame bytes for announcing [deviceId]. Throws if the id is not a well-formed one. */
    fun encode(deviceId: String): ByteArray {
        require(isValid(deviceId)) { "refusing to announce malformed device id '$deviceId'" }
        return (PREFIX + deviceId).toByteArray(Charsets.UTF_8)
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
     * The announced device id, or null if [frame] is not a usable handshake.
     *
     * Null covers three cases on purpose, because all three have the same correct handling:
     * ignore it. Not a hello; a hello with a malformed id; a hello with no id at all.
     * Distinguishing them would only produce a log line nobody can act on.
     */
    fun decode(frame: ByteArray): String? {
        if (!isHello(frame)) return null
        val value = String(frame, PREFIX.length, frame.size - PREFIX.length, Charsets.UTF_8)
        return value.takeIf { isValid(it) }
    }

    /** The one place the id shape is decided. */
    fun isValid(deviceId: String): Boolean =
        deviceId.length <= MAX_DEVICE_ID_BYTES && DEVICE_ID.matches(deviceId)
}