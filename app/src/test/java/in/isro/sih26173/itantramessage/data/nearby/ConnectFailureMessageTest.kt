package `in`.isro.sih26173.itantramessage.data.nearby

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the text shown to a user when an outbound RFCOMM connect fails.
 *
 * ## Why wording is worth testing
 *
 * `NearbyManager` puts this string in front of the user as `Could not connect to <peer>:
 * <reason>`, so it is behaviour, not logging. The previous version of it was a checklist of
 * three conditions -- both phones on, Bluetooth on for both, devices paired -- and passed
 * every one of those checks being true. That is the ordinary state of a phone that fails to
 * connect, so the message described the happy path and called it a diagnostic.
 *
 * The failure this project actually produces is that the peer is not listening: `RfcommServer`
 * must be accepting on the SPP channel, and a handset with the app closed satisfies every
 * condition the old message listed while refusing the connection outright.
 *
 * A test cannot prove the wording helps a confused user. What it can do is stop the specific
 * regression that happened, where the most likely cause was dropped and nobody noticed,
 * because a message can be true, grammatical, and useless at the same time.
 *
 * The function is pure and takes a `Throwable?`, so none of this needs a Bluetooth stack, a
 * device, or a paired peer. That is the point of extracting it from the instance method.
 */
class ConnectFailureMessageTest {

    private fun describe(t: Throwable?): String = RfcommTransport.describeConnectFailure(t)

    @Test
    fun `names the not-listening case, which is the most likely one`() {
        val text = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))

        assertTrue(
            "the message must name the peer not listening, which is this app's usual failure; was: $text",
            text.contains("not listening"),
        )
    }

    @Test
    fun `tells the user what to do about it, not only what went wrong`() {
        val text = describe(IOException("read failed"))

        assertTrue(
            "the hint must say what the user should check; was: $text",
            text.contains("it must have this app open"),
        )
    }

    @Test
    fun `keeps the pairing checks, which are still real possibilities`() {
        val text = describe(IOException("read failed"))

        assertTrue("Bluetooth being on must still be mentioned; was: $text", text.contains("Bluetooth is on"))
        assertTrue("pairing must still be mentioned; was: $text", text.contains("paired"))
    }

    @Test
    fun `puts the likely cause before the raw text so it is read first`() {
        // The raw platform text is long and describes a symptom. Leading with it buries the
        // one sentence that tells the user what to do.
        val text = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))

        assertTrue("raw text must be kept, it is what helps a developer; was: $text", text.contains("read failed"))
        assertTrue("the hint should precede the raw text; was: $text", text.indexOf("not listening") < text.indexOf("read failed"))
    }

    @Test
    fun `does not claim to know the cause, because it cannot`() {
        // Android returns the same message for a refused SDP lookup, an out-of-range peer and
        // an absent peer. A confident diagnosis would be a guess, and a wrong one is worse
        // than an honest ranked list.
        val text = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))

        val overclaims = listOf("the cause is", "because the", "this means", "definitely", "certainly")
        for (claim in overclaims) {
            assertFalse("message must not assert a cause ('$claim'); was: $text", text.contains(claim))
        }
    }

    @Test
    fun `survives an exception with no message`() {
        val text = describe(IOException())

        assertTrue("the class name is more useful than silence; was: $text", text.contains("IOException"))
        assertTrue("the hint must survive too; was: $text", text.contains("not listening"))
    }

    @Test
    fun `survives a blank message, which is not the same as no message`() {
        val text = describe(IOException("   "))

        assertFalse("a blank message must not be quoted as if it were information; was: $text", text.startsWith("   ."))
        assertTrue("the class name should be used instead; was: $text", text.contains("IOException"))
    }

    @Test
    fun `survives no throwable at all`() {
        val text = describe(null)

        assertTrue("there must still be something to read; was: $text", text.contains("unknown error"))
        assertTrue("the hint must survive a null; was: $text", text.contains("not listening"))
    }

    @Test
    fun `a permission failure reads as a permission failure, not as a peer problem`() {
        // BLUETOOTH_CONNECT is missing until the user grants it, which surfaces as a
        // SecurityException. Telling someone whose permission was never granted that the other
        // phone is not listening sends them to check the wrong handset, so the two cases get
        // different text.
        val text = describe(SecurityException("need BLUETOOTH_CONNECT permission"))

        assertTrue("a local permission fault must be named; was: $text", text.contains("missing a Bluetooth permission"))
        assertTrue("the fix must be stated; was: $text", text.contains("Permissions"))
        assertFalse(
            "must not blame the other phone for a fault on this one; was: $text",
            text.contains("not listening"),
        )
        assertTrue("the raw platform text is still the only clue here; was: $text", text.contains("BLUETOOTH_CONNECT"))
    }

    @Test
    fun `every variant carries its raw text`() {
        val cases = mapOf(
            null to "unknown error",
            IOException() to "IOException",
            IOException("   ") to "IOException",
            IOException("read failed") to "read failed",
            SecurityException("denied") to "denied",
        )

        for ((t, expected) in cases) {
            val text = describe(t)
            assertTrue("raw text '$expected' was lost from: $text", text.contains(expected))
        }
    }

    @Test
    fun `always ends in balanced parentheses`() {
        val cases = listOf<Throwable?>(null, IOException(), IOException("read failed"), SecurityException("denied"))

        for (t in cases) {
            val text = describe(t)
            assertTrue("should end with ')', was: '$text'", text.endsWith(")"))
            assertEquals("unbalanced parentheses in: $text", text.count { it == '(' }, text.count { it == ')' })
        }
    }

    @Test
    fun `is short enough to read on a phone row`() {
        // This is rendered inside a peer row. A paragraph is a worse diagnostic than a
        // sentence, because nobody reads the second one.
        val text = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))
        val sentences = text.split(". ").size

        assertTrue("hint is too long at $sentences sentences; was: $text", sentences <= 5)
        assertTrue("message is too long at ${text.length} chars", text.length < 400)
    }

    @Test
    fun `two different failures with the same raw text still produce identical text`() {
        // Deliberate: the platform does not distinguish them, so neither does this. If a future
        // change makes these differ, that change has found real information and the tests
        // should be revisited rather than quietly accepted.
        val a = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))
        val b = describe(IOException("read failed, socket might closed or timeout, read ret: -1"))

        assertEquals(a, b)
    }
}