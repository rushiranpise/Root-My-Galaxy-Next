package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout rule every dialog in this app now shares, held still.
 *
 * The rule is arithmetic, not taste - it decides how many answers go in a row and nothing else - so it is
 * the part of the design that can be tested without a screen, and the part that a later dialog would
 * quietly break by hand-laying its own rows. Two properties are what make it a rule rather than a packing
 * function: no row holds more than three answers, and no row is left with a single one stretched beside a
 * full row, because one button at the dialog's width under three at a third of it reads as a different
 * size of answer.
 *
 * The order of the answers is deliberately not this file's business: which one is filled is
 * [AppActionRole], which is the caller's decision about what it recommends.
 */
class ActionRowsTest {

    @Test
    fun `a set that fits in one row keeps one row`() {
        assertEquals(listOf(1), actionRowSizes(1))
        assertEquals(listOf(2), actionRowSizes(2))
        assertEquals(listOf(3), actionRowSizes(3))
    }

    @Test
    fun `four answers are two and two, not three and one`() {
        // The case the rule exists for: packing would put three in the first row and leave the fourth
        // stretched across the whole dialog underneath them.
        assertEquals(listOf(2, 2), actionRowSizes(4))
    }

    @Test
    fun `the rows of a set are as even as they can be`() {
        // Every row is the first row's size or one less, which is what "balanced and not packed" means on
        // a phone: five answers are three and two, seven are three, two and two.
        assertEquals(listOf(3, 2), actionRowSizes(5))
        assertEquals(listOf(3, 3), actionRowSizes(6))
        assertEquals(listOf(3, 2, 2), actionRowSizes(7))
        assertEquals(listOf(3, 3, 2, 2), actionRowSizes(10))
    }

    @Test
    fun `no row holds more than three answers`() {
        for (count in 1..12) {
            val sizes = actionRowSizes(count)
            assertTrue("$count answers in rows of $sizes", sizes.all { it in 1..3 })
        }
    }

    @Test
    fun `no row is left with one answer beside a full one`() {
        for (count in 1..12) {
            val sizes = actionRowSizes(count)
            assertTrue(
                "$count answers in rows of $sizes leaves a lone answer in a row",
                sizes.all { it == sizes.first() || it == sizes.first() - 1 },
            )
        }
    }

    @Test
    fun `the rows hold exactly the answers there are`() {
        for (count in 1..20) {
            assertEquals("$count", count, actionRowSizes(count).sum())
        }
    }

    @Test
    fun `a set with nothing in it has no rows`() {
        assertEquals(emptyList<Int>(), actionRowSizes(0))
        assertEquals(emptyList<Int>(), actionRowSizes(-1))
    }

    @Test
    fun `rows keep the answers in the order they were given`() {
        // The order is the caller's point - the recommended answer is first because the caller said so -
        // so the rows are a slicing of that order and nothing else.
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), actionRows(listOf(1, 2, 3, 4)))
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), actionRows(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `an answer is not working unless it says so`() {
        // The slot has to be off by default, or every dialog that already exists grows a spinner it never
        // asked for - and "is it working" is a claim only the caller can make.
        val quiet = AppAction(R.string.action_cancel) {}
        assertFalse(quiet.progress)
        assertTrue(quiet.enabled)
        assertEquals(AppActionRole.Standard, quiet.role)
    }

    @Test
    fun `an answer that is working is still one answer in the same row`() {
        // The spinner is per answer, not per row: the set a screen asks with keeps its shape while one of
        // its answers is in flight, so the remaining answers do not jump to a new place mid-press.
        val actions = listOf(
            AppAction(R.string.action_cancel, progress = false) {},
            AppAction(R.string.action_cancel, role = AppActionRole.Priority, progress = true) {},
            AppAction(R.string.action_cancel) {},
            AppAction(R.string.action_cancel) {},
        )
        assertEquals(listOf(2, 2), actionRows(actions).map { it.size })
        assertEquals(listOf(false, true, false, false), actionRows(actions).flatten().map { it.progress })
    }

    @Test
    fun `the shared button draws the spinner only for an answer that is working`() {
        val source = source("DialogActions.kt")
        assertTrue(
            "the spinner is drawn unconditionally, so every answer in the app is busy",
            source.contains("if (action.progress)"),
        )
        assertTrue("no spinner in the shared answer button", source.contains("LoadingIndicator("))
    }

    @Test
    fun `the shizuku start prompt asks with the shared set instead of its own button`() {
        // The special case the slot exists for: this dialog built its own pressable answer, spinner and
        // all, which is how one screen came to have buttons that did not match the other twenty.
        val body = bodyOf(source("InstallActivity.kt"), "private fun ShizukuHoldDialog")
        assertTrue("the prompt does not ask with the shared set", body.contains("AppDialogActions("))
        assertTrue("the prompt does not mark the answer it is waiting on", body.contains("progress = prompt.starting"))
        assertFalse("the prompt still hand-builds an answer", body.contains("TextButton"))
    }

    @Test
    fun `every answer lands in exactly one row`() {
        // The bug this catches is an off-by-one in the slicing, which shows up as a dropped answer - and a
        // dropped answer here is a button that is never drawn, not a layout that looks wrong.
        val answers = (1..11).toList()
        assertEquals(answers, actionRows(answers).flatten())
    }

    /** One of this package's sources, wherever the test JVM was started from. */
    private fun source(fileName: String): String =
        listOf(
            File("src/main/java/dev/busung/s25uroot/$fileName"),
            File("app/src/main/java/dev/busung/s25uroot/$fileName"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("$fileName was not found from ${File(".").absolutePath}")

    /**
     * One declaration's body, up to the next declaration at the same indentation.
     *
     * Scoped rather than searched file-wide because the file holds a dozen of these: "does the prompt
     * still build its own button" has to be asked of this prompt, not of some other screen's.
     */
    private fun bodyOf(text: String, declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue("no `$declaration` in the source", start >= 0)
        val next = text.indexOf("\nprivate fun ", start + declaration.length)
        return text.substring(start, if (next < 0) text.length else next)
    }
}
