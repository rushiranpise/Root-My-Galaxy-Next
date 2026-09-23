package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permissive lever's decisions, without a device.
 *
 * The cases that matter cannot be produced on demand on hardware - a write whose confirmation could
 * not be read, an `insmod` that failed before the module ran, a refusal only the log explains - so
 * they are the reason the decisions are pure functions.
 *
 * The two log lines used below are copied from what `lkm/permissive/rmg_permissive.c` prints, so a
 * change to the module's format fails here rather than being read as "the module logged nothing".
 */
class PermissiveModuleTest {

    private fun support(
        rootShell: Boolean = true,
        enforceNode: String? = "1",
        develop: Boolean? = true,
        insmod: String? = "/system/bin/insmod",
    ) = PermissiveSupport(rootShell, enforceNode, develop, insmod)

    // ---------------------------------------------------------------------------------------
    // the precondition
    // ---------------------------------------------------------------------------------------

    @Test
    fun `everything answered is the only ready state`() {
        assertEquals(PermissiveAvailability.Ready, support().availability)
    }

    @Test
    fun `no root route is not the same as no node`() {
        // Both leave the node unread, and they need different words: one is a grant to give, the
        // other is a kernel with no switch at all.
        assertEquals(PermissiveAvailability.NoRoot, support(rootShell = false).availability)
        assertEquals(PermissiveAvailability.NoEnforceNode, support(enforceNode = null).availability)
    }

    @Test
    fun `a kernel without the switch is refused, by either reading of it`() {
        assertEquals(
            PermissiveAvailability.NoEnforceNode,
            support(enforceNode = null, develop = null).availability,
        )
        assertEquals(PermissiveAvailability.DevelopOff, support(develop = false).availability)
    }

    @Test
    fun `a missing insmod is its own reason`() {
        assertEquals(PermissiveAvailability.NoInsmod, support(insmod = null).availability)
    }

    @Test
    fun `the config line is read both ways the kernel prints it`() {
        assertEquals(true, parseDevelopSetting("CONFIG_SECURITY_SELINUX_DEVELOP=y"))
        assertEquals(
            false,
            parseDevelopSetting("# CONFIG_SECURITY_SELINUX_DEVELOP is not set"),
        )
        // Absent, or a build without embedded config, is "did not answer" rather than "off".
        assertNull(parseDevelopSetting(""))
        assertNull(parseDevelopSetting("CONFIG_SECURITY_SELINUX=y"))
    }

    @Test
    fun `the enforce node is read as a value, not as text`() {
        assertEquals(false, parseEnforceValue("0"))
        assertEquals(true, parseEnforceValue("1\n"))
        assertNull(parseEnforceValue(""))
        assertNull(parseEnforceValue("permissive"))
    }

    // ---------------------------------------------------------------------------------------
    // the module's own account
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an applied line is read field by field`() {
        val report = parseModuleReport(
            "rmg_permissive: result=applied offset=0 before=1 after=0 symbol=selinux_state value=0",
        )
        assertEquals(ModuleVerdict.Applied, report?.verdict)
        assertEquals(0, report?.offset)
        assertEquals(1, report?.before)
        assertEquals(0, report?.after)
        assertEquals("selinux_state", report?.symbol)
    }

    @Test
    fun `a refusal carries the reason and the bytes it saw`() {
        val report = parseModuleReport(
            "rmg_permissive: result=refused reason=layout-mismatch symbol=selinux_state offset=0 bytes=01 01 ff ff",
        )
        assertEquals(ModuleVerdict.Refused, report?.verdict)
        assertEquals("layout-mismatch", report?.reason)
    }

    @Test
    fun `the newest line is this load's account`() {
        val report = parseModuleReport(
            """
            rmg_permissive: result=refused reason=no-offset
            something else entirely
            rmg_permissive: result=applied offset=0 before=1 after=0 value=0
            """.trimIndent(),
        )
        assertEquals(ModuleVerdict.Applied, report?.verdict)
    }

    @Test
    fun `no line at all is not a refusal`() {
        // The difference decides what the UI says: a refusal is this device's layout, and no line is
        // a load that never reached the check.
        assertNull(parseModuleReport("insmod: failed: Invalid argument"))
        assertNull(parseModuleReport("rmg_permissive: something went wrong"))
    }

    // ---------------------------------------------------------------------------------------
    // the command the readings are taken with
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every marker is quoted, because an unquoted hash is a comment`() {
        // The bug this pins: `echo #rmg-node` prints nothing - the shell reads the marker as a
        // comment - so every section came back empty and a kernel that has the switch was reported as
        // having no enforce node at all. It was found on the device, not here, which is why it is a
        // test now.
        val command = PermissiveLever.supportCommand()
        listOf(PermissiveLever.NODE_MARK, PermissiveLever.CONFIG_MARK, PermissiveLever.INSMOD_MARK)
            .forEach { marker ->
                assertTrue(
                    "$marker is not echoed inside quotes: $command",
                    command.contains("echo '$marker'"),
                )
            }
        // And nothing anywhere in it leaves a bare `#` in command position.
        assertTrue(command, !Regex("(^|[;&|]\\s*)#").containsMatchIn(command))
    }

    @Test
    fun `the readings are taken in one read, in the order they are parsed`() {
        val command = PermissiveLever.supportCommand()
        val node = command.indexOf(PermissiveLever.NODE_MARK)
        val config = command.indexOf(PermissiveLever.CONFIG_MARK)
        val insmod = command.indexOf(PermissiveLever.INSMOD_MARK)
        assertTrue("markers out of order: $command", node in 0 until config && config < insmod)
        assertTrue(command.contains(PermissiveLever.NODE))
        assertTrue(command.contains("command -v insmod"))
    }

    // ---------------------------------------------------------------------------------------
    // the verdict
    // ---------------------------------------------------------------------------------------

    private val applied =
        "rmg_permissive: result=applied offset=0 before=1 after=0 symbol=selinux_state value=0"

    @Test
    fun `a write with the node reading zero is permissive`() {
        val outcome = permissiveOutcome(support(), parseModuleReport(applied), "0\n")
        assertEquals(PermissiveState.Permissive, outcome.state)
    }

    @Test
    fun `an already permissive device is not an error`() {
        val outcome = permissiveOutcome(
            support(),
            parseModuleReport("rmg_permissive: result=noop offset=0 before=0 after=0 value=0"),
            "0",
        )
        assertEquals(PermissiveState.Permissive, outcome.state)
    }

    @Test
    fun `a write whose confirmation could not be read says so`() {
        val outcome = permissiveOutcome(support(), parseModuleReport(applied), null)
        assertEquals(PermissiveState.PermissiveUnconfirmed, outcome.state)
        assertTrue(outcome.summary.contains("could not be read back"))
    }

    @Test
    fun `a write with the node still enforcing is not a success`() {
        val outcome = permissiveOutcome(support(), parseModuleReport(applied), "1")
        assertEquals(PermissiveState.Refused, outcome.state)
        assertTrue(outcome.summary.contains("still reads 1"))
    }

    @Test
    fun `a refusal is reported as the module worded it`() {
        val outcome = permissiveOutcome(
            support(),
            parseModuleReport("rmg_permissive: result=refused reason=no-offset offset=-1"),
            "1",
        )
        assertEquals(PermissiveState.Refused, outcome.state)
        assertTrue(outcome.summary.contains("no-offset"))
    }

    @Test
    fun `a post-condition failure is not read as a refusal to write`() {
        // The module only prints this when the byte changed and the shape no longer holds, which is
        // worse than a refusal and must not be worded like one.
        val outcome = permissiveOutcome(
            support(),
            parseModuleReport("rmg_permissive: result=verify-failed offset=0 after=0"),
            "0",
        )
        assertEquals(PermissiveState.Refused, outcome.state)
        assertTrue(outcome.summary.contains("verify-failed"))
    }

    @Test
    fun `a load that logged nothing is its own outcome`() {
        val outcome = permissiveOutcome(support(), report = null, enforceAfter = "1")
        assertEquals(PermissiveState.NotLoaded, outcome.state)
        assertTrue(outcome.summary.contains("did not reach its own check"))
    }

    @Test
    fun `an unusable device is refused before anything is attempted`() {
        // Each reason is worded for itself: "no root" must not read as "no such kernel feature".
        val cases = mapOf(
            support(rootShell = false) to "no root shell",
            support(enforceNode = null) to "no runtime SELinux switch",
            support(develop = false) to "DEVELOP is off",
            support(insmod = null) to "no insmod",
        )
        cases.forEach { (support, wording) ->
            val outcome = permissiveOutcome(support, report = null, enforceAfter = null)
            assertEquals(PermissiveState.Unavailable, outcome.state)
            assertTrue(outcome.summary, outcome.summary.contains(wording))
        }
    }
}
