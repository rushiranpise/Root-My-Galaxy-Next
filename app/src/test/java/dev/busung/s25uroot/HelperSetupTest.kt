package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line between a phone the helper can be set up on and one it cannot.
 *
 * The rule itself is one comparison, so what is worth pinning is the two mistakes around it: what it is
 * read from - the verified payload rather than a remembered flow or a descriptor - and how much of the
 * screen it is allowed to switch off. The clean-up in particular must never be behind it, because the
 * phone this answer refuses is the phone whose verified payload has been forgotten, and the clean-up is
 * the only way to take a helper that is already installed back off.
 */
class HelperSetupTest {

    @Test
    fun `only a verified payload answers ready`() {
        assertEquals(HelperSetup.Ready, HelperSetup.of(true))
        assertEquals(HelperSetup.NoPayload, HelperSetup.of(false))
        assertTrue("a phone with a verified payload may run the setup", HelperSetup.Ready.ready)
        assertFalse(
            "the helper would be told it can load a daemon that is not on the phone",
            HelperSetup.NoPayload.ready,
        )
        // Two answers rather than a boolean, so the screen can say which one it is in - and so a third
        // state cannot be added later by accident and silently read as ready.
        assertNotEquals(HelperSetup.Ready, HelperSetup.NoPayload)
    }

    @Test
    fun `the answer is the verified payload, not a remembered flow`() {
        val ui = source(UI)
        // The verifying read, not `describe`: a cache whose artifacts were deleted or replaced has to
        // answer here exactly as the staging would, and only the verifying read knows the difference.
        assertTrue(
            "the setup readiness does not come from the verified payload",
            ui.contains("HelperSetup.of(KnownGoodPayloadStore.hasValid(context))"),
        )
        // A flow remembered from a run would be a claim about the past, and it is wrong in both
        // directions - a payload root whose copy was since forgotten, and a chain root on a phone whose
        // payload is still cached and usable.
        assertFalse(
            "the readiness is decided from which flow ran, which goes stale",
            ui.contains("HelperSetup.of(") && ui.contains("RunKind"),
        )
    }

    @Test
    fun `the reading is made off the main thread`() {
        // It hashes several megabytes, and the dialog is opened from a tap.
        assertTrue(
            "the payload verification runs on the main thread",
            source(UI).contains(
                "setup = withContext(Dispatchers.IO) {\n" +
                    "                HelperSetup.of(KnownGoodPayloadStore.hasValid(context))\n" +
                    "            }",
            ),
        )
    }

    @Test
    fun `only the two presses that write are held to the answer`() {
        val ui = source(UI)
        val uses = Regex("setupEnabled").findAll(ui).count()
        // The declaration and the two gates - the certificate inject, which writes `packages.xml`, and
        // the helper install, which stages the daemon and installs the system app. A fourth use is a
        // decision rather than a slip: the readings, the restart and the clean-up are the ones that have
        // to keep working, and a press added here without that being thought about is how the way back
        // out of an installed helper gets closed.
        assertEquals("setupEnabled is used somewhere other than the two presses that write", 3, uses)
        assertTrue(
            "the certificate inject is not held to it, so it writes a key nothing can spend",
            ui.contains("enabled && reading?.injected != true && setupEnabled"),
        )
        assertTrue(
            "the helper install is not held to it, so a system app lands with no daemon to load",
            ui.contains("enabled && setupEnabled"),
        )
    }

    @Test
    fun `the clean-up and the removals keep working with no payload`() {
        val lines = source(UI).lines()
        // The screen's own rule, and the reason the whole card is not switched off: a helper that is
        // already installed has to be removable on a phone whose verified payload is gone.
        val cleanUp = windowAround(lines, "R.string.dfr_clean_up")
        assertFalse("the clean-up is switched off with the setup: $cleanUp", cleanUp.contains("setupEnabled"))
        val removals = windowAround(lines, "R.string.dfr_action_remove_stage2")
        assertFalse("the removals are switched off with the setup: $removals", removals.contains("setupEnabled"))
    }

    @Test
    fun `the refusal is a sentence this app has`() {
        assertTrue(
            "the screen explains nothing, so a disabled press reads as a bug",
            source(UI).contains("R.string.dfr_setup_needs_payload"),
        )
        assertTrue(
            "the string the screen names does not exist in this app's resources",
            source(STRINGS).contains("name=\"dfr_setup_needs_payload\""),
        )
    }

    /** Both sources were really read: every assertion above passes on an empty string. */
    @Test
    fun `the files this reads were really read`() {
        assertTrue(source(UI).contains("internal fun DfrInstallDialog"))
        assertTrue(source(STRINGS).contains("dfr_apk_bundled"))
    }

    /** The lines around the first one containing [needle], for the two checks that are about a neighbour. */
    private fun windowAround(lines: List<String>, needle: String): String {
        val index = lines.indexOfFirst { it.contains(needle) }
        assertTrue("no line in the dialog mentions $needle", index >= 0)
        return lines.subList(index, (index + 5).coerceAtMost(lines.size)).joinToString("\n")
    }

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))

    private companion object {
        private const val UI = "app/src/main/java/dev/busung/s25uroot/DfrUi.kt"
        private const val STRINGS = "app/src/main/res/values/strings.xml"
    }
}
