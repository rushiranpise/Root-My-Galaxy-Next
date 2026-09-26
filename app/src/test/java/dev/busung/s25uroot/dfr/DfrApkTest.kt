package dev.busung.s25uroot.dfr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Why this build has no helper, which is two answers and not one.
 *
 * The read used to return a nullable file, so "this APK carries no helper" and "it carries one that
 * could not be written to app storage" arrived as the same `null` - and the screen said the first for
 * both, which sends a phone with a full disk looking for a build to replace. The classification is a
 * function here rather than a branch inside the read so it can be tested without an asset to unpack:
 * the mistake was in the decision, not in the file.
 */
class DfrApkTest {

    @Test
    fun `no asset, and an empty one, are a build without a helper`() {
        // What a build that never staged the `:dfr` artifact into its assets looks like, both ways: an
        // absent entry and a zero-length one.
        assertEquals(DfrHelperAvailability.NotInBuild, classifyHelper(null, written = false))
        assertEquals(DfrHelperAvailability.NotInBuild, classifyHelper(ByteArray(0), written = false))
        // An empty asset is not a failed unpack: there is nothing to write, so the storage never came
        // into it, and the advice is about the APK.
        assertEquals(DfrHelperAvailability.NotInBuild, classifyHelper(ByteArray(0), written = true))
    }

    @Test
    fun `an APK that did not reach storage is not reported as a build without one`() {
        // The case this exists for: the bytes are in the APK, the write failed - no room, or storage this
        // app may not write to - and the honest answer is about the phone rather than about the build.
        assertEquals(
            DfrHelperAvailability.Unwritable,
            classifyHelper(ByteArray(4096) { 1 }, written = false),
        )
    }

    @Test
    fun `an unpacked APK is the only reading that lets the flow run`() {
        assertEquals(DfrHelperAvailability.Ready, classifyHelper(ByteArray(4096) { 1 }, written = true))
    }

    @Test
    fun `a file comes back only for the reading that has one`() {
        // The two fields are one answer: whatever the availability says, the file says the same about
        // whether there is something to inject. A pair that could disagree would put the refusal's own
        // copy on a screen where a helper is sitting there ready.
        val ready = DfrBundled(file = java.io.File("/tmp/bundled.apk"), DfrHelperAvailability.Ready)
        assertEquals(DfrHelperAvailability.Ready, ready.availability)
        val refused = DfrBundled(file = null, DfrHelperAvailability.Unwritable)
        assertEquals(null, refused.file)
    }
}
