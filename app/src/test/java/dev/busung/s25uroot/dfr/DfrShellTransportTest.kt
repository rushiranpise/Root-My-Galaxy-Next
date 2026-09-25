package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shell each part of the system-uid flow runs on, at boot and at the screen.
 *
 * The flow is for a phone whose root comes from the helper, so the state it is opened in most often is a
 * reboot with **no root at all** - and everything it reads (`pm list packages`, one `test -e`, `pidof`) and
 * three of the things it does (`am start`, `pm uninstall`, `pm install`) are commands the `shell` user holds
 * by itself. The difference between a screen that reports and one that says it could not look is therefore
 * one fallback, and the difference between a boot that works and one that hangs for thirty seconds is the
 * same fallback *not* being taken there. Both are properties of code that needs a device to exercise - a
 * rooted phone and an unrooted one, a granted `su` and a prompt nobody answers - so they are held here,
 * against the source.
 *
 * The install is in that list because it is what makes a *helper* the repairable thing: a phone that has
 * rebooted with a stale copy, or none, can be put right from this screen before it has ever been rerooted,
 * which is the state the flow is opened in whenever the problem is the helper rather than the root. What
 * that route costs is a second copy of the APK somewhere the `shell` user can read - app storage is not.
 */
class DfrShellTransportTest {

    @Test
    fun `a screen's probe is taken on whichever shell the phone has`() {
        val body = declaration(installSource(), "fun probe():")
        assertTrue(
            "the reading the screen opens with is root-only again, so the phone this flow exists for - " +
                "a fresh boot with no root - is reported as a device that could not be read",
            body.contains("runOnEitherShell"),
        )
        assertTrue("the probe is no longer read through a shell at all", body.contains("probeOf"))
    }

    @Test
    fun `a screen's launch is sent on whichever shell the phone has`() {
        val body = declaration(installSource(), "internal fun launch(")
        assertTrue(
            "opening the helper is root-only again, and that is the one press that still helps on a boot " +
                "with no root: the helper is what puts root back",
            body.contains("runOnEitherShell"),
        )
        assertTrue(
            "the launch no longer asks for the `am` refusal word, so a start that reached nothing is " +
                "reported as the helper having been opened",
            body.contains("LAUNCH_FAILURE"),
        )
    }

    @Test
    fun `a screen's uninstall is sent on whichever shell the phone has`() {
        val body = declaration(installSource(), "internal fun uninstallStageTwo(")
        assertTrue(
            "removing the helper is root-only again, so a setup cannot be undone on the boot that has not " +
                "been rerooted yet - which is the boot somebody wanting to undo it is looking at",
            body.contains("runOnEitherShell"),
        )
        assertTrue("the uninstall no longer names the package", body.contains("uninstallCommand()"))
    }

    @Test
    fun `root is tried first and the plain shell is the fallback`() {
        // The order, which is the whole reason a rooted phone never touches Shizuku here: `rootShell` falls
        // back through KernelSU's own `su` and needs nothing else running, while the plain shell needs a
        // server that is up and granted.
        val body = declaration(installSource(), "private fun runOnEitherShell(")
        val root = body.indexOf("KernelSuRuntime.rootShell(")
        val plain = body.indexOf("KernelSuRuntime.unprivilegedShell(")
        assertTrue("the chain no longer tries root", root >= 0)
        assertTrue("the chain no longer falls back to the plain shell", plain >= 0)
        assertTrue("the plain shell is tried before root, which needs nothing else running", root < plain)
    }

    @Test
    fun `the boot gate's two readings never touch the root half`() {
        // The same fallback that makes the screen work is a wait on a boot: `rootShell` can end inside `su`,
        // and a `su` waiting on a grant prompt with nobody at the screen waits out its whole timeout. The
        // boot gate decides whether to spend the boot's one attempt, so it is given the plain shell only.
        listOf("fun probeWithoutRoot(", "internal fun launchWithoutRoot(").forEach { signature ->
            val body = declaration(installSource(), signature)
            assertTrue(
                "$signature no longer reads through the plain shell",
                body.contains("unprivilegedShell"),
            )
            assertFalse(
                "$signature runs through the root half, where a boot can wait out a `su` grant prompt " +
                    "nobody is there to answer",
                body.contains("rootShell"),
            )
            assertFalse(
                "$signature takes the screen's fallback, which tries root first",
                body.contains("runOnEitherShell"),
            )
        }
    }

    @Test
    fun `the readings and actions that need root are still root-only`() {
        // The other half of the rule: the fallback is for commands the `shell` user holds, and every write
        // to `packages.xml` is not one of them. A fallback added here by reflex would report a failed
        // inject as a device that has no root - which is the same sentence for a different problem.
        val inject = declaration(installSource(), "fun run(")
        assertTrue("the inject no longer runs as root", inject.contains("KernelSuRuntime.rootShell"))
        assertFalse("the inject tries the plain shell, which cannot write packages.xml", inject.contains("unprivilegedShell"))
        val removal = declaration(installSource(), "fun removeAndRestart(")
        assertTrue(
            "the removal and the restart no longer run as root, so the write to packages.xml is attempted " +
                "by a shell that cannot make it - and the phone restarts with the key still in the file",
            removal.contains("KernelSuRuntime.rootShell"),
        )
        assertFalse(
            "the combined command tries the plain shell, which cannot write packages.xml",
            removal.contains("unprivilegedShell"),
        )
        val action = declaration(installSource(), "fun runAction(")
        assertTrue("the daemon staging no longer runs as root", action.contains("KernelSuRuntime.rootShell"))
        assertFalse(
            "the daemon staging tries the plain shell, where the `chown system:system` the module's policy " +
                "expects cannot be made - and half a staged daemon fails inside the exploit, which reads " +
                "as the exploit being broken",
            action.contains("unprivilegedShell"),
        )
    }

    @Test
    fun `the install is sent on whichever shell the phone has`() {
        // The third action the `shell` user holds, and the reason this is not a write like the inject: what
        // decides whether an APK is accepted under android.uid.system is Package Manager's signature test
        // against that user's list, and it does not care who asked. What a wrong answer would look like -
        // a helper installed as an ordinary app - is caught by the uid the next reading reports, with the
        // flow's own remove step as the way back.
        val body = declaration(installSource(), "internal fun installStageTwo(")
        assertTrue(
            "installing the helper is root-only again, so a phone that rebooted with a stale or missing " +
                "helper can only be repaired by rooting it first - which is what the helper is for",
            body.contains("KernelSuRuntime.unprivilegedShell("),
        )
        val root = body.indexOf("runAction(installCommand(")
        val plain = body.indexOf("KernelSuRuntime.unprivilegedShell(")
        assertTrue(
            "the rooted install is no longer tried first, so a rooted phone pays for a file transfer it " +
                "does not need",
            root >= 0 && root < plain,
        )
        assertTrue(
            "the shell route installs something other than the copy staged for it",
            body.contains("installCommand(staged)"),
        )
    }

    @Test
    fun `the copy the shell installs from is one the shell user may read`() {
        // The trap this exists for: `pm install` reads the APK as whoever asked for it, and this build's
        // copy lives in app storage - mode 0700 under the app's own uid - which the `shell` user cannot
        // open. So the route that has no root needs its own copy in the shell's own directory, written the
        // way a run writes its payload: over the Shizuku binder, which is the only thing here that can
        // write as the shell user at all.
        assertTrue(
            "the helper is staged somewhere the shell user cannot be relied on to read: " +
                DfrInstall.SHELL_INSTALL_PATH,
            DfrInstall.SHELL_INSTALL_PATH.startsWith("/data/local/tmp/"),
        )
        val body = declaration(installSource(), "private fun stageForShell(")
        assertTrue(
            "the copy for the shell route is not written over the Shizuku binder",
            body.contains("ShizukuController.writeFile(SHELL_INSTALL_PATH"),
        )
    }

    @Test
    fun `the key check is taken on whichever shell the phone has`() {
        // A read and not a write: `--check` parses `packages.xml` and prints one verdict per target, and it
        // is the reading that decides whether a phone whose helper is gone is offered its install step or
        // told that nothing could be read. Refused, it is null - which is the state the flow was already
        // in, so trying cannot make the screen claim something nobody read.
        val body = declaration(installSource(), "internal fun checkInjected(")
        assertTrue("the key check is root-only again", body.contains("runOnEitherShell("))
        assertTrue(
            "the key check no longer asks for the mode that writes nothing",
            body.contains("DfrMode.Check"),
        )
        assertFalse(
            "a mode that writes packages.xml is being run through the shell, which cannot make that write",
            body.contains("DfrMode.Inject") || body.contains("DfrMode.Uninstall"),
        )
    }

    @Test
    fun `the screen's install and its key check are reached through DfrInstall`() {
        val install = declaration(uiSource(), "fun install() =")
        assertTrue(
            "the install no longer takes the shell route, so the step a phone with a stale helper is on " +
                "goes back to refusing for want of root",
            install.contains("DfrInstall.installStageTwo("),
        )
        assertTrue(
            "the screen's key check no longer falls back to the plain shell, which is what a phone with a " +
                "missing helper and no root has instead of a root shell",
            uiSource().contains("DfrInstall.checkInjected(context)"),
        )
    }

    @Test
    fun `the screen reaches those commands through DfrInstall rather than a shell of its own`() {
        // The screen's three actions and the shells they use, which is where a second, root-only call would
        // silently put the flow back to refusing on a boot with no root.
        val open = declaration(uiSource(), "fun open() =")
        // The call and the flavour it carries, rather than one line of it: the extras it hands over are
        // named one per line, and a check on the first line alone would break every time another one is
        // added - which is a test of formatting wearing the clothes of a test of behaviour.
        assertTrue(
            "opening the helper no longer goes through DfrInstall.launch with the flavour the run loads",
            open.contains("DfrInstall.launch(") &&
                open.contains("flavor = AppPreferences.kernelsuFlavor(context)"),
        )
        val remove = declaration(uiSource(), "fun removeStageTwo() =")
        assertTrue(
            "removing the helper no longer goes through the shell-capable uninstall",
            remove.contains("DfrInstall.uninstallStageTwo()"),
        )
        val cleanUp = declaration(uiSource(), "fun cleanUp() =")
        assertTrue(
            "the clean-up's helper half no longer goes through the shell-capable uninstall, so the half " +
                "that does not need root is refused for want of it",
            cleanUp.contains("DfrInstall.uninstallStageTwo()"),
        )
        assertFalse(
            "the screen runs a root shell of its own, where nothing is checking that the command is one " +
                "the shell user holds",
            listOf("open() =", "fun removeStageTwo() =", "fun cleanUp() =")
                .any { declaration(uiSource(), it).contains("KernelSuRuntime.rootShell") },
        )
    }

    private fun installSource() = source("src/main/java/dev/busung/s25uroot/dfr/DfrInstall.kt")

    private fun uiSource() = source("src/main/java/dev/busung/s25uroot/DfrUi.kt")

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$relativeToApp was not found from ${File(".").absolutePath}")

    /**
     * One declaration's own text: the function at [signature], up to the next thing declared beside it.
     *
     * The boundary is the next declaration at the same indentation rather than the function's closing brace,
     * because several of these are expression-bodied one-liners: a search for the first `{` after one of those
     * runs on into the next function and reports about code the assertion was never about. It is also not
     * simply "the next line that is not indented further", because a signature wrapped over several lines
     * ends with a line at the declaration's own indentation. See [nextDeclaration].
     */
    private fun declaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found in the source", start >= 0)
        val lineStart = source.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indent = source.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        val end = nextDeclaration(source, start + signature.length, indent) ?: throw AssertionError(
            "no declaration follows $signature, so this slice would have run to the end of the file - and " +
                "every assertion made about it could then pass by reading somebody else's code",
        )
        return source.substring(start, end)
    }
}

/**
 * Where the first declaration at [indent] after [from] begins, or null when there is none.
 *
 * A line at that indentation *that reads like a declaration*, which is stricter than "the next line that is
 * not indented further" and has to be: a signature wrapped over several lines ends with a line at the same
 * indentation, and the loose rule stops there and hands back half a signature.
 */
private fun nextDeclaration(source: String, from: Int, indent: String): Int? {
    val pattern = Regex(
        "\n" + Regex.escape(indent) +
            "(?:@|/\\*\\*|(?:internal |private |public )?(?:fun|val|var|const|object|class|enum|data) )",
    )
    return pattern.find(source, from)?.range?.first
}
