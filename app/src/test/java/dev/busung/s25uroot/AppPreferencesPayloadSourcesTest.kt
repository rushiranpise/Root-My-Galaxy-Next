package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesPayloadSourcesTest {
    @Test
    fun freshPrefsNoLegacyKeysUseMaintainedFirstDefaults() {
        val sources = AppPreferences.defaultPayloadSourcesForLegacy(null, null)

        assertEquals(PayloadSource.DEFAULTS, sources)
        assertEquals(PayloadSource.MAINTAINED.id, sources.first().id)
    }

    @Test
    fun legacyDefaultSingleRepoPrefsNormalizeToMaintainedFirstDefaults() {
        val sources = AppPreferences.defaultPayloadSourcesForLegacy(
            PayloadSource.DEFAULT_REPOSITORY,
            PayloadSource.DEFAULT_BRANCH,
        )

        assertEquals(PayloadSource.DEFAULTS, sources)
        assertEquals(PayloadSource.MAINTAINED.id, sources.first().id)
    }

    @Test
    fun legacyExplicitCustomRepoPrefsPreserveIntentDeterministically() {
        val custom = PayloadSource("example-org/custom-payloads", "stable", enabled = true)
        val sources = AppPreferences.defaultPayloadSourcesForLegacy(custom.repository, custom.branch)

        assertEquals(PayloadSource.MAINTAINED.id, sources.first().id)
        assertEquals(custom.id, sources[1].id)
        assertTrue(sources.any { it.id == PayloadSource.DEFAULT.id })
        assertTrue(sources.any { it.id == PayloadSource.OFFICIAL.id })
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
    }

    @Test
    fun savedListMissingMaintainedIsPrependedOncePreservingOrderStateAndPins() {
        val customPinned = PayloadSource(
            repository = "example-org/custom-payloads",
            branch = "main",
            enabled = false,
            pinnedCommit = "0123456789abcdef0123456789abcdef01234567",
        )
        val decoded = listOf(PayloadSource.DEFAULT.copy(enabled = false), customPinned)

        val migrated = AppPreferences.migrateSavedPayloadSources(decoded)

        assertEquals(PayloadSource.MAINTAINED.id, migrated.first().id)
        assertEquals(decoded, migrated.drop(1))
        assertEquals(1, migrated.count { it.id == PayloadSource.MAINTAINED.id })
    }

    @Test
    fun savedListAlreadyContainingMaintainedIsUnchanged() {
        val custom = PayloadSource("example-org/custom-payloads", "dev", enabled = true)
        val decoded = listOf(custom, PayloadSource.MAINTAINED.copy(enabled = false), PayloadSource.OFFICIAL)

        val migrated = AppPreferences.migrateSavedPayloadSources(decoded)

        assertEquals(decoded, migrated)
    }

    @Test
    fun emptySavedStringFallsBackLikeNoSavedList() {
        val decoded = AppPreferences.decodePayloadSources("")
        val effective = if (decoded.isEmpty()) {
            AppPreferences.defaultPayloadSourcesForLegacy(null, null)
        } else {
            AppPreferences.migrateSavedPayloadSources(decoded)
        }

        assertTrue(decoded.isEmpty())
        assertEquals(PayloadSource.DEFAULTS, effective)
        assertEquals(PayloadSource.MAINTAINED.id, effective.first().id)
    }

    @Test
    fun decodeEncodeRoundTripPreservesPinnedCommitAndEnabledState() {
        val pinned = PayloadSource(
            repository = "example-org/custom-payloads",
            branch = "release",
            enabled = false,
            pinnedCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        val encoded = AppPreferences.encodePayloadSources(listOf(PayloadSource.MAINTAINED, pinned))

        val decoded = AppPreferences.decodePayloadSources(encoded)

        assertEquals(listOf(PayloadSource.MAINTAINED, pinned), decoded)
    }
}
