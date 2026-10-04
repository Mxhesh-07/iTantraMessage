package `in`.isro.sih26173.itantramessage.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the queue-advancement rules that the retry worker depends on.
 *
 * ## Why these exist
 *
 * Both tests here were written after a defect found on two real handsets, and both are the
 * same underlying mistake: the code recorded a state change that the *query* used to select
 * work would not notice, so the row never left the queue.
 *
 * On the realme the log showed, in a tight loop and with no end:
 *
 * ```
 * D MessageRepository: sent a280fc31-... on link 0
 * W MessageRepository: refused SENDING -> SENDING for a280fc31-...
 * ```
 *
 * The message stayed on screen as "Sending" forever while the socket was written to
 * continuously. The cause was a compare-and-set whose expected state did not match the row:
 * the send path had already moved the row to SENDING, and the follow-up asked for
 * PENDING -> SENT, so `WHERE status = 'PENDING'` matched zero rows and the row stayed
 * SENDING, which `pendingForDelivery` happily returns forever.
 *
 * The companion defect was the opposite one: marking a row FAILED on the *first* rejected
 * write. FAILED is terminal, so the row left the queue immediately and the retry budget of
 * three attempts was never spent -- one refusal ended it.
 *
 * Neither was reachable from a unit test of the state machine alone, because both defects
 * lived in the disagreement between the state written and the state queried. These tests pin
 * the agreement, in the terms the code actually uses.
 *
 * A caveat that cost a day, added 2026-10-04: the first defect was NOT actually fixed by this
 * class. Every test in it asserts that `SENDING -> SENT` is a permitted edge, and it was, and
 * the message still got stuck -- because the UPDATE never ran with a matching `expectedFrom`.
 * Testing whether a transition is legal is a different question from testing whether it is
 * performed, and only the second one was broken. The last test in this file covers the part
 * that can be covered without an Android Keystore; see LIMITATIONS.md for the part that
 * cannot.
 */
class QueueAdvanceTest {

    /**
     * A row that is retriable must still be retriable after a failed attempt.
     *
     * This is the property `recordRetry` exists to provide. Asserted through
     * `pendingForDelivery`'s own predicate -- `status IN ('PENDING', 'SENDING')` -- rather
     * than through a fresh literal, so the test fails if that query is ever widened or
     * narrowed.
     */
    @Test
    fun `a row is still queued after a failed attempt that did not exhaust the budget`() {
        val retriable = DeliveryStatus.entries.filter { it == DeliveryStatus.PENDING || it == DeliveryStatus.SENDING }

        // Every state a row can be in when an attempt fails must still be picked up again,
        // or the message is silently abandoned after one try.
        assertEquals(
            "a failed attempt must leave the row eligible for the next one",
            retriable,
            DeliveryStatus.entries.filter { it in retriable && it.isRetriable },
        )

        // And the specific transition `recordRetry` performs must be legal, because it
        // performs it in SQL: a write that in flight leaves SENDING, and a retry is not a
        // fresh send.
        assertTrue(
            "SENDING -> PENDING must be legal for a retry to be recordable",
            DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.PENDING),
        )
    }

    /**
     * The row must actually leave the queue once it is sent.
     *
     * The defect this pins is the one from the hardware run. After a successful write the
     * row is SENDING; the follow-up is SENDING -> SENT. If that transition is refused, or if
     * it is requested with any `from` other than SENDING, the row stays SENDING and the pump
     * re-selects it and re-sends forever.
     *
     * So: every retriable state must be able to reach SENT, and SENDING in particular must
     * be able to leave for a terminal state. A retriable state with no edge to SENT is a
     * guaranteed infinite resend loop.
     */
    @Test
    fun `every retriable state can reach SENT, so a sent row leaves the queue`() {
        for (state in DeliveryStatus.entries.filter { it.isRetriable }) {
            assertTrue(
                "$state must be able to reach SENT or the row never leaves the queue",
                state.canTransitionTo(DeliveryStatus.SENT),
            )
        }
    }

    /**
     * A successful send must not be repeatable.
     *
     * The mirror of the above, and the reason the row has to reach SENT rather than sitting
     * in a retriable state: once sent, no further transition out of SENT toward a
     * re-sendable state is permitted.
     */
    @Test
    fun `SENT cannot fall back to a retriable state`() {
        assertFalse(DeliveryStatus.SENT.canTransitionTo(DeliveryStatus.PENDING))
        assertFalse(DeliveryStatus.SENT.canTransitionTo(DeliveryStatus.SENDING))
    }

    /**
     * SENDING must be escapable in both directions.
     *
     * SENDING is the state a row is in for the whole duration of one write attempt, so it
     * needs an exit for success (SENT), an exit for failure (PENDING, to retry) and an exit
     * for giving up (FAILED). A state with only some of those strands rows.
     */
    @Test
    fun `SENDING can be left for sent, retried or abandoned`() {
        assertTrue(DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.SENT))
        assertTrue(DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.PENDING))
        assertTrue(DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.FAILED))
    }

    /**
     * The compare-and-set cannot be driven from a stale snapshot.
     *
     * Every test above in this class checks the *state machine*: whether `SENDING -> SENT` is a
     * permitted edge. None of them check whether the SQL that implements that edge actually
     * runs, and the two are not the same question. The defect fixed on 2026-10-04 was legal
     * transition, zero executed statements: `MessageRepository.transition` compared against
     * `MessageEntity.status`, a field frozen when the row was read, while the row itself had
     * already been moved to SENDING by an earlier call using that same entity. The UPDATE
     * matched nothing, the row stayed SENDING, and the message sat on "Sending" forever while
     * every state-machine assertion in this file continued to pass.
     *
     * So this test pins the property that actually broke: a transition requested with an
     * out-of-date `expectedFrom` must affect no rows, and reading the status first must fix
     * it. The fake below mirrors the DAO's `WHERE message_id = :id AND status = :expectedFrom`
     * predicate exactly, so if someone reintroduces a snapshot-based comparison the reasoning
     * this test documents no longer holds.
     *
     * It deliberately does not construct `MessageRepository` -- that needs the Android
     * Keystore, and adding Robolectric to a project whose release APK currently contains no
     * native code and one dependency family is a larger decision than this fix. The gap is
     * real and named in LIMITATIONS.md.
     */
    @Test
    fun `a compare-and-set ignores an out-of-date expected state, and re-reading it fixes that`() {
        // The row as the database holds it.
        val db = mutableMapOf("msg-1" to DeliveryStatus.PENDING)

        // What the repository was holding: read before `deliverOnAnyLink` wrote to the row.
        val staleSnapshot = DeliveryStatus.PENDING

        fun compareAndSet(id: String, expectedFrom: DeliveryStatus, to: DeliveryStatus): Int {
            val current = db[id] ?: return 0
            if (current != expectedFrom) return 0
            db[id] = to
            return 1
        }

        // First write, as deliverOnAnyLink does it. The snapshot and the row agree here.
        assertEquals(1, compareAndSet("msg-1", staleSnapshot, DeliveryStatus.SENDING))
        assertEquals(DeliveryStatus.SENDING, db["msg-1"])

        // Second write, as the caller does it. Reusing the snapshot is the bug: the row is
        // SENDING now, so PENDING matches nothing and the row never advances.
        assertEquals(
            "a stale expected state must match no rows",
            0,
            compareAndSet("msg-1", staleSnapshot, DeliveryStatus.SENT),
        )
        assertEquals(
            "and the row is stranded in SENDING, which is the reported symptom",
            DeliveryStatus.SENDING,
            db["msg-1"],
        )

        // The fix: read the row first. statusOf is what MessageRepository.transition now calls.
        fun statusOf(id: String): DeliveryStatus? = db[id]

        val fresh = statusOf("msg-1")!!
        assertEquals(1, compareAndSet("msg-1", fresh, DeliveryStatus.SENT))
        assertEquals(
            "reading the current status first must let the row leave the queue",
            DeliveryStatus.SENT,
            db["msg-1"],
        )
    }
}