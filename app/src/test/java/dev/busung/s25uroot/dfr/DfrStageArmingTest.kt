package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping the daemon stage file armed, which is what a boot with no root depends on.
 *
 * The file the daemon's own `late-load` renames onto `/data/adb/ksud` is *consumed* by every run that uses
 * it - a payload's and the system-uid helper's - and nothing puts it back on its own. So the sequence
 * "root now, restart" leaves the next boot with a helper that starts, looks for a daemon, finds none and
 * aborts with `Failed to stage ksud`, which reads as the exploit having failed. Two things have to hold for
 * that not to happen: the app has to re-stage the file wherever it knows it has root, and it has to be able
 * to ask whether that is even needed without paying for a copy every time it is asked.
 *
 * Both are decisions rather than device behaviour, so they are held here: the marker read, the shape of the
 * check, and which transport each of the two callers is given.
 */
class DfrStageArmingTest {

    @Test
    fun `only the marker for the daemon being in place counts as armed`() {
        assertTrue(DfrInstall.stageArmed("RMG-stage=armed"))
        // The other three all lead to the same step - stage it again - and none of them may read as armed:
        // a missing file, a copy of another build, and a running daemon that could not be read.
        assertFalse("a missing stage file is not an armed one", DfrInstall.stageArmed("RMG-stage=absent"))
        assertFalse("another build's daemon at that path is not armed", DfrInstall.stageArmed("RMG-stage=different"))
        assertFalse(
            "a version that could not be read cannot say it is armed",
            DfrInstall.stageArmed("RMG-stage=uncompared"),
        )
        assertFalse("nothing answered, so nothing is known to be in place", DfrInstall.stageArmed(null))
        assertFalse("an empty answer is not an armed one", DfrInstall.stageArmed(""))
    }

    @Test
    fun `each marker is its own answer, because what to do about each one differs`() {
        assertEquals(DfrStageReading.Armed, DfrInstall.stageReadingOf("RMG-stage=armed"))
        assertEquals(DfrStageReading.Absent, DfrInstall.stageReadingOf("RMG-stage=absent"))
        assertEquals(DfrStageReading.Different, DfrInstall.stageReadingOf("RMG-stage=different"))
        assertEquals(DfrStageReading.Uncompared, DfrInstall.stageReadingOf("RMG-stage=uncompared"))
    }

    @Test
    fun `an answer nobody could place is never reported as armed`() {
        // The one direction of that mistake the next boot cannot recover from on its own: the file the
        // late-load needs is not there and the row says it is, so the restart nothing explains is the one
        // nobody looks into.
        assertEquals(DfrStageReading.Unreadable, DfrInstall.stageReadingOf(null))
        assertEquals(DfrStageReading.Unreadable, DfrInstall.stageReadingOf(""))
        assertEquals(
            DfrStageReading.Unreadable,
            DfrInstall.stageReadingOf("sh: /data/local/tmp/.ksud-stage: not found"),
        )
    }

    @Test
    fun `the row and the arming decision read the same answer the same way`() {
        // One is the boolean the staging branches on and the other is what a screen shows. A marker that
        // meant different things to the two would either copy five megabytes over a file that is already
        // in place or show a row that contradicts what the write just did.
        listOf(
            "RMG-stage=armed",
            "RMG-stage=absent",
            "RMG-stage=different",
            "RMG-stage=uncompared",
            null,
            "",
        ).forEach { output ->
            assertEquals(
                "$output: the arming decision and the settings row disagree about it",
                DfrInstall.stageArmed(output),
                DfrInstall.stageReadingOf(output) == DfrStageReading.Armed,
            )
        }
    }

    @Test
    fun `the reading is taken on whichever shell the phone has, and asks the running version`() {
        val body = declaration(installSource(), "fun readDaemonStage(")
        assertTrue(
            "the stage file is read through root only, so the phone this reading is for - a boot with no " +
                "root - is the one that cannot be told whether its next restart has a daemon to load",
            body.contains("runOnEitherShell("),
        )
        assertTrue(
            "the reading no longer asks for the running daemon's version, so any copy at that path is " +
                "called armed - including another flavour's, whose interface the module in the kernel does " +
                "not match",
            body.contains("runningDaemonVersion(context)"),
        )
        assertTrue(
            "the reading no longer asks the question the arming asks, so what the row says and what the " +
                "next write does can come apart",
            body.contains("stageArmedCommand("),
        )
    }

    @Test
    fun `the settings row shows the reading, and the reason it is not armed`() {
        val shell = shellSource()
        assertTrue(
            "no settings row reads the stage file, so nothing on the screen says whether a restart would " +
                "have a daemon to late-load",
            shell.contains("DfrInstall.readDaemonStage(context)"),
        )
        assertTrue(
            "the row no longer shows the reading's own value",
            shell.contains("daemonStage?.let { stringResource(it.label) }"),
        )
        assertTrue(
            "the row no longer says why it is not armed, which is the half of the answer that has an action " +
                "behind it",
            shell.contains("?.takeIf { it != DfrStageReading.Armed }"),
        )
    }

    @Test
    fun `the check reads the file the late-load renames and copies nothing`() {
        val command = DfrInstall.stageArmedCommand(expectedVersion = "ksud 3.4.0 (uapi: 4)")
        assertTrue(
            "the check no longer looks at the file the late-load renames, which is the only one whose " +
                "absence stops the next boot",
            command.contains(DfrInstall.DAEMON_STAGE_PATH),
        )
        assertFalse(
            "the check copies the daemon, so asking whether it is needed costs what doing it costs - and " +
                "this runs every time the app comes back to the foreground",
            command.contains("/system/bin/cp"),
        )
        assertFalse(
            "the check now looks at the copy the exploit execs, which whoever is about to run rewrites on " +
                "the way in - so its absence is not what stops a boot",
            command.contains(DfrInstall.STAGED_DAEMON),
        )
        // A version and not an existence check, and asked the same two ways the staging asks: a copy of
        // another flavour is a file that is there and is the wrong daemon.
        assertTrue("the check no longer asks the staged file what version it is", command.contains("-V"))
        assertTrue("the daemon's other spelling of --version was dropped", command.contains("--version"))
        assertTrue("the expected version is not compared against it", command.contains("RMG-stage=armed"))
    }

    @Test
    fun `the version the staging prefers is read the same way for the check`() {
        // One spelling, or the check and the staging can disagree about whether a copy is the right one -
        // and the check is what decides whether the staging happens at all.
        val staging = DfrInstall.stageDaemonCommand(expectedVersion = "ksud 3.4.0 (uapi: 4)")
        val check = DfrInstall.stageArmedCommand(expectedVersion = "ksud 3.4.0 (uapi: 4)")
        listOf("-V", "--version").forEach { spelling ->
            assertTrue("the staging no longer asks `$spelling`", staging.contains(spelling))
            assertTrue("the check no longer asks `$spelling`", check.contains(spelling))
        }
    }

    @Test
    fun `arming is asked of the phone, and refused before anything is run when it has no root`() {
        val body = declaration(installSource(), "fun armStageForNextBoot(")
        assertTrue(
            "the re-arming no longer asks whether the phone has root, so a phone without one is made to " +
                "wait on a root shell it cannot have before every command it has is refused",
            body.contains("RootStatusProbe.isActive()"),
        )
        assertTrue(
            "a phone with no root is not refused before the shell is opened",
            body.indexOf("RootStatusProbe.isActive()") < body.indexOf("rootShell"),
        )
    }

    @Test
    fun `the check runs before the staging, so a phone that is already armed copies nothing`() {
        val body = declaration(installSource(), "fun armStageForNextBoot(")
        val check = body.indexOf("stageArmedCommand")
        val staged = body.indexOf("stageDaemonAs")
        assertTrue("the re-arming no longer checks first", check >= 0)
        assertTrue("the re-arming no longer stages anything", staged >= 0)
        assertTrue(
            "the daemon is written before anything asks whether it has to be, so every return to the " +
                "foreground copies five megabytes twice for nothing",
            check < staged,
        )
        assertTrue("the outcome of the staging is no longer reported at all", body.contains("DfrStageArming.Armed"))
        assertTrue(
            "a phone with no root is no longer distinguished from one whose write failed, so every return " +
                "to the foreground on an unrooted phone logs a warning about the next boot",
            body.contains("DfrStageArming.NoRoot"),
        )
        assertTrue("a failed write is no longer reported as one", body.contains("DfrStageArming.Failed"))
    }

    @Test
    fun `the run writes the daemon back through the transport that still has root`() {
        // Not through the app's own root shell: a first install has not been granted `su` yet, so asking for
        // one at the end of the run is a prompt nobody asked for or a minute spent waiting for it. The run
        // holds a shell of its own - Shizuku's elevated one, or the bootstrap helper - and that is the same
        // transport its own module handling above uses.
        val body = declaration(viewModelSource(), "private suspend fun installKernelSu(")
        assertTrue(
            "the run no longer writes the daemon back, so the next boot has nothing to late-load",
            body.contains("DfrInstall.stageDaemonCommand("),
        )
        assertTrue(
            "the staging no longer goes through the run's own transport",
            body.contains("runMaintenance(\n") && body.contains("DfrInstall.stageDaemonCommand("),
        )
        assertFalse(
            "the run asks the app's own root shell for the staging, which on a first install is a grant " +
                "this app has not been given",
            body.contains("DfrInstall.stageDaemon(") || body.contains("DfrInstall.armStageForNextBoot("),
        )
        assertTrue(
            "the daemon is written back before the load was confirmed, so it is written on runs that " +
                "loaded nothing and the file it writes is the one that run just consumed either way",
            body.indexOf("storeInstallReceipt()") < body.indexOf("DfrInstall.stageDaemonCommand("),
        )
    }

    @Test
    fun `a screen that comes back to the foreground arms it too, off the main thread`() {
        val shell = block(shellSource(), "LaunchedEffect(resumeTick) {")
        assertTrue(
            "no return to the foreground arms the stage file, so a phone rooted by a hand-run or by the " +
                "helper is left with whatever the last run left behind it",
            shell.contains("DfrInstall.armStageForNextBoot(context)"),
        )
        assertTrue(
            "the arming runs on the main thread, where the root reading behind it can start a process and " +
                "a five-megabyte copy can be a frozen frame",
            shell.indexOf("withContext(Dispatchers.IO)") < shell.indexOf("DfrInstall.armStageForNextBoot"),
        )
        // Only the failure, and the shell is where that matters: this runs on every return to the
        // foreground, so a line for the armed phone and a line for the unrooted one would both be noise.
        assertTrue(
            "the one outcome the next boot cannot recover from on its own is not reported",
            shell.contains("DfrStageArming.Failed"),
        )
        assertFalse(
            "the shell reports the phone simply having no root, which is a state rather than a fault",
            shell.contains("DfrStageArming.NoRoot"),
        )
        // One call and no second one: a screen that armed it somewhere of its own would be a second place
        // for the check-inside-the-arming to be forgotten, and the cost of forgetting it is a five-megabyte
        // copy on every return to the foreground.
        assertEquals(
            "the stage is armed from more than one place in the shell",
            1,
            Regex("DfrInstall\\.armStageForNextBoot\\(context\\)").findAll(shellSource()).count(),
        )
    }

    private fun installSource() = source("src/main/java/dev/busung/s25uroot/dfr/DfrInstall.kt")

    private fun viewModelSource() = source("src/main/java/dev/busung/s25uroot/InstallViewModel.kt")

    private fun shellSource() = source("src/main/java/dev/busung/s25uroot/MainActivity.kt")

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$relativeToApp was not found from ${File(".").absolutePath}")

    /**
     * One braced block's own text, from [signature] to the brace that closes it.
     *
     * For the one assertion here about a block rather than a declaration: what follows it in the shell is a
     * comment and a `CompositionLocalProvider`, neither of which reads as a declaration, so an
     * indentation-based slice would run on through both of them.
     */
    private fun block(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found in the source", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        throw AssertionError("$signature has no closing brace")
    }

    /**
     * One declaration's own text: the function at [signature], up to the next thing declared beside it.
     *
     * The boundary is the next declaration at the same indentation rather than the closing brace, because
     * several of these are expression-bodied one-liners: a search for the first `{` after one of those runs
     * on into the next function and reports about code the assertion was never about.
     */
    private fun declaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found in the source", start >= 0)
        val lineStart = source.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indent = source.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        val end = Regex(
            "\n" + Regex.escape(indent) +
                "(?:@|/\\*\\*|(?:internal |private |public )?(?:fun|val|var|const|object|class|enum|data) )",
        ).find(source, start + signature.length)?.range?.first ?: throw AssertionError(
            "no declaration follows $signature, so this slice would have run to the end of the file - and " +
                "every assertion made about it could then pass by reading somebody else's code",
        )
        return source.substring(start, end)
    }
}
