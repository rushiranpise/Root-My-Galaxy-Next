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
 * 4. The installed manager's own `libksud.so`, which is how a manager's daemon reaches the kernel. Read
 *    through the manager's package rather than from a path, because the daemon is unpacked into the
 *    app's native library directory and its name there is decided by the installer.
 *
 * Nothing is invented and nothing is patched: the bytes are copied verbatim, because a daemon is
 * version-locked to the kernel module that will load it. And nothing is downloaded: a source that is
 * absent is reported, because a missing daemon is a fact about the phone that the run's log has to
 * carry - the exploit's last step would otherwise fail with nothing to exec and nothing to say why.
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
     * The three flavours' manager packages, in the order the app offers them.
     *
     * Hard-coded here rather than imported, because this module is a second APK that shares no code with
     * the app - and held to the app's own table by `StageTwoDaemonSourceTest`, which reads both sources.
     * A project renaming its manager package would otherwise leave this list naming an app that no
     * longer exists, and the failure would be a manager that is installed and invisible.
     */
    private val MANAGER_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.resukisu.resukisu",
    )

    private const val LIBRARY = "libksud.so"

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

        for (packageName in MANAGER_PACKAGES) {
            val bytes = runCatching {
                val app = context.packageManager.getApplicationInfo(packageName, 0)
                File(app.nativeLibraryDir, LIBRARY).readBytes()
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return "$packageName/$LIBRARY" to bytes
        }
        log.appendLine("[!] no manager found among ${MANAGER_PACKAGES.joinToString()}")
        return null
    }

    /** Whether a daemon is already at [DEST]. */
    fun staged(): Boolean = File(DEST).isFile

    /** The manager packages this reads from, for the test that holds them to the app's table. */
    val managerPackages: List<String> get() = MANAGER_PACKAGES
}
