package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the stage-two screen: what is a card, what is a log, and the two things that made it look
 * broken on the phone it was fixed from.
 *
 * The first is the log pane. It drew a scrollbar and could not be moved, and both halves of that have a
 * source-level cause worth pinning: a selectable `TextView` takes the drag for a text selection, and a parent
 * `ScrollView` intercepts a gesture that a child pane was about to use. Neither is visible in a screenshot,
 * and both come back the moment somebody re-adds the convenient-looking line.
 *
 * The second is where the readings live. The list of files a run needs is the screen's answer to "can I press
 * this", so it belongs under the buttons it decides; the log is the run's own output, and a summary printed
 * into it is a summary nobody reads next to the exploit's own reporting.
 */
class StageTwoScreenTest {

    @Test
    fun `the log pane takes the drag it is given, and follows only from its end`() {
        val activity = stageTwoActivity()
        assertTrue(
            "the log is selectable again, which takes every drag for a text selection and leaves the pane " +
                "unscrollable - the one screenshot-invisible cause of the bug this fixes",
            !activity.contains("setTextIsSelectable(true)"),
        )
        assertTrue(
            "the log pane no longer asks the page not to intercept its gesture, so the page takes the drag " +
                "and the pane is scrollbar-only again",
            activity.contains("requestDisallowInterceptTouchEvent(true)"),
        )
        assertTrue(
            "the pane is a plain ScrollView again, which is the class that loses this gesture to its parent",
            activity.contains("class LogScrollView") && activity.contains(": ScrollView(context)"),
        )
        assertTrue(
            "appending a line no longer checks where the pane is, so a run drags a person off the line they " +
                "were reading on every report the exploit makes",
            activity.contains("val follow = !logScroll.canScrollVertically(1)"),
        )
        assertTrue(
            "the follow happens before the line is there, so the check is about the previous content",
            activity.indexOf("logView.append(line)") < activity.indexOf("if (follow) logScroll.post"),
        )
    }

    @Test
    fun `the file list is the card's and the log carries the run`() {
        val activity = stageTwoActivity()
        assertTrue(
            "the file list is not drawn into the card, so the screen's answer to whether a run can work is " +
                "somewhere nobody looks",
            activity.contains("needsRows.addView("),
        )
        assertTrue(
            "the list is not built from the reading, so the rows and the count above them could disagree",
            activity.contains("reading.items.forEach { checked ->"),
        )
        assertTrue(
            "the tick and the cross are not both drawn, so a missing file no longer reads differently from " +
                "one that is there",
            activity.contains("\\u2713") && activity.contains("\\u2717"),
        )
        assertTrue(
            "the first read still prints the whole list into the log, which is the summary this screen moved " +
                "onto the card",
            activity.contains("if (announce) append(openingLine())"),
        )
        assertTrue(
            "the list no longer reaches the log when a run is refused for it, so a refusal that outlives the " +
                "screen names a count and nothing else",
            activity.contains("append(it.report())"),
        )
        // And the card the buttons are in is the one the list is in: a list in one card and the Run button it
        // decides in another is a screen where the connection has to be remembered.
        assertTrue(
            "the file list and the Run button are not in the same card",
            activity.contains("sectionLabel(\"This boot\")") && activity.contains("needsRows,"),
        )
    }

    private fun stageTwoActivity(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt")

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
