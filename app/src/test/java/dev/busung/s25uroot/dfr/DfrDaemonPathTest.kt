package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon path, which is one string in two places and only one of them is ours.
 *
 * Stage two's shellcode bind-mounts and execs a path that is compiled into it, and the Kotlin side stages
 * a file at a path it names. Neither is derived from the other, nothing at runtime compares them, and the
 * failure when they disagree is silent up to the last step of the exploit - the shellcode simply finds
 * nothing to mount, long after the run looked healthy. So they are held together here, by reading both
 * sources, which is the only place the two can be compared without a device.
 *
 * The second assertion is a rule about this fork rather than a fact about the code: DFReroot's own app
 * stages *its* daemon at `/data/system/dfreroot-ksud`, both installs can be present at once, and a shared
 * path would mean one of them silently replacing the other's daemon between the stage and the run.
 */
class DfrDaemonPathTest {

    @Test
    fun `the path the exploit execs is the path the stage two writes`() {
        val compiledIn = stringAfterLabel(exploitSource(), "ksud_path")
        val staged = constantIn(stageTwoSource(), "DEST")

        assertEquals(
            "the shellcode in libexp.so bind-mounts one path and the stage two writes another, so the " +
                "exploit would find nothing to exec - and nothing but this comparison can notice",
            staged,
            compiledIn,
        )
    }

    @Test
    fun `the daemon is not staged where the other install stages its own`() {
        // A fork rule, not a coincidence: this install and DFReroot's can both be on the phone.
        assertNotEquals(
            "this install stages its daemon at DFReroot's own path, so whichever ran last would be the " +
                "daemon the other one execs",
            "/data/system/dfreroot-ksud",
            constantIn(stageTwoSource(), "DEST"),
        )
    }

    @Test
    fun `the daemon lives outside app storage and outside the shared temp directory`() {
        val staged = constantIn(stageTwoSource(), "DEST")
        // /data/system is what keeps it out of reach of every app on the phone, which is the whole
        // reason it is staged there rather than in /data/local/tmp like the rest of this app's files.
        assertTrue("a daemon under /data/local/tmp is readable by any app", !staged.startsWith("/data/local/tmp/"))
        assertTrue("the daemon is not staged under /data/system", staged.startsWith("/data/system/"))
    }

    @Test
    fun `the app stages the daemon at the path the exploit execs, and it is the same spelling`() {
        // The hand-off the helper's own doc assumes and that nothing used to make: without it the helper's
        // best source is always empty and it settles for another app's KernelSU.
        assertEquals(constantIn(stageTwoSource(), "DEST"), DfrInstall.STAGED_DAEMON)
        assertTrue(
            "the staging command does not write the path stage1.S execs",
            DfrInstall.stageDaemonCommand().contains("'${DfrInstall.STAGED_DAEMON}'"),
        )
    }

    @Test
    fun `the staged daemon carries the identity the module's policy expects`() {
        // 0700 system:system, which is what the helper - running as the system uid - can read and no app
        // can. A world-readable daemon under /data/system would be the same staging done unsafely.
        val command = DfrInstall.stageDaemonCommand()
        assertTrue(command.contains("chown system:system '${DfrInstall.STAGED_DAEMON}'"))
        assertTrue(command.contains("chmod 700 '${DfrInstall.STAGED_DAEMON}'"))
    }

    @Test
    fun `the flavour-correct sources are tried in order and a manager is never one of them`() {
        // The installed daemon first - that is the one the verified load put there, and it is outside
        // every shared directory - then the copy this app stages for its own runs.
        val command = DfrInstall.stageDaemonCommand()
        val installed = command.indexOf("'/data/adb/ksud'")
        val temp = command.indexOf("'/data/local/tmp/ksud-s25u-kdp'")
        assertTrue("the installed daemon is not a source: $command", installed >= 0)
        assertTrue("the app's own staged copy is not a source: $command", temp > installed)
        assertTrue("every source is checked for content, not existence", command.contains("[ -s '"))
    }

    @Test
    fun `with no source to stage, the command refuses instead of guessing`() {
        // Staging *a* daemon would be worse than staging none: the exploit would exec it and fail where
        // nothing points at why. Exit 3 is that refusal, and it is checked before anything is written.
        val command = DfrInstall.stageDaemonCommand()
        assertTrue("no refusal in: $command", command.contains("no daemon to stage"))
        assertTrue("the refusal does not come before the staging", command.indexOf("exit 3") < command.indexOf("cp -f"))
    }

    @Test
    fun `the stage two no longer takes a daemon out of an installed manager`() {
        // The measurement that put this rule here: a KernelSU-Next 3.4.0 kernel, and a daemon staged
        // byte-for-byte from me.weishu.kernelsu's bundle. Reading a manager's own libksud.so is how that
        // happened, so the read is gone and the list it used is kept only to name what was refused.
        val source = stageTwoSource()
        assertTrue(
            "the stage two reads a manager's libksud.so again, which is some other KernelSU's daemon",
            !source.contains("nativeLibraryDir"),
        )
        assertTrue("the refusal does not name the managers it passed over", source.contains("MANAGER_PACKAGES"))
    }

    @Test
    fun `a source that answers with the running version wins over one that merely exists`() {
        // Measured on this device: both sources exist, both answer `ksud 3.4.0 (uapi: 4)`, and they are
        // 5,518,544 and 4,230,992 bytes - two different builds. Choosing by "is it there" is a coin toss
        // presented as a decision, so the choice is made by asking each source what version it is.
        val command = DfrInstall.stageDaemonCommand(expectedVersion = "3.4.0")
        assertTrue("the expected version is not carried into the command: $command", command.contains("want='3.4.0'"))
        assertTrue("no source is asked for its version", command.contains("-V 2>/dev/null"))
        assertTrue("the version read comes after the staging", command.indexOf("-V 2>/dev/null") < command.indexOf("cp -f"))
        assertTrue(
            "a source is not matched against the running version",
            command.contains("case \"${'$'}v\" in *\"${'$'}want\"*"),
        )
        // The first source that exists is kept, but as the fallback and not as the answer: a device whose
        // installed daemon is a stale flavour must still be able to stage the one that matches.
        assertTrue("there is no fallback to the first source that exists", command.contains("alt='"))
        assertTrue(
            "the fallback is not used when nothing matched",
            command.indexOf("src=\"${'$'}alt\"") > command.indexOf("case \"${'$'}v\" in"),
        )
    }

    @Test
    fun `what was staged is reported with its version and whether it is the running one`() {
        val command = DfrInstall.stageDaemonCommand(expectedVersion = "3.4.0")
        assertTrue("the staged version is not named", command.contains("${'$'}{got:+ (${'$'}got)}"))
        assertTrue("a matching daemon is not said to match", command.contains("that is the daemon this device is running"))
        assertTrue("a mismatched daemon is not warned about", command.contains("a different version"))
    }

    @Test
    fun `an unreadable running daemon is never reported as a match`() {
        // A device whose version could not be read is the case where a false "matches" would be worst:
        // the run would exec a daemon nothing had compared, and the log would say it had been checked.
        val unknown = DfrInstall.stageDaemonCommand(expectedVersion = null)
        assertTrue("an empty expectation still claims a comparison", unknown.contains("cannot be compared"))
        // Guarded at runtime rather than only in wording: with nothing to match on, the command takes the
        // "not read" branch, and the comparison branches below it are never reached on the device.
        val guard = "if [ -z \"${'$'}want\" ]; then echo '[?]"
        assertTrue("the comparison is not guarded by the empty expectation", unknown.contains(guard))
        assertTrue(
            "the guard does not come before the match it is there to prevent",
            unknown.indexOf(guard) < unknown.indexOf("that is the daemon this device is running"),
        )

        // With nothing to match on, no source can be picked by version - so the order is the old one and
        // the report says the question was never answered.
        val blank = DfrInstall.stageDaemonCommand(expectedVersion = "  ")
        assertTrue(blank.contains("want=''"))
    }

    @Test
    fun `the staging command the app runs asks for the running daemon's version`() {
        // The rule has to be wired to the reading, or it is a parameter nobody passes: the app's own
        // staging call is the only caller, and it asks the probe that reads this boot's KernelSU.
        val source = source("app/src/main/java/dev/busung/s25uroot/dfr/DfrInstall.kt")
        assertTrue(
            "stageDaemon does not pass the running version",
            source.contains("KernelSuVersionProbe.read(context).daemon"),
        )
    }

    @Test
    fun `the shellcode passes the daemon no argument its cli does not declare`() {
        // The failure this pins, measured on the device: the shellcode exec'd
        //   late-load --package-name me.weishu.kernelsu --stage-from /data/system/rmgnext-ksud
        // against a ReSukiSU/Next daemon, which answered
        //   error: unexpected argument '--stage-from' found
        // and exited at argument parsing, before loading anything - while the exploit still reported
        // "Done. Check KSU Manager.", because its success marker only says the daemon was exec'd.
        // DFReroot's argv works for DFReroot because its shellcode and its daemon are the same fork.
        val source = exploitSource()
        assertTrue("the shellcode no longer asks for late-load", source.contains("\"late-load\""))
        assertTrue(
            "the shellcode passes --stage-from, which this daemon answers with a usage error",
            !source.contains("argv_stage_from"),
        )
        assertTrue(
            "the shellcode names a manager package instead of leaving the daemon's own default",
            !source.contains("argv_ksu"),
        )
    }

    @Test
    fun `the daemon is also left where its own late-load moves it from`() {
        // A late-loaded daemon installs itself: its late-load renames this path onto /data/adb/ksud
        // before it loads anything, and a missing file fails the whole command with "Failed to stage
        // ksud". The rename consumes it, so it is written for every run rather than once per install.
        val command = DfrInstall.stageDaemonCommand()
        assertTrue(
            "the daemon's own staging path is not written: $command",
            command.contains("'${DfrInstall.DAEMON_STAGE_PATH}'"),
        )
        // Held against the path the main install flow writes for its own late-load: one contract, two
        // callers, and both hand their file to the same daemon code.
        assertTrue(
            "InstallViewModel's stage path and DfrInstall's have come apart",
            source("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt")
                .contains("\"${DfrInstall.DAEMON_STAGE_PATH}\""),
        )
    }

    @Test
    fun `both sources were really read`() {
        // Every assertion above passes on an empty string, so a moved file would turn this whole class
        // green. The paths are resolved rather than assumed for the same reason.
        assertTrue(exploitSource().contains(".asciz"))
        assertTrue(stageTwoSource().contains("object KsudStage"))
    }

    /** The stage-two shellcode, wherever the test JVM was started from. */
    private fun exploitSource(): String = source("dfr/src/main/jni/stage1.S")

    /** The Kotlin that stages it. */
    private fun stageTwoSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt")

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))

    /** The first quoted string after [label], with the label's own comment lines stepped over. */
    private fun stringAfterLabel(text: String, label: String): String {
        val start = text.indexOf("$label:")
        assertTrue("no $label label in the source", start >= 0)
        val quote = text.indexOf('"', start)
        val end = text.indexOf('"', quote + 1)
        assertTrue("the $label label has no string after it", quote in start until end)
        return text.substring(quote + 1, end)
    }

    /** The value of a `val NAME = "..."` in Kotlin source. */
    private fun constantIn(text: String, name: String): String =
        Regex("""const val $name = "([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no `const val $name = \"...\"` in the source")
}
