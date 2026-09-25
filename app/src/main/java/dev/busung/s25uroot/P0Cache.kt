package dev.busung.s25uroot

import android.content.Context
import java.io.File

/**
 * The p0 offset this boot has already won, kept where every later run on the same boot can find it.
 *
 * The p0 stage is a lottery. It is won by landing a write in a window that has to fall on a page the
 * kernel still holds, attempt after attempt, and the number it wins is the same for the whole boot - so
 * a run that is handed it skips the lottery outright. The payload prints the number on its
 * `slide-kaslr-ok` line, the app reads it back out of the log, and this is where it goes: a number has to
 * survive from one run to the next, and the only thing running in both is the app.
 *
 * ## Why it is keyed by the boot token, and why that is not enough
 *
 * The slide belongs to the boot that produced it. KASLR is drawn once per boot, so an offset carried
 * across a reboot is not a stale hint - it is a wrong answer, and the payload treats a supplied offset as
 * final and returns before it prepares anything else. So the token is stored beside the number and
 * compared before the number is used, which is what makes a leftover from an earlier boot harmless
 * rather than dangerous.
 *
 * Harmless, and still worth being able to see. A number no run will use is one a person may want gone,
 * and until this object was read by the settings screen there was no way to remove it: the file was
 * written by a run, read by a run, and named nowhere a user could reach. [entry] is that reading -
 * including the case where the token does not match, because a cache that reads as empty while holding a
 * number from last week is exactly the kind of answer this app does not give.
 */
internal object P0Cache {

    /**
     * The preferences file, named here and nowhere else.
     *
     * A file of its own rather than a key in the app's settings, because it is not a setting: it is what
     * a run on this boot produced, it is replaced by the next run that wins the stage, and the one action
     * that clears it clears the whole thing.
     */
    internal const val PREFERENCES = "p0_cache"

    /** The boot the cached number was won on. */
    private const val BOOT_TOKEN = "kernel_boot_id"

    /** The number itself, as the payload printed it: `0x` and lowercase hex. */
    private const val OFFSET = "offset"

    /**
     * The offset cached for this boot, or null when there is none or it belongs to an earlier boot.
     *
     * Null is the whole answer here, deliberately: a caller has no use for another boot's number, and the
     * one screen that does want it reads [entry] instead.
     */
    fun offsetFor(context: Context, bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (stored.getString(BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(OFFSET, null)
    }

    /**
     * Records the offset a run's own output reported.
     *
     * The value is written by the caller, which is the only side that knows how to read it out of a
     * payload's log - and the same side that refuses to store a number the payload could not have
     * produced. This writes and nothing else: it has no opinion about whether the number is any good.
     */
    fun store(context: Context, bootToken: String, offset: String) {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (stored.getString(BOOT_TOKEN, null) == bootToken &&
            stored.getString(OFFSET, null) == offset
        ) {
            return
        }
        stored.edit()
            .putString(BOOT_TOKEN, bootToken)
            .putString(OFFSET, offset)
            .apply()
    }

    /**
     * What the cache holds, or null when it holds nothing.
     *
     * Both keys are read rather than one, because half an entry is not an entry: a token with no number
     * behind it is a file a run began writing, and reporting it as a cached offset would be reporting a
     * number that is not there.
     */
    fun entry(context: Context, bootToken: String? = kernelBootToken()): P0CacheEntry? {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val token = stored.getString(BOOT_TOKEN, null)?.takeIf(String::isNotBlank) ?: return null
        val offset = stored.getString(OFFSET, null)?.takeIf(String::isNotBlank) ?: return null
        return P0CacheEntry(
            offset = offset,
            writtenAtMillis = writeTimeOf(context),
            forThisBoot = bootToken != null && token == bootToken,
        )
    }

    /**
     * Empties the cache, and reports whether it did.
     *
     * False means a run is in flight and nothing was touched. The guard is not about the file being in
     * use - the run read its value before it started and a payload is not holding the file open - it is
     * about the answer: a run writes the offset it is using back when it ends, so a clear in the middle
     * of one would be undone a minute later by the run, and a screen that had said "cleared" would have
     * been describing a moment rather than a state. Standing down and saying so is the same rule the
     * sweeps follow, for the one delete on that screen that needs no shell at all.
     *
     * `deleteSharedPreferences` rather than an `edit().clear()`: the point is for the file to be gone,
     * not for its two keys to be empty, and a cache reading as present-but-blank is the shape a later
     * reader would have to explain.
     */
    fun clear(context: Context): Boolean {
        if (RunInFlight.holder(context) != null) return false
        context.deleteSharedPreferences(PREFERENCES)
        return true
    }

    /**
     * When the cache was last written, for the age the row reports.
     *
     * The file's own timestamp rather than a key of its own, because the two keys are written together
     * and there is nothing else in the file to date separately. Null when the file cannot be stat'd,
     * which the age line has its own word for.
     */
    private fun writeTimeOf(context: Context): Long? = runCatching {
        preferencesFile(context).lastModified().takeIf { it > 0L }
    }.getOrNull()

    /**
     * Where the platform keeps it, which is the one place in this app that names that layout.
     *
     * Derived from the file name rather than typed out beside it, so the reading and the writing cannot
     * come to disagree about which file the cache is.
     */
    private fun preferencesFile(context: Context): File =
        File(context.dataDir, "shared_prefs/$PREFERENCES.xml")
}

/**
 * The cache as a screen reads it: what it holds, and the two facts that give the number its meaning.
 *
 * [forThisBoot] is carried rather than folded into a null, because the interesting state is the one a
 * check that answered "no cached offset" would hide: a file holding a number from an earlier boot. It is
 * on the device, no run will use it, and it is the entry somebody wants to see before deciding to clear
 * it.
 */
internal data class P0CacheEntry(
    /** The offset as it is stored, which is how the payload reported it: `0x20000`. */
    val offset: String,
    /** When it was written, or null when the file cannot be read. */
    val writtenAtMillis: Long?,
    /** Whether the boot that won it is the boot running now. */
    val forThisBoot: Boolean,
) {

    /**
     * The heading's second line, as a resource and its arguments.
     *
     * A [ResidueFolderSummary] rather than a string, and for the reason every other summary in that file
     * is one: which sentence a cache has earned is the part worth testing, and a `Context` in the middle
     * of the choice is the part that could not be. The two sentences are not the same claim - one says a
     * run will use the number, the other says no run will - and one word for both would be the reading
     * this app has spent the most words avoiding.
     */
    fun summary(): ResidueFolderSummary = ResidueFolderSummary(
        res = if (forThisBoot) {
            R.string.residue_p0_summary_this_boot
        } else {
            R.string.residue_p0_summary_other_boot
        },
        args = listOf(offset, StagedResidue.ageLabelOf(writtenAtMillis)),
    )
}
