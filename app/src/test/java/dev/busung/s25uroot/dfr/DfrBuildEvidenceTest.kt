package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helper's two build numbers, where a person arguing with the flow can read them.
 *
 * This is the one judgement in the flow that is about two files rather than about the phone: the helper on
 * the device against the one this APK carries. It decides a step of its own ([DfrStep.StaleStageTwo]) and it
 * is the failure nothing else makes visible - a helper from another build installs cleanly, reports the
 * system uid it really has, and fails inside the exploit in the shape of the exploit having failed. Until
 * now the two codes were written down only in the app log, so the screen that had chosen a step from them
 * could not be read against them.
 *
 * Where they live now is a decision rather than device behaviour, which is what makes it checkable here: the
 * dialog's own panel, with every answer the comparison has and not only the disagreement, and with the app
 * log still saying the same thing for a report that arrives without a screen.
 */
class DfrBuildEvidenceTest {

    @Test
    fun `the panel shows the reading even before any action has run`() {
        // The panel used to be the last command's output and nothing else, so the evidence was invisible
        // until somebody pressed something - which is backwards for a screen whose whole job is to say
        // which step the phone is on: the reading is what the step was chosen from.
        val ui = uiSource()
        assertTrue(
            "the panel no longer holds the build reading, so the numbers behind the flow's one judgement " +
                "about two files are back to being only in the app log",
            ui.contains("val helperBuild = reading?.build?.let"),
        )
        assertTrue(
            "the panel is composed from the action's output alone again, so nothing is shown until a " +
                "press has printed something",
            ui.contains("listOfNotNull(helperBuild, log)"),
        )
        assertFalse(
            "the panel is drawn only when an action has printed something, which hides the reading behind " +
                "a press nobody has made",
            ui.contains("log?.let"),
        )
    }

    @Test
    fun `every answer the comparison has is a line, and none of them is a fallback`() {
        // A `when` over the verdict with no `else`, so a fifth answer cannot arrive as some other answer's
        // sentence. Four lines and not one: "the helper is this build", "nothing is installed to compare"
        // and "the APK in this app could not be read" all leave the step list looking the same, and each of
        // them is what somebody needs when the step it chose is the thing they are arguing with.
        val evidence = between(uiSource(), "val helperBuild = reading?.build?.let", "val panel = listOfNotNull")
        StageTwoBuild.entries.forEach { verdict ->
            assertTrue(
                "the panel has no line for $verdict, so that answer is either hidden or shown as another",
                evidence.contains(verdict.name),
            )
        }
        assertFalse(
            "the panel falls back to one sentence for answers it does not name, which reports a phone " +
                "with no helper installed as a phone whose build could not be read",
            evidence.contains("else ->"),
        )
    }

    @Test
    fun `the numbers are printed only where the comparison guarantees them`() {
        // The comparison answers absent on a missing installed code and unreadable on a missing bundled one
        // before it compares anything, so two codes are only ever printed where two codes were read. The
        // two sentences that print one are the two that have one.
        val evidence = between(uiSource(), "val helperBuild = reading?.build?.let", "val panel = listOfNotNull")
        val absent = evidence.substringAfter("StageTwoBuild.Absent").substringBefore("StageTwoBuild.Unreadable")
        val unreadable = evidence.substringAfter("StageTwoBuild.Unreadable")
        assertTrue(
            "the line for a phone with nothing installed no longer says so, which is the answer a person " +
                "needs at the install step",
            absent.contains("dfr_log_build_absent"),
        )
        assertFalse(
            "the line for a phone with nothing installed prints a code from this app's own APK, which is " +
                "not the number that step is about",
            absent.contains("build.bundled"),
        )
        assertTrue(
            "the line for an unreadable APK no longer names the code the phone does have",
            unreadable.contains("build.installed"),
        )
        assertFalse(
            "the line for an unreadable APK prints a code from the APK that could not be read",
            unreadable.contains("build.bundled"),
        )
    }

    @Test
    fun `the app log still carries the same reading`() {
        // Not instead of it: the dialog is what somebody is looking at while the flow is open, and the log
        // is what a report of it has to be argued against afterwards - including the step the readings came
        // to, which is not a thing a panel line can say for the screen that drew it.
        val ui = uiSource()
        assertTrue(
            "the reading is no longer written to the app log, so a report of a wrong step has nothing left " +
                "to be argued against",
            ui.contains("helper=\${build.verdict}(\${build.installed}/\${build.bundled})"),
        )
        assertTrue(
            "the app log no longer records which step the readings came to",
            ui.contains("-> \${DfrFlow.next(state)}"),
        )
    }

    private fun uiSource() = source("src/main/java/dev/busung/s25uroot/DfrUi.kt")

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$relativeToApp was not found from ${File(".").absolutePath}")

    /** The text between two anchors, which is how a region inside a composable is held here. */
    private fun between(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        val end = source.indexOf(to)
        assertTrue("$from was not found in the source", start >= 0)
        assertTrue("$to was not found after $from", end > start)
        return source.substring(start, end)
    }
}
