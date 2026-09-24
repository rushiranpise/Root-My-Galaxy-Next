package dev.busung.s25uroot.dfr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's readings, against the lines the device actually printed.
 *
 * Every fixture here is a copy of real output, quoted from the phone this was developed against, because
 * the first version of this parsing was written from what the command was expected to say and was wrong in
 * two ways at once - a `dumpsys` field that does not exist, and a uid that contains the system uid as a
 * prefix. Both mistakes read as "an ordinary app", which is the one answer that tells someone to uninstall
 * a working install.
 */
class DfrProbeTest {

    /** What the one command prints for a stage two installed as an ordinary app. */
    private fun output(
        packageLine: String,
        armed: Boolean = false,
        /** Exactly what the framework's own section printed, or empty for a device that did not say. */
        framework: String = "",
    ) = buildString {
        appendLine("RMG-package")
        if (packageLine.isNotEmpty()) appendLine(packageLine)
        appendLine("RMG-armed")
        appendLine(if (armed) "armed" else "clear")
        appendLine("RMG-framework")
        if (framework.isNotEmpty()) appendLine(framework)
        appendLine("RMG-end")
    }

    /** The numbers the phone printed: 7804 s of kernel uptime, a framework up 2558 s of it. */
    private val frameworkOnThePhone = "up=7803.96 start=524585"

    @Test
    fun `an installed package reports its uid, and the system uid is read as one`() {
        // The shape the device printed for a package running as android.uid.system.
        val probe = DfrProbe.parse(output("package:dev.rushiranpise.rmgnext.helper uid:1000"))
        assertEquals(DfrProbe(installed = true, isSystemUid = true, armed = false), probe)
    }

    @Test
    fun `a uid that merely begins with 1000 is an ordinary app`() {
        // `uid:10005` is a real uid on this device, and this is the reason the read is anchored.
        val probe = DfrProbe.parse(output("package:dev.rushiranpise.rmgnext uid:10555"))
        assertEquals(DfrProbe(installed = true, isSystemUid = false, armed = false), probe)
    }

    @Test
    fun `a package that is not installed is not installed, not unreadable`() {
        // Nothing between the markers: the command ran, the package is absent. This is the state the
        // install step is for, and reading it as "could not look" would stop the flow on a healthy device.
        val probe = DfrProbe.parse(output(""))
        assertEquals(DfrProbe(installed = false, isSystemUid = false, armed = false), probe)
    }

    @Test
    fun `armed hooks are read from the marker node`() {
        val probe = DfrProbe.parse(output("", armed = true))
        assertTrue(probe!!.armed)
        assertFalse(probe.installed)
    }

    @Test
    fun `output with no markers at all is null rather than three falses`() {
        // A shell that answered with something else entirely - a refusal, a stack trace, an empty reply -
        // is not a measurement, and the flow has a step for that which says so.
        assertNull(DfrProbe.parse(""))
        assertNull(DfrProbe.parse("su: not found"))
        assertNull(DfrProbe.parse("RMG-package\npackage:x uid:1000\n"))
    }

    @Test
    fun `a missing second section is not read as the end of the output`() {
        // The sections are found by their markers, so the last one runs to the end of the string. Cutting
        // the string short must not be read as a package that is installed and unprivileged.
        assertNull(DfrProbe.parse("RMG-armed\narmed\nRMG-end\n"))
    }

    @Test
    fun `the framework's age is read from the device's own numbers`() {
        // Both halves are required to make one: the numbers are what the phone printed, and 2558.11 s is
        // the difference between them at the hundredth of a second `/proc/<pid>/stat` counts in.
        val probe = DfrProbe.parse(output("", framework = frameworkOnThePhone))
        assertEquals(2_558_110L, probe!!.frameworkUptimeMillis)
    }

    @Test
    fun `a framework the phone did not describe is unknown, not zero`() {
        // The shape that inverts this whole flow if it is got wrong: an age of zero reads as a framework
        // that started at this instant, which is a restart for every recorded instant there is. A device
        // that answered nothing, and one that answered half a line, are both unreadable.
        val silent = DfrProbe.parse(output(""))
        assertNull(silent!!.frameworkUptimeMillis)
        assertNull(DfrProbe.parse(output("", framework = "up=7803.96"))!!.frameworkUptimeMillis)
        assertNull(DfrProbe.parse(output("", framework = "start=524585"))!!.frameworkUptimeMillis)
        assertNull(frameworkUptimeMillis(""))
        assertNull(frameworkUptimeMillis(null))
        assertNull(
            "a start time from a boot this one replaced is not a negative age",
            frameworkUptimeMillis("up=5.0 start=999999999"),
        )
    }

    @Test
    fun `the command puts every marker in quotes`() {
        // The class of bug this guards is not hypothetical: an unquoted marker is a shell word, and this
        // app shipped a probe whose markers were never printed because of it.
        val command = DfrInstall.probeCommand()
        listOf("RMG-package", "RMG-armed", "RMG-framework", "RMG-end").forEach { marker ->
            assertTrue("$marker is not quoted in: $command", command.contains("echo '$marker'"))
        }
        assertFalse("an unquoted # in the command starts a shell comment", command.contains(" #"))
    }

    @Test
    fun `the command asks the package manager rather than the dump`() {
        // The reading that was wrong first: `dumpsys package` prints `uid=` under a permission listing, and
        // the list command prints the package's own uid and nothing else.
        val command = DfrInstall.probeCommand("some.package")
        assertTrue(command.contains("pm list packages -U 'some.package'"))
        assertFalse(command.contains("dumpsys"))
    }

    @Test
    fun `the framework's age is asked of the process table, not of a clock the kernel would answer`() {
        // `pidof system_server` and the 22nd field of its stat, which is what the zygote report already
        // reads: the kernel's own uptime is the same number as before and cannot see a userspace restart.
        val command = DfrInstall.probeCommand()
        assertTrue(command.contains("pidof system_server"))
        assertTrue("the start time is the 22nd field", command.contains("{print ${'$'}22}"))
        assertTrue("every read is redirected so a refusal is silence", command.contains("2>/dev/null"))
    }
}
