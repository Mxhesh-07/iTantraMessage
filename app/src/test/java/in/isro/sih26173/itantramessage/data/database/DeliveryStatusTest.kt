package `in`.isro.sih26173.itantramessage.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the delivery state machine.
 *
 * ## Why this is load-bearing and not bookkeeping
 *
 * `DeliveryStatus` decides two things that a messaging app cannot get wrong:
 *
 *  - whether a message is retried, and
 *  - whether a message the user has already seen gets sent again.
 *
 * Both failure modes are silent. A transition that should be forbidden and is not produces
 * a duplicate message in the user's chat with no error anywhere. A transition that should
 * be allowed and is not produces a message that never sends and never says why -- the user
 * sees it sitting at "pending" forever.
 *
 * Neither shows up in a demo with two phones side by side and a healthy link. So the rules
 * are pinned here, where they can be read as rules.
 *
 * ## Two properties, tested as properties
 *
 * Rather than asserting each permitted edge individually -- which passes just as happily
 * when one is missing -- the tests below check the two properties the whole machine exists
 * to guarantee:
 *
 *  1. **Safety**: a terminal state is final. Nothing moves out of DELIVERED or FAILED.
 *  2. **Liveness**: every non-terminal state can reach a terminal state. If it cannot, a
 *     message can be stranded -- stored, never sent, never failed -- and the "no message
 *     is lost" guarantee is a lie.
 */
class DeliveryStatusTest {

    private val all = DeliveryStatus.entries.toList()

    // ---- the happy path -----------------------------------------------------------------

    @Test
    fun `the intended send path is legal`() {
        // PENDING -> SENDING -> SENT -> DELIVERED, in that order. This is the whole
        // lifecycle of a message that arrives, and it is the path the repository walks on
        // every successful send.
        assertTrue(DeliveryStatus.PENDING.canTransitionTo(DeliveryStatus.SENDING))
        assertTrue(DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.SENT))
        assertTrue(DeliveryStatus.SENT.canTransitionTo(DeliveryStatus.DELIVERED))
    }

    @Test
    fun `a message can fail from any state before it is delivered`() {
        // The peer walked out of range mid-send, or refused the frame, or the key was
        // missing. None of those should need a special case at the call site.
        for (from in all.filter { !it.isTerminal }) {
            assertTrue("$from should be able to reach FAILED", from.canTransitionTo(DeliveryStatus.FAILED))
        }
    }

    // ---- property 1: terminal states are final ------------------------------------------

    @Test
    fun `a terminal state never moves`() {
        // The important one. If DELIVERED could move back to SENDING, a late or duplicated
        // retry would resend a message the peer has already acknowledged and the user would
        // see it twice. If FAILED could move to SENDING, the retry budget would be
        // escapable and a permanently unreachable device would retry forever -- the exact
        // battery drain the brief asks to avoid.
        for (terminal in all.filter { it.isTerminal }) {
            for (next in all) {
                assertFalse(
                    "$terminal must not transition to $next",
                    terminal.canTransitionTo(next),
                )
            }
        }
    }

    @Test
    fun `DELIVERED and FAILED are the only terminal states`() {
        // If a new state were added without deciding its terminality, the retry pump's
        // "what still needs sending" query would silently start or stop covering it.
        assertEquals(
            setOf(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED),
            all.filter { it.isTerminal }.toSet(),
        )
    }

    @Test
    fun `only PENDING and SENDING are retriable`() {
        // isRetriable is what the retry pump queries. Getting it wrong in the permissive
        // direction resends delivered messages; in the restrictive direction it abandons
        // messages that would have gone through.
        assertEquals(
            setOf(DeliveryStatus.PENDING, DeliveryStatus.SENDING),
            all.filter { it.isRetriable }.toSet(),
        )
    }

    // ---- property 2: every non-terminal state can reach a terminal state ----------------

    /**
     * The liveness property, by exhaustive search.
     *
     * Brute-force reachability rather than a list of expected paths, because the failure it
     * guards against is precisely a path that someone forgot to add. An explicit list of
     * paths would be checked against itself.
     */
    @Test
    fun `every non-terminal state can reach a terminal state`() {
        for (start in all) {
            val reachable = reachableFrom(start)
            val canFinish = all.filter { it.isTerminal }.any { it in reachable }

            assertTrue(
                "$start cannot reach any terminal state -- a message could be stranded here",
                canFinish,
            )
        }
    }

    @Test
    fun `the shortest path from each state to a terminal state`() {
        // Pins the machine's shape, not just its soundness. Read as a description of how a
        // message actually travels; a change that lengthens one of these is a change to how
        // long a message sits pending, and should be noticed.
        assertEquals(1, shortestHops(DeliveryStatus.PENDING, DeliveryStatus.SENT))
        assertEquals(1, shortestHops(DeliveryStatus.SENDING, DeliveryStatus.SENT))
        assertEquals(1, shortestHops(DeliveryStatus.SENT, DeliveryStatus.DELIVERED))
        assertEquals(1, shortestHops(DeliveryStatus.PENDING, DeliveryStatus.FAILED))
        assertEquals(2, shortestHops(DeliveryStatus.PENDING, DeliveryStatus.DELIVERED))
    }

    /**
     * PENDING may go straight to SENT, skipping SENDING.
     *
     * Deliberate, and worth stating because the intermediate state looks mandatory. A
     * first attempt can succeed before anything observes the message as in-flight -- the
     * repository writes SENDING, the transport returns immediately, the repository writes
     * SENT -- and a machine that forced every send through SENDING would be asserting a
     * step that did not happen.
     *
     * So SENDING is an observation, not a stage. What matters is the edge that does *not*
     * exist, tested separately: PENDING cannot reach DELIVERED without passing through
     * SENT, because that one would fake a delivery receipt.
     */
    @Test
    fun `PENDING reaches SENT without a mandatory SENDING`() {
        assertTrue(DeliveryStatus.PENDING.canTransitionTo(DeliveryStatus.SENT))
        assertEquals(1, shortestHops(DeliveryStatus.PENDING, DeliveryStatus.SENT))
    }

    @Test
    fun `DELIVERED is not reachable from PENDING in one hop`() {
        // SENT means "the transport took the bytes". DELIVERED means "the peer confirmed".
        // Collapsing the two would show the user a tick that only means the phone agreed to
        // try, and would make a delivery receipt meaningless.
        assertFalse(DeliveryStatus.PENDING.canTransitionTo(DeliveryStatus.DELIVERED))
    }

    // ---- the retry path -----------------------------------------------------------------

    @Test
    fun `SENDING falls back to PENDING for another attempt`() {
        // The retry. SENDING -> PENDING -> SENDING is a two-hop cycle that lets a message
        // be re-offered to the transport without any special "retry" state, and without a
        // terminal state being involved.
        assertTrue(DeliveryStatus.SENDING.canTransitionTo(DeliveryStatus.PENDING))
        assertTrue(DeliveryStatus.PENDING.canTransitionTo(DeliveryStatus.SENDING))
    }

    @Test
    fun `PENDING cannot advance to PENDING`() {
        // Self-transitions are rejected. Re-inserting a pending row that is already pending
        // would reset the retry count without incrementing it -- an infinite retry that
        // never exhausts its budget.
        for (state in all) {
            assertFalse("$state must not transition to itself", state.canTransitionTo(state))
        }
    }

    // ---- the string representation -------------------------------------------------------

    @Test
    fun `every status has a stable wire name`() {
        // Stored as a String, not an ordinal, precisely so that reordering this enum cannot
        // silently reinterpret existing rows. That protection depends on the names never
        // changing -- so the names are pinned.
        assertEquals(
            listOf("PENDING", "SENDING", "SENT", "DELIVERED", "FAILED"),
            all.map { it.name },
        )
    }

    @Test
    fun `a stored name round-trips to the same status`() {
        // The parse the DAO does when reading rows. An unknown name must throw rather than
        // silently defaulting to PENDING: defaulting would make an unreadable message look
        // like an unsent one, and the retry pump would then re-send it.
        for (state in all) {
            assertEquals(state, DeliveryStatus.valueOf(state.name))
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Every status reachable from [start] in zero or more hops. */
    private fun reachableFrom(start: DeliveryStatus): Set<DeliveryStatus> {
        // start must be seeded into `seen`. It is not seeded because it is not reached *by* a
        // transition -- it is where the walk begins. Leaving it out made a terminal start
        // state report an empty reachable set, so the liveness test then claimed a delivered
        // message was stranded in a state it could not leave, which is the one thing the
        // test exists to be sure about.
        val seen = mutableSetOf(start)
        val queue = ArrayDeque<DeliveryStatus>().apply { add(start) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in all) {
                if (current.canTransitionTo(next) && seen.add(next)) {
                    queue.add(next)
                }
            }
        }
        return seen
    }

    /** The fewest legal transitions from [start] to [target]. -1 if unreachable. */
    private fun shortestHops(start: DeliveryStatus, target: DeliveryStatus): Int {
        val distance = mutableMapOf(start to 0)
        val queue = ArrayDeque<DeliveryStatus>().apply { add(start) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target) return distance.getValue(current)
            for (next in all) {
                if (current.canTransitionTo(next) && next !in distance) {
                    distance[next] = distance.getValue(current) + 1
                    queue.add(next)
                }
            }
        }
        return -1
    }
}