package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val THIS_BOOT = "0f2a4c6e-1b2d-4f6a-8c0e-2d4f6a8c0e2d"
private const val LAST_BOOT = "9e8d7c6b-5a49-4837-2615-0403f2e1d0c9"

/**
 * The reroot-at-boot rule, and the three things the service around it must not get wrong.
 *
 * The decision itself is pure and is tested as one: a boot with no root has a single `am start` it may make,
 * and every reason not to make it is a different sentence in a notification nobody asked for. The service
 * assertions below are the parts of that path that a device is needed to see: where the boot's one attempt
 * is spent, which shell does the launching, and that the gate the receiver starts is reachable at all.
 */
class DfrBootTest {

    private fun decide(
        enabled: Boolean = true,
        kernelSuActive: Boolean = false,
        attemptedBootToken: String? = null,
        bootToken: String = THIS_BOOT,
        helper: DfrHelperStanding = DfrHelperStanding.System,
        armed: Boolean? = false,
    ) = dfrBootDecision(
        enabled = enabled,
        kernelSuActive = kernelSuActive,
        attemptedBootToken = attemptedBootToken,
        bootToken = bootToken,
        helper = helper,
        armed = armed,
    )

    @Test
    fun `a boot with no root, a system helper and a clean kernel is rerooted`() {
        assertEquals(DfrBootDecision.Reroot, decide())
    }

    @Test
    fun `the setting outranks everything a boot could be`() {
        // Including the readings that would otherwise be sentences of their own: a boot nobody asked for
        // help on is not a boot to report a missing helper on either.
        assertEquals(DfrBootDecision.SkipDisabled, decide(enabled = false))
        assertEquals(
            DfrBootDecision.SkipDisabled,
            decide(enabled = false, helper = DfrHelperStanding.Missing, armed = null),
        )
        assertEquals(
            DfrBootDecision.SkipDisabled,
            decide(enabled = false, kernelSuActive = true, attemptedBootToken = THIS_BOOT),
        )
    }

    @Test
    fun `root already in the kernel is left alone`() {
        // The userspace-restart case as much as the rooted one: BOOT_COMPLETED arrives again, KernelSU is
        // still in the kernel, and starting the exploit against it would be a second patch of one kernel.
        assertEquals(DfrBootDecision.SkipAlreadyRooted, decide(kernelSuActive = true))
    }

    @Test
    fun `one attempt per boot, keyed by the boot id`() {
        assertEquals(DfrBootDecision.SkipAttempted, decide(attemptedBootToken = THIS_BOOT))
        // Another boot's attempt is another boot's: this is exactly the case that has to keep working, or
        // the feature works once and then never again.
        assertEquals(DfrBootDecision.Reroot, decide(attemptedBootToken = LAST_BOOT))
    }

    @Test
    fun `the attempt is spent before the helper is looked at`() {
        // The order is the answer: a boot that has already run is over whatever the phone's packages look
        // like now - the helper could have been uninstalled between the run and this reading.
        assertEquals(
            DfrBootDecision.SkipAttempted,
            decide(attemptedBootToken = THIS_BOOT, helper = DfrHelperStanding.Missing, armed = null),
        )
    }

    @Test
    fun `no helper is a sentence about the setup, before anything about the kernel`() {
        assertEquals(DfrBootDecision.NoHelper, decide(helper = DfrHelperStanding.Missing))
        // Armed as well: an armed kernel and a missing helper is a phone with two problems, and the helper
        // is the one the user can act on - so it is the one named.
        assertEquals(
            DfrBootDecision.NoHelper,
            decide(helper = DfrHelperStanding.Missing, armed = true),
        )
    }

    @Test
    fun `a helper that is not the shared user is not a helper the exploit can run in`() {
        assertEquals(DfrBootDecision.NotSystemUid, decide(helper = DfrHelperStanding.Ordinary))
        assertEquals(
            DfrBootDecision.NotSystemUid,
            decide(helper = DfrHelperStanding.Ordinary, armed = true),
        )
    }

    @Test
    fun `another build's helper is refused rather than run`() {
        // At boot there is nobody to notice a failure that looks like the exploit's fault, which is what a
        // helper from another app version produces: its shellcode, its arguments, its bugs.
        assertEquals(DfrBootDecision.StaleHelper, decide(helper = DfrHelperStanding.Stale))
        assertEquals(
            DfrBootDecision.StaleHelper,
            decide(helper = DfrHelperStanding.Stale, armed = true),
        )
    }

    @Test
    fun `no shell is the last refusal, and it is the only one a later minute can turn into a run`() {
        // Null is "no shell could be asked", which is also the answer to "is there a shell to start the
        // helper with" - one field carrying both, and this is the assertion that they stay one thing.
        assertEquals(DfrBootDecision.NoShell, decide(armed = null))
        // The helper's standing and the kernel's state are all fine here: nothing but the transport is
        // missing, which is what makes the wait and the notification's own retry worth having.
        assertEquals(DfrBootDecision.NoShell, decide(armed = null, attemptedBootToken = LAST_BOOT))
    }

    @Test
    fun `an armed kernel is the one state a rerun cannot fix, so it is said rather than skipped`() {
        assertEquals(DfrBootDecision.SkipArmed, decide(armed = true))
        assertEquals(DfrBootDecision.SkipArmed, decide(armed = true, attemptedBootToken = LAST_BOOT))
    }

    @Test
    fun `the helper is started by exactly one decision`() {
        assertEquals(
            listOf(DfrBootDecision.Reroot),
            enumValues<DfrBootDecision>().filter { it.startsTheHelper() },
        )
    }

    @Test
    fun `the phone working as asked is not reported, and everything else is`() {
        // Off, already rooted, already attempted: all three are the state the user asked for, and a
        // notification about one of them is the app talking to itself after every restart. The rest are
        // boots that could not do what they were asked, including the ones the user can do nothing about
        // from the shade - because "nothing happened" and "nothing could happen" look the same outside.
        val reported = enumValues<DfrBootDecision>().filter { it.isWorthReporting() }
        assertEquals(
            listOf(
                DfrBootDecision.Reroot,
                DfrBootDecision.SkipArmed,
                DfrBootDecision.NoHelper,
                DfrBootDecision.NotSystemUid,
                DfrBootDecision.StaleHelper,
                DfrBootDecision.NoShell,
            ),
            reported,
        )
    }

    @Test
    fun `the helper's standing is read without a shell`() {
        // The one part of the boot decision a phone with nothing running can still answer, and the reason
        // the reroot is decided at all rather than refused on a boot where Shizuku is not up yet. A shell
        // read here would turn a missing helper into "no shell", which is the wrong sentence and, worse,
        // the one the gate answers by waiting two minutes.
        val body = declaration(dfrInstallSource(), "internal fun helperStanding(")
        assertFalse("the standing is read through a shell", body.contains("unprivilegedShell"))
        assertFalse("the standing is read as root", body.contains("rootShell"))
        assertTrue("the standing is no longer read from Package Manager", body.contains("packageManager"))
    }

    @Test
    fun `the launch at boot is the screen's own command through the plain shell`() {
        val body = declaration(dfrInstallSource(), "internal fun launchWithoutRoot(")
        assertTrue(
            "the boot rerun no longer sends the command the screen sends, so the two can drift apart",
            body.contains("launchCommand(autorun = autorun, rerootAtBoot = rerootAtBoot)"),
        )
        assertTrue("the launch is no longer through Shizuku's plain shell", body.contains("unprivilegedShell"))
        assertFalse("the launch escalates when a boot has no root to escalate with", body.contains("rootShell"))
        // `am` exits zero on some builds while saying on its first line that it reached nothing, and a run
        // that was never started being read as started is the one thing this verdict must not do.
        assertTrue("the refusal word is the package manager's, not the shell's", body.contains("LAUNCH_FAILURE"))
    }

    @Test
    fun `the boot's attempt is spent when the helper is started, not when the boot is considered`() {
        val service = serviceSource()
        val startTheHelper = service.indexOf("private suspend fun startTheHelper")
        assertTrue("startTheHelper is gone from the gate", startTheHelper > 0)
        val claim = service.indexOf("AutoRootSupport.claimRerootAttempt")
        assertTrue("the gate no longer claims this boot's reroot attempt", claim > 0)
        assertTrue(
            "an attempt claimed before the launch is spent by a boot the gate then refuses to act on, and " +
                "the notification's own retry is only reachable while it is unspent",
            claim > startTheHelper,
        )
        assertTrue(
            "the gate reads whether this boot has already run without asking the rule that decides it",
            service.contains("hasAttemptedRerootBoot"),
        )
    }

    @Test
    fun `the gate starts the helper through the one transport a boot with no root has`() {
        val service = serviceSource()
        assertTrue("the boot path no longer launches the helper", service.contains("DfrInstall.launchWithoutRoot"))
        assertFalse(
            "the gate has a root path again, which a boot with no root cannot reach - so it would read as " +
                "the exploit failing rather than as the phone not having root yet",
            service.contains("KernelSuRuntime.rootShell"),
        )
        assertTrue(
            "the marker is no longer read through the shell that does the launching, which is the same " +
                "shell the launch needs - so a boot that cannot read it cannot start anything either",
            service.contains("DfrInstall.probeWithoutRoot()"),
        )
    }

    @Test
    fun `the stage file for the next boot is written back once root is live`() {
        val service = serviceSource()
        val afterRoot = service.indexOf("if (awaitRoot())")
        val staged = service.indexOf("DfrInstall.stageDaemon")
        assertTrue("the gate no longer watches for KernelSU coming up", afterRoot > 0)
        assertTrue("the gate no longer writes the daemon stage file back", staged > 0)
        assertTrue(
            "the stage file is written before there is a root shell to write it with, and the daemon's " +
                "late-load consumes it - so the next boot would start the helper and abort on a missing file",
            staged > afterRoot,
        )
    }

    @Test
    fun `the gate is reached on a boot with no root, and only then`() {
        val receiver = source("src/main/java/dev/busung/s25uroot/AutoRootBootReceiver.kt")
        val start = receiver.indexOf("DfrBootService.start(context)")
        assertTrue("the boot receiver no longer starts the reroot gate", start > 0)
        assertTrue(
            "the gate is started behind the rooted boot's early return, which makes it unreachable on the " +
                "only boots that need it",
            start < receiver.indexOf("if (rootActive) {"),
        )
        assertTrue(
            "the gate is started on every boot rather than on the boots that asked for it",
            receiver.contains("!rootActive && AppPreferences.rerootAtBoot(context)"),
        )
    }

    @Test
    fun `every reason the gate reports has a sentence, and none of them share a fallback`() {
        // The `when` in the gate has no `else`, so a reason added to the vocabulary cannot reach a
        // notification with nothing to say. An `else` would compile just as well and say "Nothing to do in
        // this boot" about a boot that could not do anything - which is the confusion this refuses.
        val body = declaration(serviceSource(), "private fun reasonFor(")
        assertFalse(
            "a reason added later would be reported as \"nothing to do\", which reads as the boot working",
            body.contains("else ->"),
        )
        assertTrue("the gate's sentences are no longer looked up per reason", body.contains("R.string.dfr_boot_"))
    }

    @Test
    fun `a reroot does not spend the install attempt or the retry armed for it`() {
        // The reroot's attempt is its own token, and the reason is the install's claim: claiming that one is
        // what consumes an armed retry, so a boot that rerooted and also ate the retry the user armed for it
        // would have taken back a request it never honoured.
        val body = declaration(
            source("src/main/java/dev/busung/s25uroot/AutoRootSupport.kt"),
            "fun claimRerootAttempt(",
        )
        assertFalse("the reroot's claim spends the install's attempt", body.contains("LAST_ATTEMPT_TOKEN"))
        assertFalse("the reroot's claim touches the armed retry", body.contains("RETRY"))
        assertTrue("the reroot no longer keeps an attempt of its own", body.contains("LAST_REROOT_ATTEMPT_TOKEN"))
    }

    @Test
    fun `the setting is off until it is turned on`() {
        // The default matters more than usual here: this is behaviour that runs with nobody watching on
        // every boot of every install, and it works by starting an exploit.
        val preferences = source("src/main/java/dev/busung/s25uroot/AppPreferences.kt")
        assertTrue(
            "reroot at boot is no longer off by default, so a phone that never asked for it starts an " +
                "exploit after a reboot",
            preferences.contains("getBoolean(DFR_REROOT_AT_BOOT, false)"),
        )
        val settings = source("src/main/java/dev/busung/s25uroot/MainActivity.kt")
        assertTrue(
            "the setting has no row, so the only way to reach the feature is to edit the stored preference",
            settings.contains("title = stringResource(R.string.dfr_reroot_at_boot)"),
        )
        assertTrue(
            "turning the setting off no longer reaches a gate that is already waiting on a shell",
            settings.contains("if (!enabled) DfrBootService.stop(this)"),
        )
    }

    private fun serviceSource() = source("src/main/java/dev/busung/s25uroot/DfrBootService.kt")

    private fun dfrInstallSource() = source("src/main/java/dev/busung/s25uroot/dfr/DfrInstall.kt")

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$relativeToApp was not found from ${File(".").absolutePath}")

    /**
     * One declaration's own text: the function at [signature], up to the next thing declared beside it.
     *
     * Position assertions are what the rest of this file is made of, and they are only worth anything if the
     * text between them is the function: `indexOf` over the whole file happily finds a call in the wrong
     * function, which is exactly the mistake these guards exist to catch.
     *
     * The boundary is the next declaration at the *same indentation* rather than the function's closing
     * brace, and the reason is a shape several of these have: an expression-bodied one-liner has no braces of
     * its own, and a search for its first `{` would run on into the next function and assert about the wrong
     * code while looking like it passed. It is not simply "the next line that is not indented further"
     * either, because a signature wrapped over several lines ends with a line at the same indentation.
     */
    private fun declaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found in the source", start >= 0)
        val lineStart = source.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indent = source.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        val match = Regex(
            "\n" + Regex.escape(indent) +
                "(?:@|/\\*\\*|(?:internal |private |public )?(?:fun|val|var|const|object|class|enum|data) )",
        ).find(source, start + signature.length) ?: throw AssertionError(
            "no declaration follows $signature, so this slice would have run to the end of the file - and " +
                "every assertion made about it could then pass by reading somebody else's code",
        )
        return source.substring(start, match.range.first)
    }
}
