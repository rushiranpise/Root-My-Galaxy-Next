package dev.busung.s25uroot.dfr

import android.content.Context
import dev.busung.s25uroot.KernelSuRuntime
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

    /** One line for the app log. */
    fun summary(): String = "dfr ${mode.name.lowercase()}: ${if (ok) "ok" else "failed"}" +
        log.lineSequence().lastOrNull { it.startsWith("[x]") }?.let { " - $it" }.orEmpty()
}

/**
 * What the device's own tools answered about the stage two and the kernel.
 *
 * Three facts, and each is read from the thing that decides it rather than from this app's own records:
 * whether the APK is installed and which uid it runs as (`pm list packages -U`, one line carrying both),
 * and whether the exploit has already armed its hooks this boot (the marker node). The app keeps its own
 * record of when it did each step, and that record is only ever used for ordering - never as the answer
 * to "is it done".
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
            val markers = listOf(MARK_PACKAGE, MARK_ARMED, MARK_END)
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
    const val STAGE_TWO_PACKAGE = "dev.rushiranpise.rmg.stage2"
    const val STAGE_TWO_ACTIVITY = "dev.busung.s25uroot.dfr.stage2.Stage2Activity"

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
    ): DfrResult? {
        val command = command(context.packageCodePath, mode, apkPath = apkPath, keyHex = keyHex)
        val result = KernelSuRuntime.rootShell(command, TIMEOUT_SECONDS) ?: return null
        val log = result.output
        // The entry point exits non-zero and prints "[x] FAILED: ..." for anything it refused, and a
        // refusal is the common, expected answer for several of these modes - so it is reported as a
        // result with the reason, not as an absence of one.
        val ok = result.exitCode == 0 && !log.contains("[x] FAILED")
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
        append("echo '").append(MARK_END).append("'")
    }

    /**
     * `pm install` of the stage two, run as root.
     *
     * `-r` reinstalls over a copy already there and `-d` permits a version code lower than the installed
     * one, because the artifact being installed is built by hand and its code does not track releases.
     * Neither flag weakens the check that matters here: the signature check against the shared user's
     * certificate is what this whole flow is for, and it is not a flag.
     */
    internal fun installCommand(apkPath: String): String =
        "/system/bin/pm install -r -d --user 0 '" + apkPath + "'"

    /**
     * Removes the stage two, which is the only way past an install that landed as an ordinary app:
     * Package Manager assigns a package's uid when it installs it and never revisits it.
     */
    internal fun uninstallCommand(packageName: String = STAGE_TWO_PACKAGE): String =
        "/system/bin/pm uninstall --user 0 '" + packageName + "'"

    /** Starts the stage two's own screen, where the run button is. */
    internal fun launchCommand(
        packageName: String = STAGE_TWO_PACKAGE,
        activity: String = STAGE_TWO_ACTIVITY,
    ): String = "/system/bin/am start -n '" + packageName + "/" + activity + "'"

    /**
     * How long this boot has been up, from the kernel's own counter.
     *
     * The kernel's, not the wall clock's: uptime cannot be moved, so it is the only reading that can
     * answer "has this phone restarted since then" - see [DfrFlow.rebootedSince]. A phone that cannot be
     * read answers zero, which reads as "booted just now" and therefore as "the reboot still has to
     * happen" - the instruction that is safe to repeat and dangerous to skip.
     */
    fun uptimeMillis(): Long = runCatching {
        File("/proc/uptime").readText().trim().substringBefore(' ').toDouble()
    }.getOrDefault(0.0).let { (it * 1000).toLong() }

    /** Reads the three facts, or null when no root shell answered. */
    fun probe(): DfrProbe? {
        val result = KernelSuRuntime.rootShell(probeCommand(), PROBE_TIMEOUT_SECONDS) ?: return null
        return DfrProbe.parse(result.output)
    }

    /** Runs one of the actions above as root. */
    fun runAction(command: String): DfrAction? {
        val result = KernelSuRuntime.rootShell(command, TIMEOUT_SECONDS) ?: return null
        val log = result.output
        // `pm` answers with a line that begins with Success or Failure; am answers with an Error or a
        // Starting line. Both are reported as text, and the verdict is the one word they use.
        val ok = result.exitCode == 0 && !log.contains(FAILURE)
        return DfrAction(ok, log)
    }

    /**
     * Longer than the app's usual shell window: this process builds a framework context and parses a
     * packages.xml that can be megabytes, and being cut off mid-write is not a thing to be impatient
     * about.
     */
    private const val TIMEOUT_SECONDS = 120L

    /** Three reads of a package manager, which is quick but is still a package manager. */
    private const val PROBE_TIMEOUT_SECONDS = 30L

    private const val FAILURE = "Failure"
}

// The one command's markers and the words it answers with, shared by the code that writes the command
// and the code that reads it - the two are in different objects, and a marker that drifted between them
// would read as a device that answered nothing.
private const val MARK_PACKAGE = "RMG-package"
private const val MARK_ARMED = "RMG-armed"
private const val MARK_END = "RMG-end"
private const val PACKAGE_PREFIX = "package:"
private const val ARMED = "armed"
private const val CLEAR = "clear"

/** `uid:1000` exactly - see [DfrProbe.Companion.parse] for why the right edge is anchored. */
private val SYSTEM_UID_REGEX = Regex("""uid:1000(?!\d)""")
