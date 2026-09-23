package dev.busung.s25uroot

import android.content.Context

/**
 * Deleting what the app left behind, when it is asked to.
 *
 * The app leaves files in places a shell can reach because it has to - a payload has to be executable by
 * a shell, and the system-uid flow has to work in `/data/system` because that is the directory no other
 * app can read. This is the code that takes them away again.
 *
 * ## Nothing here runs on its own
 *
 * A run used to end by sweeping what it staged, and every launch used to sweep again for the runs that
 * never got that far. Both are gone. Deleting is now a thing a person asks for, from the residue screen
 * that lists what is there - one row, or the whole list - and the reasons are worth stating because the
 * automatic version was not wrong about the facts:
 *
 * - The screen is where the decision belongs. What is safe to delete depends on what is on the device:
 *   `/data/local/tmp/ksud-s25u-kdp` is read by the payload's own helper during a run and by nothing
 *   afterwards, which is a fact about a *moment* that a sweep could measure and a person cannot see -
 *   and the sweep that ran after a failed run was the one that could take a file a retry was about to
 *   use. Listed, it is a name with its size and its age beside it, and the person who wants it gone
 *   says so.
 * - A sweep after every run was also a delete nobody asked for. It removed a five-megabyte daemon copy
 *   from a directory the user cannot easily inspect, and the only trace it left was a log line.
 * - The two files the system-uid flow leaves in `/data/system` were the last straw: a pre-inject copy of
 *   `packages.xml` is a *rescue*, and the automatic clean-up threw it away as part of a button press
 *   whose name said nothing about it.
 *
 * So this object is now only the machinery: build a command, run it through a shell, read back what
 * `rm` said. What to delete is decided one screen over, in [ResidueScopes] and the dialog that draws it.
 *
 * ## Why a shell is needed at all
 *
 * The app cannot delete these itself. `/data/local/tmp` is mode `0771` owned by `shell`, so the app's
 * uid may traverse it and may not write in it - the same permission that makes the reading in
 * [StagedResidue] a name-by-name stat. `/data/system` is root-only outright. Deletion therefore goes
 * through a shell this app already has: KernelSU's `su`, or the `shell`-uid server Shizuku provides,
 * whose uid owns the first directory. A device with no shell deletes nothing, which is reported as
 * [SweepOutcome.NoShell] rather than as a failure - the files stay, and the screen still lists them.
 *
 * ## What it can and cannot report
 *
 * One shell command that says what is there, deletes it, and says what is there afterwards. What it
 * cannot see it cannot report: the temp-su socket is denied to the `shell` domain altogether, so `rm`
 * fails on it and that failure is the only evidence there is. Both are carried, and the app's own
 * [StagedResidue] reading is left to say what is actually there now.
 */
internal sealed interface SweepOutcome {

    /**
     * Nothing was deleted: there is no shell on this device.
     *
     * Not a failure, and deliberately not reported as one. It is the state of every device before the
     * first run, and the correct answer is to keep the files rather than to start asking for a shell.
     */
    data object NoShell : SweepOutcome

    /**
     * Nothing was deleted because another process is on a run.
     *
     * Its own outcome rather than [NoShell]: the two read the same in a log and mean opposite things,
     * and a sweep that stood down has to be tellable from one that found no way to start.
     */
    data object SkippedRun : SweepOutcome

    /**
     * The sweep ran.
     *
     * [found] is what was there before it deleted anything, which is the only way to tell a cleanup
     * from a no-op: `rm -f` is silent about a file that was already gone.
     */
    data class Done(
        val found: List<String>,
        val left: List<String>,
        /** What `rm` said about the paths it would not remove, empty when it said nothing. */
        val complaint: String,
    ) : SweepOutcome {

        /**
         * Which of the sweep's four answers this is.
         *
         * Decided here rather than in the line below, so the choice can be tested without a device: the
         * order these are checked in is the part that matters, and it is the part a `Context` would
         * otherwise hide.
         */
        val verdict: SweepVerdict
            get() = when {
                // A refusal is reported before a leftover, and it is the stronger of the two: `rm`
                // saying it was not allowed to remove something is a fact about the device, where a name
                // missing from its own check is only what the shell could see.
                complaint.isNotEmpty() -> SweepVerdict.Refused
                left.isNotEmpty() -> SweepVerdict.LeftBehind
                found.isEmpty() -> SweepVerdict.NothingToDo
                else -> SweepVerdict.Removed
            }

        /** How many of what was there are gone, which is the difference and not what was attempted. */
        val removed: Int get() = found.size - left.size
    }

    /**
     * The line for the app log, for both of the things a delete can be.
     *
     * One sentence per outcome rather than two spellings of the same fact: a row's delete and the
     * whole-list delete are the same `rm` measured the same way, and what distinguishes them is only
     * whether what was named was one path or everything a scope holds.
     */
    fun clearLogLine(context: Context): String = when (this) {
        NoShell -> context.getString(R.string.residue_log_clear_none)
        SkippedRun -> context.getString(R.string.residue_log_clear_skipped)
        is Done -> context.getString(R.string.residue_log_clear, removed, left.size)
    }
}

/** What a sweep came to, in the four ways it can: the sentence for each is string assembly. */
internal enum class SweepVerdict { NothingToDo, Removed, LeftBehind, Refused }

internal object StagingSweep {

    /**
     * The command: say what is there, delete, say what `rm` said about it, say what is still there.
     *
     * One round trip rather than one per file, and self-checking rather than trusting `rm`'s exit
     * status - which is useless here rather than merely unreliable. The status of a script is the
     * status of its last command, and that is a `[ -e ]` test for a path this sweep has just deleted,
     * so a clean sweep would report failure every time. What `rm` has to say is therefore carried in
     * the output, under its own prefix, and the shell is told to end clean.
     *
     * `2>&1` on the delete is what puts a refusal where it can be read at all: a denied unlink writes
     * to stderr, which this transport does not carry.
     */
    internal fun command(paths: List<String>): String {
        val quoted = paths.map { shellQuote(it) }
        // The same question asked twice, under two different words, which is what makes one output
        // readable as both "what was there" and "what is there now".
        fun askedUnder(prefix: String) = quoted.joinToString("\n") { path ->
            "[ -e $path ] && printf '$prefix%s\\n' $path"
        }
        return askedUnder(FOUND_PREFIX) +
            "\nrm_out=\$(rm -f -- ${quoted.joinToString(" ")} 2>&1)\n" +
            "[ -n \"\$rm_out\" ] && printf '$SAID_PREFIX%s\\n' \"\$rm_out\"\n" +
            askedUnder(LEFT_PREFIX) +
            "\nexit 0"
    }

    /**
     * The paths a sweep's output names under [prefix].
     *
     * Everything else in the output is passed over, which is what lets `rm`'s own errors travel in the
     * same stream without being mistaken for paths. A path cannot mimic either prefix: these names are
     * absolute, so a line that starts with a prefix and then a path is one of these loops talking.
     */
    internal fun pathsIn(output: String, prefix: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * Removes these paths, one at a time from the caller's point of view and in one round trip here.
     *
     * The list is the caller's: a row's delete button names one path, the whole-list delete names every
     * present path in the scopes that allow it, and a delete of one file is not a different kind of
     * operation from a delete of twenty - it is the same `rm -f`, measured against the same "what was
     * there" lines.
     *
     * `-f` and not `-r`: every name here is a path this app writes, and a name in the catalogue is a
     * file. A leftover that is a directory is another app's or an old build's, which the screen offers
     * through the clear rather than through a row.
     */
    fun remove(paths: List<String>): SweepOutcome {
        if (paths.isEmpty()) return SweepOutcome.Done(emptyList(), emptyList(), "")
        val command = command(paths)
        // Root first, then Shizuku's own shell: whichever one put the files there can take them away,
        // and the order keeps the quiet route - a Shizuku server that already answers as root - first.
        val result = KernelSuRuntime.rootShell(command)
            ?: KernelSuRuntime.unprivilegedShell(command)
            ?: return SweepOutcome.NoShell
        return SweepOutcome.Done(
            found = pathsIn(result.output, FOUND_PREFIX),
            left = pathsIn(result.output, LEFT_PREFIX),
            // `rm`'s own words, and only when it had any: its silence is the clean case, which is why
            // the exit status is not consulted - see [command].
            complaint = pathsIn(result.output, SAID_PREFIX)
                .joinToString(", ")
                .take(COMPLAINT_LIMIT),
        )
    }

    /**
     * Removes these paths unless a run is in flight.
     *
     * The guard is the point of every delete in this app: a run is executed *out of* the temp directory,
     * so a delete that named the payload would be taking the file out from under the process using it.
     * [RunInFlight] is what both processes can see, and it is why the screen says a delete was skipped
     * rather than pretending it worked.
     */
    fun removeWhenQuiet(context: Context, paths: List<String>): SweepOutcome {
        if (RunInFlight.holder(context) != null) return SweepOutcome.SkippedRun
        return remove(paths)
    }

    /**
     * Empties the temp directory, which is more than a delete of the listed paths.
     *
     * Deleting by name is this app clearing up after itself: it names paths it wrote and leaves every
     * other name alone, because a name it does not write is not its to delete. This is the user asking
     * for the directory to be empty - the button somebody presses after a detector has told them what is
     * in there - so it removes what the listing found, the entries this app cannot account for included.
     *
     * That is a wider claim than `rm` of known names, so the screen that offers it names what is about
     * to go, and this refuses while a run is in flight: the payload is executed out of this directory,
     * which is the one thing here another process may be mid-way through using.
     */
    fun clearWhenQuiet(context: Context): SweepOutcome {
        if (RunInFlight.holder(context) != null) return SweepOutcome.SkippedRun
        return clear()
    }

    /** The clear itself, through the first shell that answers. Root first, since it can remove more. */
    fun clear(): SweepOutcome {
        val command = clearCommand()
        val result = KernelSuRuntime.rootShell(command)
            ?: KernelSuRuntime.unprivilegedShell(command)
            ?: return SweepOutcome.NoShell
        return SweepOutcome.Done(
            found = pathsIn(result.output, FOUND_PREFIX),
            left = pathsIn(result.output, LEFT_PREFIX),
            complaint = pathsIn(result.output, SAID_PREFIX)
                .joinToString(", ")
                .take(COMPLAINT_LIMIT),
        )
    }

    /**
     * The clear, as one command: say what is there, delete all of it with `rm -rf`, say what `rm` said,
     * say what is still there.
     *
     * The globs are the same two the listing uses, and for the same reason: a dot-name is exactly the
     * shape a staging marker takes. `-r` because a leftover can be a directory - `dalvik-cache` is one
     * on a real device - and `-f` so that an unmatched glob is silence rather than an error. The
     * directory itself is never removed, only its contents: it is a system directory with a mode this
     * app has no business rewriting.
     */
    internal fun clearCommand(directory: String = StagedResidue.DIRECTORY): String {
        val globs = "$directory/* $directory/.[!.]*"
        fun askedUnder(prefix: String) = "for e in $globs; do [ -e \"\$e\" ] || continue; " +
            "printf '$prefix%s\\n' \"\$e\"; done"
        return askedUnder(FOUND_PREFIX) +
            "\nrm_out=\$(rm -rf -- $globs 2>&1)\n" +
            "[ -n \"\$rm_out\" ] && printf '$SAID_PREFIX%s\\n' \"\$rm_out\"\n" +
            askedUnder(LEFT_PREFIX) +
            "\nexit 0"
    }

    /** What a path still there is reported under. Both are prefixes of a whole line, not of a name. */
    internal const val FOUND_PREFIX = "had "

    /** What a path that survived the delete is reported under. */
    internal const val LEFT_PREFIX = "left "

    /** What the delete's own output is reported under, which is the refusal if there was one. */
    internal const val SAID_PREFIX = "said "

    /** How much of a complaint is worth carrying; a refusal is a phrase, not a transcript. */
    private const val COMPLAINT_LIMIT = 200
}
