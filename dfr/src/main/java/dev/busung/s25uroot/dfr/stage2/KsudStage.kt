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
 * Two sources, in the order that prefers the one this project chose:
 *
 * 1. The daemon the app already staged for its own run (`ksud-s25u-kdp`, the name the payload's loader
 *    itself reads). When the app has run once, this is the daemon matching the payload pair on this
 *    phone, which is more specific than anything that can be looked up here.
 * 2. The installed manager's own `libksud.so`, which is how a manager's own daemon reaches the kernel.
 *    Which manager is installed is not knowable from this APK - it is chosen in the app, and there are
 *    several - so the obvious ones are tried and the log says which answered.
 *
 * Nothing is invented and nothing is patched: the bytes are copied verbatim, because a daemon is
 * version-locked to the kernel module that will load it.
 */
internal object KsudStage {

    /** Outside the app sandbox and outside `/data/local/tmp`, both deliberately. */
    const val DEST = "/data/system/rmgnext-ksud"

    /** The daemon the app stages for its own runs; a payload-owned name, so it is read, not written. */
    private const val STAGED_BY_THE_APP = "/data/local/tmp/ksud-s25u-kdp"

    /** Manager packages whose `libksud.so` is a candidate, most likely first. */
    private val MANAGER_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "com.rifsxd.reukisu",
    )

    private const val LIBRARY = "libksud.so"

    /** 0700: readable and executable by the system, and by nothing else. */
    private const val MODE = 448

    /** Stages the daemon and returns what happened, as lines for the log. */
    fun stage(context: Context): String {
        val log = StringBuilder()
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
        val staged = File(STAGED_BY_THE_APP)
        if (staged.isFile) {
            runCatching { staged.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                return staged.path to it
            }
            log.appendLine("[!] ${staged.path} is present but unreadable")
        } else {
            log.appendLine("[*] no daemon staged by the app yet (${staged.path} absent)")
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
}
