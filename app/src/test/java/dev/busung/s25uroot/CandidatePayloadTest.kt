package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePayloadTest {
    @Test
    fun displayName_marks_candidate_or_experimental_case_insensitively() {
        assertTrue(isCandidateDisplayName("e1q [CANDIDATE/EXPERIMENTAL] S9210ZHU6EZIF"))
        assertTrue(isCandidateDisplayName("something candidate build"))
        assertTrue(isCandidateDisplayName("experimental test"))
        assertFalse(isCandidateDisplayName("Galaxy S25 Ultra stable"))
    }

    @Test
    fun profileId_can_mark_candidate_profiles() {
        assertTrue(isCandidateProfileId("e1q-S9210ZHU6EZIF"))
        assertTrue(isCandidateProfileId("foo-candidate-bar"))
        assertTrue(isCandidateProfileId("foo-experimental-bar"))
        assertFalse(isCandidateProfileId("dm3q-S918BXXUAZAJB"))
    }

    @Test
    fun target_profile_is_candidate_when_named_as_candidate() {
        val profile = TargetProfile(
            profileId = "e1q-S9210ZHU6EZIF",
            displayName = "e1q [CANDIDATE/EXPERIMENTAL] S9210ZHU6EZIF",
            models = setOf("SM-S9210"),
            kernelVersions = setOf("6.6.98"),
            exploit = RemoteArtifact("https://example.invalid/app.so", 1),
            kernelSu = RemoteArtifact("https://example.invalid/ksud", 1),
        )
        assertTrue(profile.isCandidate)
    }

    @Test
    fun normal_profile_is_not_candidate() {
        val profile = TargetProfile(
            profileId = "dm3q-S918BXXUAZAJB",
            displayName = "Galaxy S23 Ultra",
            models = setOf("SM-S918B"),
            kernelVersions = setOf("6.1.75"),
            exploit = RemoteArtifact("https://example.invalid/app.so", 1),
            kernelSu = RemoteArtifact("https://example.invalid/ksud", 1),
        )
        assertFalse(profile.isCandidate)
    }
}
