package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buzz a failed run gives, and the endings that have to stay silent.
 *
 * The failure these exist for is a buzz that should not have happened: a phone that hums at three in the
 * morning about a boot gate nobody started, or one that argues with a stop by answering a tap with a
 * complaint. A buzz that never happens at all is the other half, and it is invisible - the run ends, the
 * phone stays still, and there is nothing to say whether that was the rule working or the wiring missing -
 * so the decisions are checked directly and the call is checked where it is written.
 */
class RunFailureBuzzTest {

    @Test
    fun `a failure somebody was there for buzzes`() {
        assertTrue(shouldBuzzRun(RunVerdict.Failed, unattended = false))
    }

    @Test
    fun `a stop was asked for, so it does not buzz`() {
        // The user ended this run. A buzz now would be the phone disagreeing with the tap that did it.
        assertFalse(shouldBuzzRun(RunVerdict.Stopped, unattended = false))
    }

    @Test
    fun `a boot gate buzzes nothing, however it ends`() {
        // Nobody started this run and nobody is looking at the phone: it was woken by BOOT_COMPLETED, and
        // the result is left as a notification for whenever its owner picks the phone up.
        RunVerdict.entries.forEach { verdict ->
            assertFalse(verdict.name, shouldBuzzRun(verdict, unattended = true))
        }
    }

    @Test
    fun `every other ending is quiet`() {
        RunVerdict.entries
            .filter { it != RunVerdict.Failed }
            .forEach { verdict ->
                assertFalse(verdict.name, shouldBuzzRun(verdict, unattended = false))
            }
    }

    @Test
    fun `the buzz is short`() {
        // Short enough to read as a nudge rather than an alarm, and long enough to be felt through a pocket.
        assertTrue(FAILURE_BUZZ_MILLIS in 1..500L)
    }

    @Test
    fun `the run's own ending asks for the buzz`() {
        val viewModel = source("InstallViewModel.kt")
        // In the run's ending block, beside the wake: both are about the phone reaching its owner after the
        // run, and a call placed anywhere later would be on a path some endings do not take.
        assertTrue(
            "nothing buzzes for a failed run",
            viewModel.contains("runFailureBuzz("),
        )
        assertTrue(
            "the buzz is not asked with the verdict the run actually ended on",
            viewModel.contains("verdict = runVerdict(mutableState.value.phase, busy = false)"),
        )
        assertTrue(
            "the buzz is not told whether anyone asked for this run",
            viewModel.contains("unattended = runIsUnattended"),
        )
    }

    private fun source(name: String): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name }.toList() }
            .firstOrNull()
        requireNotNull(file) { "$name was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main"),
        File("app/src/main"),
    ).filter(File::isDirectory)
}
