package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's rule for Shizuku, which is the one place a boot can be held back by a setting.
 *
 * The cases worth writing down are the ones that cannot be tried by hand: a phone whose Shizuku is
 * switched off in settings but present on the device, one that asked for Shizuku with nothing able to
 * start it - the case the wait would spend two minutes discovering - and the one where the payload does
 * not need the transport being waited for at all.
 */
class ShizukuWaitTest {

    @Test
    fun `a run that did not ask for Shizuku waits for nothing`() {
        // Even with Shizuku up and usable: asking is what makes it the run's transport.
        assertEquals(
            ShizukuWait.NotRequested,
            shizukuWait(requested = false, usable = true, startable = true),
        )
    }

    @Test
    fun `a usable Shizuku is taken without waiting`() {
        assertEquals(
            ShizukuWait.Ready,
            shizukuWait(requested = true, usable = true, startable = true),
        )
    }

    @Test
    fun `asking for Shizuku on a device that can start it means hold`() {
        assertEquals(
            ShizukuWait.Await,
            shizukuWait(requested = true, usable = false, startable = true),
        )
    }

    @Test
    fun `asking for Shizuku with no way to start it is refused, not waited for`() {
        // The wait would end by saying this same thing, two minutes later, in front of the one attempt
        // this boot gets.
        assertEquals(
            ShizukuWait.Unstartable,
            shizukuWait(requested = true, usable = false, startable = false),
        )
    }

    @Test
    fun `a usable Shizuku outranks the question of starting one`() {
        // The two readings are not ordered by what the device can do, and a device that cannot start
        // Shizuku is still a device that can use the one already running.
        assertEquals(
            ShizukuWait.Ready,
            shizukuWait(requested = true, usable = true, startable = false),
        )
    }

    // --- what a boot does with that answer, given the payload it is about to run ---------------------

    @Test
    fun `a boot runs a payload that does not need a shell instead of waiting for one`() {
        // The case this rule exists for: Use Shizuku is on, Shizuku is not up, something here could
        // start it - and the payload can carry itself, so the wait is two minutes spent buying a
        // transport the run would not have used anyway.
        assertEquals(
            BootShizukuPlan.WithoutShell,
            bootShizukuPlan(
                requested = true,
                usable = false,
                startable = true,
                shellRequired = false,
            ),
        )
    }

    @Test
    fun `a boot runs a payload that does not need a shell with nothing here able to start one`() {
        // A boot with no Wi-Fi and no root: nothing can bring Shizuku up, and refusing over it would be
        // refusing to install a payload that never needed Shizuku in the first place.
        assertEquals(
            BootShizukuPlan.WithoutShell,
            bootShizukuPlan(
                requested = true,
                usable = false,
                startable = false,
                shellRequired = false,
            ),
        )
    }

    @Test
    fun `waiting and refusing are both answers about getting a shell`() {
        // So neither can be reached by a payload that does not need one, whatever the device looks like.
        listOf(true, false).forEach { startable ->
            listOf(true, false).forEach { usable ->
                val plan = bootShizukuPlan(
                    requested = true,
                    usable = usable,
                    startable = startable,
                    shellRequired = false,
                )
                assertTrue(
                    "usable=$usable startable=$startable gave $plan",
                    plan == BootShizukuPlan.AsAsked || plan == BootShizukuPlan.WithoutShell,
                )
            }
        }
    }

    @Test
    fun `a payload that needs a shell keeps the wait and the refusal`() {
        // The other half of the rule, and the half that must not change: for a payload the app's own
        // process cannot carry, waiting is the only way to get it a transport, and saying so is the only
        // honest answer when there is none.
        assertEquals(
            BootShizukuPlan.Wait,
            bootShizukuPlan(true, usable = false, startable = true, shellRequired = true),
        )
        assertEquals(
            BootShizukuPlan.Unstartable,
            bootShizukuPlan(true, usable = false, startable = false, shellRequired = true),
        )
    }

    @Test
    fun `a usable Shizuku is used whether or not the payload needs one`() {
        // The setting is what the user asked for, and the shell reading only decides what to do when it
        // cannot be honoured - not whether to honour it.
        listOf(true, false).forEach { shellRequired ->
            assertEquals(
                BootShizukuPlan.AsAsked,
                bootShizukuPlan(true, usable = true, startable = false, shellRequired = shellRequired),
            )
        }
    }

    @Test
    fun `a boot that did not ask for Shizuku never goes a different way than asked`() {
        listOf(true, false).forEach { shellRequired ->
            listOf(true, false).forEach { usable ->
                assertEquals(
                    BootShizukuPlan.AsAsked,
                    bootShizukuPlan(
                        requested = false,
                        usable = usable,
                        startable = true,
                        shellRequired = shellRequired,
                    ),
                )
            }
        }
    }

    // --- which of the device's two payloads answers that question ------------------------------------

    @Test
    fun `a payload that is there needs no shell of its own`() {
        // The feed's own field for this went with the rest of its policy: the official app has no such field,
        // so nothing here asks for a shell a profile wanted. Which of the two descriptors answers is still
        // the caller's rule, and this is it.
        assertFalse(bootPayloadNeedsShell(preferAttempted = true, attempted = payload(), cached = null))
        assertFalse(bootPayloadNeedsShell(preferAttempted = false, attempted = null, cached = payload()))
    }

    @Test
    fun `a cached timer payload needs a shell`() {
        // The timer payload calibrates through tracefs. Its cache entry predates the optional cve field,
        // so the profile id is what keeps it out of the app-process transport.
        val timer = payload().copy(profileId = "pa3q-S938U1UEUDDZJB-cve-2026-98260-ksun")
        assertTrue(bootPayloadNeedsShell(preferAttempted = true, attempted = timer, cached = null))
        assertTrue(bootPayloadNeedsShell(preferAttempted = false, attempted = null, cached = timer))
    }

    @Test
    fun `a device with no payload at all keeps the wait`() {
        // The conservative default, and what keeps this from reaching a boot nobody can describe: no cache
        // and no attempt means the old behaviour, which is to wait and then say what happened.
        assertTrue(bootPayloadNeedsShell(preferAttempted = true, attempted = null, cached = null))
        assertTrue(bootPayloadNeedsShell(preferAttempted = false, attempted = null, cached = null))
    }
}

/** A cached descriptor, with nothing in it that a policy could have chosen. */
private fun payload() = CachedPayload(
    id = knownGoodId("a".repeat(64), "b".repeat(64), "c".repeat(64)),
    profileId = "pa3q-kernelsu-next-6.6.98",
    displayName = "Galaxy S25 kernel 6.6.98 (KernelSU-Next)",
    models = listOf("SM-S938U1"),
    kernelVersions = listOf("6.6.98"),
    exploit = RemoteArtifact(url = "https://example.invalid/exploit.so", size = 64, verifySize = true),
    kernelSu = RemoteArtifact(url = "https://example.invalid/ksud", size = 48, verifySize = true),
    helperSha256 = "d".repeat(64),
    helperSize = 2048L,
)
