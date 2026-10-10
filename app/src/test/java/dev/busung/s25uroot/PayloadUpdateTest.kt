package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a rooted phone should be told that its payload has moved on.
 *
 * The two ways of getting it wrong cost opposite things. A false yes tells somebody to re-root their phone
 * for nothing, which is the more expensive of the two - and the reason a KernelSU version string moving is
 * not the test, since this repository rebuilds one release several times. A false no leaves the phone running
 * the old exploit, which is the state this whole file exists to end.
 */
class PayloadUpdateTest {

    @Test
    fun `the same entry is not an update`() {
        val held = cached()
        assertNull(
            "an offer identical to the payload on the phone is reported as an update, so the card would tell " +
                "somebody to re-root for nothing",
            PayloadUpdate.of(held, offered()),
        )
    }

    @Test
    fun `rebuilt daemon bytes under the same id are an update`() {
        // What a fix to the same target looks like: the id carries the firmware and the KernelSU release, so
        // it stays, and the artifact is rebuilt.
        val update = PayloadUpdate.of(cached(), offered(kernelSuSha = "f".repeat(64)))
        assertNotNull("a rebuilt daemon for the same id is not reported", update)
        assertEquals("Galaxy S25 Ultra", update?.offeredName)
    }

    @Test
    fun `a differently named entry for this device is an update`() {
        val update = PayloadUpdate.of(cached(), offered(profileId = "pa3q-S938USQSCCZG1-ksun341"))
        assertNotNull("a new payload id for this phone is not reported", update)
    }

    @Test
    fun `a moved KernelSU version alone is not an update`() {
        // The version string is what the card says out loud, not what it decides on: several rebuilds of one
        // release carry the same string, and a card keyed on it would fire for a rebuild that changed nothing.
        assertNull(
            "a version string moving on its own is treated as an update, which tells people to re-root for a " +
                "rebuild that changed nothing",
            PayloadUpdate.of(cached(version = "3.4.0"), offered(version = "3.4.1")),
        )
    }

    @Test
    fun `nothing cached, or nothing offered, says nothing`() {
        // The first is a phone that has never run a payload; the second is the phone the chain exists for,
        // where no source has an entry at all. Neither is an update, and neither may be reported as one.
        assertNull(PayloadUpdate.of(null, offered()))
        assertNull(PayloadUpdate.of(cached(), null))
    }

    @Test
    fun `a feed without hashes falls back to url and size`() {
        // An entry that declares no hash cannot be compared by content, so the reading that is left is the
        // pair of things the feed does state. Identical ones are the same payload; either moving is not.
        assertNull(PayloadUpdate.of(cached(sha = null), offered(kernelSuSha = null)))
        assertNotNull(PayloadUpdate.of(cached(sha = null), offered(kernelSuSha = null, kernelSuUrl = "other")))
    }

    private fun cached(version: String? = "3.4.0", sha: String? = "a".repeat(64)) = CachedPayload(
        id = "pa3q-S938USQSCCZF9-ksun340",
        profileId = "pa3q-S938USQSCCZF9-ksun340",
        displayName = "Galaxy S25 Ultra",
        models = listOf("SM-S938U1"),
        kernelVersions = listOf("6.6.98"),
        exploit = RemoteArtifact(url = "https://example.invalid/exploit.so", size = 1L),
        kernelSu = RemoteArtifact(url = "https://example.invalid/ksud", size = 2L, sha256 = sha),
        kernelSuVersion = version,
        helperSha256 = "b".repeat(64),
        helperSize = 3L,
    )

    private fun offered(
        profileId: String = "pa3q-S938USQSCCZF9-ksun340",
        kernelSuSha: String? = "a".repeat(64),
        kernelSuUrl: String = "https://example.invalid/ksud",
        version: String? = "3.4.0",
    ) = TargetProfile(
        profileId = profileId,
        displayName = "Galaxy S25 Ultra",
        models = setOf("SM-S938U1"),
        kernelVersions = setOf("6.6.98"),
        exploit = RemoteArtifact(url = "https://example.invalid/exploit.so", size = 1L),
        kernelSu = RemoteArtifact(url = kernelSuUrl, size = 2L, sha256 = kernelSuSha),
        kernelSuVersion = version,
    )
}
