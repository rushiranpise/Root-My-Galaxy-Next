package dev.busung.s25uroot.dfr

import dev.busung.s25uroot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flow's order, held to the cases that actually happen.
 *
 * The point of writing this down is that four of these states are indistinguishable from a screen that
 * only shows a button: an install that landed as an ordinary app looks like an install, and a reboot that
 * has not happened looks like one that has. Each test here is one of those, named for what it would look
 * like if it were wrong.
 */
class DfrFlowTest {

    private val now = 1_000_000_000L

    /** Nothing observed yet: no armed hooks, no key, nothing installed. */
    private fun fresh(
        keyInjected: Boolean? = false,
        injectedAtMillis: Long? = null,
        stageTwoInstalled: Boolean = false,
        stageTwoIsSystemUid: Boolean = false,
        installedAtMillis: Long? = null,
        keyRemovedAtMillis: Long? = null,
        stageTwoArmed: Boolean = false,
        stageTwoBuild: StageTwoBuild = StageTwoBuild.Unreadable,
        helper: DfrHelperAvailability = DfrHelperAvailability.Ready,
        frameworkUptimeMillis: Long? = null,
        uptimeMillis: Long = 4 * 60 * 60 * 1000L,
    ) = DfrState(
        keyInjected = keyInjected,
        injectedAtMillis = injectedAtMillis,
        stageTwoInstalled = stageTwoInstalled,
        stageTwoIsSystemUid = stageTwoIsSystemUid,
        installedAtMillis = installedAtMillis,
        keyRemovedAtMillis = keyRemovedAtMillis,
        stageTwoArmed = stageTwoArmed,
        stageTwoBuild = stageTwoBuild,
        helper = helper,
        frameworkUptimeMillis = frameworkUptimeMillis,
        nowMillis = now,
        uptimeMillis = uptimeMillis,
    )

    @Test
    fun `a helper built by another version of this app is replaced rather than opened`() {
        // The failure this whole reading exists for: a helper from another build runs another build's
        // shellcode, and the run it starts fails inside the exploit - which reads as the exploit having
        // failed, on a phone whose helper installed cleanly and is exactly the system app it says it is.
        assertEquals(
            DfrStep.StaleStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 10 * 60 * 1000L,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 5 * 60 * 1000L,
                    stageTwoBuild = StageTwoBuild.Different,
                ),
            ),
        )
    }

    @Test
    fun `a stale helper is replaced while root is still live`() {
        // The one ordering worth arguing about: the hooks are in this boot's kernel, so the flow used to
        // answer Ready and offer nothing - and Ready is exactly the state that could still fix the helper,
        // because installing it is a shell command and the next reboot leaves no shell at all.
        assertEquals(
            DfrStep.StaleStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 10 * 60 * 1000L,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 5 * 60 * 1000L,
                    stageTwoArmed = true,
                    stageTwoBuild = StageTwoBuild.Different,
                ),
            ),
        )
    }

    @Test
    fun `the helper this app ships is opened the way it always was`() {
        assertEquals(
            DfrStep.OpenStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 10 * 60 * 1000L,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 5 * 60 * 1000L,
                    stageTwoBuild = StageTwoBuild.Current,
                    // Restarted since that install: a phone whose framework is a minute old, against an
                    // install five minutes ago.
                    frameworkUptimeMillis = 60_000L,
                ),
            ),
        )
    }

    @Test
    fun `a comparison nobody could make changes nothing`() {
        // Both unanswerable cases at once, because they have to behave the same way: a build that could
        // not be read and one that is not installed at all are the same silence here, and neither may turn
        // into "install this one" over a number nobody read.
        val samePhone = fresh(
            keyInjected = true,
            injectedAtMillis = now - 10 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 5 * 60 * 1000L,
        )
        val restarted = samePhone.copy(frameworkUptimeMillis = 60_000L)
        assertEquals(DfrStep.OpenStageTwo, DfrFlow.next(restarted.copy(stageTwoBuild = StageTwoBuild.Unreadable)))
        assertEquals(DfrStep.OpenStageTwo, DfrFlow.next(restarted.copy(stageTwoBuild = StageTwoBuild.Absent)))
    }

    @Test
    fun `an install that landed as an ordinary app is still removed before anything else`() {
        // The identity problem dominates: Package Manager assigns the uid when a package is installed and
        // never revisits it, so installing over an ordinary-app copy could not make it a system app - the
        // removal is what the step sequence is for, and the build comparison waits behind it.
        assertEquals(
            DfrStep.RemoveStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 10 * 60 * 1000L,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = false,
                    installedAtMillis = now - 5 * 60 * 1000L,
                    stageTwoBuild = StageTwoBuild.Different,
                ),
            ),
        )
    }

    @Test
    fun `the comparison is equality, and the codes are never ordered`() {
        // A version code is conventionally asked to go up, which is not the question: the question is
        // whether the helper on the phone is the code in this APK. A build by a *newer* app is the same
        // fix as one by an older one, and a number that could not be read is no fix at all.
        assertEquals(StageTwoBuild.Absent, stageTwoBuild(installed = null, bundled = 42L))
        assertEquals(StageTwoBuild.Unreadable, stageTwoBuild(installed = 42L, bundled = null))
        assertEquals(StageTwoBuild.Current, stageTwoBuild(installed = 42L, bundled = 42L))
        assertEquals(StageTwoBuild.Different, stageTwoBuild(installed = 41L, bundled = 42L))
        assertEquals(StageTwoBuild.Different, stageTwoBuild(installed = 43L, bundled = 42L))
        // Nothing installed answers before the second number is even asked about: there is nothing on the
        // phone to disagree with, and the flow's own install step is the one that owns this state.
        assertEquals(StageTwoBuild.Absent, stageTwoBuild(installed = null, bundled = null))
    }

    @Test
    fun `the reading of both builds is what the verdict is drawn from`() {
        assertEquals(StageTwoBuild.Current, StageTwoBuildReading(1L, 1L).verdict)
        assertEquals(StageTwoBuild.Different, StageTwoBuildReading(1L, 2L).verdict)
        assertEquals(StageTwoBuild.Absent, StageTwoBuildReading(null, 2L).verdict)
        assertEquals(StageTwoBuild.Unreadable, StageTwoBuildReading(1L, null).verdict)
    }

    @Test
    fun `a build with no helper refuses on a fresh device`() {
        // The refusal the screen shows before it opens a shell: there is nothing to inject a key for.
        assertEquals(DfrStep.NoHelper, DfrFlow.next(fresh(helper = DfrHelperAvailability.NotInBuild)))
    }

    @Test
    fun `a helper that could not be unpacked is its own refusal`() {
        // Not [DfrStep.NoHelper], because the two send somebody to different places: one is a build to
        // replace, and this one is a phone with no room, where reading again after freeing some is the
        // action that works. One sentence for both would tell a full disk that its APK is missing.
        assertEquals(
            DfrStep.HelperUnwritable,
            DfrFlow.next(fresh(helper = DfrHelperAvailability.Unwritable)),
        )
        assertNotEquals(
            DfrFlow.next(fresh(helper = DfrHelperAvailability.NotInBuild)),
            DfrFlow.next(fresh(helper = DfrHelperAvailability.Unwritable)),
        )
    }

    @Test
    fun `both refusals are reached before the phone is asked anything, and by name`() {
        // The state the screen builds without measuring: every other field is empty, so a step that
        // depended on one of them could not be reached this way - which is the point of the two refusals
        // being decided before the first shell command.
        assertEquals(
            DfrStep.NoHelper,
            DfrFlow.next(DfrFlow.refusalState(DfrHelperAvailability.NotInBuild)),
        )
        assertEquals(
            DfrStep.HelperUnwritable,
            DfrFlow.next(DfrFlow.refusalState(DfrHelperAvailability.Unwritable)),
        )
        // And a refused build never reads as finished whatever the phone looks like: the reason alone is
        // what the answer rests on.
        assertEquals(
            DfrStep.HelperUnwritable,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 2 * 60 * 60 * 1000L,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 30 * 60 * 1000L,
                    stageTwoArmed = true,
                    helper = DfrHelperAvailability.Unwritable,
                    uptimeMillis = 5 * 60 * 1000L,
                ),
            ),
        )
    }

    @Test
    fun `a build with no helper refuses on a phone that looks finished`() {
        // The case that makes this a refusal rather than a hint: an armed kernel is otherwise Ready, and
        // a system-uid install is otherwise the end of the flow. Neither can be reached from here,
        // because the helper is the APK doing the asking and not a reading of the phone.
        val armed = fresh(stageTwoArmed = true, helper = DfrHelperAvailability.NotInBuild)
        assertEquals(DfrStep.NoHelper, DfrFlow.next(armed))
        val finished = fresh(
            keyInjected = true,
            injectedAtMillis = now - 2 * 60 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 30 * 60 * 1000L,
            helper = DfrHelperAvailability.NotInBuild,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.NoHelper, DfrFlow.next(finished))
    }

    @Test
    fun `the state the screen builds without a helper is the refusal`() {
        // What the screen asks for instead of deciding: it can name this state before it measures
        // anything, because the helper is a file in this app's own assets.
        assertEquals(DfrStep.NoHelper, DfrFlow.next(DfrFlow.refusalState(DfrHelperAvailability.NotInBuild)))
    }

    @Test
    fun `a stamp from a previous boot is read as a reboot that already happened`() {
        // Correct for an inject that really did happen before this boot, and identical to how an inject
        // from *this* boot looks when nothing moves the stamp. The app moves the stamp when the action
        // runs, whatever the command's output says - this is the case that makes that necessary, and it
        // is why the reboot step can go missing rather than being asked for twice.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.InstallStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `a stale install stamp is read as the second reboot having happened`() {
        // The same hazard one step later: a system-uid install whose stamp is older than this boot is
        // sent straight to opening the helper, so the restart that makes the shared user take effect is
        // never asked for.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 20 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.OpenStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `a missing helper is not the same detour as a device that could not be read`() {
        // Both are detours and both stop the flow, but they are answered by different fields: one by a
        // file in this app, the other by a shell that said nothing. A screen that read them as one state
        // would send somebody to find a root shell for a problem that no shell can fix.
        assertEquals(
            DfrStep.NoHelper,
            DfrFlow.next(fresh(helper = DfrHelperAvailability.NotInBuild, keyInjected = null)),
        )
        assertEquals(DfrStep.ReadState, DfrFlow.next(fresh(keyInjected = null)))
    }

    @Test
    fun `a fresh device is asked to inject`() {
        assertEquals(DfrStep.Inject, DfrFlow.next(fresh()))
    }

    @Test
    fun `an unreadable state asks to be read rather than guessing`() {
        // No shell answered, so nothing was observed. Offering the inject here would be offering the one
        // action that refuses when it has already been done.
        assertEquals(DfrStep.ReadState, DfrFlow.next(fresh(keyInjected = null)))
    }

    @Test
    fun `a phone with no root and a helper under the shared user is opened rather than sent to read again`() {
        // The boot this flow is opened on most often: no root at all, so `packages.xml` cannot be parsed and
        // the key reading comes back null. The helper's own uid is the evidence that outranks it - Package
        // Manager gives a package the shared user at install time and never revisits it, so a system uid
        // could not have been reached before this app's certificate was in the list or before the reboot the
        // list is read at. Read first, this phone was sent to a "read again" that can never answer, because
        // the reading it waits for is the one a phone with no root cannot take.
        assertEquals(
            DfrStep.OpenStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = null,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 10 * 60 * 1000L,
                    frameworkUptimeMillis = 60 * 1000L,
                ),
            ),
        )
    }

    @Test
    fun `the missing key reading still stops the flow where nothing else is evidence`() {
        // The exception is the system uid and nothing wider: an ordinary install, or no install at all,
        // leaves the flow with no evidence about the file, and guessing there is what the step exists for.
        assertEquals(
            DfrStep.ReadState,
            DfrFlow.next(fresh(keyInjected = null, stageTwoInstalled = true)),
        )
        assertEquals(DfrStep.ReadState, DfrFlow.next(fresh(keyInjected = null)))
    }

    @Test
    fun `an unreadable key does not stand in for the restart either`() {
        // A system-uid helper installed inside the running framework is still waiting for the restart the
        // install depends on, and the key being unreadable says nothing about that. Restarting is the safe
        // answer: it costs a minute, and the alternative is opening a helper Package Manager has not yet
        // been told about.
        assertEquals(
            DfrStep.RebootAgain,
            DfrFlow.next(
                fresh(
                    keyInjected = null,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 10 * 60 * 1000L,
                    frameworkUptimeMillis = 60 * 60 * 1000L,
                ),
            ),
        )
    }

    @Test
    fun `a key that could be read is not second-guessed`() {
        // The exception is the *unreadable* key and only that one: a file that was parsed and has no such
        // certificate is still an inject, however convincing the helper's uid looks.
        assertEquals(
            DfrStep.Inject,
            DfrFlow.next(
                fresh(
                    keyInjected = false,
                    stageTwoInstalled = true,
                    stageTwoIsSystemUid = true,
                    installedAtMillis = now - 10 * 60 * 1000L,
                ),
            ),
        )
    }

    @Test
    fun `an inject with no reboot after it asks for the reboot`() {
        val state = fresh(keyInjected = true, injectedAtMillis = now - 60_000)
        // Up for four hours and injected a minute ago: the phone has not restarted since.
        assertEquals(DfrStep.Reboot, DfrFlow.next(state))
    }

    @Test
    fun `a reboot after the inject moves the flow on to the install`() {
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.InstallStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `the restart the flow performs is a userspace one, and the kernel clock cannot see it`() {
        // The bug this flow shipped with, in the numbers the phone reported: the kernel had been up
        // 7804 s, the framework 2558 s, and the inject was 3679 s ago - so the restart had happened, the
        // framework had started after the inject, and the kernel's own uptime said "not since" for ever.
        // The step that asks for a restart could not be satisfied by taking it.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 3_679_000L,
            frameworkUptimeMillis = 2_558_110L,
            uptimeMillis = 7_804_000L,
        )
        assertEquals(DfrStep.InstallStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `a framework that started before the inject is not a restart`() {
        // The other half of the same reading, and the one that keeps the gate honest: a framework older
        // than the inject has not restarted since it, whatever the kernel's uptime says.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            frameworkUptimeMillis = 4 * 60 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.Reboot, DfrFlow.next(state))
    }

    @Test
    fun `an unreadable framework leaves the kernel clock answering`() {
        // The fallback, and the direction it is safe in: with no framework age the rule is the kernel's
        // own, which can only fail to notice a restart - asking for one that costs fifteen seconds -
        // rather than inventing one and skipping the reboot the install depends on.
        assertEquals(
            DfrStep.Reboot,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 60_000L,
                    frameworkUptimeMillis = null,
                    uptimeMillis = 4 * 60 * 60 * 1000L,
                ),
            ),
        )
        assertEquals(
            DfrStep.InstallStageTwo,
            DfrFlow.next(
                fresh(
                    keyInjected = true,
                    injectedAtMillis = now - 30 * 60 * 1000L,
                    frameworkUptimeMillis = null,
                    uptimeMillis = 5 * 60 * 1000L,
                ),
            ),
        )
    }

    @Test
    fun `the second restart is satisfied by a userspace restart as well`() {
        // Both restart steps rest on the same reading, so the one after the install cannot be stuck on
        // the kernel clock either - that is the same bug one rung further down the ladder.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 2 * 60 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 30 * 60 * 1000L,
            frameworkUptimeMillis = 5 * 60 * 1000L,
            uptimeMillis = 4 * 60 * 60 * 1000L,
        )
        assertEquals(DfrStep.OpenStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `a removal waiting on a restart is applied by a userspace restart too`() {
        // The third place the same clock was asked, and the one with the worst failure: a clean-up whose
        // restart never counted would keep offering the restart instead of the inject that comes next.
        val applied = fresh(
            keyInjected = false,
            keyRemovedAtMillis = now - 60_000L,
            frameworkUptimeMillis = 10_000L,
            uptimeMillis = 4 * 60 * 60 * 1000L,
        )
        assertEquals(DfrStep.Inject, DfrFlow.next(applied))
        assertNotEquals(DfrStep.ApplyRemoval, DfrFlow.next(applied))
    }

    @Test
    fun `an install that landed as an ordinary app asks to be removed`() {
        // The failure this model exists for: installed, working, and not privileged. Another reboot
        // changes nothing, because Package Manager does not re-key a package it has already installed.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = false,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.RemoveStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `a system-uid install with no reboot after it asks for the second reboot`() {
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 60_000,
            uptimeMillis = 20 * 60 * 1000L,
        )
        assertEquals(DfrStep.RebootAgain, DfrFlow.next(state))
    }

    @Test
    fun `a system-uid install after a reboot is ready to be opened`() {
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 2 * 60 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 30 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.OpenStageTwo, DfrFlow.next(state))
    }

    @Test
    fun `armed hooks are the end of the flow whatever else is true`() {
        // The kernel is the authority on whether the exploit ran, and it outranks every file: a device
        // whose packages.xml was restored or whose app was reinstalled still has the hooks.
        val armed = fresh(keyInjected = null, stageTwoArmed = true)
        assertEquals(DfrStep.Ready, DfrFlow.next(armed))
    }

    @Test
    fun `a removal that has not been applied asks for the restart, not the inject`() {
        // The state a clean-up leaves on this boot: the key is out of the file, and the Package Manager
        // that started before the change is still holding it. Offering the inject here is the mistake this
        // test is named for - it would re-add the key the user just removed, and the running copy's own
        // next rewrite of packages.xml would put it back anyway.
        val afterCleanUp = fresh(keyRemovedAtMillis = now - 60_000)
        assertEquals(DfrStep.ApplyRemoval, DfrFlow.next(afterCleanUp))
        assertNotEquals(DfrStep.Inject, DfrFlow.next(afterCleanUp))
        assertNotEquals(
            "a running exploit does not make a pending removal applied",
            DfrStep.Ready,
            DfrFlow.next(fresh(keyRemovedAtMillis = now - 60_000, stageTwoArmed = true)),
        )
    }

    @Test
    fun `once the phone restarts, the removal is done and the ladder starts again`() {
        // The removal happened before this boot: uptime is shorter than the elapsed time, so nothing is
        // waiting - and the phone is genuinely uninjected, which is step one.
        val rebooted = fresh(
            keyRemovedAtMillis = now - 30 * 60 * 1000L,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.Inject, DfrFlow.next(rebooted))
    }

    @Test
    fun `a key that came back after a removal still owes its restart`() {
        // The measurement this rule comes from: a clean-up wrote packages.xml without our key at 00:08:53
        // and Package Manager's own rewrite had it back at 00:09:38, with no restart in between - which is
        // why the file is the one thing here a running Package Manager can undo, and the stamp is the
        // record that survives it. The phone owes the restart, and the step re-makes the removal under it.
        // Falling through to the ladder instead is how the screen came to offer a reboot that could not
        // help, because the file it would boot from had the key in it.
        val back = fresh(keyInjected = true, keyRemovedAtMillis = now - 60_000)
        assertEquals(DfrStep.ApplyRemoval, DfrFlow.next(back))
        assertNotEquals(DfrStep.Reboot, DfrFlow.next(back))
    }

    @Test
    fun `an install with no record of when it happened is treated as needing the reboot`() {
        // The install was done by hand, or by a build of this app that did not keep a record. Assuming a
        // reboot that cannot be shown would skip the step the install depends on.
        val state = fresh(
            keyInjected = true,
            injectedAtMillis = now - 30 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = null,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.RebootAgain, DfrFlow.next(state))
    }

    @Test
    fun `an inject with no record of when it happened asks for the reboot`() {
        val state = fresh(keyInjected = true, injectedAtMillis = null)
        assertEquals(DfrStep.Reboot, DfrFlow.next(state))
    }

    @Test
    fun `a framework younger than the elapsed time is what a restart looks like`() {
        // The rule [DfrFlow.next] uses, tested where it is defined: it is the kernel's own rule with the
        // framework's age in place of the kernel's uptime, and it answers for both kinds of restart.
        val restarted = fresh(keyInjected = true, injectedAtMillis = now - 60_000L)
        assertTrue(DfrFlow.restartedSince(now - 60_000L, restarted.copy(frameworkUptimeMillis = 5_000L)))
        assertFalse(
            DfrFlow.restartedSince(
                now - 60_000L,
                restarted.copy(frameworkUptimeMillis = 4 * 60 * 60 * 1000L),
            ),
        )
        assertFalse("no record of the action at all is not a restart", DfrFlow.restartedSince(null, restarted))
    }

    @Test
    fun `uptime below the elapsed time is what a reboot looks like`() {
        // The rule the two reboot steps rest on, tested where it is defined rather than through a state.
        assertEquals(true, DfrFlow.rebootedSince(now - 60_000, now, 5_000))
        assertEquals(false, DfrFlow.rebootedSince(now - 60_000, now, 4 * 60 * 60 * 1000L))
        assertEquals(false, DfrFlow.rebootedSince(null, now, 5_000))
    }

    @Test
    fun `the clean-up confirmation names both things it removes, not just one`() {
        // The button writes a file the phone boots from and does two unrelated things, so the question it
        // asks has to be about both. Key first, because that is the one with a restart behind it.
        assertEquals(
            listOf(R.string.dfr_clean_up_key, R.string.dfr_clean_up_helper),
            DfrFlow.cleanUpRemovals(keyInjected = true, helperInstalled = true),
        )
        assertEquals(
            "only the certificate is there, so only it is named",
            listOf(R.string.dfr_clean_up_key),
            DfrFlow.cleanUpRemovals(keyInjected = true, helperInstalled = false),
        )
        assertEquals(
            "and only the helper, on a phone whose key is out",
            listOf(R.string.dfr_clean_up_helper),
            DfrFlow.cleanUpRemovals(keyInjected = false, helperInstalled = true),
        )
    }

    @Test
    fun `a clean-up with nothing to remove says so instead of asking`() {
        // An empty list is the screen's cue to show the sentence and one Close, rather than a Remove for a
        // run that would change nothing - the same reason the inject step is not offered on a build that
        // carries no helper.
        assertTrue(DfrFlow.cleanUpRemovals(keyInjected = false, helperInstalled = false).isEmpty())
    }

    @Test
    fun `an unreadable check is named as such rather than as a removal`() {
        // The one state where the certificate may be there and cannot be confirmed: the sentence has to
        // say it removes it *if* it is, because a confirmation for a check that did not happen would be
        // the app claiming a measurement it does not have.
        val unread = DfrFlow.cleanUpRemovals(keyInjected = null, helperInstalled = false)
        assertEquals(listOf(R.string.dfr_clean_up_key_unread), unread)
        assertFalse(
            "the unread case must not borrow the wording of a confirmed one",
            unread.contains(R.string.dfr_clean_up_key),
        )
    }

    @Test
    fun `an uptime of zero is read as every reboot having happened`() {
        // The shape that made this flow skip its own reboot steps on a real phone: uptime came back zero
        // because /proc/uptime is denied to an app domain, and zero is shorter than every elapsed time, so
        // each recorded instant looked like it predated a reboot. The clock is fixed; the shape is worth
        // pinning, because a measurement that fails to zero is the one failure that inverts this rule.
        assertEquals(true, DfrFlow.rebootedSince(now - 60_000, now, 0))
        assertEquals(DfrStep.InstallStageTwo, DfrFlow.next(
            fresh(keyInjected = true, injectedAtMillis = now - 60_000, uptimeMillis = 0),
        ))
    }

    @Test
    fun `every step the flow can reach is on the screen's list, or one of the two detours`() {
        // A step the model returns but the screen cannot draw would be a state with nothing to show for a
        // phone it had just measured - and the reverse, a row for a step no state reaches, is a screen
        // with a rung on a ladder that does not exist.
        val reachable = listOf(
            fresh(),
            fresh(keyInjected = null),
            fresh(keyInjected = true, injectedAtMillis = now - 60_000),
            // Injected and rebooted, nothing installed: the install step.
            fresh(
                keyInjected = true,
                injectedAtMillis = now - 30 * 60 * 1000L,
                uptimeMillis = 5 * 60 * 1000L,
            ),
            fresh(
                keyInjected = true,
                injectedAtMillis = now - 30 * 60 * 1000L,
                stageTwoInstalled = true,
                uptimeMillis = 5 * 60 * 1000L,
            ),
            fresh(
                keyInjected = true,
                injectedAtMillis = now - 30 * 60 * 1000L,
                stageTwoInstalled = true,
                stageTwoIsSystemUid = true,
                installedAtMillis = now - 60_000,
                uptimeMillis = 20 * 60 * 1000L,
            ),
            fresh(
                keyInjected = true,
                injectedAtMillis = now - 2 * 60 * 60 * 1000L,
                stageTwoInstalled = true,
                stageTwoIsSystemUid = true,
                installedAtMillis = now - 30 * 60 * 1000L,
                uptimeMillis = 5 * 60 * 1000L,
            ),
            fresh(stageTwoArmed = true),
            // The helper on the phone is another build's: installed as a system app, and not the one this
            // APK carries. Reached from the armed state above as well, which is the ordering that matters.
            fresh(
                keyInjected = true,
                injectedAtMillis = now - 30 * 60 * 1000L,
                stageTwoInstalled = true,
                stageTwoIsSystemUid = true,
                installedAtMillis = now - 30 * 60 * 1000L,
                stageTwoBuild = StageTwoBuild.Different,
                uptimeMillis = 5 * 60 * 1000L,
            ),
            // A clean-up that changed the file on this boot: no key in packages.xml, a removal waiting on
            // the restart that makes it true.
            fresh(keyRemovedAtMillis = now - 60_000),
            // A build whose assets have no helper in them, which is the one state here that is not a
            // reading of the phone.
            fresh(helper = DfrHelperAvailability.NotInBuild),
            fresh(helper = DfrHelperAvailability.Unwritable),
            DfrFlow.refusalState(DfrHelperAvailability.NotInBuild),
            DfrFlow.refusalState(DfrHelperAvailability.Unwritable),
        ).map(DfrFlow::next).toSet()

        assertEquals(
            "the flow returns a step that is neither on the list nor a named detour, so nothing on " +
                "the screen can say which step the phone is on",
            emptySet<DfrStep>(),
            reachable - DfrFlow.order.toSet() - DfrFlow.detours.toSet(),
        )
        assertEquals(
            "the list carries a step no state reaches",
            emptySet<DfrStep>(),
            DfrFlow.order.toSet() - reachable,
        )
        assertEquals(
            "a detour is named but never reached, so the screen has copy for a state that cannot happen",
            emptySet<DfrStep>(),
            DfrFlow.detours.toSet() - reachable,
        )
    }
}
