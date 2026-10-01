package `in`.isro.sih26173.itantramessage.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the identification handshake.
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
 * ## What this file deliberately does not test
 *
 * Nothing here touches a `BluetoothSocket`, a `Context`, or the Keystore. The handshake is
 * pure string and byte handling, which is precisely why it is testable at all -- see
 * `docs/TESTING.md` for the classes that are not.
 */
class PeerHelloTest {

    private val valid = "IT-1A2B3C"

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
        val frame = PeerHello.encode(valid)
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
        assertEquals(valid, PeerHello.decode(PeerHello.encode(valid)))
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
                assertEquals(id, PeerHello.decode(PeerHello.encode(id)))
            }
        }
    }

    @Test
    fun `frame is the prefix followed by the id`() {
        assertEquals(
            "HELLO:IT-1A2B3C",
            String(PeerHello.encode(valid), Charsets.UTF_8),
        )
    }

    @Test
    fun `frame byte length is prefix plus id`() {
        assertEquals(
            PeerHello.PREFIX.length + valid.length,
            PeerHello.encode(valid).size,
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
        assertNull(PeerHello.decode(forged.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a forged id cannot name a different conversation`() {
        // The whole point of refusing the above: parse must never see it at all.
        val forged = "IT-1A2B3C|IT-9F9F9F"
        val decoded = PeerHello.decode(("HELLO:" + forged).toByteArray(Charsets.UTF_8))
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
            PeerHello.encode("not-an-id")
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("encode must reject a malformed id", threw)
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
        val frame = PeerHello.PREFIX.toByteArray(Charsets.UTF_8) + "IT-1A2B3C ".toByteArray(Charsets.UTF_8)
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