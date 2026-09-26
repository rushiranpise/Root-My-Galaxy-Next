package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BOOT = "0f2a4c6e-1b2d-4f6a-8c0e-2d4f6a8c0e2d"
private const val ACCEPTED = "/data/local/tmp/.rmgnext-reboot-accepted"
private const val SUMMARY = "/data/local/tmp/.rmgnext-reboot-wipe"

/**
 * The one action that unroots *and* cleans up.
 *
 * KernelSU lives in the running kernel, so the restart is what removes root and the *root on boot*
 * setting is what would bring it back. What is left is everything on disk that says the phone was
 * rooted: `/data/adb`, whose module store, superuser grants and daemon all live there, and the shared
 * temp directory every root solution writes into. These tests hold the three things that make emptying
 * them safe rather than merely destructive - that nothing is deleted through a mount point, that the
 * account of what happened is written after the work and read before the restart, and that the script
 * survives deleting the directory it is running from.
 */
class RebootUnrootWipeTest {

    private val script = RootRecovery.rebootScript(BOOT, ACCEPTED, SUMMARY)

    // --- what the wipe covers --------------------------------------------------------------------------

    @Test
    fun `both directories are emptied, contents only`() {
        assertTrue(script.contains("rmg_wipe_dir /data/adb"))
        assertTrue(script.contains("rmg_wipe_dir /data/local/tmp"))

        // The directories themselves stay: KernelSU creates /data/adb and init creates /data/local/tmp,
        // each with a mode the platform relies on, and recreating either by hand is how a phone ends up
        // with a temp directory no app can write to. So the removal takes the entry, never the directory.
        assertTrue(script.contains("rm -rf -- \"\$rmg_entry\""))
        assertFalse(script.contains("rm -rf -- \"/data/adb\""))
        assertFalse(script.contains("rm -rf -- \"/data/local/tmp\""))
        assertFalse(script.contains("rm -rf /data/adb"))
        assertFalse(script.contains("rm -rf /data/local/tmp"))
    }

    @Test
    fun `the wipe never follows a mount point into another filesystem`() {
        val check = script.indexOf("if rmg_is_mount \"\$rmg_entry\"; then")
        val detach = script.indexOf("umount -l \"\$rmg_entry\"")
        val removal = script.indexOf("rm -rf -- \"\$rmg_entry\"")

        // The order is the safety property, not a detail of the implementation: `rm -rf` descends into
        // whatever is mounted on a path, so a leftover bind mount sitting in one of these directories
        // would have the wipe empty somebody else's tree through it.
        assertTrue("the mount check must come before the removal", check in 0 until removal)
        assertTrue("the mount must be detached before anything is removed", detach in 0 until removal)

        // And read again afterwards, because a lazy detach can fail on a busy mount - the entry is then
        // reported rather than removed through.
        val recheck = script.indexOf("if rmg_is_mount \"\$rmg_entry\"; then", check + 1)
        assertTrue("the mount must be checked again after the detach", recheck in detach until removal)
        assertTrue(
            script.substring(recheck, removal).contains("continue"),
        )
    }

    @Test
    fun `an entry that survives is named rather than counted away`() {
        val reports = RootRecovery.rebootLastMile("/system/bin/reboot")

        // A name is what makes a leftover actionable - someone can go and look at it - which is why the
        // record carries paths and the app decides how many of them a sentence can hold.
        assertTrue(reports.contains("printf \"left %s\\n\""))
        assertTrue(reports.contains("wipe %s removed=%s total=%s\\n"))
    }

    @Test
    fun `a directory that is not there is no entries, not a failure`() {
        // /data/adb does not exist on a phone that has never been rooted, and /data/local/tmp always
        // does. An absent directory is `0 of 0`, which is the truth and not a refusal.
        val body = RootRecovery.rebootLastMile("/system/bin/reboot")

        assertTrue(body.contains("if [ -d \"\$rmg_dir\" ] && [ ! -L \"\$rmg_dir\" ]; then"))
        assertFalse(body.contains("reject_handoff"))
    }

    // --- when the account is written, and when the phone goes -----------------------------------------

    @Test
    fun `the wipe happens before the acknowledgement and the restart after it`() {
        val mile = RootRecovery.rebootLastMile("/system/bin/reboot")

        val wipeAdb = mile.indexOf("rmg_wipe_dir /data/adb")
        val wipeTmp = mile.indexOf("rmg_wipe_dir /data/local/tmp")
        val report = mile.indexOf("printf \"%s\" \"\$rmg_report\" > \"\$SUMMARY\"")
        val publish = mile.indexOf("publish_handoff \"\$ACCEPTED_VALUE\"")
        val consumed = mile.indexOf("rmg_handoff_consumed ||", publish)
        val reboot = mile.lastIndexOf("/system/bin/reboot")

        assertTrue(wipeAdb < wipeTmp)
        assertTrue("the report is written after the wipe", report > wipeTmp)
        // Read after it, in this order, which is what makes an accepted outcome a statement about work
        // that has already happened rather than a promise about work that has not.
        assertTrue("the acknowledgement follows the report", publish > report)
        assertTrue("the app must read the acknowledgement first", consumed > publish)
        assertTrue("the restart is the last thing that happens", reboot > consumed)
    }

    @Test
    fun `a restart nobody read is not performed`() {
        val mile = RootRecovery.rebootLastMile("/system/bin/reboot")

        // The rule every action here follows, and the one this action needs most: the files are already
        // gone by this point, so a phone whose user was told the action failed must not then be
        // restarted under them.
        val consumed = mile.indexOf("rmg_handoff_consumed ||")
        val abort = mile.indexOf("exit 0", consumed)
        assertTrue(consumed in 0..abort)
        // The report goes with it, since nobody is left to read that either.
        assertTrue(mile.substring(consumed, abort).contains("\"\$SUMMARY\""))
    }

    // --- the script that empties its own directory -----------------------------------------------------

    @Test
    fun `the last mile is read before anything is deleted`() {
        // The script lives in /data/local/tmp, which it empties: a shell reads a script as it goes, so a
        // line after that point may no longer be there. Assembled into a variable and evaluated at the
        // end, everything it does is in memory before the first entry is removed.
        val assignment = script.indexOf("\nRMG_LAST_MILE='")
        val eval = script.indexOf("eval \"\$RMG_LAST_MILE\"")

        assertTrue(assignment > 0)
        assertTrue(eval > assignment)
        assertEquals(
            "the eval must be the last line of the script; anything after it may be gone by the time " +
                "the shell gets there",
            script.trimEnd().length,
            eval + "eval \"\$RMG_LAST_MILE\"".length,
        )
        // Nothing between the checks and the eval touches a file, apart from writing the script itself.
        assertTrue(script.substring(0, assignment).contains("sync"))
    }

    @Test
    fun `the last mile cannot end its own assignment early`() {
        // It is assigned single-quoted. A single quote in it would close the assignment and turn the
        // rest into commands, and this is the one script whose failure mode is a phone that is emptied
        // and not restarted - so the body is checked where it is built rather than trusted.
        val mile = RootRecovery.rebootLastMile("/system/bin/reboot")

        assertFalse(mile.contains('\''))
        val body = script.substringAfter("\nRMG_LAST_MILE='").substringBefore("'\n\neval ")
        assertTrue(body.contains("rmg_wipe_dir /data/local/tmp"))
    }

    @Test
    fun `the window outlasts a wipe of any size`() {
        // Every other action's cost is a loop it can count; this one's is however long it takes to
        // delete two directories. An app that gave up first would leave a phone emptied and not
        // restarted, with the user told it was on its way.
        assertTrue(
            RootRecovery.REBOOT_WIPE_ACCEPT_POLL_ATTEMPTS * RootRecovery.ACCEPT_POLL_INTERVAL_SECONDS > 60.0,
        )
        assertTrue(
            RootRecovery.REBOOT_WIPE_ACCEPT_POLL_ATTEMPTS > RootRecovery.reloadModulesAcceptPollAttempts,
        )
    }

    // --- the app's side of the report ------------------------------------------------------------------

    @Test
    fun `the report the shell prints is the report the app reads`() {
        // Written by hand in the shell and parsed here, so the shape is asserted rather than assumed:
        // a shell that printed something else would be a wipe the app cannot read, and a silent unroot
        // is the one outcome this must never have.
        val shellPrints = RootRecovery.rebootLastMile("/system/bin/reboot")
        assertTrue(shellPrints.contains("printf \"wipe %s removed=%s total=%s\\n\""))
        assertTrue(shellPrints.contains("printf \"left %s\\n\""))

        val report = parseWipeReport(
            listOf(
                wipeDirectoryLine("/data/adb", removed = 5, total = 5),
                wipeDirectoryLine("/data/local/tmp", removed = 3, total = 4),
                wipeLeftoverLine("/data/local/tmp/dalvik-cache"),
            ).joinToString("\n"),
        )

        assertEquals(listOf(5, 3), report?.directories?.map { it.removed })
        assertEquals(listOf(5, 4), report?.directories?.map { it.total })
        assertEquals(listOf("/data/local/tmp/dalvik-cache"), report?.leftovers)
        assertEquals(5, report?.forDirectory("/data/adb")?.removed)
        assertFalse(report!!.complete)
    }

    @Test
    fun `nothing reported is not the same as nothing found`() {
        // Null means the action published no account at all, which is what keeps it from reading as one
        // that ran and found the directories already empty.
        assertNull(parseWipeReport(""))
        assertNull(parseWipeReport("The recovery action did not answer"))
        assertNull(parseWipeReport("wipe /data/adb removed=x total=1"))

        val empty = parseWipeReport(wipeDirectoryLine("/data/adb", removed = 0, total = 0))
        assertEquals(0, empty?.forDirectory("/data/adb")?.total)
        assertTrue(empty!!.complete)
    }

    @Test
    fun `a report is read out only for the action that publishes one`() {
        val detail = wipeDirectoryLine("/data/adb", removed = 2, total = 2)
        val accepted = RecoveryOutcome(accepted = true, detail = detail)

        assertTrue(wipeReportFor(accepted, RecoveryTool.RebootAndUnroot) != null)
        // Every other action's acceptance carries no report, and a refusal carries nothing to report on.
        assertEquals(
            null,
            wipeReportFor(accepted, RecoveryTool.SoftReboot),
        )
        assertEquals(
            null,
            wipeReportFor(RecoveryOutcome(accepted = false, detail = detail), RecoveryTool.RebootAndUnroot),
        )
    }

    @Test
    fun `a sentence holds a few leftovers and counts the rest`() {
        // On a shell that is not root every entry is left behind, and a dialog that prints a module
        // store is a dialog nobody reads to the end of.
        assertEquals(LeftoverNames(listOf("a", "b"), more = 0), splitLeftovers(listOf("a", "b"), limit = 3))
        assertEquals(
            LeftoverNames(listOf("l0", "l1", "l2"), more = 7),
            splitLeftovers(List(10) { "l$it" }, limit = 3),
        )
        assertEquals(LeftoverNames(emptyList(), more = 0), splitLeftovers(emptyList(), limit = 3))
    }

    @Test
    fun `the acknowledgement still reads as accepted with nothing added`() {
        // The other three actions publish the marker alone, and one of them is what these tests run
        // beside: a marker with nothing after it is an acceptance with nothing to say.
        val outcome = RootRecovery.parseHandoff("RMG_RECOVERY_ACCEPTED")

        assertTrue(outcome.accepted)
        assertEquals("", outcome.detail)
    }

    @Test
    fun `a line after the marker is the action's own account`() {
        val report = wipeDirectoryLine("/data/adb", removed = 4, total = 5)
        val outcome = RootRecovery.parseHandoff("RMG_RECOVERY_ACCEPTED\n$report\nleft /data/adb/ksu\n")

        assertTrue(outcome.accepted)
        assertEquals("$report\nleft /data/adb/ksu", outcome.detail)
    }

    @Test
    fun `a marker that is not the first line is not an acceptance`() {
        // The launcher compares the acknowledgement whole and only the app turns it into words, so the
        // two have to agree about where the answer starts - otherwise a runner that printed the log
        // first would be read here as an acceptance the launcher never reported.
        val outcome = RootRecovery.parseHandoff(
            "the recovery action did not acknowledge the request\nRMG_RECOVERY_ACCEPTED",
        )

        assertFalse(outcome.accepted)
    }
}
