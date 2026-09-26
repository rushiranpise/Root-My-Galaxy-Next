package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files a stage-two run stands on, held to the code that reads, patches and execs them.
 *
 * The list is a second copy of facts that live somewhere else: the paths the shellcode has compiled into it,
 * the daemon the helper stages and the file its late-load consumes, and the name of the library this APK
 * loads as the exploit. None of those can be read from the other at runtime, and the failure when they
 * disagree is the one this whole change exists to remove - a run that starts, spends the boot, and stops at
 * a step whose reason was a file that was not there when it began.
 *
 * So the list is compared with its sources here, by reading both: the shellcode's own string labels, the
 * app's staging constants, and the module's own JNI build.
 */
class StageTwoNeedsTest {

    @Test
    fun `every path the shellcode has compiled in is on the list`() {
        // The shellcode bind-mounts, patches and execs these by name, and a phone where one of them moved
        // is one where the run cannot get past that step. Compared rather than restated: the strings are
        // read out of stage1.S so a path that changes there cannot leave this list naming the old one.
        val shellcode = exploitSource()
        val compiledIn = mapOf(
            "logcat_path" to stringAfterLabel(shellcode, "logcat_path"),
            "executable_path" to stringAfterLabel(shellcode, "executable_path"),
            "lib_path" to stringAfterLabel(shellcode, "lib_path"),
        )
        assertTrue(
            "no shellcode strings were read, so this test would pass on an empty file",
            compiledIn.values.all { it.startsWith("/") },
        )
        val needs = needsSource()
        compiledIn.forEach { (label, path) ->
            assertTrue(
                "the shellcode's $label is $path and the helper's list of required files does not name it, " +
                    "so a run would start on a phone where that step cannot work",
                needs.contains("\"$path\""),
            )
        }
    }

    @Test
    fun `the daemon's own staging path is the app's, spelled once each`() {
        // Two APKs that share no code, and one contract: the app writes the file, the daemon the exploit
        // execs renames it onto /data/adb/ksud as its first act. A missing copy fails that command after the
        // module is already in the kernel - so the helper has to know which path to require, and the app has
        // to be writing that one.
        assertEquals(
            "the helper requires a staging path the app does not write, so it would refuse a phone whose " +
                "daemon is staged correctly",
            DfrInstall.DAEMON_STAGE_PATH,
            constantIn(needsSource(), "LATE_LOAD_SOURCE"),
        )
        assertTrue(
            "the app's staging command does not write the path the helper requires",
            DfrInstall.stageDaemonCommand(payloadSha256 = "a".repeat(64))
                .contains("'${DfrInstall.DAEMON_STAGE_PATH}'"),
        )
    }

    @Test
    fun `the daemon the exploit execs is required by the name the helper stages it under`() {
        // The helper writes KsudStage.DEST itself, out of the app's copy, and the shellcode has that same
        // path compiled in - `DfrDaemonPathTest` holds those two together, and this reads the same pair the
        // other way round: the list requires the path out of that constant rather than as a literal, which
        // is why the comparison here is a reference and not a string.
        assertEquals(
            "the daemon the shellcode execs is not the path the helper stages under, so the gate would check " +
                "a file nothing writes",
            stringAfterLabel(exploitSource(), "ksud_path"),
            constantIn(stageTwoSource(), "DEST"),
        )
        assertTrue(
            "the list does not require the daemon the exploit execs",
            needsSource().contains("KsudStage.DEST"),
        )
        // And the reading that decides it is three-way, because two of the three answers are not the same
        // problem: on the device this gate was written from, `File.isFile` refused a run over two `/vendor`
        // paths that are plainly present, because a `stat` `system_server` is not allowed to make answers as
        // *nothing there*. Only absence stops a run.
        assertTrue(
            "the reading no longer tells a path that is not there from one this process may not look at, so a " +
                "phone whose vendor files are denied to system_server would be refused a run it can do",
            needsSource().contains("Os.lstat") && needsSource().contains("Presence.Unreadable"),
        )
        assertTrue(
            "something other than a required file refuses a run",
            needsSource().contains("it.need.required && it.presence != Presence.Present"),
        )
        // And the line between the two kinds of file is the group, because it is the same fact: what this
        // project stages is the run's hand-off and the phone's own files are the environment. A phone whose
        // vendor files a `stat` from `system_server` cannot see is a phone this gate must not refuse.
        assertTrue(
            "the phone's own files gate a run again, so a reading this process is not able to make refuses a " +
                "run the phone can do",
            needsSource().contains("val required: Boolean get() = group != Group.Phone"),
        )
        assertTrue(
            "`isFile` is back, which follows links and cannot tell a refusal from an empty path",
            !needsSource().contains("file.isFile"),
        )
    }

    @Test
    fun `the exploit library is required under the name it is loaded by`() {
        // `System.loadLibrary("exp")` resolves to libexp.so in this APK's own library directory, and the
        // module extracts its libraries at install - which is what makes the file stat-able at all. Both
        // halves are held here: a rename on either side is a requirement that is never satisfied.
        assertTrue(
            "the JNI build no longer produces a library called exp, so the name this list requires is stale",
            cmake().contains("add_library(exp SHARED"),
        )
        assertTrue(
            "the receiver no longer loads the library this list requires",
            receiverSource().contains("System.loadLibrary(\"exp\")"),
        )
        assertEquals(
            "the library this list requires is not the one the receiver loads",
            "libexp.so",
            constantIn(needsSource(), "EXPLOIT_LIBRARY"),
        )
        assertTrue(
            "the helper's libraries are mapped out of the APK rather than extracted, so the path this list " +
                "checks would not exist",
            helperBuildFile().contains("jniLibs.useLegacyPackaging = true"),
        )
    }

    @Test
    fun `the marker the screen reads is the file the shellcode creates`() {
        // The gate's first reading: stage 1 creates this file as an O_CREAT|O_EXCL mutex before doing
        // anything, so present means the exploit has already been here this boot. Named in the shellcode and
        // in the reading, and there is nothing to compare them at runtime.
        assertEquals(
            "the screen watches a marker the shellcode does not create, so the gate would never close",
            stringAfterLabel(exploitSource(), "L_filename"),
            constantIn(bootStateSource(), "ARMED_MARKER"),
        )
        assertTrue(
            "the screen does not read the marker, so a boot that already ran would look untouched",
            bootStateSource().contains("exists(ARMED_MARKER)"),
        )
    }

    @Test
    fun `a run is refused before it is started, and refused by the same rule that greys the button`() {
        // One rule with two faces: the button's own state and the refusal inside startRun. Two spellings of
        // it would be a button that looks live and a press that answers with a log line - the behaviour this
        // replaces - or the other way round.
        val activity = stageTwoActivity()
        assertTrue(
            "the run is not gated on the files it needs, so an impossible run still spends the boot: " +
                "no reading of the list",
            activity.contains("StageNeeds.check(this)"),
        )
        assertTrue(
            "the screen does not keep the reading, so a press would decide on nothing",
            activity.contains("needs = reading"),
        )
        assertTrue(
            "the run is no longer refused with one shared reason",
            activity.indexOf("val refusal = blockReason()") < activity.indexOf("result = exploit()"),
        )
        assertTrue(
            "the refusal does not name what is missing",
            activity.contains("it.need.path"),
        )
        assertTrue(
            "the Run button's own state is not the gate's, so a press that cannot work is still offered",
            activity.contains("runButton.isEnabled = !busy && !armed && !rootLive && ready"),
        )
        assertTrue(
            "the gate no longer answers the two states a boot cannot be rooted from again",
            activity.contains("BootState.armed()") && activity.contains("BootState.rootLive()"),
        )
        assertTrue(
            "the missing-file branch of the refusal is gone, so only a rooted boot is refused",
            activity.contains("if (!reading.ready)"),
        )
    }

    private fun needsSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageNeeds.kt")

    private fun bootStateSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/BootState.kt")

    private fun stageTwoSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt")

    private fun stageTwoActivity(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt")

    private fun receiverSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageReceiver.kt")

    private fun exploitSource(): String = source("dfr/src/main/jni/stage1.S")

    private fun cmake(): String = source("dfr/src/main/jni/CMakeLists.txt")

    private fun helperBuildFile(): String = source("dfr/build.gradle.kts")

    /** The first quoted string after [label], with the label's own comment lines stepped over. */
    private fun stringAfterLabel(text: String, label: String): String {
        val start = text.indexOf("$label:")
        assertTrue("no $label label in the source", start >= 0)
        val quote = text.indexOf('"', start)
        val end = text.indexOf('"', quote + 1)
        assertTrue("the $label label has no string after it", quote in start until end)
        return text.substring(quote + 1, end)
    }

    /** The value of a `const val NAME = "..."` in Kotlin source. */
    private fun constantIn(text: String, name: String): String =
        Regex("""const val $name = "([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no `const val $name = \"...\"` in the source")

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
