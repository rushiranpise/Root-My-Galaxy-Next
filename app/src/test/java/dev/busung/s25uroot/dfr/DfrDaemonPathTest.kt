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
