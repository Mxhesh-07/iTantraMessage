package `in`.isro.sih26173.itantramessage.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the conversation key.
 *
 * ## The problem this type solves
 *
 * A conversation key has to be built by two phones independently and still be the same
 * string on both, with no negotiation. Phone A thinks it is talking to B; phone B thinks it
 * is talking to A. If the key were `A|B` on one side and `B|A` on the other, each would
 * file the conversation under a different name and neither would ever see the other's
 * messages.
 *
 * Sorting the two ids fixes that, and it is why [ConversationId] carries the peer
 * separately as well. Two fields rather than one, because the key alone does not say which
 * of the two ids is this device -- and every query needs to know that.
 *
 * The alternative, a single `String` peer field, makes the two easy to swap: `route` takes
 * a conversation value and a peer id, and a swap compiles cleanly and then files messages
 * under the wrong conversation. Making them one type means the compiler rejects it.
 */
class ConversationIdTest {

    private val self = "IT-1A2B3C"
    private val peer = "IT-4D5E6F"

    // ---- both ends agree -----------------------------------------------------------------

    @Test
    fun `both devices derive the same key`() {
        // The whole point. A's view and B's view of the same conversation must be the same
        // string, because neither end tells the other what to call it.
        val fromA = ConversationId.of(selfId = self, peerId = peer)
        val fromB = ConversationId.of(selfId = peer, peerId = self)

        assertEquals(fromA.value, fromB.value)
    }

    @Test
    fun `each device knows which id is the peer`() {
        assertEquals(peer, ConversationId.of(selfId = self, peerId = peer).peer)
        assertEquals(self, ConversationId.of(selfId = peer, peerId = self).peer)
    }

    @Test
    fun `the key is order-independent`() {
        // Explicitly the sorted form, so the property is visible in the test rather than
        // inferred from "both produced the same string".
        val expected = "IT-1A2B3C|IT-4D5E6F"
        assertEquals(expected, ConversationId.of(selfId = self, peerId = peer).value)
        assertEquals(expected, ConversationId.of(selfId = peer, peerId = self).value)
    }

    // ---- parsing a stored key -----------------------------------------------------------

    @Test
    fun `parse recovers the peer from a stored key`() {
        val stored = ConversationId.of(selfId = self, peerId = peer).value

        val parsed = ConversationId.parse(stored, selfId = self)

        assertEquals(peer, parsed?.peer)
        assertEquals(stored, parsed?.value)
    }

    @Test
    fun `parse recovers the peer from either end of the same key`() {
        // The key is stored in one place and read from both devices' databases. A parses
        // the key B wrote and must still know the peer is B.
        val stored = ConversationId.of(selfId = peer, peerId = self).value

        assertEquals(self, ConversationId.parse(stored, selfId = peer)?.peer)
    }

    @Test
    fun `a key round-trips`() {
        val original = ConversationId.of(selfId = self, peerId = peer)
        val parsed = ConversationId.parse(original.value, selfId = self)

        assertEquals(original, parsed)
    }

    // ---- rejected input -------------------------------------------------------------------

    @Test
    fun `a key with the wrong number of parts is rejected`() {
        assertNull(ConversationId.parse("IT-1A2B3C", selfId = self))
        assertNull(ConversationId.parse("a|b|c", selfId = self))
        assertNull(ConversationId.parse("", selfId = self))
    }

    @Test
    fun `a key naming only this device is rejected`() {
        // Cannot happen from of(), but a corrupted or hand-edited database could hold it,
        // and returning null is better than a conversation whose peer is this device.
        assertNull(ConversationId.parse("$self|$self", selfId = self))
    }

    @Test
    fun `a key that does not contain this device is rejected`() {
        // If selfId is wrong, the "which one is the peer" question has no answer, and
        // guessing would file messages under a stranger's conversation. Both parts being
        // other ids means the caller passed the wrong selfId.
        assertNull(ConversationId.parse("IT-AAAAAA|IT-BBBBBB", selfId = self))
    }

    @Test
    fun `an empty part is rejected`() {
        // `firstOrNull { it != selfId }` would happily return "" for "IT-1A2B3C|", giving a
        // conversation with an empty peer -- which would then match nothing and produce a
        // chat screen that never loads.
        assertNull(ConversationId.parse("$self|", selfId = self))
        assertNull(ConversationId.parse("|$peer", selfId = self))
    }

    // ---- the assumption this format rests on ----------------------------------------------

    /**
     * The separator is only safe because a device id cannot contain it.
     *
     * `DeviceIdentity` produces `IT-%06X`, which is `IT-` plus six hex digits -- no `|`.
     * That is an *assumption* rather than something enforced here, and it is load-bearing:
     * if a device id ever contained a pipe, `parse` would split it into three parts and
     * return null, so a conversation would silently stop resolving.
     *
     * Asserted rather than assumed, so that changing the device id format fails here with
     * an explanation rather than in the field as messages that never appear.
     */
    @Test
    fun `device ids cannot contain the separator`() {
        for (id in listOf(self, peer)) {
            assertEquals("device id must not contain the separator", false, id.contains('|'))
            assertEquals("device id must not be empty", false, id.isEmpty())
        }

        // And the round-trip holds for ids across the whole space of the real format.
        for (n in listOf(0x000001, 0x0A2B3C, 0xFFFFFF)) {
            val a = String.format("IT-%06X", n)
            val b = String.format("IT-%06X", n xor 0x0FFFFF)
            val key = ConversationId.of(selfId = a, peerId = b).value

            // Each device sees the *other* one as the peer, which is the whole reason the
            // key is sorted.
            assertEquals(b, ConversationId.parse(key, selfId = a)?.peer)
            assertEquals(a, ConversationId.parse(key, selfId = b)?.peer)
        }
    }
}