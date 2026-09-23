package dev.busung.s25uroot.dfr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's two readings, against the lines the device actually printed.
 *
 * Every fixture here is a copy of real output, quoted from the phone this was developed against, because
 * the first version of this parsing was written from what the command was expected to say and was wrong in
 * two ways at once - a `dumpsys` field that does not exist, and a uid that contains the system uid as a
 * prefix. Both mistakes read as "an ordinary app", which is the one answer that tells someone to uninstall
 * a working install.
 */
class DfrProbeTest {

    /** What the one command prints for a stage two installed as an ordinary app. */
    private fun output(packageLine: String, armed: Boolean = false) = buildString {
        appendLine("RMG-package")
        if (packageLine.isNotEmpty()) appendLine(packageLine)
        appendLine("RMG-armed")
        appendLine(if (armed) "armed" else "clear")
        appendLine("RMG-end")
    }

    @Test
    fun `an installed package reports its uid, and the system uid is read as one`() {
        // The shape the device printed for a package running as android.uid.system.
        val probe = DfrProbe.parse(output("package:dev.rushiranpise.rmg.stage2 uid:1000"))
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
    fun `the command puts every marker in quotes`() {
        // The class of bug this guards is not hypothetical: an unquoted marker is a shell word, and this
        // app shipped a probe whose markers were never printed because of it.
        val command = DfrInstall.probeCommand()
        listOf("RMG-package", "RMG-armed", "RMG-end").forEach { marker ->
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
}
