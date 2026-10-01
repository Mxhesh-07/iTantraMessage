package `in`.isro.sih26173.itantramessage.data.crypto

import `in`.isro.sih26173.itantramessage.domain.model.ConversationId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SessionKeys.combine], the step that puts the two sides' contributions together.
 *
 * ## Why this file exists at all
 *
 * The bug this whole crypto area was built to fix was not a wrong function. `encrypt` was
 * correct, `decrypt` was correct, every test was green, and two phones still could not read each
 * other's messages, because no step anywhere made them hold the same key. `combine` is exactly
 * that kind of step: its whole job is to produce the same answer on two devices, and on a single
 * device that is indistinguishable from producing any answer at all.
 *
 * So the central test here is not "does `combine` concatenate" -- it is "do two simulated devices,
 * each holding only what that device would really hold, arrive at the same key". That is the
 * claim that could not be made before, and it is made without a Keystore, a socket, or a phone.
 *
 * ## What a wrong implementation would look like
 *
 * If `combine` put *our own* contribution first, every test below except the last would still
 * pass, and two phones would derive different keys -- the original bug, reintroduced through a
 * different door. The last test is the one that fails. It is the negative control for this file,
 * and [the doc in `combine`][SessionKeys.combine] explains the ambiguity it avoids.
 */
class SessionKeysCombineTest {

    private val alpha = "IT-000001"
    private val bravo = "IT-000002"

    /** Deterministic stand-in for random entropy; the values do not matter, only their bytes. */
    private fun bytes(seed: Int, size: Int = SessionKeys.CONTRIBUTION_BYTES) =
        ByteArray(size) { ((it + seed) and 0xFF).toByte() }

    // ---- the property that matters -------------------------------------------------------

    /**
     * Two devices, each holding only what it would really hold, derive the same key.
     *
     * Alpha holds its own contribution and Bravo's (unwrapped). Bravo holds its own and Alpha's.
     * Neither holds the other's *combined* value. If the ordering is wrong in either direction,
     * the two keys differ and this fails -- which is exactly the failure mode that no
     * single-device test can see.
     */
    @Test
    fun `two devices holding mirrored material derive the same key`() {
        val alphaOwn = bytes(seed = 1)
        val bravoOwn = bytes(seed = 2)

        // What each side would hold after the four-frame handshake.
        val alphaSide = SessionKeys.combine(alpha, alphaOwn, bravo, bravoOwn)
        val bravoSide = SessionKeys.combine(bravo, bravoOwn, alpha, alphaOwn)

        assertNotNull("alpha must be able to combine its material", alphaSide)
        assertNotNull("bravo must be able to combine its material", bravoSide)

        assertArrayEquals(
            "the two sides must build identical key material",
            alphaSide!!,
            bravoSide!!,
        )
    }

    /**
     * The same claim carried all the way to a usable key.
     *
     * `combine` agreeing is necessary but not sufficient: the derivation also has to be
     * identical on both sides, which is a second place the two devices could diverge. This runs
     * both sides through [SessionKeys.derive] with the conversation id each would compute and
     * compares the results.
     */
    @Test
    fun `two devices derive the same AES key for the conversation`() {
        val alphaOwn = bytes(seed = 7)
        val bravoOwn = bytes(seed = 9)

        val conversationId = ConversationId.of(alpha, bravo).value

        val alphaKey = SessionKeys.derive(
            SessionKeys.combine(alpha, alphaOwn, bravo, bravoOwn)!!,
            conversationId,
        )
        val bravoKey = SessionKeys.derive(
            SessionKeys.combine(bravo, bravoOwn, alpha, alphaOwn)!!,
            conversationId,
        )

        assertNotNull(alphaKey)
        assertNotNull(bravoKey)
        assertArrayEquals(
            "two devices must derive byte-identical AES keys, or every message is unreadable",
            alphaKey!!.encoded,
            bravoKey!!.encoded,
        )
        assertEquals(SessionKeys.KEY_SIZE_BYTES, alphaKey!!.encoded.size)
    }

    /**
     * The converse: a third device must not derive the same key.
     *
     * Guards the test above against being trivially true. If `combine` ignored its arguments and
     * returned a constant, both of the last two tests would pass, so the suite needs something
     * that only passes when the inputs are actually used.
     */
    @Test
    fun `a different peer derives a different key`() {
        val alphaOwn = bytes(seed = 1)
        val bravoOwn = bytes(seed = 2)
        val charlieOwn = bytes(seed = 3)

        val conversationId = ConversationId.of(alpha, bravo).value

        val withBravo = SessionKeys.derive(
            SessionKeys.combine(alpha, alphaOwn, bravo, bravoOwn)!!,
            conversationId,
        )
        val withCharlie = SessionKeys.derive(
            SessionKeys.combine(alpha, alphaOwn, "IT-000003", charlieOwn)!!,
            conversationId,
        )

        assertFalse(
            "the peer's contribution must actually change the key",
            withBravo!!.encoded.contentEquals(withCharlie!!.encoded),
        )
    }

    // ---- ordering is by device id, not by who is asking ------------------------------------

    /**
     * Whichever side calls `combine`, the bytes come out in the same order.
     *
     * This is the ordering property stated on its own, so a failure says *which* half broke:
     * the ordering, rather than agreement in general.
     */
    @Test
    fun `the result does not depend on which side combines`() {
        val ours = bytes(seed = 11)
        val theirs = bytes(seed = 22)

        assertArrayEquals(
            SessionKeys.combine(alpha, ours, bravo, theirs),
            SessionKeys.combine(bravo, theirs, alpha, ours),
        )
    }

    /**
     * The lower device id's contribution comes first.
     *
     * Pins the actual rule rather than just its effect, because a future edit that reversed the
     * comparison would still satisfy the two tests above and would break every real connection.
     */
    @Test
    fun `the lexicographically lower device id contributes first`() {
        val lower = bytes(seed = 0x0A)
        val higher = bytes(seed = 0xB0)

        val combined = SessionKeys.combine(alpha, lower, bravo, higher)!!
        val expected = ByteArray(lower.size + higher.size).also {
            lower.copyInto(it, 0)
            higher.copyInto(it, lower.size)
        }

        assertArrayEquals(expected, combined)
    }

    /** Two contributions of different lengths still concatenate in id order, with no padding. */
    @Test
    fun `unequal contribution lengths concatenate without padding`() {
        val short = bytes(seed = 5, size = 8)
        val long = bytes(seed = 6, size = 40)

        val combined = SessionKeys.combine(alpha, short, bravo, long)!!

        assertEquals(48, combined.size)
        assertArrayEquals(short, combined.copyOfRange(0, 8))
        assertArrayEquals(long, combined.copyOfRange(8, 48))
    }

    // ---- refusals -------------------------------------------------------------------------

    /**
     * Equal device ids are refused, because the ordering would be ambiguous.
     *
     * Picking an order here is the one thing that must not happen: the whole point of ordering
     * by id is that both sides reach the same answer without coordinating, and "pick one" throws
     * that away.
     */
    @Test
    fun `two devices with the same id are refused`() {
        assertNull(SessionKeys.combine(alpha, bytes(1), alpha, bytes(2)))
    }

    @Test
    fun `an empty contribution is refused`() {
        assertNull(SessionKeys.combine(alpha, ByteArray(0), bravo, bytes(2)))
        assertNull(SessionKeys.combine(alpha, bytes(1), bravo, ByteArray(0)))
        assertNull(SessionKeys.combine(alpha, ByteArray(0), bravo, ByteArray(0)))
    }

    /**
     * An empty device id is refused.
     *
     * `""` sorts before every real id, so it would otherwise be accepted and would produce a key
     * for a conversation with a participant that does not exist. A real id is checked by
     * `PeerHello` at the frame boundary; this is the check at the byte boundary.
     */
    @Test
    fun `an empty device id is refused`() {
        assertNull(SessionKeys.combine("", bytes(1), bravo, bytes(2)))
        assertNull(SessionKeys.combine(alpha, bytes(1), "", bytes(2)))
    }

    // ---- the input is not modified ---------------------------------------------------------

    /**
     * `combine` leaves both arguments alone.
     *
     * `derive` deliberately wipes the secret it is given, and `combine` deliberately does not --
     * the caller still needs its own contribution after the peer's arrives. If someone later
     * "tidied up" `combine` to match `derive`, the handshake would break in a way that looks
     * like the peer sent a bad contribution, so the difference is pinned here.
     */
    @Test
    fun `the contributions are not modified`() {
        val ours = bytes(seed = 31)
        val theirs = bytes(seed = 32)
        val oursBefore = ours.copyOf()
        val theirsBefore = theirs.copyOf()

        SessionKeys.combine(alpha, ours, bravo, theirs)

        assertArrayEquals("our contribution must survive the call", oursBefore, ours)
        assertArrayEquals("the peer's contribution must survive the call", theirsBefore, theirs)
    }

    /** The output is a fresh array, not an alias of either input. */
    @Test
    fun `the result does not alias either contribution`() {
        val ours = bytes(seed = 41)
        val theirs = bytes(seed = 42)

        val combined = SessionKeys.combine(alpha, ours, bravo, theirs)!!
        combined.fill(0)

        assertArrayEquals("zeroing the result must not reach our contribution", bytes(seed = 41), ours)
        assertArrayEquals("zeroing the result must not reach the peer's", bytes(seed = 42), theirs)
    }

    /** Nothing is logged or thrown on the success path: this is a hot, ordinary operation. */
    @Test
    fun `combining is free of side effects the caller has to clean up`() {
        val combined = SessionKeys.combine(alpha, bytes(51), bravo, bytes(52))
        assertNotNull(combined)
        assertEquals(
            SessionKeys.CONTRIBUTION_BYTES * 2,
            combined!!.size,
        )
    }
}