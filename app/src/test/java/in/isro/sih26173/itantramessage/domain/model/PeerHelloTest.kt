package `in`.isro.sih26173.itantramessage.domain.model

import `in`.isro.sih26173.itantramessage.data.crypto.IdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the identification and key-agreement handshake.
 *
 * ## What this file is for
 *
 * Two of these tests exist specifically to back a claim made in [PeerHello]'s own
 * documentation: that the `HELLO:` prefix can never be confused with an envelope, and that an
 * envelope can never be mistaken for a hello. That claim is a property of the *protocol*, and
 * nothing at runtime enforces it -- there is no dispatch table that would catch a future edit
 * to the magic byte. If somebody changed `EnvelopeCodec.MAGIC` to `H`, both frame types would
 * become ambiguous, the failure would be silent, and the symptom would be dropped messages
 * rather than a crash. Asserting the property is the only thing that turns it into something
 * with a build failure attached.
 *
 * The public-key half was added when the handshake started carrying this device's identity key,
 * which is the missing half of the key agreement. Before that, the frame announced only an id,
 * so two phones had no way to arrive at a shared secret and no message could be read by the
 * receiver.
 *
 * ## What this file deliberately does not test
 *
 * Nothing here touches a `BluetoothSocket`, a `Context`, or the Keystore. The handshake is
 * pure string and byte handling, which is precisely why it is testable at all -- see
 * `docs/TESTING.md` for the classes that are not.
 */
class PeerHelloTest {

    private val valid = "IT-1A2B3C"

    /**
     * A stand-in public key: 92 hex characters of well-formed hex.
     *
     * Not a real point. Decoding a real P-256 key needs a `KeyFactory`, which belongs to
     * `IdentityKey` and to the hardware run. What matters here is that the parser accepts and
     * refuses the *shape*, and this is a shape the parser cannot distinguish from a real key.
     */
    private val key = "ab".repeat(46)

    // ---- the collision property, asserted rather than asserted-in-a-comment --------------

    /**
     * The prefix cannot begin an envelope.
     *
     * Written in terms of [EnvelopeCodec.MAGIC] rather than the literal `0x49` so that this
     * test *fails* if the magic is ever changed, instead of quietly continuing to pass while
     * asserting something about a number nobody uses any more.
     */
    @Test
    fun `hello prefix cannot begin an envelope`() {
        val firstEnvelopeByte = EnvelopeCodec.MAGIC.toByte()
        val firstHelloByte = PeerHello.PREFIX[0].code.toByte()

        assertNotEquals(
            "the HELLO prefix and the envelope magic must differ, or the two frame " +
                "types are indistinguishable on the wire",
            firstEnvelopeByte,
            firstHelloByte,
        )
    }

    /** An envelope's second byte is pinned, so the two-frame types cannot overlap even if byte 0 did. */
    @Test
    fun `an envelope is never reported as a hello`() {
        val frame = EnvelopeCodec.encode(sample())
        assertFalse(PeerHello.isHello(frame))
        assertNull(PeerHello.decode(frame))
    }

    /** The converse: a hello never decodes as an envelope, so it cannot reach the message path. */
    @Test
    fun `a hello is never reported as an envelope`() {
        val frame = PeerHello.encode(valid, key)
        assertTrue(PeerHello.isHello(frame))
        assertNull(
            "a hello must not be routable as a message",
            EnvelopeCodec.decode(frame),
        )
    }

    /** Byte-level restatement of the above, so the property holds even if the codec changes. */
    @Test
    fun `envelope framing is pinned to magic and version`() {
        val frame = EnvelopeCodec.encode(sample())
        assertEquals(EnvelopeCodec.MAGIC.toByte(), frame[0])
        assertEquals(EnvelopeCodec.VERSION, frame[1])
    }

    // ---- round trip ---------------------------------------------------------------------

    @Test
    fun `encode then decode returns the announced id`() {
        assertEquals(valid, PeerHello.decode(PeerHello.encode(valid, key))?.deviceId)
    }

    /**
     * The key must survive the round trip byte for byte.
     *
     * Not merely "a key came back": if the encoder emitted upper case hex and the decoder
     * accepted it, agreement would still work but the registry and any later comparison would
     * disagree about the same value. Identity is compared by string in this app.
     */
    @Test
    fun `encode then decode returns the announced public key unchanged`() {
        assertEquals(key, PeerHello.decode(PeerHello.encode(valid, key))?.publicKeyHex)
    }

    @Test
    fun `round trip holds for every id shape the generator can mint`() {
        // Exhaustive over the hex alphabet rather than sampled, because the accepted shape is
        // a regex and the interesting failures are the characters *near* the boundary.
        val hex = "0123456789ABCDEF".toCharArray()
        for (a in hex) {
            for (b in hex) {
                val id = "IT-$a$b${a}${b}AA"
                assertTrue(id, PeerHello.isValid(id))
                val decoded = PeerHello.decode(PeerHello.encode(id, key))
                assertEquals(id, decoded?.deviceId)
                assertEquals(key, decoded?.publicKeyHex)
            }
        }
    }

    @Test
    fun `frame is the prefix then id then separator then key`() {
        assertEquals(
            "HELLO:IT-1A2B3C:" + key,
            String(PeerHello.encode(valid, key), Charsets.UTF_8),
        )
    }

    @Test
    fun `frame byte length is prefix plus id plus separator plus key`() {
        assertEquals(
            PeerHello.PREFIX.length + valid.length + PeerHello.SEPARATOR.length + key.length,
            PeerHello.encode(valid, key).size,
        )
    }

    @Test
    fun `decodeDeviceId returns just the id`() {
        assertEquals(valid, PeerHello.decodeDeviceId(PeerHello.encode(valid, key)))
    }

    // ---- a hello with no key is refused --------------------------------------------------

    /**
     * The old-format hello, which carried an id and nothing else.
     *
     * Refused rather than partially accepted. A peer that announced an id but no key cannot be
     * keyed, and accepting it would produce a conversation that opens, accepts typing, and then
     * fails to deliver anything -- the exact failure this change was made to remove.
     */
    @Test
    fun `a hello with no public key is refused`() {
        val old = (PeerHello.PREFIX + valid).toByteArray(Charsets.UTF_8)
        assertTrue("the prefix is present", PeerHello.isHello(old))
        assertNull("an id with no key cannot be agreed on", PeerHello.decode(old))
    }

    @Test
    fun `a hello with an empty public key is refused`() {
        assertNull(PeerHello.decode(("HELLO:" + valid + ":").toByteArray(Charsets.UTF_8)))
        assertFalse(PeerHello.isValidPublicKey(""))
    }

    @Test
    fun `a hello with an odd length public key is refused`() {
        assertFalse(PeerHello.isValidPublicKey("abc"))
        assertNull(PeerHello.decode(("HELLO:" + valid + ":abc").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a hello with a non-hex public key is refused`() {
        assertFalse(PeerHello.isValidPublicKey("zz"))
        assertNull(
            PeerHello.decode(("HELLO:" + valid + ":zzzz").toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * Upper case hex is refused.
     *
     * Device ids are upper case, so this looks inconsistent until it is spelled out: the id is
     * generated by this app in one case and the key is generated by a `KeyFactory` whose
     * encoding is lower case. Accepting either case would give a key that compares unequal to
     * itself depending on who encoded it.
     */
    @Test
    fun `a hello with an upper case public key is refused`() {
        assertFalse(PeerHello.isValidPublicKey("ABCD"))
        assertNull(PeerHello.decode(("HELLO:" + valid + ":ABCD").toByteArray(Charsets.UTF_8)))
    }

    /**
     * The key length bound is real, not decorative.
     *
     * The hex check already rejects anything that is not hex, so this asserts the *cap*: it
     * would fail if someone raised the cap in one class and not the other.
     */
    @Test
    fun `an over-long public key is refused`() {
        val overLong = "ab".repeat(PeerHello.MAX_PUBLIC_KEY_BYTES + 1)
        assertFalse(PeerHello.isValidPublicKey(overLong))
        assertNull(
            PeerHello.decode(
                ("HELLO:" + valid + ":" + overLong).toByteArray(Charsets.UTF_8),
            ),
        )
    }

    @Test
    fun `the key cap admits the maximum`() {
        assertTrue(PeerHello.isValidPublicKey("ab".repeat(PeerHello.MAX_PUBLIC_KEY_BYTES)))
    }

    /**
     * The parser and the agreement step must agree about the cap.
     *
     * `MAX_PUBLIC_KEY_BYTES` is a compile-time constant, so this compares values rather than
     * loading the Keystore-backed class. If the two ever diverge, one of them is rejecting
     * something the other accepted, and the handshake fails for no visible reason.
     */
    @Test
    fun `the key cap matches the one the agreement step enforces`() {
        assertEquals(
            "PeerHello and IdentityKey must agree on the largest acceptable public key",
            IdentityKey.MAX_PUBLIC_KEY_BYTES,
            PeerHello.MAX_PUBLIC_KEY_BYTES,
        )
    }

    // ---- malformed ids are refused -----------------------------------------------------

    /**
     * The `|` injection case.
     *
     * [ConversationId] builds its key by joining two ids with `|` and parses by splitting on
     * it. An id containing `|` from an untrusted peer would let that peer name a conversation
     * that resolves to a *third* id, which is the same class of defect as the two-call-site
     * key mismatch described in `docs/TESTING.md`: a message filed under a conversation
     * nobody is looking at, with no error anywhere.
     */
    @Test
    fun `an id carrying the conversation separator is refused`() {
        val forged = "IT-1A2B3C|IT-9F9F9F"
        assertFalse(PeerHello.isValid(forged))
        assertNull(PeerHello.decode(("HELLO:" + forged + ":" + key).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a forged id cannot name a different conversation`() {
        // The whole point of refusing the above: parse must never see it at all.
        val forged = "IT-1A2B3C|IT-9F9F9F"
        val decoded = PeerHello.decode(
            ("HELLO:" + forged + ":" + key).toByteArray(Charsets.UTF_8),
        )
        assertNull(decoded)

        // And with a null decode, the registry has nothing to persist, so nothing to parse.
        val conversation = ConversationId.parse(
            ConversationId.of("IT-AAAAAA", valid).value,
            "IT-AAAAAA",
        )
        assertEquals(valid, conversation?.peer)
    }

    @Test
    fun `lowercase hex is refused`() {
        // The generator emits uppercase. Accepting both would mean two spellings of one id and
        // a conversation key that differs by letter case while addressing the same device.
        assertFalse(PeerHello.isValid("it-1a2b3c"))
        assertFalse(PeerHello.isValid("IT-1a2b3c"))
        assertNull(PeerHello.decode("HELLO:IT-1a2b3c".toByteArray(Charsets.UTF_8)))
        assertNull(
            PeerHello.decode(("HELLO:IT-1a2b3c:" + key).toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `wrong lengths are refused`() {
        assertFalse("too short", PeerHello.isValid("IT-1A2B3"))
        assertFalse("too long", PeerHello.isValid("IT-1A2B3CD"))
        assertFalse("no prefix", PeerHello.isValid("1A2B3C"))
        assertFalse("empty", PeerHello.isValid(""))
        assertFalse("wrong prefix", PeerHello.isValid("XX-1A2B3C"))
    }

    @Test
    fun `non-hex characters are refused`() {
        assertFalse(PeerHello.isValid("IT-1A2B3G"))
        assertFalse(PeerHello.isValid("IT-______"))
        assertFalse(PeerHello.isValid("IT-1A2B3C "))
        assertFalse(PeerHello.isValid(" IT-1A2B3C"))
        assertFalse(PeerHello.isValid("IT-1A2B3C\n"))
    }

    /**
     * The length bound is real, not decorative.
     *
     * Without it a peer could send a 60 KB "id" and have it copied into a preferences key.
     * The regex already rejects anything but nine bytes, so this asserts the *bound* rather
     * than the shape -- it would fail if someone widened the regex without revisiting the cap.
     */
    @Test
    fun `an over-long id is refused even if it is otherwise well formed`() {
        val overLong = "IT-" + "A".repeat(PeerHello.MAX_DEVICE_ID_BYTES)
        assertTrue("fixture must exceed the cap to be a real test", overLong.length > PeerHello.MAX_DEVICE_ID_BYTES)
        assertFalse(PeerHello.isValid(overLong))
    }

    @Test
    fun `the cap admits the valid form`() {
        assertTrue(valid.length <= PeerHello.MAX_DEVICE_ID_BYTES)
        assertTrue(PeerHello.isValid(valid))
    }

    @Test
    fun `encode throws rather than sending a malformed id`() {
        // Sending it would put an unparseable value on the wire and store it in the registry;
        // failing loudly at the call site is the only version of this that is debuggable.
        val threw = try {
            PeerHello.encode("not-an-id", key)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("encode must reject a malformed id", threw)
    }

    /** The same, for the key: the app refuses to announce an identity it cannot agree with. */
    @Test
    fun `encode throws rather than sending a malformed public key`() {
        val threw = try {
            PeerHello.encode(valid, "not-hex")
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("encode must reject a malformed key", threw)
    }

    // ---- frames that are not handshakes at all -----------------------------------------

    @Test
    fun `a frame too short to carry an id is not a hello`() {
        // Exactly the prefix, and one byte short of prefix+id.
        assertFalse(PeerHello.isHello(PeerHello.PREFIX.toByteArray(Charsets.UTF_8)))
        assertNull(PeerHello.decode(PeerHello.PREFIX.toByteArray(Charsets.UTF_8)))
        assertNull(PeerHello.decode("HELLO:IT-1A2B3".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `an empty frame is not a hello`() {
        assertFalse(PeerHello.isHello(ByteArray(0)))
        assertNull(PeerHello.decode(ByteArray(0)))
    }

    @Test
    fun `a near-miss prefix is not a hello`() {
        assertFalse(PeerHello.isHello("HELLO;IT-1A2B3C".toByteArray(Charsets.UTF_8)))
        assertFalse(PeerHello.isHello("hello:IT-1A2B3C".toByteArray(Charsets.UTF_8)))
        assertFalse(PeerHello.isHello("HELLOXIT-1A2B3C".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a hello whose id is not valid utf8 text is ignored`() {
        // Bytes that cannot form a valid id in any encoding. decode must return null, not throw
        // and not hand back a string that will later become a preferences key.
        val frame = PeerHello.PREFIX.toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertTrue("prefix is present", PeerHello.isHello(frame))
        assertNull(PeerHello.decode(frame))
    }

    @Test
    fun `an id padded with a nul byte is refused`() {
        // A fixed-width reader elsewhere might leave the terminator attached.
        val frame = PeerHello.PREFIX.toByteArray(Charsets.UTF_8) + "IT-1A2B3C ".toByteArray(Charsets.UTF_8)
        assertNull(PeerHello.decode(frame))
    }

    private fun sample() = Envelope(
        messageId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        senderId = "IT-1A2B3C",
        receiverId = "IT-4D5E6F",
        timestamp = 1_726_000_000_000L,
        type = MessageType.TEXT,
        payload = "itm1.1.aXY=.Y2lwaGVy",
    )
}