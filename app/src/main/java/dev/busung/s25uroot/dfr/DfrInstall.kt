package dev.busung.s25uroot.dfr

import android.content.Context
import android.os.SystemClock
import dev.busung.s25uroot.KernelSuRuntime
import dev.busung.s25uroot.KernelSuVersionProbe
import dev.busung.s25uroot.SYSTEM_STAGED_DAEMON
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

    /**
     * The daemons this app will stage, best first, both of them the flavour this device resolved.
     *
     * `/data/adb/ksud` is the installed daemon - the one the verified load put there, so it is the
     * flavour-correct copy by construction and outside every shared directory. The temp copy is the one
     * this app stages for its own runs, which is the same binary and the fallback for a boot whose
     * installed daemon the payload has not written yet.
     */
    internal val DAEMON_SOURCES: List<String> = listOf(
        "/data/adb/ksud",
        "/data/local/tmp/ksud-s25u-kdp",
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
     * **A size is not a version.** Measured on this device once the staging worked, the two sources are
     * both `ksud 3.4.0 (uapi: 4)` and still 1.2 MB apart - 5,518,544 bytes for the installed daemon,
     * 4,230,992 for this app's own pair build - so "both files are there, take the first" would have been
     * a coin toss between two different builds presented as one decision. [expectedVersion] is what makes
     * it a decision instead: a source answers `-V` with the version it *is*, and the first one that
     * agrees with the daemon this device is running wins over a source that merely exists first.
     *
     * The chosen daemon is left in **two** places, because two different things read it: the path the
     * exploit execs ([destination]), and [DAEMON_STAGE_PATH] - the copy the daemon's own `late-load`
     * renames onto `/data/adb/ksud` as its first act. Only the first was written before, so a DFR run
     * got as far as exec'ing the daemon and no further: without the second file the daemon aborts with
     * "Failed to stage ksud" and nothing is loaded.
     */
    internal fun stageDaemonCommand(
        sources: List<String> = DAEMON_SOURCES,
        destination: String = STAGED_DAEMON,
        expectedVersion: String? = null,
        stagePath: String = DAEMON_STAGE_PATH,
    ): String = buildString {
        // Asked with `-V` and then `--version`, the two spellings the daemon has had, and only the first
        // line of whatever it answers: the version is the first thing it says or it is nothing.
        fun versionOf(source: String): String =
            "${'$'}({ '" + source + "' -V 2>/dev/null || '" + source + "' --version 2>/dev/null; } | " +
                "head -1)"

        append("want='").append(expectedVersion?.trim().orEmpty()).append("'; src=''; got=''; alt=''; altgot=''; ")
        sources.forEach { source ->
            append("if [ -s '").append(source).append("' ]; then v=").append(versionOf(source)).append("; ")
            // The first source that exists is remembered, but only as a fallback: a source that answers
            // with the right version is preferred however late in the list it is.
            append("if [ -z \"${'$'}alt\" ]; then alt='").append(source)
                .append("'; altgot=\"${'$'}v\"; fi; ")
            append("if [ -z \"${'$'}src\" ] && [ -n \"${'$'}want\" ]; then case \"${'$'}v\" in ")
                .append("*\"${'$'}want\"*) src='").append(source)
                .append("'; got=\"${'$'}v\";; esac; fi; fi; ")
        }
        append("if [ -z \"${'$'}src\" ]; then src=\"${'$'}alt\"; got=\"${'$'}altgot\"; fi; ")
        // Named and refused rather than guessed: staging *a* daemon would be worse than staging none,
        // because the exploit would exec it and fail somewhere that looks like the exploit's fault.
        append("if [ -z \"${'$'}src\" ]; then echo '[x] no daemon to stage'").append("; exit 3; fi; ")
        append("/system/bin/cp -f \"${'$'}src\" '").append(destination).append("' || exit 4; ")
        // The identity the module's policy expects: the system, and nothing else.
        append("/system/bin/chown system:system '").append(destination).append("' || exit 5; ")
        append("/system/bin/chmod 700 '").append(destination).append("' || exit 6; ")
        // The daemon's own copy, mode 0755 like the one it replaces: it renames this onto /data/adb/ksud
        // and then re-applies root:root 0755 itself, so what matters here is only that the file is
        // there and readable by the root process doing the rename.
        append("/system/bin/cp -f \"${'$'}src\" '").append(stagePath).append("' || exit 7; ")
        append("/system/bin/chmod 755 '").append(stagePath).append("' || exit 8; ")
        append("echo \"[+] staged ").append(destination).append(" from ${'$'}src${'$'}{got:+ (${'$'}got)}\"; ")
        // Said out loud rather than left to a size or a hash nobody reads: what was staged, what version
        // it is, and whether that is the daemon this device is running. A mismatch is reported and the
        // staging still stands - a device whose daemon cannot be read is not a reason to refuse - but it
        // is said before the run, and not discovered by the run failing.
        append("if [ -z \"${'$'}want\" ]; then echo '[?] the running daemon was not read, so the staged one cannot be compared to it'; ")
            .append("elif case \"${'$'}got\" in *\"${'$'}want\"*) true;; *) false;; esac; then ")
            .append("echo \"[*] that is the daemon this device is running (${'$'}want)\"; ")
            .append("else echo \"[!] the daemon this device is running is ${'$'}want: the staged one is a different version\"; fi")
    }

    /**
     * Runs [stageDaemonCommand] as root, or null when no root shell answered.
     *
     * The running daemon's version is read first and passed in, because it is the only reading that can
     * say whether a candidate is the right build - and it is read from the same daemon the app already
     * asks after every boot, so this costs a cached lookup rather than a new probe.
     */
    fun stageDaemon(context: Context): DfrAction? =
        runAction(
            stageDaemonCommand(
                expectedVersion = runCatching { KernelSuVersionProbe.read(context).daemon }.getOrNull(),
            ),
        )

    /**
     * Removes the stage two, which is the only way past an install that landed as an ordinary app:
     * Package Manager assigns a package its uid when it installs it and never revisits it.
     */
    internal fun uninstallCommand(packageName: String = STAGE_TWO_PACKAGE): String =
        "/system/bin/pm uninstall --user 0 '" + packageName + "'"

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
    ): String = buildString {
        append("/system/bin/am start -n '").append(packageName).append("/").append(activity).append('\'')
        if (autorun) append(" --ez ").append(STAGE_TWO_AUTORUN_EXTRA).append(" true")
        if (rerootAtBoot != null) {
            append(" --ez ").append(STAGE_TWO_REROOT_EXTRA).append(' ').append(rerootAtBoot)
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
