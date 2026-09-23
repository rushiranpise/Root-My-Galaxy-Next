package dev.busung.s25uroot.dfr

import androidx.annotation.StringRes
import dev.busung.s25uroot.R

/**
 * The order this has to happen in, as a state and not as a paragraph of instructions.
 *
 * The flow is six steps that cannot be reordered, and every one of them fails in a way that looks like a
 * different problem: injecting after the app is installed does nothing, installing before the reboot
 * leaves an ordinary app that no later reboot can re-key, and running the exploit twice in one boot arms
 * hooks that only a hard reboot clears. Written as prose, the order is something to get right while
 * reading; written as this, the screen can say which step the phone is actually on and offer only the
 * action that step needs.
 *
 * Two of the steps are things only the user can do (a reboot), and the rest are one command each, which
 * the app has the shell for. So the model's job is the *decision* - given what was observed, what is
 * next - and the callers' job is to press it.
 *
 * ## What it reads, and what it cannot
 *
 * Every field here is a measurement except one kind: the two instants. "Has this phone rebooted since
 * the inject?" is answered by comparing how long the phone has been up against how long ago the inject
 * was *recorded*, which is exact as long as the wall clock is not moved - and the wall clock can be
 * moved, so the answer is treated as evidence rather than as truth, and the one place a wrong answer
 * matters (an install that landed as an ordinary app) is caught by the measurement instead: the uid the
 * package actually runs as.
 *
 * The other limit is deliberate: [DfrStep.ReadState]. When no shell answered, nothing was observed, and
 * an app that guessed here would either offer an inject that refuses because the key is already there,
 * or hide a step that has not happened. So "could not read" is its own step, and it says so.
 */
internal enum class DfrStep(
    /** The row's own label. */
    @StringRes val label: Int,
    /** One line for what this step is for, or why it is the one being asked for. */
    @StringRes val detail: Int,
) {
    ReadState(R.string.dfr_step_read, R.string.dfr_step_read_detail),
    Inject(R.string.dfr_step_inject, R.string.dfr_step_inject_detail),
    Reboot(R.string.dfr_step_reboot, R.string.dfr_step_reboot_detail),
    RemoveStageTwo(R.string.dfr_step_remove, R.string.dfr_step_remove_detail),
    InstallStageTwo(R.string.dfr_step_install, R.string.dfr_step_install_detail),
    RebootAgain(R.string.dfr_step_reboot_again, R.string.dfr_step_reboot_again_detail),
    OpenStageTwo(R.string.dfr_step_open, R.string.dfr_step_open_detail),
    Ready(R.string.dfr_step_ready, R.string.dfr_step_ready_detail),
}

/** Everything the flow was observed to be, and the two clocks the answer also needs. */
internal data class DfrState(
    /** Whether the certificate is in `pastSigs`; null when no shell answered to ask. */
    val keyInjected: Boolean?,
    /** When this app recorded the inject, or null when it did not do it or has no record. */
    val injectedAtMillis: Long?,
    /** Whether the stage-two APK is installed at all. */
    val stageTwoInstalled: Boolean,
    /** Whether it is installed as uid 1000, which is the whole point of the inject. */
    val stageTwoIsSystemUid: Boolean,
    /** When this app recorded the stage-two install, or null. */
    val installedAtMillis: Long?,
    /** Whether the exploit's hooks are already in the kernel this boot. */
    val stageTwoArmed: Boolean,
    val nowMillis: Long,
    val uptimeMillis: Long,
)

internal object DfrFlow {

    /**
     * Whether the phone booted after [atMillis].
     *
     * Uptime against elapsed time, which is exact while the clock is stable and degrades in one
     * direction only: a clock moved backwards makes the phone look freshly booted, so the step it asks
     * for is a reboot - the instruction that costs a minute and cannot make anything worse.
     */
    fun rebootedSince(atMillis: Long?, nowMillis: Long, uptimeMillis: Long): Boolean {
        val recorded = atMillis ?: return false
        return uptimeMillis < nowMillis - recorded
    }

    /** The step the phone is on, given what was observed. */
    fun next(state: DfrState): DfrStep {
        // Armed is first because it is the only state that needs nothing: hooks in the kernel this boot
        // mean the flow already completed, whatever any file says about how it started.
        if (state.stageTwoArmed) return DfrStep.Ready
        val injected = state.keyInjected ?: return DfrStep.ReadState
        if (!injected) return DfrStep.Inject
        // A system uid is proof the reboot happened: Package Manager applies the shared user at install
        // time, and an install before the reboot could only have produced an ordinary app.
        if (state.stageTwoIsSystemUid) {
            return if (rebootedSince(state.installedAtMillis, state.nowMillis, state.uptimeMillis)) {
                DfrStep.OpenStageTwo
            } else {
                DfrStep.RebootAgain
            }
        }
        // Installed but not privileged: the app went in under a shared user that does not list its key -
        // either before the reboot, or with a different key injected than the APK is signed with. PMS
        // will not re-key an installed package, so this is a removal, not another reboot.
        if (state.stageTwoInstalled) return DfrStep.RemoveStageTwo
        if (!rebootedSince(state.injectedAtMillis, state.nowMillis, state.uptimeMillis)) return DfrStep.Reboot
        return DfrStep.InstallStageTwo
    }

    /** Every step in order, for the screen that shows how far along the flow is. */
    val order: List<DfrStep> = listOf(
        DfrStep.Inject,
        DfrStep.Reboot,
        DfrStep.InstallStageTwo,
        DfrStep.RebootAgain,
        DfrStep.OpenStageTwo,
        DfrStep.Ready,
    )

    /**
     * The two steps that are not part of that order, because they are not steps forward.
     *
     * [DfrStep.ReadState] is where the flow stops when nothing could be measured, and
     * [DfrStep.RemoveStageTwo] undoes an install that landed under the wrong identity - so neither has a
     * position, and a screen that numbered them would be claiming progress that has not happened. They
     * are named on their own line instead.
     */
    val detours: List<DfrStep> = listOf(DfrStep.ReadState, DfrStep.RemoveStageTwo)
}
