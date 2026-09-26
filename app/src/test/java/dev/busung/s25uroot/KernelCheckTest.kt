package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelCheckTest {

    private val idle = KernelCheckState.Idle
    private val running = KernelCheckState.Running(3)
    private val byVersion = KernelCheckState.Done(VulnerabilityProbe.Verdict.Patched, true, "6.6.140")
    private val measuredVulnerable =
        KernelCheckState.Done(VulnerabilityProbe.Verdict.Vulnerable, false, "6.6.140")
    private val measuredPatched =
        KernelCheckState.Done(VulnerabilityProbe.Verdict.Patched, false, "6.6.140")
    private val cannotRun = KernelCheckState.NotPossible
    private val restartedMidTest = KernelCheckState.Interrupted(restarted = true)
    private val diedWithoutRestart = KernelCheckState.Interrupted(restarted = false)

    @Test
    fun aCheckLeftUnfinishedAcrossTwoBootsIsAVerdictThatTheBugIsHere() {
        // Read after the fact, because the usual restart takes this app with it: the record was written before
        // the first attempt and read in a different boot, which is the only thing in this app that changes when
        // the phone restarts.
        assertEquals(
            VulnerabilityProbe.Verdict.Vulnerable,
            kernelVerdictOf(restartedMidTest),
        )
        assertEquals(KernelStepMark.Done, kernelStepMark(restartedMidTest, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Done, kernelStepMark(restartedMidTest, KernelCheckStep.Answer, true))
        assertEquals(
            R.string.kernel_step_test_restarted,
            kernelStepDetail(restartedMidTest, KernelCheckStep.Test),
        )
        assertEquals(
            R.string.kernel_step_answer_rootable,
            kernelStepDetail(restartedMidTest, KernelCheckStep.Answer),
        )
    }

    @Test
    fun aCheckLeftUnfinishedWithoutARestartClaimsNothingAboutTheKernel() {
        // The same record read in the same boot: the app went away and the phone did not, which is a test that
        // did not finish rather than a kernel that was found to have the bug. Answering "vulnerable" here would
        // be the false accusation the whole verdict rule is built to avoid.
        assertNull(kernelVerdictOf(diedWithoutRestart))
        assertEquals(KernelStepMark.Failed, kernelStepMark(diedWithoutRestart, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Pending, kernelStepMark(diedWithoutRestart, KernelCheckStep.Answer, true))
        assertEquals(
            R.string.kernel_step_test_unclear,
            kernelStepDetail(diedWithoutRestart, KernelCheckStep.Test),
        )
        assertEquals(
            R.string.kernel_step_answer_detail,
            kernelStepDetail(diedWithoutRestart, KernelCheckStep.Answer),
        )
    }

    @Test
    fun onlyTwoDifferentBootTokensAreARestart() {
        assertTrue(restartedSince("boot-a", "boot-b"))
        assertFalse(restartedSince("boot-a", "boot-a"))
        // An unreadable token on either side is a reading this app does not have, and the one answer a check
        // must never reach by accident is that the phone is vulnerable.
        assertFalse(restartedSince(null, "boot-b"))
        assertFalse(restartedSince("boot-a", null))
        assertFalse(restartedSince(null, null))
    }

    @Test
    fun theVersionStepIsDoneAsSoonAsTheVersionReads() {
        for (state in listOf(idle, running, byVersion, measuredVulnerable, cannotRun)) {
            assertEquals(
                "$state",
                KernelStepMark.Done,
                kernelStepMark(state, KernelCheckStep.Version, versionRead = true),
            )
        }
    }

    @Test
    fun theVersionStepFailsRatherThanPassesWhenTheKernelCouldNotBeRead() {
        // The step had one job. A tick over a version that could not be read would be the screen claiming the
        // read that decides whether anything else is needed.
        assertEquals(
            KernelStepMark.Failed,
            kernelStepMark(idle, KernelCheckStep.Version, versionRead = false),
        )
    }

    @Test
    fun theTestStepIsOnlyWorkingWhileTheTestIsRunning() {
        assertEquals(KernelStepMark.Pending, kernelStepMark(idle, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Active, kernelStepMark(running, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Done, kernelStepMark(measuredPatched, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Failed, kernelStepMark(cannotRun, KernelCheckStep.Test, true))
    }

    @Test
    fun aTestThatWasNeverNeededStaysPendingRatherThanBeingTicked() {
        // A version at or above the fix answers it outright. The test did not run, so the step must not be
        // drawn as work that was done - and the answer step beside it is done, which is the difference
        // between "this step was not needed" and "this step is still to come".
        assertEquals(KernelStepMark.Pending, kernelStepMark(byVersion, KernelCheckStep.Test, true))
        assertEquals(KernelStepMark.Done, kernelStepMark(byVersion, KernelCheckStep.Answer, true))
    }

    @Test
    fun theAnswerStepIsOnlyDoneWhenThereIsAnAnswer() {
        for (state in listOf(idle, running, cannotRun)) {
            assertEquals(
                "$state",
                KernelStepMark.Pending,
                kernelStepMark(state, KernelCheckStep.Answer, true),
            )
        }
        assertEquals(KernelStepMark.Done, kernelStepMark(measuredVulnerable, KernelCheckStep.Answer, true))
    }

    @Test
    fun theTestStepSaysWhatItFoundRatherThanOnlyThatItFinished() {
        assertEquals(
            R.string.kernel_step_test_not_needed,
            kernelStepDetail(byVersion, KernelCheckStep.Test),
        )
        assertEquals(
            R.string.kernel_step_test_stuck,
            kernelStepDetail(measuredVulnerable, KernelCheckStep.Test),
        )
        assertEquals(
            R.string.kernel_step_test_stopped,
            kernelStepDetail(measuredPatched, KernelCheckStep.Test),
        )
        assertEquals(
            R.string.kernel_step_test_unclear,
            kernelStepDetail(
                KernelCheckState.Done(VulnerabilityProbe.Verdict.Undecided, false, null),
                KernelCheckStep.Test,
            ),
        )
    }

    @Test
    fun theAnswerStepSaysWhichAnswerItIs() {
        assertEquals(
            R.string.kernel_step_answer_rootable,
            kernelStepDetail(measuredVulnerable, KernelCheckStep.Answer),
        )
        assertEquals(
            R.string.kernel_step_answer_fixed,
            kernelStepDetail(measuredPatched, KernelCheckStep.Answer),
        )
        assertEquals(
            R.string.kernel_step_answer_unknown,
            kernelStepDetail(
                KernelCheckState.Done(VulnerabilityProbe.Verdict.Undecided, false, null),
                KernelCheckStep.Answer,
            ),
        )
        // Before anything has been asked, the step says what it is for rather than what it found.
        for (state in listOf(idle, running, cannotRun)) {
            assertEquals(
                "$state",
                R.string.kernel_step_answer_detail,
                kernelStepDetail(state, KernelCheckStep.Answer),
            )
        }
    }

    @Test
    fun everyStepOfTheCheckIsExplainedInTheGuideAndInTheSameOrder() {
        // The guide is the only place the screen's words are defined for somebody who has not used it, so a
        // step with no entry would be a step the app refuses to explain - and the order matters, because the
        // guide is read against the list on the screen behind it.
        assertEquals(
            KernelCheckStep.entries.map { it.title },
            kernelCheckGuide.map { it.title },
        )
    }

    @Test
    fun everyStepOfARunIsExplainedInTheSameGuideAndInTheSameOrder() {
        // The run screen's own step list, referenced rather than copied: a fifth step added there fails this
        // until it is explained, which is the state the copy is meant to be kept in.
        assertEquals(
            installerSteps.map { it.title },
            runGuide.map { it.title },
        )
        assertTrue("the run's steps are the four this guide describes", installerSteps.size == 4)
        assertEquals("the check's steps are the three it draws", 3, KernelCheckStep.entries.size)
    }
}
