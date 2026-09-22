package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest parser on real `org.json` rather than Android's stubbed copy, so what is asserted
 * here is what the app does when it reads a feed.
 */
class SupportManifestTest {

    private val manifest = """
        {
          "schemaVersion": 3,
          "payloads": [
            {
              "payloadId": "e2s-S926BXXUEDZDR",
              "displayName": "Galaxy S25",
              "models": ["SM-S931B", "SM-S931N"],
              "kernelVersions": ["6.6.98"],
              "requiresFreshP0Session": true,
              "exploit": {
                "url": "https://raw.githubusercontent.com/example/feed/main/exploit.so",
                "size": 4096
              },
              "kernelsu": {
                "url": "https://raw.githubusercontent.com/example/feed/main/ksud",
                "size": 8192
              }
            },
            {
              "payloadId": "dm3q-S918B",
              "displayName": "Galaxy S23 Ultra",
              "models": ["SM-S918B"],
              "kernelVersions": ["5.15.149", "5.15.149-android13-8-build"],
              "exploit": {
                "url": "https://raw.githubusercontent.com/example/feed/main/old.so",
                "size": 0,
                "verifySize": false
              },
              "kernelsu": {
                "url": "https://raw.githubusercontent.com/example/feed/main/old-ksud",
                "size": 1024
              }
            }
          ]
        }
    """.trimIndent().toByteArray()

    @Test
    fun readsEveryPayloadWithItsModelsAndKernelVersions() {
        val parsed = SupportManifest.parse(manifest)

        assertEquals(3, parsed.schemaVersion)
        assertEquals(2, parsed.targets.size)

        val first = parsed.targets[0]
        assertEquals("e2s-S926BXXUEDZDR", first.profileId)
        assertEquals(setOf("SM-S931B", "SM-S931N"), first.models)
        assertEquals(setOf("6.6.98"), first.kernelVersions)
        assertEquals(4096L, first.exploit.size)
        assertEquals("https://raw.githubusercontent.com/example/feed/main/ksud", first.kernelSu.url)
    }

    @Test
    fun keepsTheFreshSessionFlagAndTheSizeEscapeHatch() {
        val parsed = SupportManifest.parse(manifest)

        // Both are per-payload opt-ins the app acts on, so a parse that dropped either would change
        // how a run behaves without anyone editing the feed.
        assertTrue(parsed.targets[0].requiresFreshP0Session)
        assertFalse(parsed.targets[1].requiresFreshP0Session)
        assertFalse(parsed.targets[1].exploit.verifySize)

        // And the default stays strict for a payload that does not ask otherwise.
        assertTrue(parsed.targets[1].kernelSu.verifySize)
    }

    @Test
    fun resolvesProfilesForADeviceFromParsedInput() {
        val parsed = SupportManifest.parse(manifest)
        val snapshot = DeviceSnapshot(
            manufacturer = "samsung",
            model = "SM-S931B",
            device = "e2s",
            kernelRelease = "6.6.98-android15-8-build",
            kernelVersionInfo = "#1 SMP PREEMPT",
            machine = "aarch64",
            buildId = "BP4A.251205.006",
            fingerprint = "samsung/e2s",
            androidRelease = "16",
            sdk = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
        )

        assertEquals("e2s-S926BXXUEDZDR", parsed.targets.resolveFor(snapshot)?.profileId)
    }

    @Test
    fun readsTheKernelSuVersionAnEntryDeclares() {
        // A feed may write the tag it was built from or the version, and the two have to arrive here as
        // the same value: this is compared against a manager's own `versionName` and against the
        // flavour's fallback, both of which are dotted numbers.
        val tagged = SupportManifest.parse(
            """
            {"schemaVersion":3,"payloads":[{
              "payloadId":"pa3q-S938USQSCCZF9-ksun340",
              "displayName":"Galaxy S25 Ultra | KernelSU-Next 3.4.0 (test)",
              "models":["SM-S938U1"],
              "kernelVersions":["6.6.98"],
              "flavor":"kernelsu-next",
              "exploit":{"url":"https://example.invalid/exploit.so","size":4096},
              "kernelsu":{"url":"https://example.invalid/ksud","size":8192,"version":"v3.4.0"}
            }]}
            """.trimIndent().toByteArray(),
        )
        assertEquals("3.4.0", tagged.targets.single().kernelSuVersion)
    }

    @Test
    fun anEntryThatDeclaresNoVersionReadsAsNothing() {
        // Rather than as a version: the manager offer falls back to the flavour's own release there, and
        // a value invented here would be a version no release can be looked up for.
        val parsed = SupportManifest.parse(manifest)

        assertNull(parsed.targets[0].kernelSuVersion)
        assertNull(parsed.targets[1].kernelSuVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesASchemaItCannotRead() {
        // A feed that moves to a schema this build does not know must fail loudly at parse time
        // rather than have its fields silently ignored.
        SupportManifest.parse("""{"schemaVersion":4,"payloads":[]}""".toByteArray())
    }
}
