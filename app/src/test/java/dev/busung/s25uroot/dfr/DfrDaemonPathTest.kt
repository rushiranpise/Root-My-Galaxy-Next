package dev.busung.s25uroot.dfr

import dev.busung.s25uroot.SYSTEM_HELPER_DAEMON
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `every candidate is held to the payload's own digest, and none is taken for being there`() {
        // The installed daemon *first* was the old rule, and it is what killed the phone: whichever KernelSU
        // the phone has installed writes its own build to /data/adb/ksud, and a version string called that
        // the same daemon as the payload's. So the order says only which copy to prefer when more than one
        // is right, and the digest decides which ones are right at all.
        val sha = "c".repeat(64)
        val command = DfrInstall.stageDaemonCommand(payloadDaemon = "/data/cache/ksud", payloadSha256 = sha)
        val payload = command.indexOf("'/data/cache/ksud'")
        val temp = command.indexOf("'${DfrInstall.PAYLOAD_STAGED_DAEMON}'")
        val installed = command.indexOf("'${DfrInstall.INSTALLED_DAEMON}'")
        assertTrue("the payload's own daemon is not read: $command", payload >= 0)
        assertTrue("this app's staged copy is not a source: $command", temp > payload)
        assertTrue("the installed daemon is not considered at all: $command", installed > temp)
        assertTrue("every source is checked for content, not existence", command.contains("[ -s '"))
        assertTrue(
            "a source is not held to the payload's digest",
            command.contains("if [ \"${'$'}v\" = \"${'$'}want\" ]"),
        )
        assertTrue("the payload's digest is not in the command: $command", command.contains("want='$sha'"))
    }

    @Test
    fun `a copy that is not the payload's own is named and refused, not staged`() {
        // The failure this is written from: /data/adb/ksud held a vanilla KernelSU-Next 3.4.0 daemon of
        // 5,518,544 bytes while the payload's own was 6,407,096, both answering a 3.4.0-family version, and
        // staging the first one ended the run in a kernel panic. So a candidate that is merely present has
        // to be *said* before the run - or the only report of it is a kernel that does not come back.
        val command = DfrInstall.stageDaemonCommand(payloadSha256 = "d".repeat(64))
        assertTrue(
            "a copy that is not the payload's is staged without a word: $command",
            command.contains("not the daemon this device payload ships"),
        )
        assertTrue("the refused path is not named", command.contains("so it is not staged: "))
        assertTrue(
            "a copy is accepted for existing rather than for being this device's payload's, so the file " +
                "named above is staged after all: $command",
            command.contains("if [ \"${'$'}v\" = \"${'$'}want\" ]; then src='"),
        )
        assertTrue(
            "the refusal comes after something is written",
            command.indexOf("no daemon to stage") < command.indexOf("/system/bin/cp -f"),
        )
    }

    @Test
    fun `with the payload's daemon unknown, nothing is staged and the run is told why`() {
        // Null is a state and not an error: a phone that has never resolved a payload - or whose cache was
        // refused - has no daemon of its own to stage, and the installed one is not a substitute. Refusing
        // this costs a run; staging *something* costs the kernel.
        val command = DfrInstall.stageDaemonCommand()
        assertTrue(
            "an unknown payload daemon does not refuse: $command",
            command.contains("the payload daemon for this device is not known"),
        )
        assertTrue(
            "the refusal comes after a copy",
            command.indexOf("is not known") < command.indexOf("/system/bin/cp -f"),
        )
        assertTrue("the refusal no longer exits non-zero", command.contains("exit 3"))
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
        assertTrue("the refusal does not name the managers it passed over", source.contains("MANAGER_FLAVORS"))
    }

    @Test
    fun `the daemon staged is this device's payload, read out of the verified cache`() {
        // The app's half of the hand-off: the file the exploit will exec is a copy of the payload artifact
        // the run resolved, so the cache is asked for it rather than the device's own installed daemon.
        val source = source("app/src/main/java/dev/busung/s25uroot/dfr/DfrInstall.kt")
        assertTrue(
            "the staging no longer reads the daemon out of the verified payload cache",
            source.contains("KnownGoodPayloadStore.daemon(context)"),
        )
        assertTrue(
            "the staging no longer carries the payload's digest into the command",
            source.contains("payloadSha256 = daemon?.let"),
        )
        // And the run's own write-back names the payload it just resolved, because that run is holding the
        // very artifact this boot loaded - there is nothing better to stage from.
        assertTrue(
            "the write-back at the end of a run no longer names the payload that run resolved",
            source("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt")
                .contains("payloadDaemon = payloads.kernelSu.absolutePath"),
        )
    }

    @Test
    fun `the staging leaves the daemon where the helper reads it, in the directory the helper can read`() {
        // The helper runs inside system_server, whose context is denied shell_data_file - the type on every
        // path under /data/local/tmp. So a copy left only there is one the helper can stat and cannot open,
        // which is the whole of "present but unreadable" on a phone whose daemon was staged correctly. The
        // copy it actually reads is under /data/system, beside the one the exploit execs, and this is the
        // pair the app has to keep in step.
        val command = DfrInstall.stageDaemonCommand(payloadSha256 = "f".repeat(64))
        assertTrue(
            "the staging no longer leaves the copy the helper reads: $command",
            command.contains("/system/bin/cp -f \"${'$'}src\" '${DfrInstall.HELPER_STAGED_DAEMON}'"),
        )
        assertTrue(
            "the copy the helper reads is not left under /data/system, where system_server may open it",
            DfrInstall.HELPER_STAGED_DAEMON.startsWith("/data/system/"),
        )
        assertTrue(
            "the copy the helper reads is not left with the identity the system uid needs",
            command.contains("chown system:system '${DfrInstall.HELPER_STAGED_DAEMON}'"),
        )
        assertTrue(
            "the copy the helper reads is world-readable rather than readable by the system uid alone",
            command.contains("chmod 600 '${DfrInstall.HELPER_STAGED_DAEMON}'"),
        )
        // And never by copying a file onto itself: that is an error.
        assertTrue(
            "the copy the helper reads is copied onto itself when it was the source, which fails the staging",
            command.contains("if [ \"${'$'}src\" != '${DfrInstall.HELPER_STAGED_DAEMON}' ]; then"),
        )
    }

    @Test
    fun `the helper reads the copy the app stages, under the name the app writes`() {
        // Two APKs that share no code, so the only place the two spellings can be compared is here. The
        // failure when they disagree is silent: the helper reports a phone clean and refuses a run while
        // its daemon sits staged under a name nothing read.
        val helper = constantIn(stageTwoSource(), "STAGED_BY_THE_APP")
        assertEquals(
            "the app stages the helper's copy at one path and the helper reads another",
            SYSTEM_HELPER_DAEMON,
            helper,
        )
        assertEquals(DfrInstall.HELPER_STAGED_DAEMON, helper)
        assertTrue(
            "the helper was pointed back at the temp directory, which system_server cannot read",
            !helper.startsWith("/data/local/tmp/"),
        )
    }

    @Test
    fun `the stage two stages the app's copy or nothing, and never the installed daemon`() {
        // The helper's half, and the half that runs first on a phone with no root: it used to read the
        // installed daemon as its first source and to keep whatever was already at the path the exploit
        // execs. Both are how a KernelSU-Next daemon the *installed manager* had written got handed to a
        // run whose payload was another project's build - and the kernel panicked, six boots in one morning.
        val source = stageTwoSource()
        assertTrue("the helper no longer reads the app's own copy", source.contains("File(STAGED_BY_THE_APP)"))
        assertTrue(
            "a file already at the path the exploit execs is kept without being compared with the app's copy",
            source.contains("sameBytes(existing, mine)"),
        )
        assertTrue(
            "the helper reads the installed daemon again, which is the copy that panicked the kernel",
            !source.contains("File(LEFT_BY_THE_PAYLOAD)"),
        )
        assertTrue(
            "the refusal no longer says which copy it will not take",
            source.contains("is not taken"),
        )
        // The keep is conditional on the app's copy being unreadable, and says so: trusting a daemon at the
        // path the exploit execs is only safe because nothing but the app writes it, and a keep that claimed
        // a comparison it did not make is the failure this whole change is about.
        assertTrue(
            "a daemon at the exec path is still taken without a word about why it could not be compared",
            source.contains("was not readable to compare it against"),
        )
        assertTrue(
            "the keep happens before the app's copy is even looked at, so a mismatch would never be noticed",
            source.indexOf("mine == null") < source.indexOf("was not readable to compare it against"),
        )
    }

    @Test
    fun `what was staged is reported with its version, and the installed copy's as context`() {
        val command = DfrInstall.stageDaemonCommand(payloadSha256 = "a".repeat(64), expectedVersion = "3.4.0")
        assertTrue("the staged version is not named", command.contains("${'$'}{got:+ (${'$'}got)}"))
        // The file the *other* version comes from is named, because that is the whole content of the line: a
        // version with no owner reads as the app being confused about the daemon it just wrote itself.
        assertTrue(
            "the file the other version comes from is not named, so a difference has no owner",
            command.contains("the copy installed at ${DfrInstall.INSTALLED_DAEMON}"),
        )
        assertTrue(
            "a match is not said to be a match",
            command.contains("is also the build installed at ${DfrInstall.INSTALLED_DAEMON}"),
        )
        assertTrue(
            "a difference is not reported at all",
            command.contains("reports ${'$'}wantver, the daemon this run execs reports"),
        )
        // A note and not a warning: the staging has already happened by the time this prints, and the copy it
        // names is not the copy that was staged - so an `[!]` here is a failure message for something that
        // worked, which is what sent a reader looking for a failure to fix.
        assertTrue(
            "the note about the installed copy is not marked as a note: $command",
            command.contains("[*] the copy installed at ${DfrInstall.INSTALLED_DAEMON}"),
        )
        assertFalse(
            "the report reads as a failure again: $command",
            command.contains("[!] the daemon this device is running"),
        )
    }

    @Test
    fun `an unreadable running daemon is never reported as a match`() {
        // A device whose daemon could not be read is the case where a false "matches" would be worst:
        // the run would exec a daemon nothing had compared, and the log would say it had been checked.
        val unknown = DfrInstall.stageDaemonCommand(expectedVersion = null)
        assertTrue("an empty expectation still claims a comparison", unknown.contains("cannot be compared"))
        // Guarded at runtime rather than only in wording: with nothing to match on, the command takes the
        // "not read" branch, and the comparison branches below it are never reached on the device.
        val guard = "if [ -z \"${'$'}wantver\" ]; then echo '[?]"
        assertTrue("the comparison is not guarded by the empty expectation", unknown.contains(guard))
        assertTrue(
            "the guard does not come before the report it is there to prevent",
            unknown.indexOf(guard) < unknown.indexOf("is also the build installed at"),
        )

        // And the version is a separate question from the digest: what may be staged is decided by content,
        // so a phone whose *running* daemon could not be read still stages the payload's own copy - the run
        // says it could not compare versions and proceeds anyway, which is the honest half of the answer.
        val digest = "e".repeat(64)
        val blank = DfrInstall.stageDaemonCommand(payloadSha256 = digest, expectedVersion = "  ")
        assertTrue(blank.contains("wantver=''"))
        assertTrue("the digest is not carried separately from the version", blank.contains("want='$digest'"))
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
        // callers, and both hand their file to the same daemon code. One constant now rather than two
        // spellings this line kept together, because the second caller made the difference visible: the
        // file a run consumes is the file the reroot re-arms, and a second spelling would make those two
        // files, each of which looks correct on its own.
        assertTrue(
            "InstallViewModel spells the stage path out again, so the file a run consumes and the file " +
                "the reroot re-arms can come apart",
            source("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt")
                .contains("KSUD_STAGE_PATH = DfrInstall.DAEMON_STAGE_PATH"),
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
