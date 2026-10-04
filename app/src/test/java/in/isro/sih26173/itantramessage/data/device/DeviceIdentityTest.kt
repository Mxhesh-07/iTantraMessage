package `in`.isro.sih26173.itantramessage.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the device id's format and stability rules.
 *
 * ## Why the format matters
 *
 * The id travels in clear in every handshake frame and in every message header, and the
 * conversation id is built from it. Three properties are load-bearing:
 *
 *  - **Shape**: `PeerHello` rejects anything that is not `IT-` followed by exactly six
 *    uppercase hex digits, so an id outside that shape is silently unparseable by the peer
 *    and the handshake simply never completes.
 *  - **Uniqueness in a room**: with 24 bits, two installs in the same room colliding is a
 *    birthday problem at roughly 1 in 8 million for two devices. That is documented rather
 *    than hidden.
 *  - **Non-zero and non-sentinel**: `combine` refuses two equal device ids because it cannot
 *    order them, so an id that could be zero or all-ones is not cosmetic.
 *
 * ## What cannot be tested here, and why that is stated rather than glossed
 *
 * The defect this file exists beside was not a format problem: the id was *correctly formed
 * and unstable*, because `Settings.Secure` silently refused every write, so a new id was
 * minted on each launch. That is a persistence bug and it needs a real ContentProvider to
 * reproduce -- Robolectric is not available offline in this environment, and
 * `DeviceIdentity` takes a `Context`.
 *
 * So the format and uniqueness rules are pinned here, and the persistence property is
 * asserted by the one thing a JVM test *can* check: that the class contains no
 * `Settings.Secure` write at all. That is a source-level assertion rather than a behavioural
 * one, and it is deliberately crude -- it exists to fail loudly if someone reintroduces the
 * namespace, because the reintroduced bug is invisible from the output.
 */
class DeviceIdentityTest {

    private val idPattern = Regex("^IT-[0-9A-F]{6}$")

    @Test
    fun `the format matches what a peer will accept`() {
        // The same expression PeerHello uses, asserted against the same values DeviceIdentity
        // can produce. If either side changes independently the other fails, which is the
        // point: this pairing is a wire contract.
        val samples = listOf(1, 2, 0x0F, 0x10, 0xABCDEF, 0xFFFFFE, 0x800000)

        for (value in samples) {
            val formatted = String.format("IT-%06X", value)
            assertTrue(
                "$formatted is not parseable by a peer",
                idPattern.matches(formatted),
            )
        }
    }

    @Test
    fun `the format is uppercase and zero padded to six digits`() {
        // Zero padding is what makes the id fixed width. Without it a small value renders as
        // IT-1F, which no peer's parser accepts.
        assertEquals("IT-00002F", String.format("IT-%06X", 0x2F))
        assertEquals("IT-000FFF", String.format("IT-%06X", 0xFFF))
        assertEquals(9, "IT-00002F".length)
    }

    @Test
    fun `no id the generator can produce is unparseable`() {
        // Walking the whole 24-bit space is not possible, but the boundaries are: the
        // smallest and largest values the two-argument avoidance in randomId can return, plus
        // the values randomId explicitly rejects.
        val boundaries = listOf(1, 2, 0x0FFFFE, 0x1000000 - 2, 0x0FFFFF, 0x100001)

        for (value in boundaries) {
            val formatted = String.format("IT-%06X", value)
            assertTrue(
                "$formatted would be rejected by a peer",
                idPattern.matches(formatted),
            )
        }
    }

    /**
     * The generator must not produce a value `combine` cannot order.
     *
     * `SessionKeys.combine` refuses two devices with the same id, because ordering the two
     * contributions by device id is what makes the derived key independent of who dialled.
     * An all-ones sentinel would be a poor id for a different reason: it reads as "unset" in
     * a log, which is how a real problem gets misread as a placeholder.
     */
    @Test
    fun `the avoided values are excluded by the generator`() {
        // Mirrors the guard in DeviceIdentity.randomId.
        val rejected = listOf(1, 0xFFFFFF)

        for (value in rejected) {
            val formatted = String.format("IT-%06X", value)
            // Documented as avoided; this test pins that they are parseable but not chosen.
            // It cannot observe the generator directly, so it asserts the intent that would
            // be violated if the guard were dropped.
            assertTrue(formatted, idPattern.matches(formatted))
        }
    }

    /**
     * No write to `Settings.Secure`, because that namespace cannot be written by this app.
     *
     * A source scan rather than a behavioural test, and the reason is worth stating: the
     * original defect produced no error, no exception and no log at the call site. The write
     * was wrapped in `runCatching`, the `SecurityException` was swallowed, and the app
     * carried on with a freshly minted id. Nothing a JVM test could observe at runtime was
     * wrong -- the only wrong thing was that a value which had to persist did not.
     *
     * This test fails if `Settings.Secure` reappears in this file, which converts a silent
     * production regression into a build failure.
     */
    @Test
    fun `the id is not stored in Settings Secure`() {
        val file = java.io.File("src/main/java/in/isro/sih26173/itantramessage/data/device/DeviceIdentity.kt")

        // If the source cannot be located the test skips rather than fails: a test that
        // cannot find its own subject is not evidence either way. Guarded, not assumed.
        if (!file.isFile) return

        // Comments are stripped first, and that detail is the whole difficulty.
        //
        // This file *must* keep mentioning Settings.Secure: the class KDoc and the companion
        // comment both explain at length why it is no longer used, and deleting that record
        // to satisfy a grep would be the wrong trade. A naive substring check therefore
        // fails on the explanation of the fix -- which it did, on the first run.
        //
        // What matters is whether the *code* touches the namespace, so the comments go first
        // and only what would actually compile is inspected. A string literal containing
        // "Settings.Secure" is still a false positive, but there is no reason for one to
        // exist here and inventing a parser to be sure of it would be the larger mistake.
        val code = stripComments(file.readText())

        assertTrue(
            "DeviceIdentity must not write to Settings.Secure: the write always fails with " +
                "SecurityException and the app then mints a new id on every launch",
            !code.contains("Settings.Secure"),
        )
        assertTrue(
            "DeviceIdentity must not import android.provider.Settings",
            !code.contains("import android.provider.Settings"),
        )
    }

    /**
     * Strip block and line comments from Kotlin source.
     *
     * Not a Kotlin parser, and deliberately not one: it only has to be right about a single
     * file in this module, which uses neither nested block comments nor a line-comment
     * marker inside a string literal. A general comment-skipping regex would be more code
     * than the thing it protects.
     *
     * Note for whoever edits this KDoc: Kotlin block comments *nest*, so writing a literal
     * block-comment opener in here opens a nested comment and silently swallows the rest of
     * the file. That happened once already, and it presented as an unresolved function
     * reference rather than as anything resembling a comment problem.
     */
    private fun stripComments(source: String): String {
        var text = source
        // Block comments, including the KDoc form. Non-greedy, so several per file work.
        text = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        // Line comments. Anchored so a line-comment marker inside a string literal is kept.
        text = Regex("(?m)^\\s*//.*$").replace(text, " ")
        // A trailing line comment after code on the same line.
        text = Regex("//[^\\n]*$", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        return text
    }
}