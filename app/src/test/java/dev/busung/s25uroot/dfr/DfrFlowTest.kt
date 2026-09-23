package dev.busung.s25uroot.dfr

import org.junit.Assert.assertEquals
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
        stageTwoArmed: Boolean = false,
        helperPresent: Boolean = true,
        uptimeMillis: Long = 4 * 60 * 60 * 1000L,
    ) = DfrState(
        keyInjected = keyInjected,
        injectedAtMillis = injectedAtMillis,
        stageTwoInstalled = stageTwoInstalled,
        stageTwoIsSystemUid = stageTwoIsSystemUid,
        installedAtMillis = installedAtMillis,
        stageTwoArmed = stageTwoArmed,
        helperPresent = helperPresent,
        nowMillis = now,
        uptimeMillis = uptimeMillis,
    )

    @Test
    fun `a build with no helper refuses on a fresh device`() {
        // The refusal the screen shows before it opens a shell: there is nothing to inject a key for.
        assertEquals(DfrStep.NoHelper, DfrFlow.next(fresh(helperPresent = false)))
    }

    @Test
    fun `a build with no helper refuses on a phone that looks finished`() {
        // The case that makes this a refusal rather than a hint: an armed kernel is otherwise Ready, and
        // a system-uid install is otherwise the end of the flow. Neither can be reached from here,
        // because the helper is the APK doing the asking and not a reading of the phone.
        val armed = fresh(stageTwoArmed = true, helperPresent = false)
        assertEquals(DfrStep.NoHelper, DfrFlow.next(armed))
        val finished = fresh(
            keyInjected = true,
            injectedAtMillis = now - 2 * 60 * 60 * 1000L,
            stageTwoInstalled = true,
            stageTwoIsSystemUid = true,
            installedAtMillis = now - 30 * 60 * 1000L,
            helperPresent = false,
            uptimeMillis = 5 * 60 * 1000L,
        )
        assertEquals(DfrStep.NoHelper, DfrFlow.next(finished))
    }

    @Test
    fun `the state the screen builds without a helper is the refusal`() {
        // What the screen asks for instead of deciding: it can name this state before it measures
        // anything, because the helper is a file in this app's own assets.
        assertEquals(DfrStep.NoHelper, DfrFlow.next(DfrFlow.noHelperState()))
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
        assertEquals(DfrStep.NoHelper, DfrFlow.next(fresh(helperPresent = false, keyInjected = null)))
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
    fun `uptime below the elapsed time is what a reboot looks like`() {
        // The rule the two reboot steps rest on, tested where it is defined rather than through a state.
        assertEquals(true, DfrFlow.rebootedSince(now - 60_000, now, 5_000))
        assertEquals(false, DfrFlow.rebootedSince(now - 60_000, now, 4 * 60 * 60 * 1000L))
        assertEquals(false, DfrFlow.rebootedSince(null, now, 5_000))
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
            // A build whose assets have no helper in them, which is the one state here that is not a
            // reading of the phone.
            fresh(helperPresent = false),
            DfrFlow.noHelperState(),
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
