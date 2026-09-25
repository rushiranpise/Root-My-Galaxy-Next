package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart that a loaded KernelSU needs, and which of the two sides asks for it.
 *
 * What a DFR run loads is inert until the Android userspace is built again - KernelSU's own soft reboot is
 * what walks the module lifecycle in its normal order - and that reboot is the installed `ksud` run as root.
 * The helper cannot ask for it: it runs as the system uid inside `system_server`, which the daemon hands no
 * shell to. The app can, and it already holds everything the request needs: the `su` grant the user gave it,
 * the *Auto Soft reboot* setting, and a script with a per-boot lock so two restarts cannot start at once.
 *
 * So the helper's whole message is "root is live" and the decision is this side's - and the two callers on
 * this side are the two ways a run happens. A run a person pressed goes through [mainActivity] and the extra
 * the helper sets; a boot's run is the gate's, which is already here for another reason and does the restart
 * from its own reading. Neither is allowed to restart without the setting, and neither is allowed to act on
 * a request that did not come from the helper.
 */
class DfrAfterRootTest {

    @Test
    fun `the restart request is taken only from the helper`() {
        // The extra names a reboot and `MainActivity` is exported with a launcher filter, so any app on the
        // phone can start it with anything it likes. The caller is therefore checked, and checked with the
        // accessor that actually answers this question: `callingPackage` is documented not to be the
        // launching app in every case, and a decision about a reboot is not one to take on the weaker of the
        // two readings.
        val activity = mainActivity()
        assertTrue(
            "the app acts on the helper's restart extra without checking who sent it, so any app on the " +
                "phone could soft reboot the device by starting this activity",
            activity.contains("if (!launchedByTheHelper())"),
        )
        assertTrue(
            "the caller check no longer compares against the helper's application id, so it answers about " +
                "the wrong app",
            activity.contains("DfrInstall.STAGE_TWO_PACKAGE"),
        )
        assertTrue(
            "the caller is read through the accessor that is documented not to be the launching app",
            activity.contains("launchedFromPackage"),
        )
        assertTrue(
            "the caller is not read on the versions whose only accessor is callingPackage, so a phone below " +
                "the accurate one would take no restart at all",
            activity.contains("callingPackage"),
        )
        assertTrue(
            "the reboot is asked for before the caller is checked, so the guard is decoration",
            activity.indexOf("if (!launchedByTheHelper())") < activity.indexOf("runRecoveryAction("),
        )
    }

    @Test
    fun `the restart is the setting's, and off is a line rather than a silent nothing`() {
        // A restart is the one action here that cannot be offered and then taken back, so it is opt-in
        // through the setting that already means exactly this. Off is still *said*, because the alternative
        // - a run that loaded KernelSU, did nothing, and explained nothing - is the state this whole change
        // exists to end.
        val activity = mainActivity()
        assertTrue(
            "the app restarts after the helper's run without asking the setting, so a phone whose owner " +
                "turned Auto soft reboot off is rebooted anyway",
            activity.contains("AppPreferences.restartAfterRoot(this)"),
        )
        assertTrue(
            "the setting is read after the reboot is asked for, so it decides nothing",
            activity.indexOf("AppPreferences.restartAfterRoot(this)") < activity.indexOf("runRecoveryAction("),
        )
        assertTrue(
            "a run that loaded KernelSU with Auto soft reboot off leaves no line anywhere, so the load " +
                "looks like it did nothing",
            activity.contains("Auto soft reboot is off"),
        )
        assertTrue(
            "the restart is not something the app's own log can be read for afterwards",
            activity.contains("AppLogTags.RESTART"),
        )
    }

    @Test
    fun `a boot's run restarts from the gate that already watched the load`() {
        // The one path the helper's own message cannot cover: it is started with autorun, so its screen does
        // not ask this app for anything. This gate is already watching this boot's kernel for the load, so it
        // is the side holding both the reading and the grant - and the restart goes in after the root that
        // was watched for, and before the gate finishes, so a refused reboot still has somewhere to report.
        val gate = dfrBootService()
        assertTrue(
            "a boot whose helper loaded KernelSU never has the restart asked for, so the phone comes back " +
                "rooted with the load inert until somebody restarts it by hand",
            gate.contains("runRecoveryAction(this, RecoveryTool.SoftReboot)"),
        )
        assertTrue(
            "the boot gate restarts without asking the setting, which is the same rule the app's own screen " +
                "has to keep",
            gate.contains("AppPreferences.restartAfterRoot(this)"),
        )
        assertTrue(
            "the gate restarts before it has watched for the root this whole branch is about",
            gate.indexOf("awaitRoot()") < gate.indexOf("runRecoveryAction("),
        )
        assertTrue(
            "the restart is asked for after the gate has already finished and stopped its own scope",
            gate.indexOf("runRecoveryAction(") < gate.indexOf("finish(getString(R.string.dfr_boot_rerooted))"),
        )
        assertTrue(
            "a refused restart at boot is not reportable anywhere",
            gate.contains("AppLogTags.RESTART"),
        )
    }

    @Test
    fun `both sources were really read`() {
        // Every assertion above passes on an empty string, so a moved file would turn this class green.
        assertTrue(mainActivity().contains("class MainActivity"))
        assertTrue(dfrBootService().contains("class DfrBootService"))
    }

    private fun mainActivity(): String = source("app/src/main/java/dev/busung/s25uroot/MainActivity.kt")

    private fun dfrBootService(): String = source("app/src/main/java/dev/busung/s25uroot/DfrBootService.kt")

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
