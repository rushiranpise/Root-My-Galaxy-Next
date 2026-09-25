package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The p0 offset cache: what it says about what it holds, and who is allowed to name it.
 *
 * The cache is the one thing the residue screen lists that is not a file on the device, so none of the
 * assertions here is about a stat. The first is the sentence a cache has earned:
 * whether the boot it was won on is the one running is the difference between a number a run will be
 * handed and a number no run will use, and a screen that said the same thing about both would be the
 * reading this app spends the most words avoiding. The second is that the preferences file has one
 * owner - the failure this test exists for is a second reader that names the file itself, which is how
 * a screen comes to show a cache the run does not read.
 */
class P0CacheTest {

    @Test
    fun `a cached offset says whether the boot that won it is this one`() {
        val thisBoot = P0CacheEntry(
            offset = "0x20000",
            writtenAtMillis = NOW - 3_600_000L,
            forThisBoot = true,
        ).summary()
        assertEquals(R.string.residue_p0_summary_this_boot, thisBoot.res)
        assertEquals(listOf("0x20000", StagedResidue.ageLabelOf(NOW - 3_600_000L)), thisBoot.args)

        // The interesting state, and the one a null would have hidden: the file holds a number from a
        // boot that has since rebooted, so the sentence has to say that no run will use it - which is
        // also why it is worth listing rather than reporting as an empty cache.
        val otherBoot = P0CacheEntry(
            offset = "0x20000",
            writtenAtMillis = NOW - 3_600_000L,
            forThisBoot = false,
        ).summary()
        assertEquals(R.string.residue_p0_summary_other_boot, otherBoot.res)
        assertEquals(thisBoot.args, otherBoot.args)
    }

    @Test
    fun `an age that cannot be read is said rather than guessed`() {
        // The file's timestamp is the only date there is, and a `Context` whose data directory cannot be
        // stat'd would otherwise be shown as "today" - a claim about when a number was won, made from a
        // failed read.
        val summary = P0CacheEntry(offset = "0x1e0000", writtenAtMillis = null, forThisBoot = true).summary()
        assertEquals(StagedResidue.ageLabelOf(null), summary.args[1])
    }

    @Test
    fun `the cache's preferences file is named in one place`() {
        val sources = sourceFiles()
        assertTrue("no sources were found; the scan is looking at the wrong directory", sources.isNotEmpty())
        val namedIn = sources
            .filter { file -> file.name != "P0Cache.kt" }
            .filter { file -> CACHE_FILE_LITERAL.containsMatchIn(file.readText()) }
            .map { it.name }
            .sorted()

        assertEquals(
            "these files name the ${P0Cache.PREFERENCES} preferences file themselves, so the reading " +
                "on the residue screen and the value a run uses could come to be two different caches",
            emptyList<String>(),
            namedIn,
        )
    }

    private fun sourceFiles(): List<File> = candidateRoots()
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).filter(File::isDirectory)

    private companion object {

        /** A timestamp to measure an age against, in milliseconds since the epoch. */
        const val NOW = 1_700_000_000_000L

        /**
         * The preferences file as a quoted Kotlin literal, which is narrower than its name.
         *
         * The file name is also part of a route description - `p0_cache=on` - and that is a coincidence
         * of naming rather than a second owner of the cache, so the scan looks for the whole literal
         * rather than the word.
         */
        val CACHE_FILE_LITERAL = Regex("\"${P0Cache.PREFERENCES}\"")
    }
}
