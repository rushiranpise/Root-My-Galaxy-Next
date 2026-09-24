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
 * Every field here is a measurement except one kind: the three instants. "Has this phone restarted since
 * the inject?" is answered by comparing **the framework's own age** against how long ago the inject was
 * *recorded* - see [restartedSince] - which is exact as long as the wall clock is not moved, and the wall
 * clock can be moved, so the answer is treated as evidence rather than as truth. The one place a wrong
 * answer matters (an install that landed as an ordinary app) is caught by the measurement instead: the uid
 * the package actually runs as.
 *
 * The other limit is deliberate: [DfrStep.ReadState]. When no shell answered, nothing was observed, and
 * an app that guessed here would either offer an inject that refuses because the key is already there,
 * or hide a step that has not happened. So "could not read" is its own step, and it says so - with one
 * exception, made where the missing reading is not the deciding evidence: a helper the phone has already
 * given the system's own uid is proof of what that reading is for, and a boot with no root is exactly the
 * phone that cannot take it and exactly the phone that still has to be opened at that step.
 *
 * ## The one thing that is this app's own state
 *
 * Every other step is about the phone. [DfrStep.NoHelper] and [DfrStep.HelperUnwritable] are about *this
 * build*: the helper APK is carried in this app's assets, so a build without it has nothing to install and
 * no way to get one - and that is checkable before anything else, because it is not a reading of the
 * device at all. It comes first for the reason the whole flow is written as a state rather than as prose:
 * the alternative is walking somebody to an inject whose certificate can only ever be used by a helper
 * that is not in the APK they are holding. An inject that cannot be followed by an install is worse than
 * no inject - it puts a signing certificate into `android.uid.system` with nothing on the device to
 * spend it.
 *
 * Those two are separate steps because they are separate problems with opposite answers. A build that
 * carries no helper is a build to replace; an APK that is in the assets and could not be written to app
 * storage is a phone to make room on, and "read again" is the action that fixes it - which is why the
 * screen hides that button for one refusal and keeps it for the other. One sentence for both would tell
 * somebody with a full disk that their APK is missing.
 */
internal enum class DfrStep(
    /** The row's own label. */
    @StringRes val label: Int,
    /** One line for what this step is for, or why it is the one being asked for. */
    @StringRes val detail: Int,
) {
    NoHelper(R.string.dfr_step_no_helper, R.string.dfr_step_no_helper_detail),
    HelperUnwritable(
        R.string.dfr_step_helper_unwritable,
        R.string.dfr_step_helper_unwritable_detail,
    ),
    ReadState(R.string.dfr_step_read, R.string.dfr_step_read_detail),
    ApplyRemoval(R.string.dfr_step_apply_removal, R.string.dfr_step_apply_removal_detail),
    Inject(R.string.dfr_step_inject, R.string.dfr_step_inject_detail),
    Reboot(R.string.dfr_step_reboot, R.string.dfr_step_reboot_detail),
    RemoveStageTwo(R.string.dfr_step_remove, R.string.dfr_step_remove_detail),
    StaleStageTwo(R.string.dfr_step_stale, R.string.dfr_step_stale_detail),
    InstallStageTwo(R.string.dfr_step_install, R.string.dfr_step_install_detail),
    RebootAgain(R.string.dfr_step_reboot_again, R.string.dfr_step_reboot_again_detail),
    OpenStageTwo(R.string.dfr_step_open, R.string.dfr_step_open_detail),
    Ready(R.string.dfr_step_ready, R.string.dfr_step_ready_detail),
}

/**
 * Whether this build's helper APK can be produced, which is the flow's one question about itself.
 *
 * Three cases rather than a boolean, because two of them are refusals that need different sentences: not
 * in the APK at all, and in the APK but not writable where a root process can read it. The app answers
 * this from its own assets and its own storage - see [DfrApk.bundled] - so nothing about the device can
 * change it, and both refusals come before any reading of the phone.
 */
internal enum class DfrHelperAvailability {
    /** Unpacked and readable: the flow can run. */
    Ready,

    /** This build's assets carry no helper APK, so there is nothing to inject or install. */
    NotInBuild,

    /** The APK is in the assets and could not be written to app storage, which a full disk explains. */
    Unwritable,
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
    /**
     * When this app took its key out of `packages.xml`, or null when it never did.
     *
     * The third instant, and the only one about a change the phone has not caught up with yet: an
     * uninstall writes the file, and Package Manager reads it at its own start, so between the two the
     * running system still holds the key. It is also not [DfrState.keyInjected]'s opposite, because the
     * file cannot say whether the key was there a moment ago.
     */
    val keyRemovedAtMillis: Long? = null,
    /** Whether the exploit's hooks are already in the kernel this boot. */
    val stageTwoArmed: Boolean,
    /**
     * Whether the helper installed on the phone is the one this APK ships.
     *
     * [StageTwoBuild.Unreadable] by default, which is the same answer as "nobody asked": every caller
     * that has not done this reading gets the flow's old behaviour rather than a claim about two builds
     * nothing compared.
     */
    val stageTwoBuild: StageTwoBuild = StageTwoBuild.Unreadable,
    /**
     * Whether this build can produce the helper APK.
     *
     * The one field here that is not a reading of the phone: the helper is an asset of the APK that is
     * doing the asking, and [DfrApk.bundled] answers this. Anything but [DfrHelperAvailability.Ready] is a
     * refusal before anything else, because every other step ends at that file.
     */
    val helper: DfrHelperAvailability = DfrHelperAvailability.Ready,
    /**
     * How long the Android framework has been up, or null when it could not be read.
     *
     * The reading that can see the restart this flow actually performs. Every step that asks for a
     * restart is satisfied by the daemon's userspace one, which replaces the framework and leaves the
     * kernel - and so [DfrState.uptimeMillis] - running: measured on this device, `system_server` was
     * 2558 s old while the kernel had been up 7804 s, and the inject 3679 s earlier, so the restart had
     * happened and the kernel clock could not say so. Null falls back to that clock.
     */
    val frameworkUptimeMillis: Long? = null,
    val nowMillis: Long,
    val uptimeMillis: Long,
)

/**
 * Whether the helper on the phone is the build this app ships, and whether that could even be asked.
 *
 * The helper is one APK built from one commit, and this app carries a copy of it - so the two are the
 * same helper only when the phone is running the one in the APK in front of you. When they are not, the
 * run the helper starts is the *other* build's run: its shellcode, its arguments, its bugs. That is the
 * failure this whole reading exists for, because nothing else about it is visible - a stale helper
 * installs cleanly, reports the system uid it really has, and fails inside the exploit in the shape of
 * the exploit having failed. See [stageTwoBuild] for what the two numbers are compared as.
 *
 * Four answers rather than a boolean, because two of them are the same silence for different reasons and
 * a third has an action. "Installed and this build" needs nothing, "installed and another build" is the
 * one step this can take, and both of the unanswerable cases - nothing installed at all, and one of the
 * two numbers unreadable - must not be reported as a disagreement: the flow's advice for another build
 * is "install this one", and sending somebody to install a helper they already have, over a number that
 * could not be read, is how a screen teaches people to ignore it.
 */
internal enum class StageTwoBuild {
    /** Installed, and it is the build this APK carries. */
    Current,

    /** Installed, and built by a different version of this app. */
    Different,

    /** Nothing is installed, so there is nothing to compare - the flow's install step owns this state. */
    Absent,

    /** One of the two numbers could not be read, so no claim is made either way. */
    Unreadable,
}

/**
 * The two version codes behind [StageTwoBuild], kept so a report can argue with the verdict.
 *
 * The numbers themselves mean nothing to a person, and that is exactly why they are kept: the verdict is
 * this app's conclusion, and "the helper on the phone is a different build" is only checkable against the
 * two codes it was drawn from - the same reason the framework's age and the boot ids are logged rather
 * than only the steps they decide.
 */
internal data class StageTwoBuildReading(val installed: Long?, val bundled: Long?) {
    val verdict: StageTwoBuild get() = stageTwoBuild(installed, bundled)
}

/**
 * The comparison itself, as a pure function so the cases can be tested without two APKs.
 *
 * Equality and not order, which is the one thing about this that is easy to get wrong twice over. A
 * version code is asked to be monotonically increasing so an *upgrade* can be recognised, and that is not
 * the question here: the question is whether the helper on the phone is the code in this APK, and two
 * builds of the same sources answer yes however their codes relate. So a helper built by a *newer* app
 * than the one asking is a disagreement too - and it is the same fix, which is why one step covers both.
 */
internal fun stageTwoBuild(installed: Long?, bundled: Long?): StageTwoBuild = when {
    installed == null -> StageTwoBuild.Absent
    bundled == null -> StageTwoBuild.Unreadable
    installed == bundled -> StageTwoBuild.Current
    else -> StageTwoBuild.Different
}

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

    /**
     * Whether the running Android has restarted since [atMillis], whichever kind of restart it was.
     *
     * The framework's own age answers both at once, and is asked first: a kernel reboot replaces the
     * framework too, so a short framework age covers a hard reboot as well, while a kernel reboot does
     * **not** cover a short framework age. That asymmetry is the whole bug this exists to fix - the
     * flow's two restart steps were measured on the kernel clock, and the restart the flow performs is
     * the daemon's userspace one, which leaves the kernel running and its uptime climbing. The step that
     * asks for a restart could therefore never be satisfied by taking it.
     *
     * When the framework could not be read, the kernel clock is the question instead, and it is the safe
     * half of the pair to fall back to: it can only answer "no restart" where one happened, which asks
     * for a restart that is harmless, rather than answering "restart" where none did, which would skip
     * the reboot the install depends on. Neither reading is trusted as truth - an install that landed
     * under the wrong identity is caught by the uid it runs as, not by either clock.
     */
    fun restartedSince(atMillis: Long?, state: DfrState): Boolean {
        val recorded = atMillis ?: return false
        val frameworkUptime = state.frameworkUptimeMillis
            ?: return rebootedSince(recorded, state.nowMillis, state.uptimeMillis)
        return frameworkUptime < state.nowMillis - recorded
    }

    /** The step the phone is on, given what was observed. */
    fun next(state: DfrState): DfrStep {
        // First, and before the armed check that would otherwise answer Ready: a build whose helper cannot
        // be produced can install nothing, so "rooted without the exploit" is not a state it can be in,
        // and the honest answer is the refusal rather than a step that reads as success. It is also the
        // only field here that does not come from the phone, so nothing about the device can change it.
        // Two refusals and not one, because the two want opposite advice: replace the build, or make room
        // on the phone.
        when (state.helper) {
            DfrHelperAvailability.Ready -> Unit
            DfrHelperAvailability.NotInBuild -> return DfrStep.NoHelper
            DfrHelperAvailability.Unwritable -> return DfrStep.HelperUnwritable
        }
        // A removal waiting on a restart comes before everything below, including the armed check: the key
        // is out of the file this app wrote and still live in the running system, so "Ready" would call a
        // phone finished while the list it boots from is the one it will not use until it starts again.
        //
        // It is answered from the stamp and not from the file, which is the opposite of what this did
        // first. The Package Manager that has not restarted since a removal still holds our key in its
        // memory and rewrites `packages.xml` from it on its own schedule - measured on this device, a
        // removal that had landed was back in the file 45 s later with no restart in between. Requiring the
        // file to still be clean before offering this step therefore sent the one phone that needed it down
        // the ladder, where the only answer left is a reboot that cannot help: the file it boots from has
        // the key in it. So the stamp is what says this phone owes a restart, and the step's own action
        // re-makes the removal under that restart.
        if (state.keyRemovedAtMillis != null && !restartedSince(state.keyRemovedAtMillis, state)) {
            return DfrStep.ApplyRemoval
        }
        // The helper on the phone is not this build's, so the run it would start is another build's run -
        // and this comes before the armed check, which is the one ordering here worth arguing about.
        //
        // Armed means root is live this boot, and "root is live" is not a reason to leave a wrong helper
        // in place: the install is a shell command, and a boot with no root has one only once somebody has
        // started Shizuku - so the moment root is live is the moment this costs nothing. Waiting would
        // leave the wrong copy for the next rerun to fail with, which is the very failure this reading
        // exists to catch.
        //
        // It needs no other condition, which is not obvious: the key and the reboot are settled by the
        // install being a *system* one already - Package Manager gives the uid at install time and never
        // revisits it, so a package that runs as uid 1000 keeps that uid when this app installs over it,
        // whatever state the key is in. What the key and the reboot still owe is the flow's own business
        // two steps down, and re-installing here does not spend either of them.
        if (state.stageTwoIsSystemUid && state.stageTwoBuild == StageTwoBuild.Different) {
            return DfrStep.StaleStageTwo
        }
        // Armed is first because it is the only state that needs nothing: hooks in the kernel this boot
        // mean the flow already completed, whatever any file says about how it started.
        if (state.stageTwoArmed) return DfrStep.Ready
        // The key could not be read, which is not a step in itself: parsing `packages.xml` needs a root
        // shell, and the phone this flow is opened on most often after a reboot is one with no root at all.
        // So the question the missing reading would have answered is asked of the evidence that is already
        // here instead - and only where that evidence decides it. A helper installed as the shared user is
        // proof this app's certificate was in the list when it was installed and that the phone rebooted
        // after it, because Package Manager applies the shared user at install time and never revisits the
        // decision; so the step that follows is to open the helper, or to restart into it, and nothing about
        // the file can change that. Everywhere else the unreadable key is still [DfrStep.ReadState], because
        // a phone with nothing under the shared user and no readable key is a phone this has no evidence
        // about - and a screen that guessed there would offer an inject that refuses because it is done.
        val injected = state.keyInjected ?: return when {
            !state.stageTwoIsSystemUid -> DfrStep.ReadState
            restartedSince(state.installedAtMillis, state) -> DfrStep.OpenStageTwo
            else -> DfrStep.RebootAgain
        }
        if (!injected) return DfrStep.Inject
        // A system uid is proof the reboot happened: Package Manager applies the shared user at install
        // time, and an install before the reboot could only have produced an ordinary app.
        if (state.stageTwoIsSystemUid) {
            return if (restartedSince(state.installedAtMillis, state)) {
                DfrStep.OpenStageTwo
            } else {
                DfrStep.RebootAgain
            }
        }
        // Installed but not privileged: the app went in under a shared user that does not list its key -
        // either before the reboot, or with a different key injected than the APK is signed with. PMS
        // will not re-key an installed package, so this is a removal, not another reboot.
        if (state.stageTwoInstalled) return DfrStep.RemoveStageTwo
        if (!restartedSince(state.injectedAtMillis, state)) return DfrStep.Reboot
        return DfrStep.InstallStageTwo
    }

    /**
     * The state of a refusal, for the caller that can name one before it measures anything.
     *
     * Every field but [DfrState.helper] is written as the emptiest value there is, and none of them is ever
     * read: [next] answers on the first line. They are empty rather than plausible on purpose - a later
     * step that looked at one would find "nothing measured" rather than a device that looks real.
     */
    fun refusalState(helper: DfrHelperAvailability): DfrState = DfrState(
        keyInjected = null,
        injectedAtMillis = null,
        stageTwoInstalled = false,
        stageTwoIsSystemUid = false,
        installedAtMillis = null,
        stageTwoArmed = false,
        // Unreadable rather than Absent: nothing was read, and the refusal answers on the first line -
        // but a field that claimed "nothing is installed" would be a claim this state never made.
        stageTwoBuild = StageTwoBuild.Unreadable,
        helper = helper,
        frameworkUptimeMillis = null,
        nowMillis = 0L,
        uptimeMillis = 0L,
    )


    /**
     * What a clean-up will take off the phone, named from what was measured rather than from the flow's own
     * records.
     *
     * The clean-up is the one button in this flow that writes a file the phone boots from, and it does two
     * unrelated things - takes this app's certificate out of the shared user's past signatures, and removes
     * the helper installed under it - so a confirmation that said "clean up?" would be asking about one
     * write while performing two. The list is what the reading says is there, which also means it can be
     * empty: that is a state worth showing rather than a question worth asking, because nothing would be
     * removed and the screen should say so instead of offering an action that cannot change anything.
     *
     * The key's line has a third answer, for a reading that could not be taken ([keyInjected] is null): the
     * certificate is removed if it is there, and the sentence says so rather than claiming a check that did
     * not happen.
     */
    fun cleanUpRemovals(keyInjected: Boolean?, helperInstalled: Boolean): List<Int> = buildList {
        when (keyInjected) {
            true -> add(R.string.dfr_clean_up_key)
            null -> add(R.string.dfr_clean_up_key_unread)
            false -> Unit
        }
        if (helperInstalled) add(R.string.dfr_clean_up_helper)
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
     * The steps that are not part of that order, because they are not steps forward.
     *
     * [DfrStep.ReadState] is where the flow stops when nothing could be measured,
     * [DfrStep.RemoveStageTwo] undoes an install that landed under the wrong identity,
     * [DfrStep.ApplyRemoval] is a clean-up waiting on the restart that makes it true, and
     * [DfrStep.NoHelper] and [DfrStep.HelperUnwritable] stop it before it starts because this build
     * cannot produce the APK the whole flow exists to install - so none of them has a position, and a
     * screen that numbered them would be claiming progress that has not happened. They are named on their
     * own line instead.
     */
    val detours: List<DfrStep> = listOf(
        DfrStep.NoHelper,
        DfrStep.HelperUnwritable,
        DfrStep.ReadState,
        DfrStep.RemoveStageTwo,
        DfrStep.StaleStageTwo,
        DfrStep.ApplyRemoval,
    )
}
