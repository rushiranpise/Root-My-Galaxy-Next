package dev.busung.s25uroot

/**
 * What a reboot-and-unroot emptied, and what it could not.
 *
 * The action is the only complete unroot this app can offer: KernelSU lives in the running kernel, so a
 * restart is what removes it, and *root on boot* is what would bring it back. Everything else that says
 * "this phone was rooted" is on disk - KernelSU's own directory, whose module store, superuser grants and
 * daemon all live in `/data/adb`, and the shared temp directory every root solution and every tool on the
 * phone writes into. So the restart is paired with an emptying of both, and this is the account of it.
 *
 * It exists as a parsed value rather than a sentence because the two halves are written in different
 * places: the shell that removes the entries prints them, and the app has to decide what to say about a
 * directory that came out short. Leftovers are *reported* and not treated as a failure - a reboot that
 * happened with one file left behind is still the unroot the user asked for, and the rule that a truthful
 * account beats a tidy one is worth more here than anywhere else in the app.
 */
internal data class WipeReport(
    val directories: List<WipeDirectory>,
    /** Entries that survived, by absolute path, in the order the shell met them. */
    val leftovers: List<String>,
) {
    /** Everything both directories held, removed. */
    val complete: Boolean get() = leftovers.isEmpty()

    fun forDirectory(path: String): WipeDirectory? = directories.firstOrNull { it.path == path }
}

/** One directory the wipe emptied, and how much of it went. */
internal data class WipeDirectory(val path: String, val removed: Int, val total: Int) {
    val complete: Boolean get() = removed == total
}

/**
 * The directories a reboot-and-unroot empties, contents only.
 *
 * The directories themselves stay: `/data/adb` is created by KernelSU and `/data/local/tmp` by init, both
 * with a mode the platform relies on, and recreating either by hand is how a phone ends up with a temp
 * directory no app can write to. Deleting the contents is the whole of the request.
 */
internal val WIPE_DIRECTORIES: List<String> = listOf("/data/adb", "/data/local/tmp")

/**
 * One directory's line, as the shell prints it and this app reads it.
 *
 * Written here rather than in both places because the two have to agree: a shell that printed a different
 * shape would be a wipe the app cannot read, which would then report as no report at all - and a silent
 * unroot is the one outcome this must never have. The shell's `printf` is asserted against this shape by
 * `RebootUnrootWipeTest`.
 */
internal fun wipeDirectoryLine(directory: String, removed: Int, total: Int): String =
    "wipe $directory removed=$removed total=$total"

/** One surviving entry's line. */
internal fun wipeLeftoverLine(path: String): String = "left $path"

/**
 * Reads the child's account back.
 *
 * Null when nothing about a wipe was reported at all, which is a different thing from a wipe that
 * removed nothing: the caller distinguishes them so that an action that never ran cannot read as one that
 * ran and found the directories already empty.
 */
internal fun parseWipeReport(detail: String): WipeReport? {
    val lines = detail.lines().map(String::trim).filter(String::isNotEmpty)

    val directories = lines.mapNotNull { line ->
        val match = WIPE_LINE.matchEntire(line) ?: return@mapNotNull null
        WipeDirectory(
            path = match.groupValues[1],
            removed = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
            total = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null,
        )
    }
    val leftovers = lines
        .filter { it.startsWith(LEFTOVER_PREFIX) }
        .map { it.removePrefix(LEFTOVER_PREFIX).trim() }
        .filter(String::isNotEmpty)

    if (directories.isEmpty() && leftovers.isEmpty()) return null
    return WipeReport(directories = directories, leftovers = leftovers)
}

private val WIPE_LINE = Regex("""wipe (\S+) removed=(\d+) total=(\d+)""")

private const val LEFTOVER_PREFIX = "left "
