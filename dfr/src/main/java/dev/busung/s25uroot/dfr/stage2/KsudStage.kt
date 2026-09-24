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
 * What it does instead is take the daemon the phone already has, from whichever of these comes first:
 *
 * 1. `/data/system/rmgnext-ksud` itself, when the app has already put it there. Whoever staged it knew
 *    which flavour this boot runs, so it is left exactly as it is - staging over it would be this
 *    object replacing a known-good binary with one it guessed.
 * 2. `/data/adb/ksud`, where the payload leaves the daemon when the app's own run succeeds. This is the
 *    flavour-correct one by construction - it came out of the pair the app resolved for this device -
 *    and it is the best source here, because it lives outside the shared temp directory and therefore
 *    survives both a reboot and this app's own staging sweep.
 * 3. `/data/local/tmp/ksud-s25u-kdp`, the copy the app stages for its own runs. Also flavour-correct,
 *    but usually gone: the sweep that empties that directory after every run removes it.
 *
 * Nothing is invented and nothing is patched: the bytes are copied verbatim, because a daemon is
 * version-locked to the kernel module that will load it. And nothing is downloaded: a source that is
 * absent is reported, because a missing daemon is a fact about the phone that the run's log has to
 * carry - the exploit's last step would otherwise fail with nothing to exec and nothing to say why.
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
 * The two flavour-correct sources above are both files *this project* put there, so a daemon from
 * neither of them means the app has not staged one on this boot - which is a fact worth saying out loud
 * rather than papering over with another project's binary. So the managers are still named, as the list
 * of copies this refused, and the run stops here instead.
 */
internal object KsudStage {

    /** Outside the app sandbox and outside `/data/local/tmp`, both deliberately. */
    const val DEST = "/data/system/rmgnext-ksud"

    /**
     * Where the payload leaves the daemon a successful run of this project's app installs.
     *
     * A payload-owned name, and the first one the app's own version probe asks for, so the two cannot
     * disagree about which daemon answers.
     */
    private const val LEFT_BY_THE_PAYLOAD = "/data/adb/ksud"

    /** The daemon the app stages for its own runs; a payload-owned name, so it is read, not written. */
    private const val STAGED_BY_THE_APP = "/data/local/tmp/ksud-s25u-kdp"

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

        // Already there: keep it. The copy in place was staged by whoever knew this boot's flavour.
        val existing = File(DEST)
        if (existing.isFile && existing.length() > 0) {
            log.appendLine("[*] daemon already staged: $DEST (${existing.length()} bytes, left as it is)")
            return log.toString()
        }

        val (source, bytes) = read(context, log) ?: return log.toString()
        log.appendLine("[*] daemon source: $source (${bytes.size} bytes)")
        return try {
            File(DEST).writeBytes(bytes)
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

    /** The first source that yields bytes, with the attempts logged. */
    private fun read(context: Context, log: StringBuilder): Pair<String, ByteArray>? {
        val payload = File(LEFT_BY_THE_PAYLOAD)
        if (payload.isFile) {
            runCatching { payload.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                return payload.path to it
            }
            log.appendLine("[!] ${payload.path} is present but unreadable")
        } else {
            log.appendLine("[*] no daemon at ${payload.path} (has this app's own run succeeded on this phone?)")
        }

        val staged = File(STAGED_BY_THE_APP)
        if (staged.isFile) {
            runCatching { staged.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                return staged.path to it
            }
            log.appendLine("[!] ${staged.path} is present but unreadable")
        } else {
            log.appendLine("[*] no daemon staged by the app (${staged.path} absent)")
        }

        // Named and refused rather than taken. Each of these bundles a daemon for its own KernelSU, and
        // which one that is cannot be known from here - the kernel is the only side that knows, and this
        // process cannot ask it. A wrong daemon is worse than none: the exploit would exec it and fail a
        // few steps later, in the shape of the exploit having failed.
        val installed = installedFlavors(context).map { it.packageName }
        log.appendLine(
            "[x] nothing flavour-correct to stage: no readable daemon at $LEFT_BY_THE_PAYLOAD or " +
                "$STAGED_BY_THE_APP" +
                if (installed.isEmpty()) {
                    "."
                } else {
                    ". ${installed.joinToString()} would each bundle one, and that copy is refused: it " +
                        "belongs to whichever KernelSU that manager is, not to the module in this kernel. " +
                        "The app stages the right one at $DEST before it hands over - run it again from " +
                        "the app."
                },
        )
        return null
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
