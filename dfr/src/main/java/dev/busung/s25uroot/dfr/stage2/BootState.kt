package dev.busung.s25uroot.dfr.stage2

import java.io.File

/**
 * The two states this boot cannot be rooted from again, read from inside `system_server`.
 *
 * A run here is not a button that can be pressed twice. The exploit's first act is to arm itself - a file
 * it creates as a mutex, before any of its work - and everything the exploit does to this boot's kernel
 * outlives the process that did it: the module stays loaded and the pages it rewrote stay rewritten. So a
 * second run is either a no-op or a second late-load into a kernel that already has the module, which is
 * the shape of the failure that ends in a panic rather than in a log line.
 *
 * The screen's own rule follows from that: these readings decide whether the Run button is live, so the
 * refusal is a greyed button instead of a log line for a press that could never have worked.
 *
 * ## Why the first reading is the exploit's own file
 *
 * `/dev/df` is created by the shellcode's stage 1 as an `O_CREAT|O_EXCL` mutex, so it is true by
 * construction in exactly the direction that matters: present means stage 1 has run, absent means it has
 * not. The kernel clears `/dev` on a hard reboot and not on a soft one, so it survives the restart that
 * applies a load - the state this screen exists for.
 *
 * It is mode 0000 root, which is deliberate and does not stop the reading: `stat` needs search permission
 * on `/dev` and nothing on the file itself, so this process can see that it is there without being able to
 * open it. Both readings are best effort, and a reading that fails answers "no" - the direction that keeps
 * the button live and lets the payload's own mutex be the thing that refuses, because a screen that greys
 * out a working button is worse than one that leaves the last word to the exploit.
 */
internal object BootState {

    /** The exploit's own mutex, and the app's `DfrInstall.ARMED_MARKER` spelled the same way. */
    const val ARMED_MARKER = "/dev/df"

    /** The kernel's list of loaded modules, which is how this project reads "KernelSU is up". */
    private const val MODULES = "/proc/modules"

    private const val MODULE = "kernelsu"

    /** Whether the exploit has already armed itself in this boot's kernel. */
    fun armed(): Boolean = exists(ARMED_MARKER)

    /**
     * Whether KernelSU is loaded in this boot's kernel.
     *
     * Read from `/proc/modules` first because that is the same file - and the same word - the app's own
     * status reading uses, so the two screens cannot disagree about the phone. `/sys/module` is the second
     * candidate rather than the only one, because which of the two a late-loaded module appears in is a
     * property of the loader, and this build has been seen in one and not the other.
     */
    fun rootLive(): Boolean = listedInModules() || exists("/sys/module/$MODULE")

    /** Whether the module's name is in the kernel's own list, read line by line and not searched for text. */
    private fun listedInModules(): Boolean = runCatching {
        File(MODULES).readLines().any { line -> line.substringBefore(' ').trim() == MODULE }
    }.getOrDefault(false)

    private fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
}
