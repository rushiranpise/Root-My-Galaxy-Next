package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the system-uid flow's dialog puts what it measured.
 *
 * The panel under the step list is that screen's evidence: the readings the step was drawn from, and then
 * what the press printed. Two of them used to be body lines of their own, in two other shapes - a sentence
 * of three answers, and two build numbers - and a reader arguing with the step had to compare them against
 * output printed somewhere else in the dialog. So the rule is one place, and this holds it: every
 * measurement the dialog acts on is a line of the panel, above the output that acted on it.
 *
 * Read from the source rather than from a rendered screen, which is the only way these lines can be
 * compared without a device - and the failure is a reading drawn beside the panel instead of in it, which
 * compiles and looks deliberate either way.
 */
class DfrPanelTest {

    @Test
    fun `every measurement the dialog acts on is a line of its panel`() {
        val panel = panelList()
        listOf("measured", "helperBuild", "stage", "log").forEach { line ->
            assertTrue(
                "$line is not a line of the panel, so that reading is drawn somewhere other than where the " +
                    "actions print:\n$panel",
                panel.contains(line),
            )
        }
    }

    @Test
    fun `the readings come before the output`() {
        val panel = panelList()
        val output = panel.indexOf("log")
        listOf("measured", "helperBuild", "stage").forEach { reading ->
            assertTrue(
                "the output is printed before $reading, so the line a reader argues with is below the " +
                    "reading it was drawn from:\n$panel",
                panel.indexOf(reading) in 0 until output,
            )
        }
    }

    @Test
    fun `the daemon's line is the settings readout's own sentence`() {
        // The same enum and the same two strings the Settings row draws, so the two screens cannot come to
        // different accounts of one file - which is what a second spelling of "another build's daemon"
        // would be, one screen apart.
        val ui = uiSource()
        assertTrue(
            "the panel's daemon line no longer takes its words from the reading itself",
            ui.contains("stringResource(stage.label)") && ui.contains("stringResource(stage.detail)"),
        )
    }

    @Test
    fun `the daemon's state is one of the things the dialog's reading takes`() {
        val ui = uiSource()
        assertTrue(
            "nothing reads the daemon's state for this dialog, so the panel's line can never appear",
            ui.contains("val stage = DfrInstall.readDaemonStage(context)"),
        )
        assertTrue(
            "the state is read and then not handed to the screen's reading, so the panel has nothing to " +
                "show for it",
            ui.contains("DfrReading(DfrFlow.next(state), probe, injected, build, stage)"),
        )
    }

    @Test
    fun `a shell nobody opened is not reported as a reading`() {
        // The refusal path answers before any shell is opened. Its daemon reading is therefore absent rather
        // than "no shell answered": a panel line about a question nobody asked is the invented measurement
        // this whole panel is arranged to avoid.
        val refusal = uiSource()
            .substringAfter("if (helper != DfrHelperAvailability.Ready) {")
            .substringBefore("val probe = DfrInstall.probe()")
        assertFalse(
            "the refusal path was not found, so this test is holding something else to the rule:\n$refusal",
            refusal.isEmpty(),
        )
        assertTrue(
            "the refusal path claims a measurement it never took:\n$refusal",
            refusal.contains("stage = null"),
        )
    }

    /** The one line that builds the panel, whatever its readings are called. */
    private fun panelList(): String = uiSource()
        .lineSequence()
        .firstOrNull { line -> line.trimStart().startsWith("val panel = listOfNotNull(") }
        ?.trim()
        ?: throw AssertionError("no panel is built in DfrUi.kt, so this test is holding nothing")

    private fun uiSource(): String = listOf(
        File("src/main/java/dev/busung/s25uroot/DfrUi.kt"),
        File("app/src/main/java/dev/busung/s25uroot/DfrUi.kt"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("DfrUi.kt was not found from ${File(".").absolutePath}")
}
