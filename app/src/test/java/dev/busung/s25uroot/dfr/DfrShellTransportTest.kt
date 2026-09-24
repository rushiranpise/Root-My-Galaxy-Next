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
 * two of the things it does (`am start`, `pm uninstall`) are commands the `shell` user holds by itself. The
 * difference between a screen that reports and one that says it could not look is therefore one fallback,
 * and the difference between a boot that works and one that hangs for thirty seconds is the same fallback
 * *not* being taken there. Both are properties of code that needs a device to exercise - a rooted phone and
 * an unrooted one, a granted `su` and a prompt nobody answers - so they are held here, against the source.
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
        val action = declaration(installSource(), "fun runAction(")
        assertTrue("the install no longer runs as root", action.contains("KernelSuRuntime.rootShell"))
        assertFalse("the install tries a shell that cannot put a shared-user APK on the phone", action.contains("unprivilegedShell"))
    }

    @Test
    fun `the screen reaches those commands through DfrInstall rather than a shell of its own`() {
        // The screen's three actions and the shells they use, which is where a second, root-only call would
        // silently put the flow back to refusing on a boot with no root.
        val open = declaration(uiSource(), "fun open() =")
        assertTrue("opening the helper no longer goes through DfrInstall.launch", open.contains("DfrInstall.launch()"))
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
