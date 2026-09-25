package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting the payload's manager onto a phone that has none, and the three things that make it safe.
 *
 * The engine itself needs a device - a download, a shell, another app's installer - so what is held here
 * is everything about it that is a decision rather than a device read: the command it runs and what that
 * command is allowed to need, the order its four routes are tried in, the one outcome it must refuse to
 * hand to a person, and where the file it installs has to sit for the `shell` user to be able to read it.
 * Those are the parts a later change could quietly get wrong while every screen still looked right.
 *
 * The rest is the two call sites, because "the app installs the manager when it is missing" is only true
 * if both of them exist: the run's own step - which is what an armed retry, a notification and the boot
 * gate all go through - and the offer on the sheet that starts a run.
 */
class ManagerInstallTest {

    @Test
    fun `the install is a package manager install the shell user holds by itself`() {
        val command = ManagerInstall.installCommand("/data/local/tmp/rmgnext-manager.apk")
        assertTrue(
            "the manager is no longer installed with the package manager, so it would need root where the " +
                "shell user already holds this permission: $command",
            command.startsWith("/system/bin/pm install"),
        )
        assertTrue(
            "the installed copy is not the one the shell user can read, so the install would be refused " +
                "for a file it cannot open: $command",
            command.contains("'/data/local/tmp/rmgnext-manager.apk'"),
        )
        // `-r` so an earlier attempt's copy is replaced rather than refused, and `-d` because a version a
        // user named can legitimately be older than the one installed.
        assertTrue("a reinstall is refused again: $command", command.contains("-r"))
        assertTrue("an older manager can no longer be installed: $command", command.contains("-d"))
        assertTrue("the install is no longer aimed at this user: $command", command.contains("--user 0"))
    }

    @Test
    fun `the extracted APK is per flavour and per version`() {
        // Two flavours are two different projects' apps and two versions are two different downloads, so one
        // file for either would have a run install a manager the Settings row never offered. The name is
        // built from the two facts rather than from a counter, because it is also the cache key.
        val path = ManagerInstall.apkFileName("kernelsu-next", "3.4.0")
        assertEquals("kernelsu-next-3.4.0.apk", path)
        assertFalse(
            "two versions of one flavour share a file, so a run would install whichever was downloaded first",
            path == ManagerInstall.apkFileName("kernelsu-next", "3.5.0"),
        )
        assertFalse(
            "two flavours share a file, so one project's manager would be installed for the other",
            path == ManagerInstall.apkFileName("resukisu", "3.4.0"),
        )
    }

    @Test
    fun `the directory the APK is written to is one the file provider serves`() {
        // The installer route hands the platform a content:// URI, and the FileProvider refuses to build one
        // for a file outside the paths it declares - at the moment of handing it over, on the route a phone
        // with no root and no Shizuku always takes.
        val paths = xml("src/main/res/xml/file_paths.xml")
        assertTrue(
            "no cache path for ${ManagerInstall.APK_DIRECTORY}/, so the phone's installer would be handed an " +
                "APK the file provider will not serve",
            paths.contains("path=\"${ManagerInstall.APK_DIRECTORY}/\""),
        )
    }

    @Test
    fun `a phone that cannot be installed to silently is asked, not assumed`() {
        // The order of the four routes is the rule: root first because it installs out of app storage and
        // leaves nothing behind, the two shell users next, and the phone's installer last because it is the
        // only one that needs a person.
        val body = objectBody()
        val root = body.indexOf("ManagerInstallRoute.Root")
        val shell = body.indexOf("ManagerInstallRoute.Shell")
        val wireless = body.indexOf("ManagerInstallRoute.WirelessAdb")
        val installer = body.indexOf("AppUpdater.installApk")
        assertTrue("the engine has no root route", root > 0)
        assertTrue("a shell install happens before root is tried", shell > root)
        assertTrue("the pairing is tried before Shizuku", wireless > shell)
        assertTrue("the phone's installer is not the last resort", installer > wireless)
        // And the one outcome that must not go to a person: an unattended run, where an installer dialog is a
        // window on a phone with nobody in front of it.
        assertTrue(
            "an unattended run still opens the phone's installer, which is a dialog nobody can answer",
            body.contains("if (!handToInstaller) {") && body.indexOf("if (!handToInstaller) {") < installer,
        )
    }

    @Test
    fun `the shell route stages a copy where the shell user can read it`() {
        // `pm install` reads the APK as whoever asked for it, and this app's own storage is mode 0700 under
        // its own uid. The copy is written through Shizuku's file path into the directory the shell user
        // owns, which is also why the residue catalogue has to name it.
        val source = engineSource()
        assertTrue(
            "the manager APK is no longer staged for the shell user, so an install without root would be " +
                "refused for a file it cannot read",
            source.contains("ShizukuController.writeFile(SHELL_APK_PATH, \"644\""),
        )
        val catalogued = StagedResidue.catalog.filter { it.path == ManagerInstall.SHELL_APK_PATH }
        assertEquals(
            "the staged manager copy is not in the residue catalogue exactly once, so it would be left in " +
                "/data/local/tmp where the screen that lists this app's leftovers cannot name it",
            1,
            catalogued.size,
        )
        assertEquals(
            "the staged manager copy is catalogued as something other than a manager APK, so the list " +
                "would describe a download as this project's own file",
            ResidueRole.ManagerApk,
            catalogued.single().role,
        )
    }

    @Test
    fun `the run installs the manager before it settles the boot for the exploit`() {
        // The ordering is the point and it is not obvious: an install is network, `installd` and dexopt work,
        // and the settle exists to let this boot go quiet before the exploit runs - so the churn has to be in
        // front of the wait, not inside the exploit's window.
        val run = viewModelSource()
        val step = run.indexOf("ensureManager(profile.flavor")
        // The call in the run, not the declaration of the wait: what the rule is about is the order the run
        // does these two things in, and a declaration could sit anywhere in the file while the order it is
        // used in is unchanged.
        val settle = run.indexOf("awaitBootSettle(AppPreferences.bootSettleSeconds(app))")
        assertTrue("a run no longer ensures the manager at all", step > 0)
        assertTrue("the run no longer waits for the boot to settle", settle > 0)
        assertTrue(
            "the manager is installed after the boot has been settled, so an install's churn lands in the " +
                "exploit's window instead of in front of it",
            step < settle,
        )
    }

    @Test
    fun `a manager that cannot be installed does not fail the run`() {
        // The kernel does not need the manager - the manager is how the root is *used* afterwards - so
        // turning this into a failed run would refuse the thing the user asked for over an app that can be
        // installed any time. Every failure is a line in the log and the run goes on.
        val body = declaration(viewModelSource(), "private suspend fun ensureManager(")
        assertFalse(
            "the manager step throws, so a phone whose manager could not be installed would not be rooted " +
                "at all",
            body.contains("error(") || body.contains("require("),
        )
        assertTrue(
            "the manager step no longer survives its own failure, so one thrown exception would take the run " +
                "down with it",
            body.contains("getOrElse"),
        )
        // The arm itself, and not a word that appears somewhere in the function: the failure sentence is used
        // by two arms, so a check that only named the string would pass on a verdict that had stopped
        // reporting why. What is held is that this arm says what failed and says it as a failure.
        val failedArm = body.substringAfter("ManagerInstallVerdict.Failed -> ")
            .substringBefore("ManagerInstallVerdict.")
        assertTrue(
            "a failed manager install is no longer reported as one, so the one outcome that leaves the " +
                "phone unready would read like nothing happened: $failedArm",
            failedArm.contains("R.string.log_manager_failed") && failedArm.contains("outcome.detail"),
        )
    }

    @Test
    fun `the sheet that starts a run says what the run will do about the manager, and installs nothing`() {
        // The sheet used to carry its own offer to install the manager. A run installs that manager itself
        // before it does anything else, so the offer was the same install asked for a second time - and it
        // was asked for before the run had said what it was going to do, which is the one place this is
        // decided. The sheet states the state; the run acts on it.
        val activity = appSource()
        assertTrue(
            "the sheet offers to install the manager again, so a phone with none is asked the same question " +
                "twice, by two things that can disagree about the answer",
            !activity.contains("R.string.action_install_manager"),
        )
        assertTrue(
            "the sheet installs a manager itself again, which is the run's step and not the sheet's",
            !activity.contains("ManagerInstall.install("),
        )
        assertTrue(
            "the sheet no longer says which of the two states the phone is in, so a run that will stop to " +
                "install an app would look like any other",
            activity.contains("R.string.install_confirm_manager,") &&
                activity.contains("R.string.install_confirm_manager_ready,"),
        )
    }

    @Test
    fun `an outcome is put into words in one place`() {
        // Three callers ask for this: the sheet, the run's own step, and anything after it. The choice is
        // pure so it can be held here - the sentence a verdict gets, and the two arguments it must carry.
        val flavor = KernelSuFlavor.KernelSuNext
        assertEquals(
            ManagerSentence(R.string.manager_present, listOf(flavor.label, "3.4.0")),
            managerOutcomeLine(
                ManagerInstallOutcome(flavor, ManagerInstallVerdict.AlreadyInstalled, version = "3.4.0"),
                versionUnread = "version could not be read",
            ),
        )
        assertEquals(
            "an installed manager whose version cannot be read is reported as a number nobody read",
            ManagerSentence(R.string.manager_present, listOf(flavor.label, "version could not be read")),
            managerOutcomeLine(
                ManagerInstallOutcome(flavor, ManagerInstallVerdict.AlreadyInstalled, version = null),
                versionUnread = "version could not be read",
            ),
        )
        assertEquals(
            ManagerSentence(R.string.manager_installed_toast, listOf(flavor.label)),
            managerOutcomeLine(ManagerInstallOutcome(flavor, ManagerInstallVerdict.Installed), "x"),
        )
        assertEquals(
            ManagerSentence(R.string.manager_handed_over_toast),
            managerOutcomeLine(
                ManagerInstallOutcome(flavor, ManagerInstallVerdict.Requested, route = ManagerInstallRoute.Installer),
                "x",
            ),
        )
        assertEquals(
            "a refusal is reported without the reason it refused",
            ManagerSentence(R.string.manager_failed_toast, listOf(flavor.label, "no shell can install it")),
            managerOutcomeLine(
                ManagerInstallOutcome(
                    flavor,
                    ManagerInstallVerdict.Failed,
                    detail = "no shell can install it",
                ),
                "x",
            ),
        )
    }

    private fun engineSource(): String = source("src/main/java/dev/busung/s25uroot/ManagerInstall.kt")

    private fun viewModelSource(): String =
        source("src/main/java/dev/busung/s25uroot/InstallViewModel.kt")

    private fun appSource(): String = source("src/main/java/dev/busung/s25uroot/MainActivity.kt")

    private fun xml(relativeToApp: String): String = source(relativeToApp)

    /** The body of `install`: from its declaration to the `awaitUserInstall` it ends on. */
    private fun objectBody(): String = declaration(engineSource(), "suspend fun install(")

    /** One function's text, up to the next line that starts a new member. */
    private fun declaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found, so this test read nothing of it", start >= 0)
        val rest = source.substring(start)
        // The next `\n    }` at the member's own indent ends it: nested blocks are indented further.
        val end = rest.indexOf("\n    }")
        return if (end > 0) rest.substring(0, end) else rest
    }

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativeToApp was not found from ${File(".").absolutePath}")
}
