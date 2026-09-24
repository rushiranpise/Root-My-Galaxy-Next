package dev.busung.s25uroot.dfr.stage2

import android.content.Context
import java.io.File

/**
 * The daemon this stage leaves behind, in the one directory no app can read.
 *
 * Root from the exploit is a root shell inside network_stack, which is not something anything else can
 * use: nothing can be granted access to a process that exists for one run. So the daemon is staged
 * where the system can reach it and no app can - `/data/system`, whose mode is what keeps it out of
 * reach - and that staged copy is what a manager afterwards drives.
 *
 * ## The daemon is never this APK's own, and that is the point
 *
 * DFReroot bundles a `ksud` in its installer's assets and stages *that*. It cannot be right here: this
 * project installs one of three KernelSUs - KernelSU, KernelSU-Next or ReSukiSU - the user chooses
 * which, and each has its own daemon built from its own kernel module. A bundled daemon is version-
 * locked to whatever the bundling commit had, so on two of the three flavours it is the wrong binary,
 * and a daemon that does not match the module in the kernel fails in exactly the way that looks like
 * the exploit having failed. So this APK carries none.
 *
 * What it does instead is take the copy this project put there: `/data/system/rmgnext-ksud.src`, which the
 * app writes from the payload it verified for this device, through whichever shell the phone has. That file
 * is the source, and what it holds is what gets staged - nothing is invented, nothing is patched, and
 * nothing is downloaded, because a daemon is version-locked to the kernel module that will load it and a
 * missing one is a fact about the phone that the run's log has to carry.
 *
 * It is read out of `/data/system` and not out of the temp directory the app also stages in, and the reason
 * is a measurement rather than a preference: this code runs *inside* `system_server`, and that context may
 * not read `shell_data_file` - the type on everything under `/data/local/tmp`, which is the shell user's
 * directory and not a type this app can relabel. So a helper pointed at that copy `stat`s a file and is
 * denied the `open`, and says "present but unreadable" about a phone whose daemon is already staged
 * correctly. `/data/system` is under `system_data_file`, which this process already writes, and is where
 * the app leaves the second copy for exactly this reader.
 *
 * `/data/adb/ksud` is deliberately **not** a source any more, and it used to be the first one. It looks like
 * the best source there is - outside every shared directory, mode 0700 root - and it is the worst: it holds
 * whichever KernelSU this *phone* has installed, which is not necessarily the one this project's payload
 * ships. Measured on the device this rule was written from: the copy there was a vanilla KernelSU-Next 3.4.0
 * daemon of 5,518,544 bytes, the payload's own daemon was 6,407,096, and both answered a `3.4.0`-family
 * version string - so a comparison by version called them the same daemon, the exploit exec'd the installed
 * one, and the phone's own dropbox recorded `SYSTEM_LAST_KMSG_*_KP` - a kernel panic - six times in one
 * morning, every one of them at `ksud::late_load: Loading kernelsu.ko for KMI android15-6.6`.
 *
 * Two consequences, both deliberate:
 *
 * 1. A daemon already at [DEST] is kept when the app's copy is readable and byte-for-byte equal - and it is
 *    *also* kept when the app's copy cannot be read at all, because [DEST] is this project's own name,
 *    nothing else on the phone writes it, and the app rewrites it from the verified payload on every run.
 *    That second branch is what a phone that has not run its app since it booted reaches, and it is said as
 *    an unverified keep rather than as a match. Anything else *is* replaced, because whatever put a
 *    different file there was an earlier build, another flavour or another project - and "it is already
 *    staged" is exactly how a wrong daemon survived every run.
 * 2. The one case that refuses is neither a readable source nor a daemon already in place. That costs a run
 *    on a phone that has to open the app first; staging the installed daemon costs the kernel, and a phone
 *    that has panicked cannot run the flow either.
 *
 * ## A manager's own copy is refused, and it used to be taken
 *
 * An installed manager bundles a `libksud.so`, and reading *that* was a fourth source here until it was
 * measured doing harm. It looks like a good source and is the worst one: it is a daemon for whichever
 * KernelSU that manager belongs to, which is not necessarily the one in the kernel. On the device this
 * was found on, a KernelSU-Next 3.4.0 kernel had a daemon staged from `me.weishu.kernelsu`'s bundle -
 * 4,892,712 bytes of the wrong line, whose UAPI is not the module's - and nothing said so, because
 * every step up to the exec had succeeded.
 *
 * The one source above is a file *this project* put there, so its absence means the app has not staged a
 * daemon on this boot - which is a fact worth saying out loud rather than papering over with another
 * project's binary. So the managers are still named, as the context for what was refused, and the run stops
 * here instead.
 */
internal object KsudStage {

    /** Outside the app sandbox and outside `/data/local/tmp`, both deliberately. */
    const val DEST = "/data/system/rmgnext-ksud"

    /**
     * The path the phone's own installed KernelSU keeps its daemon at - and the copy this refuses.
     *
     * A successful run of this project's app does rename its daemon onto this path, so it is not foreign
     * by definition - but which build is there cannot be told from this process, and a wrong answer is a
     * kernel panic. So it is named in the refusal instead of being staged.
     */
    private const val LEFT_BY_THE_PAYLOAD = "/data/adb/ksud"

    /**
     * The daemon the app stages for the helper to read, and the only source here.
     *
     * A fork-owned name under `/data/system`: the app writes it from the payload it verified for this device
     * and beside the copy the exploit execs, so what is at this path is this project's daemon or nothing. It
     * is read, never written by this APK. The temp directory holds the same bytes under the payload's own
     * name, and that copy is *not* read here - this process' context is denied `shell_data_file`, so the file
     * is visible and cannot be opened. See the object's KDoc.
     */
    private const val STAGED_BY_THE_APP = "/data/system/rmgnext-ksud.src"

    /**
     * The three flavours, as the app offers them: the id it names one by, what a person calls it, and the
     * manager package that flavour publishes.
     *
     * Two readers now, and they were one before. The refusal below names the copies this will not take - a
     * daemon out of a manager is a daemon for whichever KernelSU that manager belongs to - and the screen
     * uses the same table the other way round: told which flavour this run loads, it finds that flavour's
     * manager by package so it can say whether it is installed and open it.
     *
     * Hard-coded because this module is a second APK that shares no code with the app, and held to the
     * app's own table - all three fields, not only the package - by `StageTwoIdentityTest`, which reads
     * both sources. A project renaming its manager package would otherwise leave this naming an app that no
     * longer exists, and a manager that is installed but invisible is exactly the state this list exists to
     * end.
     */
    val MANAGER_FLAVORS: List<ManagerFlavor> = listOf(
        ManagerFlavor(id = "kernelsu", label = "KernelSU", packageName = "me.weishu.kernelsu"),
        ManagerFlavor(id = "kernelsu-next", label = "KernelSU-Next", packageName = "com.rifsxd.ksunext"),
        ManagerFlavor(id = "resukisu", label = "ReSukiSU", packageName = "com.resukisu.resukisu"),
    )

    /** 0700: readable and executable by the system, and by nothing else. */
    private const val MODE = 448

    /** Stages the daemon and returns what happened, as lines for the log. */
    fun stage(context: Context): String {
        val log = StringBuilder()
        val ours = File(STAGED_BY_THE_APP)
        val mine = runCatching { ours.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        val existing = File(DEST)
        if (mine == null) {
            // The app's copy could not be read - the state of a phone that has not run its app since it
            // booted, and the state a helper pointed at the temp copy is in whatever the phone did. A
            // daemon already at [DEST] is still kept: it is this project's own name and nothing else on
            // the phone writes it, and the app rewrites it from the verified payload on every run. Said as
            // the unverified keep it is, because nothing here compared the two files.
            if (existing.isFile && existing.length() > 0) {
                log.appendLine(
                    "[*] daemon already staged at $DEST (${existing.length()} bytes, left as it is): " +
                        "the app's copy at $STAGED_BY_THE_APP was not readable to compare it against",
                )
                return log.toString()
            }
            // Neither a copy of the app's to stage from nor a daemon already in place. With no root there
            // is nothing else correct to take, so the run stops here rather than exec'ing the daemon the
            // phone has installed - which is the copy that has to be refused rather than taken.
            log.appendLine(refusal(ours, context))
            return log.toString()
        }

        // Already there *and the same file*: keep it, so a run that follows another does not rewrite six
        // megabytes to write what is already in place. Anything else is replaced - see the KDoc above.
        if (sameBytes(existing, mine)) {
            log.appendLine(
                "[*] daemon already staged and it is this device's own: $DEST " +
                    "(${existing.length()} bytes, left as it is)",
            )
            return log.toString()
        }

        log.appendLine("[*] daemon source: $STAGED_BY_THE_APP (${mine.size} bytes)")
        return try {
            File(DEST).writeBytes(mine)
            android.system.Os.chmod(DEST, MODE)
            log.appendLine("[+] staged $DEST")
            log.toString()
        } catch (error: Throwable) {
            // Worth reporting plainly: this fails when the app was installed as an ordinary app, which
            // is exactly the state a stage two that was never re-keyed is in.
            log.appendLine("[!] $DEST not writable: ${error.javaClass.simpleName}: ${error.message}")
            log.toString()
        }
    }

    /** Whether [file] is byte-for-byte [bytes], asked of the length first so the read is only paid for once. */
    private fun sameBytes(file: File, bytes: ByteArray): Boolean =
        file.isFile && file.length() == bytes.size.toLong() &&
            runCatching { file.readBytes() }.getOrNull()?.contentEquals(bytes) == true

    /**
     * What this says when it has no daemon of its own to stage.
     *
     * It names the copy it will not take rather than taking it, because that copy is the whole failure: a
     * daemon out of the phone's own installed KernelSU loads *that* project's kernel module, and a module
     * belongs to one kernel build. On the device this rule was written from, that is a kernel panic and a
     * reboot - so a run stopped here is a run that did not happen, which is the cheaper of the two.
     */
    private fun refusal(ours: File, context: Context): String = buildString {
        appendLine(
            if (ours.isFile) {
                "[!] $STAGED_BY_THE_APP is present but unreadable, so nothing can be staged from it"
            } else {
                "[x] nothing staged by the app to stage: no daemon at $STAGED_BY_THE_APP"
            },
        )
        appendLine("    nothing at $DEST either, so there is no earlier staging to keep.")
        appendLine(
            "    $LEFT_BY_THE_PAYLOAD is not taken: that path holds whichever KernelSU this phone " +
                "has installed, and the daemon this run execs has to be this device's own payload's.",
        )
        val installed = installedFlavors(context).map { it.packageName }
        if (installed.isNotEmpty()) {
            // Named as context rather than as a source: each of these bundles a daemon for its own
            // KernelSU, and which one is in the kernel cannot be known from this process.
            appendLine("    installed managers: ${installed.joinToString()}")
        }
        append("    open the app and run the system uid flow again: it stages the daemon this device resolved.")
    }

    /** Whether a daemon is already at [DEST]. */
    fun staged(): Boolean = File(DEST).isFile

    /**
     * Which of the three managers this phone has, for the screen that reports it.
     *
     * The same table the refusal above names, read for a different reason: a run whose last step fails is
     * most often a daemon built for a different KernelSU than the module in the kernel, and the manager
     * that is installed is the one thing on the device that says which flavour this phone is meant to run.
     *
     * All three rather than the one the app named, because "which manager is installed" and "which manager
     * this run loads" are different questions and the screen asks both: the app's own flavour can be absent
     * from a phone that has another, and that is the state a wrong-daemon failure looks like.
     */
    fun installedFlavors(context: Context): List<ManagerFlavor> =
        MANAGER_FLAVORS.filter { flavor -> isInstalled(context, flavor) }

    /** Whether one flavour's manager is on the phone. */
    fun isInstalled(context: Context, flavor: ManagerFlavor): Boolean = runCatching {
        context.packageManager.getApplicationInfo(flavor.packageName, 0)
    }.isSuccess

    /**
     * The flavour an id from the app names, or null when it is not one this APK knows.
     *
     * Null rather than a fallback, and the two failures it covers are deliberately the same answer: an id
     * from a newer app than this helper, and an id mangled on the way through `am`. Either way the screen
     * has been told something it cannot act on, and the honest reading is the one it falls back to without
     * the extra at all - the managers that are installed, with no claim about which one this run loads.
     */
    fun flavorOf(id: String?): ManagerFlavor? {
        val wanted = id?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        return MANAGER_FLAVORS.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
    }
}

/**
 * One KernelSU flavour's manager: the id the app names it by, the name people use, and its package.
 *
 * The id is the app's feed id (`kernelsu-next`) rather than a package name, because that is what the app
 * can send without this APK knowing anything about how a manager is distributed - and it is what the
 * screen holds in its hand when it has been told which flavour this run loads.
 */
internal class ManagerFlavor(
    val id: String,
    val label: String,
    val packageName: String,
)
