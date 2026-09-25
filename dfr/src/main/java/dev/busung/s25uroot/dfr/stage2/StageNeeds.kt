package dev.busung.s25uroot.dfr.stage2

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Every file this boot's run stands on, and what is on the phone right now.
 *
 * The run is one press and a spent boot, and it is spent early: the exploit's first act arms a marker in
 * the kernel that only a reboot clears, so a run that was going to fail at its last step has still cost
 * this boot. That is the whole reason this list exists. Measured on the device this was written from, a
 * phone whose `/data/local/tmp` had been cleared ran for twenty minutes and ended with no root and nothing
 * loaded, because the daemon the exploit execs was not there to begin with - a fact three `stat` calls
 * would have answered before the first attempt.
 *
 * So a run the helper cannot hand over does not start, and says which file and why. The list is grouped
 * by who is supposed to have put the file there, because that is what the fix is: **staged** files are
 * this project's own (the app writes them while it has root, the helper stages from them), **this APK's**
 * file comes out of the helper's own install, and the **phone's own** files are the ones the exploit
 * patches and execs. A missing staged file is "open the app and run the System UID process again"; a
 * missing phone file is a phone this exploit was not written for, and no run can change that.
 *
 * ## What gates a run, and what is only a reading
 *
 * The first version of this refused a run over *any* path it could not see, and on the device it was written
 * from that was wrong twice over. `File.isFile` answered false for two vendor paths that are plainly there -
 * and `lstat` on them answers `ENOENT` from this process while the same process's own
 * `/proc/<pid>/root/vendor/...` lists them, so it is not even a permission this reading can name. More to
 * the point, those two files are not this process's to check: the exploit patches and execs them from init's
 * context and from `modprobe`'s, not from `system_server`, so what this screen can see of them says nothing
 * about whether a run will work. A gate built on that refuses a phone whose exploit is fine, and the run it
 * refuses is the only run that boot would get.
 *
 * So [Need.required] is the line. The three files this project stages, or that this APK carries, are the run's
 * own hand-off and a run is refused without them - which is the failure this whole list was written from, a
 * cleared temp directory and twenty minutes of exploit for nothing. The phone's own files are read and shown
 * and never gate: they are the readings that explain a payload failure when one happens, and the payload says
 * so itself, in its own log, which is the only place that answer is authoritative anyway.
 *
 * ## What is *not* here
 *
 * The one requirement that is not a file is `com.android.networkstack.process` being up, which the hop
 * needs. It is left out deliberately: the hop happens before anything is armed, costs this boot nothing
 * when it fails, and reports the process by name when it cannot find it. Everything on this list is a file
 * whose absence would only be discovered *after* the boot was spent.
 *
 * The list is also not the app's staging command. The app writes these paths; this is the reading half,
 * and where the two can drift - the daemon's own late-load path, above all - `StageTwoNeedsTest` reads both
 * sources and holds them together.
 */
internal object StageNeeds {

    /** Who is supposed to have put a file there, which is what a person does about a missing one. */
    internal enum class Group(val label: String) {
        /** This project's own: the app stages them, the helper reads or execs them. */
        Staged("staged by the app"),

        /** Inside this APK, extracted at install. */
        Helper("shipped in this APK"),

        /** The phone's, which the exploit patches or execs. */
        Phone("the phone's own"),
    }

    /** One path, and the sentence that says what stops working without it. */
    internal class Need(val path: String, val why: String, val group: Group) {

        /**
         * Whether a run may start without it.
         *
         * The group is the answer, and it is the answer rather than a second field because the two are the
         * same fact: what this project stages and what this APK carries are the run's own hand-off, and the
         * phone's own files are the environment it runs in - see the object's note on why a refusal over the
         * second kind is a refusal to root a phone that would have rooted.
         */
        val required: Boolean get() = group != Group.Phone
    }

    /** What a `stat` of a path answered, which is three answers rather than two. */
    internal enum class Presence {
        /** The kernel says there is something at that path. */
        Present,

        /** The kernel says there is nothing there. The only state that stops a run. */
        Absent,

        /**
         * The path may be there and this process is not allowed to find out.
         *
         * `EACCES` or `EPERM` from `lstat`, which on the device this was written from is what two `/vendor`
         * paths answer to `system_server` while being perfectly present. Reported, never a refusal.
         */
        Unreadable,
    }

    /** One [Need] and what was found at it. */
    internal class Checked(
        val need: Need,
        val presence: Presence,
        val size: Long,
        /** The kernel's own word for a path that is not there or not readable, or null when it is there. */
        val because: String?,
    )

    /** Everything the list found, ready to be said in one block. */
    internal class Reading(val items: List<Checked>) {

        /** The paths the kernel says are not there, which are the ones a run cannot be started over. */
        val absent: List<Checked> = items.filter { it.presence == Presence.Absent }

        /** The paths this process is not allowed to look at, which are reported and do not gate. */
        val unreadable: List<Checked> = items.filter { it.presence == Presence.Unreadable }

        /** Everything the phone's own files that this process could not see, in either state. */
        fun notSeenReadings(): List<Checked> = items.filter { !it.need.required && it.presence != Presence.Present }

        /**
         * Whether a run may start: the required files, and only those.
         *
         * Named separately from [absent] because the screen says both - "all three needed present, two of the
         * phone's not visible here" is a phrase a person can act on, and "five problems" is not.
         */
        val blocking: List<Checked> = items.filter { it.need.required && it.presence != Presence.Present }

        val ready: Boolean = blocking.isEmpty()

        /** The log's form: one line per file, which is the list itself rather than a summary of it. */
        fun report(): String = buildString {
            append("[*] What this boot's run needs (${items.size} files, this is the whole list):\n")
            items.forEach { checked ->
                append(
                    when (checked.presence) {
                        Presence.Present -> "    [ok] "
                        Presence.Absent -> "    [--] "
                        Presence.Unreadable -> "    [?]  "
                    },
                )
                append(checked.need.path)
                when (checked.presence) {
                    Presence.Present -> append("  ").append("%,d".format(checked.size)).append(" bytes")
                    Presence.Absent -> append("  NOT THERE").append(checked.because?.let { " ($it)" }.orEmpty())
                    Presence.Unreadable -> append("  not readable from here")
                        .append(checked.because?.let { " ($it)" }.orEmpty())
                }
                append("  (").append(checked.need.group.label)
                append(if (checked.need.required) ")" else ", read only)").append('\n')
            }
            blocking.forEach { append("    without it: ").append(it.need.why).append('\n') }
            if (ready) {
                append("[+] every file this run hands over is present, so it can start")
                if (notSeenReadings().isNotEmpty()) {
                    append(" (").append(notSeenReadings().size)
                    append(" of the phone's own files were not visible from here, which is this screen's ")
                    append("problem and not a run's)")
                }
                append('\n')
            } else {
                append("[x] ").append(blocking.size).append(" of the ")
                append(items.count { it.need.required })
                append(" files this run needs are not there, so it is refused before it spends this boot\n")
            }
        }
    }

    /**
     * The copy the daemon's own `late-load` renames onto `/data/adb/ksud`.
     *
     * Written by the app with the daemon, beside it, and **consumed** by every load: the daemon renames it
     * as its first act and a missing file fails the whole command with "Failed to stage ksud", after the
     * module has already been written into the kernel. So it has to exist for each run rather than once,
     * which is what makes it the file most likely to be absent on a boot that follows a successful one.
     * Held to `DfrInstall.DAEMON_STAGE_PATH` by `StageTwoNeedsTest`.
     */
    internal const val LATE_LOAD_SOURCE = "/data/local/tmp/.ksud-stage"

    /**
     * The exploit itself, by the name `System.loadLibrary("exp")` resolves.
     *
     * `StageReceiver` loads it inside network_stack, which is the only process in this chain that may map
     * executable memory out of this APK's storage - and `useLegacyPackaging` extracts the libraries at
     * install, so the file is on disk at this path rather than inside the APK. The one requirement that
     * lives on the helper's own side of the fence.
     */
    internal const val EXPLOIT_LIBRARY = "libexp.so"

    /** The executable the exploit patches and then runs; its very first step. */
    private const val CRASH_DUMP = "/apex/com.android.runtime/bin/crash_dump64"

    /** Patched so the dynamic loader runs the staged payload where the module was asked for. */
    private const val LIBC = "/system/lib64/libc.so"

    /** Patched so init's own exec of modprobe runs the shellcode. */
    private const val LIBCXX = "/system/lib64/libc++.so"

    /**
     * The process both stages of the shellcode run in.
     *
     * Stage 1 and stage 2 both compare their own `argv[0]` against this path before doing anything, which
     * is why it is not interchangeable with any other executable: a phone without it is a phone whose
     * `modprobe` moved, and the shellcode would simply not match.
     */
    private const val MODPROBE = "/vendor/bin/modprobe"

    /** Where the module comes from: the vendor library the write-through happens on. */
    private const val MODULE_SLOT = "/vendor/lib64/libstagefrighthw.so"

    /**
     * What the daemon is bind-mounted over before it is exec'd.
     *
     * The same path the shellcode execs, with the daemon mounted on top of it - so the process is a
     * `logcat` to everything that looks at it. Required as a file for exactly that reason: a device whose
     * `logcat` moved has no path to bind onto.
     */
    private const val LOGCAT = "/system/bin/logcat"

    /** The list, in the order a person reads it: ours, then this APK's, then the phone's. */
    internal fun needs(nativeLibraryDir: String): List<Need> = listOf(
        Need(
            KsudStage.DEST,
            "the daemon the exploit bind-mounts and execs as `late-load`. This helper stages it from " +
                "KsudStage.STAGED_BY_THE_APP, which the app writes from the payload it verified for this " +
                "device while it has root",
            Group.Staged,
        ),
        Need(
            LATE_LOAD_SOURCE,
            "what that daemon's own late-load renames onto /data/adb/ksud before it loads anything. A missing " +
                "copy fails the command with \"Failed to stage ksud\" - after the module is already in the " +
                "kernel - and every load consumes it, so the app writes it again after each one",
            Group.Staged,
        ),
        Need(
            "$nativeLibraryDir/$EXPLOIT_LIBRARY",
            "the exploit, loaded into network_stack. The APK extracts its libraries at install, so a copy " +
                "missing here is a helper whose install was not the one this screen is running from",
            Group.Helper,
        ),
        Need(
            CRASH_DUMP,
            "patched first and exec'd next: the exploit's earliest step, and the file its page-cache write " +
                "is proved on. Nothing after it can happen without it",
            Group.Phone,
        ),
        Need(
            LIBC,
            "patched so the loader runs the staged payload where the module was asked for",
            Group.Phone,
        ),
        Need(
            LIBCXX,
            "patched so init's own exec is the one that runs the shellcode",
            Group.Phone,
        ),
        Need(
            MODPROBE,
            "the process both stages of the shellcode run in: each compares its own argv[0] against this " +
                "path before it does anything, so a phone whose modprobe moved does not match at all",
            Group.Phone,
        ),
        Need(
            MODULE_SLOT,
            "where the module is written through - the vendor file this exploit path patches a module into",
            Group.Phone,
        ),
        Need(
            LOGCAT,
            "the path the daemon is bind-mounted over and exec'd as, so the process reads as a logcat",
            Group.Phone,
        ),
    )

    /**
     * Reads every path once, with its size for the log.
     *
     * `lstat` and not `isFile`, for two reasons that both cost a run when they are got wrong. It does not
     * follow symlinks, which is what `/system/lib64/libc.so` needs - a link into the runtime APEX, and one
     * treated as absent would refuse a run on every phone that boots. And it *throws* rather than answering
     * false, so a refusal to look (`EACCES`, the `system_server` case the object's own note describes) is a
     * different answer from an empty path. Only the second one stops a run.
     */
    fun check(context: Context): Reading {
        val directory = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull().orEmpty()
        return Reading(needs(directory).map { need -> stat(need) })
    }

    /** What the kernel says about one path, in the three states a run has an opinion about. */
    private fun stat(need: Need): Checked {
        val stat = try {
            Os.lstat(need.path)
        } catch (error: ErrnoException) {
            val name = OsConstants.errnoName(error.errno) ?: "errno ${error.errno}"
            val presence = when (error.errno) {
                // Nothing there, in the two spellings a path can fail to exist in: a directory of its path
                // is missing, or the name at the end of it is.
                OsConstants.ENOENT, OsConstants.ENOTDIR -> Presence.Absent
                else -> Presence.Unreadable
            }
            return Checked(need, presence, 0L, name)
        } catch (error: Throwable) {
            // Not a `stat` failure at all - a path that cannot even be expressed, on a build where the call
            // itself is denied. Reported as unreadable, because nothing here knows that it is gone.
            return Checked(need, Presence.Unreadable, 0L, error.javaClass.simpleName)
        }
        return Checked(need, Presence.Present, stat.st_size, null)
    }
}
