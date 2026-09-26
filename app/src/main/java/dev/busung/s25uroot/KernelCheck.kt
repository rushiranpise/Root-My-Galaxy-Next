package dev.busung.s25uroot

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What the kernel check is doing, in the five states its screen can be in.
 *
 * A screen's own state rather than one a view model holds, because nothing else in the app can be waiting on
 * it: the check is not a run, changes nothing on the device, and is over by the time anyone leaves the page.
 * What it does share with a run is the shape of its report - a list of steps, one of them working, and an
 * answer at the end of them.
 */
internal sealed interface KernelCheckState {
    /** Nothing asked yet. */
    data object Idle : KernelCheckState

    /** The test is on attempt [attempt] of [VulnerabilityProbe.ATTEMPTS]. */
    data class Running(val attempt: Int) : KernelCheckState

    /**
     * An answer, and where it came from.
     *
     * [byVersion] is not decoration: the version table and the test are two different kinds of statement, and
     * the one thing the screen must never do is let a table's answer read as a measured one. [fixedIn] is the
     * fixed release on this phone's branch when there is one, which is what a user can check for themselves.
     */
    data class Done(
        val verdict: VulnerabilityProbe.Verdict,
        val byVersion: Boolean,
        val fixedIn: String?,
    ) : KernelCheckState

    /** The test cannot be run at all, which is a fact about this build and not about the phone. */
    data object NotPossible : KernelCheckState

    /**
     * A check that was running when this app last went away.
     *
     * [restarted] is the whole of what separates an answer from a shrug, and it is read from the boot token
     * rather than guessed: a test that was running in one boot and is being read in another one stopped
     * because the phone restarted, and a kernel that can be restarted by this test is one with the bug in it.
     * The same record read in the same boot means the app died and the phone did not - which says nothing
     * about the kernel, so it is reported as a test that did not finish.
     */
    data class Interrupted(val restarted: Boolean) : KernelCheckState
}

/**
 * The verdict a screen has to show, if it has one to show.
 *
 * One place rather than a `when` in every reader, because three of them - the two step marks and both detail
 * lines - have to agree about whether this screen has answered yet. The restart is a verdict of its own kind:
 * it is not the test measuring an unkillable task, it is the test watching the phone go down under it, and
 * both are the bug.
 */
internal fun kernelVerdictOf(state: KernelCheckState): VulnerabilityProbe.Verdict? = when (state) {
    is KernelCheckState.Done -> state.verdict
    is KernelCheckState.Interrupted ->
        if (state.restarted) VulnerabilityProbe.Verdict.Vulnerable else null

    KernelCheckState.Idle, KernelCheckState.NotPossible, is KernelCheckState.Running -> null
}

/**
 * The three things this screen does, in the order it does them.
 *
 * Named steps rather than one progress figure, because the three answer different questions and only one of
 * them takes time: reading the version is instant and decides the whole answer for a fixed kernel, the test
 * is the minute, and the answer is what the person came for. A single "working" indicator would have made the
 * instant part look like the slow part.
 */
internal enum class KernelCheckStep(@StringRes val title: Int, val icon: ImageVector) {
    Version(R.string.kernel_step_version_title, Icons.Rounded.FactCheck),
    Test(R.string.kernel_step_test_title, Icons.Rounded.Memory),
    Answer(R.string.kernel_step_answer_title, Icons.Rounded.VerifiedUser),
}

/** Where a step stands, in the four marks a step list has. */
internal enum class KernelStepMark { Pending, Active, Done, Failed }

/**
 * The mark for one step, from what the screen is doing and what has been read.
 *
 * Pure, so the whole of the reporting can be checked without a device - and the two marks worth arguing about
 * are here rather than in the drawing. The version step is [KernelStepMark.Failed] when the kernel version
 * could not be read, rather than done: the step had one job and that is the one it could not do. The answer
 * step is [KernelStepMark.Pending] while a test that cannot run is failing above it, because nothing has been
 * answered yet.
 */
internal fun kernelStepMark(
    state: KernelCheckState,
    step: KernelCheckStep,
    versionRead: Boolean,
): KernelStepMark = when (step) {
    KernelCheckStep.Version -> if (versionRead) KernelStepMark.Done else KernelStepMark.Failed

    KernelCheckStep.Test -> when {
        state is KernelCheckState.Running -> KernelStepMark.Active
        state is KernelCheckState.NotPossible -> KernelStepMark.Failed
        // The test was running and the app is reading about it from a boot in which it never finished and
        // the phone never restarted: nothing was learned, so the mark is the failure it was.
        state is KernelCheckState.Interrupted && !state.restarted -> KernelStepMark.Failed
        // A version at or above the fix is the whole answer, so the test was never needed rather than
        // skipped: the step stays pending so the screen does not tick work that did not happen.
        state is KernelCheckState.Done && state.byVersion -> KernelStepMark.Pending
        kernelVerdictOf(state) != null -> KernelStepMark.Done
        else -> KernelStepMark.Pending
    }

    KernelCheckStep.Answer -> if (kernelVerdictOf(state) != null) {
        KernelStepMark.Done
    } else {
        KernelStepMark.Pending
    }
}

/**
 * What a step says under its name.
 *
 * A resource rather than a sentence because every one of these is copy, and a resource for the *step* rather
 * than for the row because the same step says different things as it goes - the test's line is "run the test"
 * before, an attempt count during, and what it found afterwards. The attempt count is the only one that needs
 * arguments, which is why the caller formats it.
 */
@StringRes
internal fun kernelStepDetail(state: KernelCheckState, step: KernelCheckStep): Int = when (step) {
    KernelCheckStep.Version -> R.string.kernel_step_version_detail

    KernelCheckStep.Test -> when (state) {
        KernelCheckState.Idle -> R.string.kernel_step_test_detail
        KernelCheckState.NotPossible -> R.string.kernel_check_failed
        is KernelCheckState.Running -> R.string.kernel_step_test_active
        is KernelCheckState.Interrupted ->
            if (state.restarted) R.string.kernel_step_test_restarted else R.string.kernel_step_test_unclear

        is KernelCheckState.Done -> when {
            state.byVersion -> R.string.kernel_step_test_not_needed
            state.verdict == VulnerabilityProbe.Verdict.Vulnerable -> R.string.kernel_step_test_stuck
            state.verdict == VulnerabilityProbe.Verdict.Patched -> R.string.kernel_step_test_stopped
            else -> R.string.kernel_step_test_unclear
        }
    }

    KernelCheckStep.Answer -> when (kernelVerdictOf(state)) {
        VulnerabilityProbe.Verdict.Vulnerable -> R.string.kernel_step_answer_rootable
        VulnerabilityProbe.Verdict.Patched -> R.string.kernel_step_answer_fixed
        VulnerabilityProbe.Verdict.Undecided -> R.string.kernel_step_answer_unknown
        null -> R.string.kernel_step_answer_detail
    }
}

/**
 * The check's own progress, drawn the way a run's steps are drawn.
 *
 * The same shape as the run screen's list on purpose - a circle per step, a tick where a step is done, a cross
 * where one failed, a spinner on the one that is working, the name over a line of what it is doing - because
 * a person who has seen one of these screens should not have to learn a second vocabulary to read the other.
 * It is drawn here rather than shared with that screen so this stays a thing that can be changed on its own;
 * what the two have in common is the look, and the look is four lines of layout.
 *
 * [onExplain] opens the guide, and it lives at the foot of the list rather than in a corner of the screen
 * because that is where the question "what do these words mean" is asked.
 */
@Composable
internal fun KernelCheckSteps(
    state: KernelCheckState,
    versionRead: Boolean,
    onExplain: () -> Unit,
) {
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KernelCheckStep.entries.forEach { step ->
            val mark = kernelStepMark(state, step, versionRead)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = when (mark) {
                        KernelStepMark.Done, KernelStepMark.Active -> MaterialTheme.colorScheme.primary
                        KernelStepMark.Failed -> MaterialTheme.colorScheme.error
                        KernelStepMark.Pending -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = when (mark) {
                        KernelStepMark.Done, KernelStepMark.Active -> MaterialTheme.colorScheme.onPrimary
                        KernelStepMark.Failed -> MaterialTheme.colorScheme.onError
                        KernelStepMark.Pending -> MaterialTheme.colorScheme.onSurface
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (mark) {
                                KernelStepMark.Done -> Icons.Rounded.Check
                                KernelStepMark.Failed -> Icons.Rounded.Close
                                else -> step.icon
                            },
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(step.title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stepDetail(state, step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
                if (mark == KernelStepMark.Active) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        AppTextAction(
            action = AppAction(R.string.step_guide_title) {
                clickHaptic(view)
                onExplain()
            },
        )
    }
}

/** One step's line, with the attempt count filled in for the one that has it. */
@Composable
private fun stepDetail(state: KernelCheckState, step: KernelCheckStep): String {
    val detail = kernelStepDetail(state, step)
    return if (step == KernelCheckStep.Test && state is KernelCheckState.Running) {
        stringResource(detail, state.attempt, VulnerabilityProbe.ATTEMPTS)
    } else {
        stringResource(detail)
    }
}

/**
 * One step of a flow, explained: what it does, and what a restart at that point means.
 *
 * The restart line is not decoration and is the part the app this screen was taken from did not have. Every
 * step here changes how much a restart costs - nothing at all for a check, a re-download for a payload that
 * was not saved yet, the whole of root for one that was - and a person deciding whether to press Restart, or
 * wondering whether they have lost something, is asking exactly that question.
 */
internal data class StepGuide(
    @StringRes val title: Int,
    @StringRes val meaning: Int,
    /**
     * What a restart at this point means, or null for a step it means nothing at.
     *
     * Nullable rather than filled in, because the alternative is what this used to be: a line saying "a
     * restart changes nothing" under every step that had nothing to say, which reads as filler and makes the
     * steps that *do* have a cost harder to find. A step with no line is a step where a restart is simply not
     * a consideration, and that is worth knowing by itself.
     */
    @StringRes val restart: Int?,
)

/** The check's own three steps, in the order its list draws them. */
internal val kernelCheckGuide: List<StepGuide> = listOf(
    // No restart line: reading a version survives a restart, and saying so would be saying nothing.
    StepGuide(
        R.string.kernel_step_version_title,
        R.string.guide_kernel_version_meaning,
        restart = null,
    ),
    StepGuide(
        R.string.kernel_step_test_title,
        R.string.guide_kernel_test_meaning,
        R.string.guide_kernel_test_restart,
    ),
    StepGuide(
        R.string.kernel_step_answer_title,
        R.string.guide_kernel_answer_meaning,
        restart = null,
    ),
)

/**
 * What a run would do on this phone, for the person who is here because no payload covers it yet.
 *
 * The second half of the guide rather than the first, and the reason the check has a guide at all: somebody
 * whose model is not supported is deciding whether to wait for it, and the thing they are deciding about is
 * the four steps a run takes. The names are the run screen's own, so the list they read here is the list they
 * will watch later.
 */
internal val runGuide: List<StepGuide> = listOf(
    StepGuide(
        R.string.step_support_title,
        R.string.guide_step_support_meaning,
        restart = null,
    ),
    StepGuide(
        R.string.step_download_title,
        R.string.guide_step_download_meaning,
        R.string.guide_step_download_restart,
    ),
    StepGuide(
        R.string.step_exploit_title,
        R.string.guide_step_exploit_meaning,
        R.string.guide_step_exploit_restart,
    ),
    StepGuide(
        R.string.step_ksu_title,
        R.string.guide_step_ksu_meaning,
        R.string.guide_step_ksu_restart,
    ),
)

/**
 * The guide itself: what the steps on the screen behind it mean, and what a restart means at each one.
 *
 * One dialog with two lists rather than a screen per list, because the question is one question - what is
 * this going to do to my phone - and the two halves of the answer are what this check does now and what a run
 * would do once a payload covers the phone.
 */
@Composable
internal fun StepGuideDialog(onDismiss: () -> Unit) {
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogDimAmount(0.34f)
            Text(stringResource(R.string.step_guide_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepGuideSection(stringResource(R.string.kernel_check), kernelCheckGuide)
                HorizontalDivider()
                StepGuideSection(stringResource(R.string.step_guide_run), runGuide)
            }
        },
        confirmButton = {
            AppDialogActions(
                listOf(
                    AppAction(R.string.action_close, AppActionRole.Priority) {
                        clickHaptic(view)
                        onDismiss()
                    },
                ),
            )
        },
    )
}

@Composable
private fun StepGuideSection(heading: String, guide: List<StepGuide>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        guide.forEach { step ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(step.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(step.meaning), style = MaterialTheme.typography.bodyMedium)
                step.restart?.let { restart ->
                    Text(
                        text = stringResource(R.string.step_guide_restart, stringResource(restart)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
