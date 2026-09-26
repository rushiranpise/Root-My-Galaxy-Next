package dev.busung.s25uroot.dfr

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.StringRes
import dev.busung.s25uroot.R
import dev.busung.s25uroot.AppLog
import dev.busung.s25uroot.AppLogTags
import dev.busung.s25uroot.KernelSuFlavor
import dev.busung.s25uroot.KernelSuRuntime
import dev.busung.s25uroot.KernelSuVersionProbe
import dev.busung.s25uroot.KnownGoodPayloadStore
import dev.busung.s25uroot.RootRecovery
import dev.busung.s25uroot.RootStatusProbe
import dev.busung.s25uroot.SYSTEM_HELPER_DAEMON
import dev.busung.s25uroot.SYSTEM_STAGED_DAEMON
import dev.busung.s25uroot.ShizukuController
import dev.busung.s25uroot.sha256Of
import java.io.File

/**
 * The app's side of the ported installer: how [InjectMain] is actually run.
 *
 * The ported code has to run as **root outside the app's own process** - it reads and writes
 * `/data/system/packages.xml`, which no app context can open - and the way that is done is
 * `app_process`, a plain dalvikvm that runs a main class from a classpath we hand it. That classpath
 * is this app's own APK: the ported classes are compiled into it, so nothing has to be pushed to the
 * device first and there is no second APK to keep in step. (DFReroot ships the equivalent as a
 * separate `df_installer.apk` and pushes it to `/data/local/tmp`; ours is already installed.)
 *
 * The key whose certificate goes into `pastSigs` must be the signing key of the APK that will run as
 * the shared user afterwards. Three ways to name it, in the order the ported entry point resolves
 * them: `--keyhex` when a caller already knows it, `--apk` for a file on the device, and the
 * no-argument default, which reads the certificate of an *installed* package - this app itself.
 *
 * **What an inject is for, and when it takes effect.** PMS reads `packages.xml` at boot, so the
 * change is not live: a reboot has to happen before an APK declaring
 * `android:sharedUserId="android.uid.system"` is accepted, and installing that APK *before* the
 * reboot does nothing. That ordering is the whole trick and it is why the ported entry point ends
 * with "next: reboot, THEN install".
 *
 * Every mode is a dry read except [DfrMode.Inject] and [DfrMode.Uninstall]. `Check` reports whether
 * the key is already in there, `Dump` summarises the file, and `DryRun` performs the whole transform
 * and verifies the result without writing a byte.
 */
internal enum class DfrMode(val flag: String) {
    /** Parse and summarise only; zero writes. */
    Dump("--dump"),

    /** Report per-target injected true/false; zero writes. */
    Check("--check"),

    /** Parse, transform, verify - no write. */
    DryRun("--dry-run"),

    /** The inject itself. Fails when the key is already present. */
    Inject(""),

    /** Remove only our key; a no-op when absent. */
    Uninstall("--uninstall"),
}

/** What a run printed, and whether it got through. */
internal data class DfrResult(
    val mode: DfrMode,
    val ok: Boolean,
    val log: String,
) {
    /**
     * The `--check` answer, or null when the mode was not a check or it printed none.
     *
     * Read from the ported entry point's own line rather than from our reading of the file, so the
     * app and the installer cannot disagree about what is injected.
     */
    val allInjected: Boolean?
        get() = Regex("all_injected=(\\w+)").find(log)?.groupValues?.get(1)?.let { it == "true" }

    /**
     * Whether `--uninstall` reported our key as gone, which is the only thing that makes a clean-up real.
     *
     * Two lines can say so, and they mean the same thing to this app: the injector removed the certs and
     * its own re-read of the bytes it wrote confirms they are absent, or the key was never there. Anything
     * else is not evidence - a refusal, a failed verification, a mode that does not uninstall at all - and
     * the app's record of having injected is kept until there is some. The record is what the next reading
     * of this screen starts from, and "nothing was ever injected" must not be reachable by a clean-up that
     * did not run: a stale stamp is what made the flow skip its own reboot step twice before.
     */
    val uninstalled: Boolean
        get() = mode == DfrMode.Uninstall && ok &&
            (log.contains(PackagesXml.KEY_ABSENT) || log.contains("${PackagesXml.KEY_REMOVED}: true"))

    /**
     * Whether this run took the key out of the file, which is the one thing it did that a restart owes.
     *
     * [uninstalled] is true for both halves of the same answer - removed, or never there - because the
     * question it answers is "is our key out of `packages.xml`". This one is narrower: the file was
     * changed, so a Package Manager that started before the change is still holding the key, and it is
     * that running copy the phone has to restart away. `ok` is required because the injector prints this
     * line before it verifies and writes; a verify that fails means nothing was written.
     */
    val keyTakenOut: Boolean
        get() = mode == DfrMode.Uninstall && ok && log.contains(PackagesXml.KEY_CHANGED)

    /**
     * Whether this run got as far as asking the daemon for the userspace restart - see
     * [DfrInstall.removalAndRestartCommand].
     *
     * Read as its own line because the two halves of that command are one command: the injector's own
     * verdicts say what happened to the file and nothing about whether the daemon was ever reached, so a
     * caller that used [uninstalled] for both would report a restart that never happened on a phone whose
     * `su` answered but whose daemon did not.
     */
    val restartRequested: Boolean
        get() = mode == DfrMode.Uninstall && ok && log.contains(DfrInstall.RESTART_REQUESTED)

    /** One line for the app log. */
    fun summary(): String = "dfr ${mode.name.lowercase()}: ${if (ok) "ok" else "failed"}" +
        log.lineSequence().lastOrNull { it.startsWith("[x]") }?.let { " - $it" }.orEmpty()
}

/**
 * Which records a clean-up is entitled to clear, given what its two halves reported.
 *
 * The flow keeps two instants - when it injected, and when it installed the stage two - and both are
 * read back as evidence that a reboot must have happened since. A record cleared by a clean-up that did
 * not run is therefore not a tidy-up but a false reading: "this phone was never injected" for a phone
 * whose packages.xml still carries our key. So each half of the undo clears its own record and only
 * when it landed - the key removal by the injector's own re-read of the bytes it wrote, the helper
 * removal by Package Manager's word. Neither asks the other.
 *
 * [uninstall] is null exactly when no root shell answered, which is the case that has no evidence at
 * all, and both halves are then kept.
 */
internal data class DfrCleanUpOutcome(val keyGone: Boolean, val helperGone: Boolean) {
    companion object {
        fun of(uninstall: DfrResult?, helper: DfrAction?): DfrCleanUpOutcome = DfrCleanUpOutcome(
            keyGone = uninstall?.uninstalled == true,
            helperGone = helper?.ok == true,
        )
    }
}

/**
 * What the device's own tools answered about the stage two, the kernel, and the framework.
 *
 * Four facts, and each is read from the thing that decides it rather than from this app's own records:
 * whether the APK is installed and which uid it runs as (`pm list packages -U`, one line carrying both),
 * whether the exploit has already armed its hooks this boot (the marker node), and how long the running
 * Android framework has been up (the start time of `system_server`). The app keeps its own record of
 * when it did each step, and that record is only ever used for ordering - never as the answer to "is it
 * done".
 *
 * The framework's age is the fourth because it is the only reading that can see the restart this flow
 * actually performs. Both steps that ask for one are satisfied by a *userspace* restart, which leaves
 * the kernel running and its uptime untouched - see [DfrFlow.restartedSince].
 *
 * The reading is `pm list packages -U` and not `dumpsys package`, which is what this first used: the dump
 * on this device prints `uid=10555` for a package - and prints it inside a permission listing rather than
 * as the package's own identity, so the number is there and does not mean what it was read as. The list
 * command prints exactly one line per package, `package:<id> uid:<n>`, with nothing else on it.
 */
internal data class DfrProbe(
    val installed: Boolean,
    val isSystemUid: Boolean,
    val armed: Boolean,
    /**
     * How long the Android framework has been up, or null when it could not be read.
     *
     * Null is not zero and must never be read as one: the kernel clock's own postmortem in this file is
     * the shape of that mistake - a reading that failed to zero answered "the phone rebooted" for every
     * instant ever recorded, and the flow skipped the reboot steps it exists to insist on. A framework
     * age of zero would say the same thing here, so an unreadable one is [DfrFlow.rebootedSince]'s
     * question instead, which can only err toward asking for a restart.
     */
    val frameworkUptimeMillis: Long? = null,
) {
    companion object {

        /**
         * Reads the three answers out of [probeCommand]'s output, or null when the markers are not in it.
         *
         * A missing reply is null and not a `false` per field: an unreadable device and a device with
         * nothing installed are different states, and the flow has a step for each - collapsing them here
         * is how a screen ends up insisting the app is not installed when it simply could not look.
         */
        fun parse(output: String): DfrProbe? {
            val sections = sections(output)
            val listing = sections[MARK_PACKAGE] ?: return null
            val armed = sections[MARK_ARMED] ?: return null
            return DfrProbe(
                installed = listing.contains(PACKAGE_PREFIX),
                // Anchored on the right: `uid:10005` is an ordinary app on this device, and a plain
                // `contains` would read it as uid 1000 and skip the step that removes an unprivileged
                // install - the one mistake this whole reading exists to catch.
                isSystemUid = SYSTEM_UID_REGEX.containsMatchIn(listing),
                armed = armed.trim() == ARMED,
                // Optional where the two above are required: the device can answer those and still have
                // no framework to ask about, and a probe that refused the whole reading over an optional
                // field would turn a readable phone into `ReadState`.
                frameworkUptimeMillis = frameworkUptimeMillis(sections[MARK_FRAMEWORK]),
            )
        }

        /**
         * The text between each marker and the next one.
         *
         * Each section ends where the *next* marker begins, which is the offset the next marker was found
         * at rather than the offset just past it - the difference is the next marker's own length, and
         * taking it off the wrong side truncates the section it belongs to. That is not a hypothetical:
         * the first version of this line cut the last character off a uid (`uid:100` for `uid:1000`), and
         * the section it cut belonged to the answer the whole probe exists for.
         */
        private fun sections(output: String): Map<String, String> {
            val markers = listOf(MARK_PACKAGE, MARK_ARMED, MARK_FRAMEWORK, MARK_END)
            val found = markers.mapNotNull { marker ->
                val start = output.indexOf(marker)
                if (start < 0) null else Marker(marker, start, start + marker.length)
            }.sortedBy { it.start }
            return buildMap {
                found.forEachIndexed { index, marker ->
                    val end = found.getOrNull(index + 1)?.start ?: output.length
                    put(marker.name, output.substring(marker.after, end.coerceAtLeast(marker.after)))
                }
            }
        }

        /** One marker's name, where it starts, and where its own text ends. */
        private class Marker(val name: String, val start: Int, val after: Int)
    }
}

/** Whether an action the app asked a root shell for was carried out. */
internal data class DfrAction(val ok: Boolean, val log: String)

/**
 * What an attempt to keep the daemon stage armed for the next boot came to.
 *
 * Three answers rather than a boolean, because the difference between the two silent ones is the whole
 * reason this is called from somewhere that runs on every return to the foreground: "it was already there"
 * is what an armed phone answers and "this boot has no root" is what an unrooted one answers, and a
 * warning about either would be a line in the log every time the app is opened. Only [Failed] is news - a
 * boot that had root and still could not put the file in place - because that is the phone whose next
 * restart will find nothing to late-load.
 */
internal enum class DfrStageArming {
    /** The daemon is where the next boot's late-load reads it, written now or already there. */
    Armed,

    /** No root is live in this boot, so there is nothing to write with - and nothing to report. */
    NoRoot,

    /** Root was live and the file is still not in place: a restart would find nothing to late-load. */
    Failed,
}

/**
 * The daemon stage file, as the device described it - the five answers a settings row can show.
 *
 * Four of them are the check's own markers, and the fifth is the silence: no shell answered, so nothing was
 * read. They are separate answers rather than one "not armed", because what the reader does about it
 * differs - nothing, stage it again, stage the *right* daemon, or start Shizuku - and a row that said "not
 * armed" for all four would send every one of them the same way.
 *
 * [DfrStageArming] next door is the other half of the same file and not the same question: that one is what
 * came of trying to *write* it, and it is answered where root is known to be live. This one is what is
 * there now, asked of whichever shell the phone has - which is the question a screen can ask on a phone
 * that has not been rerooted yet.
 */
internal enum class DfrStageReading(
    /** The value the row shows. */
    @StringRes val label: Int,
    /** One line saying what that means, or why it is not armed. */
    @StringRes val detail: Int,
) {
    /** In place, and it is the daemon this device is running. */
    Armed(R.string.dfr_stage_armed, R.string.dfr_stage_armed_detail),

    /** Nothing there: the next boot would find no daemon to late-load. */
    Absent(R.string.dfr_stage_absent, R.string.dfr_stage_absent_detail),

    /** Something there, and it is another build's daemon. */
    Different(R.string.dfr_stage_different, R.string.dfr_stage_different_detail),

    /** There, and the running daemon could not be read, so nothing was compared. */
    Uncompared(R.string.dfr_stage_uncompared, R.string.dfr_stage_uncompared_detail),

    /** No shell answered, so this is an absence of a reading rather than a reading. */
    Unreadable(R.string.dfr_stage_unreadable, R.string.dfr_stage_unreadable_detail),
}

internal object DfrInstall {

    /** The shared user the certificate is injected into. */
    const val TARGET = "android.uid.system"

    /** The class `app_process` runs; the ported entry point. */
    const val MAIN_CLASS = "dev.busung.s25uroot.dfr.InjectMain"

    const val PACKAGES_XML = "/data/system/packages.xml"

    /**
     * The stage-two APK this app drives, and the two names of it that cannot be read from the build.
     *
     * Its application id is written in the `:dfr` module's own build file, and the component `am start`
     * needs is the activity's class name - the same shape as [MAIN_CLASS]: both are named by string to
     * another process, so both are text here. What this cannot do is drift silently: the publish job
     * builds the [STAGE_TWO_PACKAGE] artifact, so a change on one side with no change on the other is a
     * download that nothing installs.
     */
    const val STAGE_TWO_PACKAGE = "dev.rushiranpise.rmgnext.helper"
    const val STAGE_TWO_ACTIVITY = "dev.busung.s25uroot.dfr.stage2.Stage2Activity"

    /**
     * How this app asks the helper for a run without anybody pressing anything.
     *
     * The helper's own screen waits for its own button, which is the right thing for a screen somebody
     * opened - and the wrong thing for a boot, where the whole point is that nobody is looking yet. So
     * the run is requested as an extra on the same `am start` the app already performs, and the helper
     * reads it there. A boolean extra rather than an action or a second component, because the two calls
     * differ in one thing only: whether the run is wanted before anyone is watching.
     */
    const val STAGE_TWO_AUTORUN_EXTRA = "rmg.autorun"

    /**
     * What this app's own reroot-at-boot setting is, so the helper's boot row can say it.
     *
     * The setting is this app's - it is the half holding the boot receipt and the once-per-boot rule - so
     * the helper is told rather than asked, and told *only* when there is something to say: an absent
     * extra is how that screen says "the app did not start me", which is a different answer from "off".
     */
    const val STAGE_TWO_REROOT_EXTRA = "rmg.rerootAtBoot"

    /**
     * Which KernelSU this run loads, so the helper can name and open that flavour's manager.
     *
     * The helper cannot work this out on its own: three managers can be installed at once, they are three
     * different projects' apps, and the only side that knows which one drives the daemon this boot will
     * exec is the app - it is the side that resolved the payload. So it is told, and it is told the feed's
     * own id (`kernelsu-next`) rather than a package, because the id is the name the extra can be held to
     * by a test while a package is a fact about somebody else's repository.
     */
    const val STAGE_TWO_FLAVOR_EXTRA = "rmg.flavor"

    /**
     * The colours this app's window is drawn with, so the helper's screen can be drawn with them too.
     *
     * The helper resolves its palette from the theme it is drawn in, which is the platform's
     * `DeviceDefault` - the OEM's palette, and not the Material one the app uses. Its accent in particular
     * is a different colour outright, which is what "the helper does not match the app" meant. So the app
     * sends the values it is drawing with and the helper paints those, and neither side keeps a second
     * copy of the other's colours.
     *
     * Absent is a real state and not an error: a launch with no screen behind it - the boot, or the helper
     * opened from the launcher - has no window to copy, and the helper then falls back to its own theme as
     * it always did.
     */
    const val STAGE_TWO_TINT_EXTRA = "rmg.tint"

    /**
     * What the helper sets on this app once its run has loaded KernelSU, so the app does the restart.
     *
     * The restart the helper's success needs is KernelSU's own soft reboot, and the helper cannot ask for
     * it: that goes through the installed `ksud` as root, and this helper runs as the system uid inside
     * `system_server`, which the daemon does not grant a shell. The app can, with a grant the user already
     * gave it and a script that will not start one twice. So the one thing the helper does is say that root
     * has arrived, and the app - which holds the setting, the grant and the one-owner-per-boot lock - is the
     * side that acts on it.
     *
     * The helper sets it only for a run a person asked for. A boot's run is the app's own [DfrBootService],
     * which is already watching this boot's kernel for the load and performs the restart from that side,
     * unattended and without a screen.
     */
    const val STAGE_TWO_AFTER_ROOT_EXTRA = "rmg.afterRoot"

    /** The compiled-in marker the exploit's module creates, and the kernel clears it on a hard reboot. */
    const val ARMED_MARKER = "/dev/df"

    /**
     * The command, as a pure function so what runs can be read and tested without a device.
     *
     * [classpath] is the APK holding the ported classes - normally this app's own. Single-quoted
     * because every path here can contain characters the shell would otherwise take for itself, and
     * the class name is deliberately unquoted so a wrong one fails loudly as a missing class rather
     * than as a shell error.
     */
    internal fun command(
        classpath: String,
        mode: DfrMode,
        apkPath: String? = null,
        keyHex: String? = null,
        target: String = TARGET,
    ): String = buildString {
        append("CLASSPATH='").append(classpath).append("' ")
        append("/system/bin/app_process /system/bin --nice-name=rmg_inject ")
        append(MAIN_CLASS)
        append(" --xml ").append(PACKAGES_XML)
        append(" --targets ").append(target)
        keyHex?.let { append(" --keyhex '").append(it).append('\'') }
        apkPath?.let { append(" --apk '").append(it).append('\'') }
        if (mode.flag.isNotEmpty()) append(' ').append(mode.flag)
    }

    /**
     * Runs [mode] as root, or returns null when no root shell answered.
     *
     * [apkPath] names the APK whose signing certificate is injected; when it is null the ported entry
     * point falls back to the installed package it was built to look up, which is this app.
     */
    fun run(
        context: Context,
        mode: DfrMode,
        apkPath: String? = null,
        keyHex: String? = null,
    ): DfrResult? = resultOf(
        KernelSuRuntime.rootShell(
            command(context.packageCodePath, mode, apkPath = apkPath, keyHex = keyHex),
            TIMEOUT_SECONDS,
        ),
        mode,
    )

    /**
     * Removes the key and then restarts the userspace, as **one** root command, because two commands are
     * a window.
     *
     * `packages.xml` belongs to Package Manager, and Package Manager rewrites it from its own memory on its
     * own schedule - so every moment between "the key is out of the file" and "the framework that read the
     * old copy is gone" is a moment in which somebody else's write can put the key back, and the phone then
     * boots the list this action exists to be done with. Measured on this device: a removal that had landed
     * was back in the file 45 s later, with no restart in between.
     *
     * Asking for the restart afterwards cannot close that window however quickly it is asked, because the
     * second command is issued when the first one's answer arrives and everything between the two is time
     * nothing is holding. So the removal and the restart are one command, issued once, with the injector's
     * own exit code as the only thing between them.
     *
     * Through the same root shell the removal uses on its own, and deliberately not [RootRecovery.softReboot]:
     * that keeper exists for callers nobody is watching - it takes a one-owner lock, checks the kernel boot
     * again and *waits* for `sys.boot_completed` - and every one of those checks is between the removal and
     * the `stop` this action is racing. Here the person who pressed the button is the owner, the phone is
     * booted by definition, and a refusal is read and reported.
     */
    internal fun removalAndRestartCommand(
        classpath: String,
        ksudPath: String = RootRecovery.KSUD_PATH,
    ): String = buildString {
        append(command(classpath, DfrMode.Uninstall)).append('\n')
        append("rc=\$?\n")
        // A removal that did not get through does not restart: a restart with the key still in the file is
        // every app on the phone closed to arrive back at the same step, where the injector's own refusal is
        // the thing worth reading.
        append("if [ \"\$rc\" -ne 0 ]; then\n")
        append("  echo \"[x] the certificate was not removed (rc=\$rc), so the userspace was not restarted\"\n")
        append("  exit \"\$rc\"\n")
        append("fi\n")
        append("echo '").append(RESTART_REQUESTED).append("'\n")
        append("'").append(ksudPath).append("' soft-reboot 2>&1\n")
        append("echo \"[*] the daemon answered rc=\$?\"\n")
    }

    /**
     * [removalAndRestartCommand] on this phone, as the step that applies a removal runs it.
     *
     * Null when no root shell answered - which on this action is also what a restart in flight looks like,
     * because the command ends by taking down the framework this app is running in. The caller records that
     * it asked before it asks, for exactly that reason.
     */
    fun removeAndRestart(context: Context): DfrResult? = resultOf(
        KernelSuRuntime.rootShell(removalAndRestartCommand(context.packageCodePath), TIMEOUT_SECONDS),
        DfrMode.Uninstall,
    )

    /**
     * Whether this app's certificate is in the shared user's list, on whichever shell this phone has.
     *
     * `--check` is the one mode of the injector that writes nothing: it parses `packages.xml` and prints one
     * verdict per target. That makes it a reading like the others this flow takes on the `shell` user's own
     * shell, and it is the reading that stands between a phone whose helper is missing and its install -
     * without it the flow answers [DfrStep.ReadState], because an install whose key nobody checked is how a
     * helper comes to be installed as an ordinary app.
     *
     * A refused read is null, which is what makes it safe to try: the file is a platform file, and whether
     * the `shell` user may open it is the platform's business rather than this app's. A build that says no
     * leaves the flow exactly where it was - no claim made, and no step offered that the reading would not
     * have - and `all_injected` is only ever printed after the file has been read and parsed, so an answer
     * that arrives at all arrived from the same parser the root route uses.
     */
    internal fun checkInjected(context: Context): DfrResult? = resultOf(
        runOnEitherShell(command(context.packageCodePath, DfrMode.Check), TIMEOUT_SECONDS),
        DfrMode.Check,
    )

    /**
     * A shell answer as a result, with the ported entry point's own refusal word read as the verdict.
     *
     * The entry point exits non-zero and prints "[x] FAILED: ..." for anything it refused, and a refusal is
     * the common, expected answer for several of the modes - so it is reported as a result with the reason,
     * not as an absence of one. Null is left for the one thing that is an absence: no shell answered at all.
     */
    private fun resultOf(result: ShizukuController.ShellResult?, mode: DfrMode): DfrResult? {
        val answered = result ?: return null
        val log = answered.output
        val ok = answered.exitCode == 0 && !log.contains(INJECTOR_FAILURE)
        return DfrResult(mode, ok, log)
    }

    /**
     * One shell command that answers both questions at once.
     *
     * One command rather than two because this is on the path of a screen opening, and both answers come
     * from tools that cost a package-manager startup each. The markers are quoted because an unquoted one
     * is a shell word rather than text - the mistake this app has already shipped once, where
     * `echo #marker` was read as a comment and every section came back empty.
     */
    internal fun probeCommand(packageName: String = STAGE_TWO_PACKAGE): String = buildString {
        append("echo '").append(MARK_PACKAGE).append("'; ")
        append("/system/bin/pm list packages -U '").append(packageName).append("' 2>/dev/null; ")
        append("echo '").append(MARK_ARMED).append("'; ")
        append("if [ -e '").append(ARMED_MARKER).append("' ]; then echo ").append(ARMED)
        append("; else echo ").append(CLEAR).append("; fi; ")
        append("echo '").append(MARK_FRAMEWORK).append("'; ")
        append(frameworkSection())
        append("; echo '").append(MARK_END).append("'")
    }

    /**
     * The framework's age, as the two numbers it is arithmetic between.
     *
     * Two readings rather than the subtraction the shell could do, because the arithmetic is the part
     * worth testing without a device - see [frameworkUptimeMillis]. `/proc/uptime`'s first field is
     * seconds since boot; the 22nd field of `/proc/<pid>/stat` is where `system_server` started, counted
     * from that same boot in hundredths of a second. Their difference is how long the Android framework
     * has been up, and it is short after a restart the kernel never saw.
     *
     * `pidof` rather than a sweep of every `/proc/<pid>/stat`: it is the same lookup the zygote report
     * makes, it is
     * present in this device's own toolbox, and a name is what the process is known by here. Everything
     * is redirected, so a device that answered none of it yields an empty section rather than an error -
     * which is the state that has to survive as *unknown* rather than become zero.
     */
    internal fun frameworkSection(): String = buildString {
        append("up=").append("${'$'}(cut -d' ' -f1 /proc/uptime 2>/dev/null); ")
        append("rmg_ss=").append("${'$'}(pidof system_server 2>/dev/null | cut -d' ' -f1); ")
        append("start=").append("${'$'}(awk '{print ${'$'}22}' /proc/${'$'}rmg_ss/stat 2>/dev/null); ")
        append("echo \"up=${'$'}up start=${'$'}start\"")
    }

    /**
     * `pm install` of the stage two, on whichever shell this phone has - see [installStageTwo].
     *
     * `-r` reinstalls over a copy already there and `-d` permits a version code lower than the installed
     * one, because the artifact being installed is built by hand and its code does not track releases.
     * Neither flag weakens the check that matters here: the signature check against the shared user's
     * certificate is what this whole flow is for, and it is not a flag.
     */
    internal fun installCommand(apkPath: String): String =
        "/system/bin/pm install -r -d --user 0 '" + apkPath + "'"

    /**
     * Where the helper has to be put before the `shell` user can install it.
     *
     * `pm install` reads the APK as whoever asked for it, and this build's copy lives in app storage -
     * `/data/data/<this app>/files/dfr-apk`, mode 0700 under the app's own uid - which the `shell` user
     * cannot open. So the route that runs without root needs a second copy in the one directory the shell
     * owns, staged the way a run stages its payload: see [stageForShell].
     *
     * Not the payload's own names, because this file is not a run's: a run replaces its staging on every
     * run, and this one may be left there for a while.
     */
    internal const val SHELL_INSTALL_PATH = "/data/local/tmp/rmgnext-stage2.apk"

    /**
     * The two files an inject can leave in `/data/system`, for the screen that lists them.
     *
     * Named from the injector's own constants rather than typed out, because the list has to name what
     * the inject actually writes: `packages.xml` is a file a detector reads closely, and a copy of it
     * from before this app touched it is the inject's own fingerprint - written by the inject, not by
     * the platform, and removed by nothing on the phone.
     *
     * Nothing deletes these automatically. They are the residue screen's to show and the user's to
     * remove, one row or all of them.
     */
    val leftoverPaths: List<String> = listOf(
        PackagesXml.PACKAGES_XML + PackagesXml.BACKUP_SUFFIX,
        PackagesXml.PACKAGES_XML + PackagesXml.TEMP_SUFFIX,
    )

    /**
     * Where the daemon goes: the same path stage two stages for itself, and the one the exploit execs.
     *
     * Written by *this* app now, not only by the helper. The helper's own first choice is this path
     * "when the app has already put it there", and until this existed nothing ever did - so its best
     * source was always empty and it fell through to the last one it had.
     */
    internal val STAGED_DAEMON: String get() = SYSTEM_STAGED_DAEMON

    /** The copy this app stages for its own runs, written from the payload and from nothing else. */
    internal const val PAYLOAD_STAGED_DAEMON = "/data/local/tmp/ksud-s25u-kdp"

    /**
     * The daemon the phone's own installed KernelSU keeps.
     *
     * Not this project's file: whichever manager is installed writes *its* build here, and a build out of
     * another KernelSU project is a module loader for a kernel this phone does not have. It is still a
     * candidate below, because after a run of ours it *is* our daemon - the late-load renames the stage
     * file onto exactly this path - and a boot that has lost the temp copy can still be armed from it.
     */
    internal const val INSTALLED_DAEMON = "/data/adb/ksud"

    /**
     * The paths the staging may copy from, preferred first - and none of them trusted for being there.
     *
     * Every candidate has to hash to the digest of the daemon *this device's payload* ships (see
     * [stageDaemonCommand]), so this list only says which copy to prefer when more than one is right.
     */
    internal val DAEMON_SOURCES: List<String> = listOf(
        PAYLOAD_STAGED_DAEMON,
        INSTALLED_DAEMON,
    )

    /**
     * The copy the daemon's own late-load moves into place, named by its code rather than by this app.
     *
     * A late-loaded daemon installs itself: its `late-load` renames this path onto `/data/adb/ksud`,
     * before it loads anything, and a missing file there fails the whole command with "Failed to stage
     * ksud". It is the same path `InstallViewModel` writes for a run's own late-load, spelled here
     * because the DFR flow is a second caller of the same contract - and the rename *consumes* it, so
     * it has to be written again for every run rather than once per install.
     */
    internal const val DAEMON_STAGE_PATH = "/data/local/tmp/.ksud-stage"

    /**
     * The copy the system-uid helper reads, staged beside [STAGED_DAEMON] rather than in the temp directory.
     *
     * The helper runs inside `system_server`, and that context may not read `shell_data_file` - the type on
     * everything under `/data/local/tmp`, which is the shell user's directory and not this app's to relabel.
     * So the copy [PAYLOAD_STAGED_DAEMON] leaves there is one the helper can see and not open: it `stat`s a
     * file and the read comes back denied, which is why the helper said "present but unreadable" and
     * refused a phone whose [STAGED_DAEMON] was already the right daemon.
     *
     * Written from the same source and in the same breath as [STAGED_DAEMON], so the two are always the same
     * bytes: the helper compares one against the other, and a copy that could drift would be worse than none.
     * `system:system` mode 0600 rather than the daemon's own 0700, because the only reader is the helper - and
     * no app on the phone can reach into `/data/system` to read it either way.
     */
    internal val HELPER_STAGED_DAEMON: String get() = SYSTEM_HELPER_DAEMON

    /**
     * Puts the flavour's own daemon where the exploit execs it, as root.
     *
     * This is the hand-off the helper's own documentation assumes, and the reason it matters is that the
     * helper cannot do it correctly by itself: it runs as the system uid, so the installed daemon - mode
     * 0700 root - is denied to it, and its remaining sources are other apps' copies of *some* KernelSU.
     * Measured on this device, that is what happened: nothing had staged this path, so the helper took
     * `me.weishu.kernelsu`'s bundled `libksud.so` (4,892,712 bytes, byte-for-byte) while the module in
     * the kernel was KernelSU-Next 3.4.0 - a daemon whose UAPI does not match the module it would be
     * asked to drive.
     *
     * Written on every call rather than only when absent, which is the other half of the fix: an existing
     * copy may be a previous flavour's, and the helper keeps whatever it finds. Rewriting it is cheap
     * next to the run it enables, and it is what makes a flavour change safe.
     *
     * **A version is not an identity.** The measurement this is built on: two daemons on this phone both
     * answered `ksud 3.4.0 (uapi: 4)` and were 1.2 MB apart - 5,518,544 bytes for the copy the installed
     * KernelSU-Next manager had written to `/data/adb/ksud`, 4,230,992 for the payload's own build. A
     * comparison by version called those the same daemon, and a run that followed handed the exploit the
     * installed one: the log ends at `ksud::late_load: Loading kernelsu.ko for KMI android15-6.6` and the
     * kernel panics - measured here as four reboots in twenty minutes, every one of them from a flow that
     * looked healthy until the exec.
     *
     * So content is the authority and a version is not: [payloadSha256] is the digest of the daemon this
     * device's payload ships - the Samsung-KDP build of the flavour the run resolved - and a candidate is
     * staged only when it hashes to it. A candidate that is merely *there* is named and refused, which
     * costs a run on a phone whose installed KernelSU is another build and cannot cost the kernel.
     * [expectedVersion] is reporting only: it is the daemon this boot is running, said under the line that
     * names which copy was staged.
     *
     * The chosen daemon is left in **three** places, because three different things read it: the path the
     * exploit execs ([destination]), [DAEMON_STAGE_PATH] - the copy the daemon's own `late-load` renames
     * onto `/data/adb/ksud` as its first act - and [HELPER_STAGED_DAEMON], the copy the system-uid helper
     * stages *from* on a boot with no root. Only the first was written before, so a DFR run got as far as
     * exec'ing the daemon and no further: without the second file the daemon aborts with "Failed to stage
     * ksud" and nothing is loaded. The third is what the helper reads, and it is a copy in `/data/system`
     * rather than the temp directory for the reason its own KDoc gives - `system_server` cannot read
     * `shell_data_file`, so the copy this app leaves in the temp directory is one the helper cannot open.
     */
    internal fun stageDaemonCommand(
        payloadDaemon: String? = null,
        payloadSha256: String? = null,
        sources: List<String> = DAEMON_SOURCES,
        destination: String = STAGED_DAEMON,
        expectedVersion: String? = null,
        stagePath: String = DAEMON_STAGE_PATH,
    ): String = buildString {
        fun shaOf(source: String) = hashCommand(source)

        append("want='").append(payloadSha256?.trim().orEmpty()).append("'; wantver='")
            .append(expectedVersion?.trim().orEmpty()).append("'; src=''; got=''; v=''; ")
        // Read from the device only when the caller has not already hashed it, because one caller runs on
        // every return to the foreground and would otherwise read six megabytes twice to learn what it
        // was just told.
        append("if [ -z \"${'$'}want\" ] && [ -s '").append(payloadDaemon.orEmpty())
            .append("' ]; then want=").append(shaOf(payloadDaemon.orEmpty())).append("; fi; ")
        append("if [ -z \"${'$'}want\" ]; then echo '[x] the payload daemon for this device is not known, so ")
            .append("nothing is staged: a daemon this app did not take from the payload belongs to whichever ")
            .append("KernelSU this phone runs'; exit 3; fi; ")
        (listOfNotNull(payloadDaemon?.takeIf(String::isNotBlank)) + sources).forEach { source ->
            append("if [ -z \"${'$'}src\" ] && [ -s '").append(source).append("' ]; then v=")
                .append(shaOf(source)).append("; ")
            append("if [ \"${'$'}v\" = \"${'$'}want\" ]; then src='").append(source)
                .append("'; got=\"${'$'}('").append(source).append("' -V 2>/dev/null | head -1)\"; fi; ")
            // The line that has to exist *before* a run: a phone whose installed KernelSU is another build
            // is told which file that is, rather than finding out from a kernel that does not come back.
            append("if [ -z \"${'$'}src\" ]; then echo \"[!] not the daemon this device payload ships ")
                .append("(sha256 ${'$'}v), so it is not staged: ").append(source).append("\"; fi; fi; ")
        }
        // Named and refused rather than guessed: staging *a* daemon would be worse than staging none,
        // because the exploit would exec it and fail somewhere that looks like the exploit's fault.
        append("if [ -z \"${'$'}src\" ]; then echo \"[x] no daemon to stage: none of the copies on this phone ")
            .append("is the daemon this device payload ships (sha256 ${'$'}want)\"; exit 3; fi; ")
        append("/system/bin/cp -f \"${'$'}src\" '").append(destination).append("' || exit 4; ")
        // The identity the module's policy expects: the system, and nothing else.
        append("/system/bin/chown system:system '").append(destination).append("' || exit 5; ")
        append("/system/bin/chmod 700 '").append(destination).append("' || exit 6; ")
        // The daemon's own copy, mode 0755 like the one it replaces: it renames this onto /data/adb/ksud
        // and then re-applies root:root 0755 itself, so what matters here is only that the file is
        // there and readable by the root process doing the rename.
        append("/system/bin/cp -f \"${'$'}src\" '").append(stagePath).append("' || exit 7; ")
        append("/system/bin/chmod 755 '").append(stagePath).append("' || exit 8; ")
        // And in the third place a daemon is read from: the copy the system-uid helper stages [destination]
        // out of, on a boot with no root at all. That boot is the one this staging is ultimately for, and the
        // file it needs used to be written only *during* a run - so a phone that had rebooted after one was
        // left with a helper that could find nothing to stage. Skipped when it is already the source: copying
        // a file onto itself is an error, and that source is this path whenever the app staged from it.
        append("if [ \"${'$'}src\" != '").append(PAYLOAD_STAGED_DAEMON).append("' ]; then ")
            .append("/system/bin/cp -f \"${'$'}src\" '").append(PAYLOAD_STAGED_DAEMON).append("' || exit 9; ")
            .append("/system/bin/chmod 755 '").append(PAYLOAD_STAGED_DAEMON).append("' || exit 10; fi; ")
        // And in the second place the helper can actually read: a `system_data_file` beside [destination],
        // because the copy above is under a type `system_server` is denied. Same bytes, same pass, so the
        // helper's comparison of the two can never be against a stale copy - and the source is always one of
        // the temp paths, never this destination, so the copy is never onto a file that is already it.
        append("if [ \"${'$'}src\" != '").append(HELPER_STAGED_DAEMON).append("' ]; then ")
            .append("/system/bin/cp -f \"${'$'}src\" '").append(HELPER_STAGED_DAEMON).append("' || exit 11; ")
            .append("/system/bin/chown system:system '").append(HELPER_STAGED_DAEMON).append("' || exit 12; ")
            .append("/system/bin/chmod 600 '").append(HELPER_STAGED_DAEMON).append("' || exit 13; fi; ")
        append("echo \"[+] staged ").append(destination).append(" from ${'$'}src${'$'}{got:+ (${'$'}got)}\"; ")
        // Said plainly, and as a note rather than a warning, because by the time it prints the staging has
        // already succeeded - and what it compares the staged daemon against is not "the device" but the file
        // the phone's own installed KernelSU keeps at [INSTALLED_DAEMON]. That file is written by whichever
        // manager is installed, so on a phone whose payload is this project's it is a *different build*:
        // measured here as 5,518,544 bytes of vanilla KernelSU-Next 3.4.0 against the payload's own 4,230,992.
        // Worth saying, because that file being another build is exactly the state that used to be staged by
        // mistake - and worth *not* saying as an `[!]`, which read as a failure that had not happened and sent
        // a reader looking for one; the digest above is what was checked.
        append("if [ -z \"${'$'}wantver\" ]; then echo '[?] the copy installed at ").append(INSTALLED_DAEMON)
            .append(" was not read, so its version cannot be compared with the one the staged daemon reports'; ")
            .append("elif case \"${'$'}got\" in *\"${'$'}wantver\"*) true;; *) false;; esac; then ")
            .append("echo \"[*] the daemon this run execs is also the build installed at ").append(INSTALLED_DAEMON)
            .append(" (${'$'}got)\"; ")
            .append("else echo \"[*] the copy installed at ").append(INSTALLED_DAEMON)
            .append(" reports ${'$'}wantver, the daemon this run execs reports ${'$'}got: the digest is what was ")
            .append("checked, and this line is what differs rather than a failure\"; fi")
    }

    /**
     * Runs [stageDaemonCommand] as root, or null when no root shell answered.
     *
     * The payload's own daemon is read first and its digest passed in, because that digest is what decides
     * which copy may be staged at all - and it comes from the verified cache rather than a download, so
     * this costs a hash rather than a network round trip. The running daemon's version goes with it as the
     * reading that says whether the copy staged is also the one this boot is running.
     */
    fun stageDaemon(context: Context): DfrAction? {
        val daemon = payloadDaemon(context)
        return stageDaemonAs(
            payloadDaemon = daemon?.absolutePath,
            payloadSha256 = daemon?.let { runCatching { sha256Of(it) }.getOrNull() },
            expectedVersion = runningDaemonVersion(context),
        )
    }

    /**
     * The staging itself, with the payload and the version already read - so a caller that needs the same
     * answers for two questions asks the device once. See [armStageForNextBoot], which asks whether the
     * staging can be skipped and then stages, and both halves of that are the same digest.
     */
    private fun stageDaemonAs(
        payloadDaemon: String?,
        payloadSha256: String?,
        expectedVersion: String?,
    ): DfrAction? = runAction(
        stageDaemonCommand(
            payloadDaemon = payloadDaemon,
            payloadSha256 = payloadSha256,
            expectedVersion = expectedVersion,
        ),
    )

    /**
     * The daemon version this device is running, or null when it could not be read.
     *
     * Null is not a version and nothing is decided by it: the staging is held to the payload's own digest,
     * and this reading only says whether what was staged is also the daemon this boot is running. An
     * unreadable daemon is reported as unread rather than treated as a match.
     */
    private fun runningDaemonVersion(context: Context): String? =
        runCatching { KernelSuVersionProbe.read(context).daemon }.getOrNull()

    /**
     * The daemon this device's payload ships, as the verified copy this app keeps of it.
     *
     * Null is "no verified payload is cached", which the staging reports instead of working around: the
     * installed daemon belongs to whichever KernelSU this phone happens to run, and handing *that* to the
     * exploit is what panics the kernel on a phone whose payload is another project's build.
     */
    private fun payloadDaemon(context: Context): File? =
        runCatching { KnownGoodPayloadStore.daemon(context) }.getOrNull()

    /** The payload daemon's digest, or null when there is no payload daemon to hash. */
    private fun payloadDaemonSha256(context: Context): String? =
        payloadDaemon(context)?.let { daemon -> runCatching { sha256Of(daemon) }.getOrNull() }

    /**
     * What a file on the device hashes to, in one command.
     *
     * One implementation for the two places that compare a file by content - the staging that picks a copy,
     * and the read that decides whether the staging can be skipped - because two spellings of a digest
     * comparison are two ways for the same question to be answered differently.
     *
     * `sha256sum` is what every Android's toybox answers, with the absolute path as the fallback for a shell
     * whose PATH is not the system's: this runs through Shizuku's shell as often as through the app's own,
     * and those two do not agree about much.
     */
    private fun hashCommand(source: String): String =
        "${'$'}({ sha256sum '" + source + "' 2>/dev/null || /system/bin/toybox sha256sum '" +
            source + "' 2>/dev/null; } | cut -d' ' -f1)"

    /**
     * Whether the file the daemon's own late-load renames is already this device's daemon, in one command.
     *
     * The staging writes two files and only one of them is read here, because only one of them is
     * *consumed*: the late-load renames [DAEMON_STAGE_PATH] onto `/data/adb/ksud` as its first act, so it
     * is the file whose absence stops the next boot - while [STAGED_DAEMON] is the copy the exploit execs
     * and is rewritten on the way in by whoever is about to run it.
     *
     * A digest and not a version, for the measurement the staging is built on: two daemons on this phone
     * answered the same `ksud 3.4.0 (uapi: 4)` and were 1.2 MB apart, so "the file says the version this
     * boot runs" is answered *yes* by a build that belongs to another KernelSU - and treating that as armed
     * is how a boot comes to exec a module loader its kernel cannot survive. The command copies nothing - it
     * is the cheap half of [armStageForNextBoot], and the point of it is that an app that already armed this
     * boot does not move five megabytes twice to find that out.
     */
    internal fun stageArmedCommand(
        payloadSha256: String? = null,
        stagePath: String = DAEMON_STAGE_PATH,
    ): String = buildString {
        append("want='").append(payloadSha256?.trim().orEmpty()).append("'; ")
        append("if [ ! -s '").append(stagePath).append("' ]; then echo '").append(STAGE_ABSENT).append("'; ")
        append("elif [ -z \"${'$'}want\" ]; then echo '").append(STAGE_UNCOMPARED).append("'; ")
        append("else v=").append(hashCommand(stagePath)).append("; if [ \"${'$'}v\" = \"${'$'}want\" ]; then echo '")
            .append(STAGE_ARMED).append("'; else echo '").append(STAGE_DIFFERENT).append("'; fi; fi")
    }

    /**
     * Whether [stageArmedCommand] said the stage file is already this device's daemon.
     *
     * A reader of the marker rather than of the whole answer, because the three ways of being *not* armed
     * - missing, another build's, and a running daemon that could not be read - all lead to the same next
     * step. The command distinguishes them anyway, and [armStageForNextBoot] puts its line in the log: on a
     * phone that will not reroot after a restart these four words are the first thing to look at, and
     * "the file was written again" without them is a line that cannot say why it had to be.
     */
    internal fun stageArmed(output: String?): Boolean = output?.contains(STAGE_ARMED) == true

    /**
     * The same answer as one of the five states a row can show.
     *
     * Read as its own function so the four markers and the two silences can be held apart without a device:
     * the failure this exists to prevent is a row that reads "armed" for a file nobody could see, which is
     * the one direction of that mistake the next boot cannot recover from on its own.
     */
    internal fun stageReadingOf(output: String?): DfrStageReading = when {
        output == null -> DfrStageReading.Unreadable
        output.contains(STAGE_ARMED) -> DfrStageReading.Armed
        output.contains(STAGE_ABSENT) -> DfrStageReading.Absent
        output.contains(STAGE_DIFFERENT) -> DfrStageReading.Different
        output.contains(STAGE_UNCOMPARED) -> DfrStageReading.Uncompared
        // A shell that answered with something other than these four is a reading this app cannot place,
        // and the safe place to leave it is with the silences.
        else -> DfrStageReading.Unreadable
    }

    /**
     * Reads the stage file, on whichever shell this phone has.
     *
     * The check itself needs no root - `test -s` and executing the staged daemon with `-V` are things the
     * `shell` user may do, and the file is mode 0755 in the one directory it owns - so a phone that has just
     * rebooted into no root can still be told whether its next restart would have a daemon to load. That is
     * the state this reading is asked about most often, and the reason it is not taken through
     * [armStageForNextBoot], which needs root to write what it re-stages.
     *
     * The digest it compares against is the payload daemon's - the same one the staging is held to - so "a
     * different build is in there" means the same thing to this row as it does to the write that follows
     * it, and a phone with no verified payload to compare against is [DfrStageReading.Uncompared] rather
     * than a guess.
     */
    fun readDaemonStage(context: Context): DfrStageReading = stageReadingOf(
        runOnEitherShell(
            stageArmedCommand(payloadSha256 = payloadDaemonSha256(context)),
            TIMEOUT_SECONDS,
        )?.output,
    )

    /**
     * Makes sure the daemon a late-load reads is in place for the next boot, if this boot has root.
     *
     * Called from the places where the app knows it has root - a load that just landed, a screen coming
     * back to the foreground on a rooted phone - because the file it writes is *consumed* by every run that
     * uses it: a payload's late-load and the system-uid helper's both rename it away, and nothing else puts
     * it back. Without this the sequence "root now, reboot" leaves the next boot with a helper that starts,
     * looks for a daemon, finds none, and aborts with "Failed to stage ksud" - which reads as the exploit
     * having failed, on a phone whose only real problem is a file nobody rewrote.
     *
     * Root is asked of the authoritative reading rather than of the native paths, because the native ones
     * can be denied by policy while root is live - and *off the main thread*, as that reading can start a
     * process. The check comes before the write so this can be called whenever the app is resumed without
     * copying a daemon every time; the copy itself is [stageDaemon]'s, so what is written here is exactly
     * what a screen about to run the helper writes.
     */
    fun armStageForNextBoot(context: Context): DfrStageArming {
        if (!RootStatusProbe.isActive()) return DfrStageArming.NoRoot
        val daemon = payloadDaemon(context)
        val sha = daemon?.let { runCatching { sha256Of(it) }.getOrNull() }
        val reading = KernelSuRuntime.rootShell(
            stageArmedCommand(payloadSha256 = sha),
            TIMEOUT_SECONDS,
        )
        // Said before it is acted on, on the same terms as the version reading above: this runs in the
        // background on every return to the foreground, and it is the only place a phone's own answer about
        // its stage file is written down - which is what a report of "it did not reroot" has to be argued
        // against. Nothing is said on a phone with no root, where the question is not asked at all.
        AppLog.debug(AppLogTags.KERNEL_SU, "Reroot staging: ${reading?.output?.trim().orEmpty()}")
        if (stageArmed(reading?.output)) return DfrStageArming.Armed
        val staged = stageDaemonAs(
            payloadDaemon = daemon?.absolutePath,
            payloadSha256 = sha,
            expectedVersion = runningDaemonVersion(context),
        )
        return if (staged?.ok == true) DfrStageArming.Armed else DfrStageArming.Failed
    }

    /**
     * Installs the stage two on whichever shell this phone has.
     *
     * `pm install` is a permission the `shell` user holds by itself - it is what makes `adb install` work
     * with no root - so a phone that has just rebooted into no root can still have its helper put back,
     * which is the state this flow is opened in whenever what is wrong is the helper rather than the root:
     * a copy from another build, or none at all.
     *
     * What this does not change is the check the flow depends on. Whether the APK is accepted under the
     * shared user is Package Manager's own signature test against that user's certificate list, and it does
     * not care who asked; an install that lands as an ordinary app anyway is caught by the uid the next
     * reading reports, with [uninstallStageTwo] and [DfrStep.RemoveStageTwo] as the way back.
     *
     * Root first, so a rooted phone installs from app storage as it always has and stages nothing extra: the
     * copy [stageForShell] writes is only paid for when there is no root to read the original, and it is a
     * file transfer rather than a second command. Null only when neither shell answered.
     */
    internal fun installStageTwo(apk: File): DfrAction? {
        runAction(installCommand(apk.absolutePath))?.let { rooted -> return rooted }
        val staged = stageForShell(apk) ?: return null
        return verdictFor(
            KernelSuRuntime.unprivilegedShell(installCommand(staged)),
            FAILURE,
        )
    }

    /**
     * The copy of the helper the `shell` user may read, or null when it could not be written.
     *
     * Written on every call rather than compared first, and that is the one place this differs from the
     * staging a run does: the app cannot read `/data/local/tmp` itself - it is the `shell` user's directory,
     * mode 0771 - so there is nothing to compare against, and a skip would need a reading this side of the
     * binder does not have. What it costs is one APK transfer on a press that only happens without root,
     * and what it buys is that the file installed is always the one in the APK that asked.
     *
     * A refusal is null and is logged rather than thrown, because null is already this route's own answer for
     * "no shell": the install has nothing else to try, and the screen's sentence for it says so. Reaching
     * here at all means no root answered, so the two are the same refusal from the reader's side.
     */
    private fun stageForShell(apk: File): String? = runCatching {
        ShizukuController.writeFile(SHELL_INSTALL_PATH, "644", apk.inputStream())
        SHELL_INSTALL_PATH
    }.onFailure { error ->
        AppLog.warn(
            AppLogTags.KERNEL_SU,
            "The helper could not be staged for an install without root: ${error.message}",
        )
    }.getOrNull()

    /**
     * Removes the stage two, which is the only way past an install that landed as an ordinary app:
     * Package Manager assigns a package its uid when it installs it and never revisits it.
     */
    internal fun uninstallCommand(packageName: String = STAGE_TWO_PACKAGE): String =
        "/system/bin/pm uninstall --user 0 '" + packageName + "'"

    /**
     * Removes the helper, on whichever shell this phone has.
     *
     * `pm uninstall` is a delete the `shell` user holds, which makes this the other half of the flow that
     * survives a phone with no root - the one a setup is undone from before it has ever been rerooted. It is
     * deliberately **not** the whole clean-up: taking this app's certificate back out of `packages.xml` is a
     * write only root can make, so that half still refuses, and the screen's clean-up keeps its record until
     * it lands - see [DfrCleanUpOutcome].
     */
    internal fun uninstallStageTwo(): DfrAction? =
        verdictFor(runOnEitherShell(uninstallCommand(), TIMEOUT_SECONDS), FAILURE)

    /**
     * Starts the stage two's own screen, where the run button is - or, with [autorun], starts the run.
     *
     * [rerootAtBoot] is passed only when this app has a value to give, so that the helper can tell "the
     * app says off" from "the app did not say", which is the same distinction the extra itself makes.
     */
    internal fun launchCommand(
        packageName: String = STAGE_TWO_PACKAGE,
        activity: String = STAGE_TWO_ACTIVITY,
        autorun: Boolean = false,
        rerootAtBoot: Boolean? = null,
        flavor: KernelSuFlavor? = null,
        tint: String? = null,
    ): String = buildString {
        append("/system/bin/am start -n '").append(packageName).append("/").append(activity).append('\'')
        if (autorun) append(" --ez ").append(STAGE_TWO_AUTORUN_EXTRA).append(" true")
        if (rerootAtBoot != null) {
            append(" --ez ").append(STAGE_TWO_REROOT_EXTRA).append(' ').append(rerootAtBoot)
        }
        // `--es` rather than `--ez`: this is a string, and `am` reads a missing `--es` as an empty one -
        // which is why the helper treats "absent" and "blank" as the same answer and says so.
        if (flavor != null) {
            append(" --es ").append(STAGE_TWO_FLAVOR_EXTRA).append(' ').append(flavor.id)
        }
        // Quoted, because the value is a list of `role=hex` pairs and the shell is the thing that parses
        // this line: unquoted, a comma would be nothing to a shell and a hex run that began with a digit
        // would still be a word - but the quoting is what keeps that true of the next value added here.
        if (tint != null) {
            append(" --es ").append(STAGE_TWO_TINT_EXTRA).append(" '").append(tint).append('\'')
        }
    }

    /**
     * How long this boot has been up.
     *
     * A clock that counts from boot rather than the wall clock's: uptime cannot be moved, so it is the only
     * reading that can answer "has this phone restarted since then" - see [DfrFlow.rebootedSince].
     *
     * This first read `/proc/uptime`, which is the same number and was **denied** on the phone this was
     * tested on: the file is `proc_uptime`, an app domain has no read on it, so the failure was swallowed
     * by a `runCatching` and every answer came back zero - and an uptime of zero is shorter than every
     * elapsed time, so "has it rebooted since" answered **yes** for every instant the app had recorded.
     * That is how the flow came to skip the reboot steps it exists to insist on. [SystemClock] is the app's
     * own monotonic clock, needs no permission, and is the same quantity - the rest of this app already
     * reads it for its own timeouts.
     */
    fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

    /**
     * Reads both helper builds - the one on the phone and the one in this APK - and compares them.
     *
     * Two reads that need no shell and no permission, which matters more than it looks: this is the one
     * judgement in the flow that can be made on a phone that has just rebooted with no root, and it is
     * answered from Package Manager alone. [bundled] is the unpacked asset [DfrApk.bundled] just wrote, so
     * a read with nothing to unpack is a read with nothing to compare rather than a disagreement.
     */
    fun readStageTwoBuild(context: Context, bundled: File?): StageTwoBuildReading = StageTwoBuildReading(
        installed = installedStageTwoVersion(context),
        bundled = bundled?.let { archiveVersion(context.packageManager, it.absolutePath) },
    )

    /**
     * The version code of the helper installed on this phone, or null when it is not installed.
     *
     * Null and not zero: "not installed" and "installed under a code I read as nothing" are different
     * answers, and the flow's own install step owns the first - a zero here would turn a missing helper
     * into a stale one.
     */
    private fun installedStageTwoVersion(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(STAGE_TWO_PACKAGE, 0).longVersionCode
    }.getOrNull()

    /**
     * The version code inside an APK file, or null when it could not be parsed.
     *
     * Read from the file rather than from an installed package, which is the whole point: the copy this
     * app ships is installed nowhere, so asking what build it is means asking the file.
     */
    private fun archiveVersion(packageManager: PackageManager, apkPath: String): Long? = runCatching {
        packageManager.getPackageArchiveInfo(apkPath, 0)?.longVersionCode
    }.getOrNull()

    /**
     * Reads the three facts, on whichever shell this phone has.
     *
     * Root first and Shizuku's plain shell when root does not answer, and that fallback is the difference
     * between a screen that reports and a screen that says it could not look: every part of [probeCommand]
     * is something the `shell` user may do itself, and the phone this flow is opened on most often is one
     * that has just rebooted with no root at all - the state the whole system-uid setup exists to be woken
     * out of. Null only when neither shell answered, which is still the one case the flow will not guess
     * about.
     */
    fun probe(): DfrProbe? = probeOf(runOnEitherShell(probeCommand(), PROBE_TIMEOUT_SECONDS))

    /**
     * The same reading through the plain Shizuku shell, which is the only one a boot with no root has.
     *
     * Every part of [probeCommand] is a thing the `shell` user may do: `pm list packages` is a read, the
     * marker is one `test -e` in a directory anything may search, and the framework section is `cut`, `pidof`
     * and `awk` over `/proc`. That is why the boot path can answer the same questions the screen does - and
     * `armed` is the one it cannot do without: it is what tells a boot whether the kernel has already been
     * patched this boot, which is the difference between a rerun and a second attempt.
     *
     * Null when Shizuku is not usable, and null is not "not armed": a boot that could not ask has spent
     * nothing, and [dfrBootDecision] answers it with its own reason instead of a guess.
     *
     * A boot is given this reading rather than [probe]'s fallback, and the difference is what the root half
     * of that fallback can do on a boot: a `su` that exists but is waiting on a grant prompt nobody is at
     * the screen to answer, with [PROBE_TIMEOUT_SECONDS] of waiting in front of it. A screen can afford to
     * find out that it has no root; a boot that is deciding whether to spend its one attempt cannot.
     */
    fun probeWithoutRoot(): DfrProbe? = probeOf(KernelSuRuntime.unprivilegedShell(probeCommand()))

    /**
     * Starts the helper's own run through the plain Shizuku shell, with no screen and nobody watching.
     *
     * The same command the screen sends - see [launchCommand] - so a boot rerun and a hand-started one cannot
     * come apart: the difference is only in the extras, which is where the difference belongs.
     *
     * The verdict is the shell's exit code and the word `am` uses for a refusal, which is `Error` rather than
     * the `Failure` the package manager prints: an `am start` that could not reach the component exits zero on
     * some builds while saying so on its first line, and a run that was never started being read as started
     * is exactly the failure the notification must not report.
     */
    internal fun launchWithoutRoot(
        autorun: Boolean,
        rerootAtBoot: Boolean?,
        flavor: KernelSuFlavor? = null,
        tint: String? = null,
    ): DfrAction? = verdictFor(
        KernelSuRuntime.unprivilegedShell(
            launchCommand(autorun = autorun, rerootAtBoot = rerootAtBoot, flavor = flavor, tint = tint),
        ),
        LAUNCH_FAILURE,
    )

    /**
     * Starts the helper's own screen - or, with [autorun], its run - on whichever shell this phone has.
     *
     * The same command as [launchWithoutRoot] through the same fallback [probe] describes, and the reason a
     * screen gets the fallback is what opening the helper is *for* on a phone with no root: the helper is
     * the thing that puts root back, so "start it by hand from the app" is the one action the flow can still
     * take on a boot that has none. `am start` is a thing the `shell` user may do itself, so this is not a
     * refusal waiting to happen - the extra round trip through the root half is only what keeps a rooted
     * phone off Shizuku entirely.
     */
    internal fun launch(
        autorun: Boolean = false,
        rerootAtBoot: Boolean? = null,
        flavor: KernelSuFlavor? = null,
        tint: String? = null,
    ): DfrAction? = verdictFor(
        runOnEitherShell(
            launchCommand(autorun = autorun, rerootAtBoot = rerootAtBoot, flavor = flavor, tint = tint),
            TIMEOUT_SECONDS,
        ),
        LAUNCH_FAILURE,
    )

    /**
     * What the phone has installed as the helper, from Package Manager alone.
     *
     * No shell, which matters more than it looks: this is a question a boot can answer before anything on the
     * device can run a command, and it is the one part of the boot decision that is about the phone rather
     * than about this boot.
     *
     * The uid comes from the running package rather than from `pm list packages -U`, and the difference is
     * not a preference: the list command is a shell tool, and this reading is deliberately shell-free. It is
     * also not the trap the probe's own uid reading was - `applicationInfo.uid` *is* the package's identity,
     * where the `dumpsys` line that misled it was a number inside a permission listing.
     *
     * [bundled] is the unpacked asset [DfrApk.bundled] wrote, or null when it could not be unpacked: with
     * nothing to compare against, a helper that is installed as the system uid is reported as
     * [DfrHelperStanding.System], because the standing the boot can act on is whether the exploit can run
     * where it has to - and "this build's helper" is a claim this cannot make without the file.
     */
    internal fun helperStanding(context: Context, bundled: File?): DfrHelperStanding {
        val info = runCatching {
            context.packageManager.getPackageInfo(STAGE_TWO_PACKAGE, 0)
        }.getOrNull() ?: return DfrHelperStanding.Missing
        if (info.applicationInfo?.uid != SYSTEM_UID) return DfrHelperStanding.Ordinary
        val bundledCode = bundled?.let { archiveVersion(context.packageManager, it.absolutePath) }
        return if (bundledCode != null && info.longVersionCode != bundledCode) {
            DfrHelperStanding.Stale
        } else {
            DfrHelperStanding.System
        }
    }

    /**
     * Runs one of the actions above as root, which is what the ones that write need.
     *
     * The daemon staging is the only caller left, and it is here by construction rather than by preference:
     * the two files it writes have to end up owned by the system and mode 0700, `cp` alone would leave them
     * the `shell` user's, and a transport that cannot `chown` would write a daemon the module's policy
     * refuses - a failure that surfaces inside the exploit. Every other command here is the `shell` user's
     * own: see [installStageTwo], [uninstallStageTwo] and [launch].
     */
    fun runAction(command: String): DfrAction? =
        verdictFor(KernelSuRuntime.rootShell(command, TIMEOUT_SECONDS), FAILURE)

    /**
     * A command the `shell` user may run itself, on whichever shell this phone has.
     *
     * Root first, and Shizuku's plain shell when root did not answer - the chain this app uses wherever the
     * command is one the shell user holds, and what makes the system-uid flow readable and drivable on a
     * phone that has rebooted into no root: `pm list packages`, one `test -e`, `am start` and `pm uninstall`
     * are all the shell user's own, and root is only ever the *cheaper* of the two here, because it needs
     * nothing else running.
     *
     * The two readings the boot gate makes - [probeWithoutRoot] and [launchWithoutRoot] - deliberately do not
     * come through here: see [probeWithoutRoot] for the `su` a boot must not wait on.
     */
    private fun runOnEitherShell(command: String, timeoutSeconds: Long): ShizukuController.ShellResult? =
        KernelSuRuntime.rootShell(command, timeoutSeconds) ?: KernelSuRuntime.unprivilegedShell(command)

    /** A probe from whatever a shell answered, or null when no shell did. */
    private fun probeOf(result: ShizukuController.ShellResult?): DfrProbe? =
        result?.let { DfrProbe.parse(it.output) }

    /**
     * The verdict for a shell command, from the word the tool refuses with.
     *
     * The word is the caller's rather than one word for both tools: `pm` answers with Success or Failure
     * while `am` answers with a Starting line or an Error, and neither word means anything to the other. The
     * exit code is required as well as the word, because both tools have printed their own refusal and
     * exited zero on some builds - and a command that never ran being read as one that did is the failure
     * this whole reading exists to catch.
     */
    private fun verdictFor(result: ShizukuController.ShellResult?, refusal: String): DfrAction? =
        result?.let { DfrAction(it.exitCode == 0 && !it.output.contains(refusal), it.output) }

    /**
     * Longer than the app's usual shell window: this process builds a framework context and parses a
     * packages.xml that can be megabytes, and being cut off mid-write is not a thing to be impatient
     * about.
     */
    private const val TIMEOUT_SECONDS = 120L

    /**
     * The line [removalAndRestartCommand] prints once the removal got through and the daemon is about to be
     * asked for the restart. One constant rather than the sentence twice, so the command and the reading of
     * it cannot drift apart.
     */
    internal const val RESTART_REQUESTED =
        "[+] rmg: the certificate is out; asking the daemon to restart the userspace"

    /** Three reads of a package manager, which is quick but is still a package manager. */
    private const val PROBE_TIMEOUT_SECONDS = 30L

    private const val FAILURE = "Failure"

    /** The word the ported entry point answers anything it refused with. */
    private const val INJECTOR_FAILURE = "[x] FAILED"

    /** The word `am` answers a start that did not happen with. */
    private const val LAUNCH_FAILURE = "Error"

    // The four answers of [stageArmedCommand], as markers rather than as prose, because that command's
    // output is read rather than logged - the same shape [probeCommand] uses, and for the same reason.
    private const val STAGE_ARMED = "RMG-stage=armed"
    private const val STAGE_ABSENT = "RMG-stage=absent"
    private const val STAGE_DIFFERENT = "RMG-stage=different"
    private const val STAGE_UNCOMPARED = "RMG-stage=uncompared"

    /** `android.uid.system`, which is what the helper's package runs as once the inject has been honoured. */
    private const val SYSTEM_UID = 1000
}

/**
 * The framework's age in milliseconds, from the two numbers [DfrInstall.frameworkSection] printed.
 *
 * `USER_HZ_MILLIS` is the hundredth of a second `/proc/<pid>/stat` counts in, which is `USER_HZ` of 100
 * on every Linux and not a per-architecture value. The subtraction is here rather than in the shell so
 * it has exactly one implementation, and either number missing is null rather than a default: the answer
 * decides whether a reboot is asked for, and a guessed one is a reboot that is never asked for.
 */
internal fun frameworkUptimeMillis(section: String?): Long? {
    val text = section ?: return null
    val up = Regex("up=([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
    val start = Regex("start=([0-9]+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    // A negative age is a reading from a boot this one replaced - a clock that moved, not a framework
    // younger than nothing - and belongs with the unreadable ones rather than in the rule.
    return (up * 1000.0 - start * USER_HZ_MILLIS).takeIf { it >= 0.0 }?.toLong()
}

// The one command's markers and the words it answers with, shared by the code that writes the command
// and the code that reads it - the two are in different objects, and a marker that drifted between them
// would read as a device that answered nothing.
private const val MARK_PACKAGE = "RMG-package"
private const val MARK_ARMED = "RMG-armed"
private const val MARK_FRAMEWORK = "RMG-framework"
private const val MARK_END = "RMG-end"

/** One hundredth of a second: what `/proc/<pid>/stat` counts its start time in. */
private const val USER_HZ_MILLIS = 10.0
private const val PACKAGE_PREFIX = "package:"
private const val ARMED = "armed"
private const val CLEAR = "clear"

/** `uid:1000` exactly - see [DfrProbe.Companion.parse] for why the right edge is anchored. */
private val SYSTEM_UID_REGEX = Regex("""uid:1000(?!\d)""")
