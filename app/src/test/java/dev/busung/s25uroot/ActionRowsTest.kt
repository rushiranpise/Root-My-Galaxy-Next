package dev.busung.s25uroot

import org.junit.Assert.assertEquals
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
    fun `every answer lands in exactly one row`() {
        // The bug this catches is an off-by-one in the slicing, which shows up as a dropped answer - and a
        // dropped answer here is a button that is never drawn, not a layout that looks wrong.
        val answers = (1..11).toList()
        assertEquals(answers, actionRows(answers).flatten())
    }
}
