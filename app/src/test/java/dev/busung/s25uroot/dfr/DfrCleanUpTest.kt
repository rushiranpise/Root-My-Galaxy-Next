package dev.busung.s25uroot.dfr

import dev.busung.s25uroot.dfr.DfrMode.Check
import dev.busung.s25uroot.dfr.DfrMode.Inject
import dev.busung.s25uroot.dfr.DfrMode.Uninstall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a clean-up is allowed to claim, read off the uninstall's own output.
 *
 * The clean-up does two things and clears a record for each of them - the key in `android.uid.system`,
 * and the helper installed under that shared user. The record is not decoration: it is the instant the
 * flow compares against uptime to decide whether a reboot has happened since, so a record cleared by a
 * clean-up that did not run reads as "this phone was never injected" - the same wrong direction as the
 * two stamps that once made the flow skip its own reboot step.
 *
 * What can be tested on a JVM is the reading itself, against logs built by the injector's own line
 * formatters ([PackagesXml.keyAbsentLine], [PackagesXml.keyRemovedLine]). The write needs a device; the
 * sentence does not.
 */
class DfrCleanUpTest {

    private val target = "android.uid.system"

    /** A log with the shape a real uninstall prints when it removed certs and re-read the file. */
    private fun removedLog(): String = """
        [*] uid=0 apk=
        [+] read 4711054 bytes from /data/system/packages.xml
        [+] removed our pastSigs from android.uid.system
        ${PackagesXml.keyRemovedLine(target, gone = true)}
        [*] restorecon rc=0
        [+] DONE. our key removed; soft reboot to apply
    """.trimIndent()

    @Test
    fun `an uninstall that removed the key is evidence`() {
        val result = DfrResult(Uninstall, ok = true, log = removedLog())
        assertTrue("the key is gone by the injector's own re-read", result.uninstalled)
    }

    @Test
    fun `an uninstall on a file that never had the key is evidence too`() {
        // Nothing to remove is the same answer to the question being asked: our key is not in the file.
        val log = """
            [*] read 4711054 bytes from /data/system/packages.xml
            ${PackagesXml.keyAbsentLine()}
            [+] DONE. our key removed; soft reboot to apply
        """.trimIndent()
        assertTrue(DfrResult(Uninstall, ok = true, log = log).uninstalled)
    }

    @Test
    fun `a failed verification is not evidence`() {
        // The injector throws on a false verify, so the process exits non-zero and carries the failure
        // line. Either half alone has to be enough to keep the record.
        val log = """
            [*] read 4711054 bytes from /data/system/packages.xml
            ${PackagesXml.keyRemovedLine(target, gone = false)}
            [x] FAILED: verify FAILED for android.uid.system (key still present)
        """.trimIndent()
        assertFalse("nothing was confirmed gone", DfrResult(Uninstall, ok = false, log = log).uninstalled)
        assertFalse(
            "and not on an exit code alone either",
            DfrResult(Uninstall, ok = true, log = log).uninstalled,
        )
    }

    @Test
    fun `a refusal before the write is not evidence`() {
        val log = """
            [!] structural check: only 12 package entries, refusing to write
            [x] FAILED: refusing to rewrite a file that is not this file
        """.trimIndent()
        assertFalse(DfrResult(Uninstall, ok = false, log = log).uninstalled)
    }

    @Test
    fun `an empty log is not evidence`() {
        // What DfrInstall.run returns when nothing answered is null, and the screen's guard is that null;
        // this is the same bug one layer down: a property that read an empty string as a confirmed
        // removal would clear the record on an uninstall that never ran.
        assertFalse(DfrResult(Uninstall, ok = false, log = "").uninstalled)
    }

    @Test
    fun `no root shell clears neither record`() {
        // What DfrInstall.run answers when nothing answered, and the same null from the helper's `pm
        // uninstall`. The clean-up reports "no root" and the two instants stay where they were: a phone
        // whose packages.xml still carries our key must not start reading as one that was never touched.
        val outcome = DfrCleanUpOutcome.of(uninstall = null, helper = null)
        assertFalse("the inject record is kept", outcome.keyGone)
        assertFalse("the install record is kept", outcome.helperGone)
    }

    @Test
    fun `a refusal keeps its own record and clears nothing`() {
        val refused = DfrResult(Uninstall, ok = false, log = "[x] FAILED: verify FAILED for $target")
        val failedInstall = DfrAction(ok = false, log = "Failure [not installed for 0]")
        val outcome = DfrCleanUpOutcome.of(refused, failedInstall)
        assertFalse("the key was not confirmed gone", outcome.keyGone)
        assertFalse("and the helper was not removed", outcome.helperGone)
    }

    @Test
    fun `each half clears only its own record`() {
        // The key removal landing while the helper's does not is the ordinary case on a phone where the
        // stage two was never installed: `pm uninstall` answers Failure. The install record has to stay,
        // because the flow reads the package itself - and keeping it costs nothing, while clearing it on
        // the other half's evidence is what a shared "clean-up succeeded" flag would do.
        val removed = DfrResult(Uninstall, ok = true, log = removedLog())
        val notInstalled = DfrAction(ok = false, log = "Failure [not installed for 0]")
        val outcome = DfrCleanUpOutcome.of(removed, notInstalled)
        assertTrue("the key is gone", outcome.keyGone)
        assertFalse("the helper was never there, so nothing confirms its removal", outcome.helperGone)

        val both = DfrCleanUpOutcome.of(removed, DfrAction(ok = true, log = "Success"))
        assertTrue("and with both halves landed, both records go", both.keyGone && both.helperGone)
    }

    @Test
    fun `another mode's output is not evidence`() {
        // `--check` prints the same target name and the same word "injected"; only an uninstall can report
        // a removal, so the mode is part of the reading rather than a detail of the caller.
        val checkLog = "[check] $target injected=true\n[check] all_injected=true"
        assertFalse(DfrResult(Check, ok = true, log = checkLog).uninstalled)
        assertFalse(
            "and an inject's own log is not a removal",
            DfrResult(Inject, ok = true, log = removedLog()).uninstalled,
        )
    }
}
