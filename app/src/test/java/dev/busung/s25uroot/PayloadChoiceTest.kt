package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload sheet's two groups and the rule that separates them.
 *
 * The universal root used to be a card on the home screen with its own two dialogs, and the whole point of
 * moving it into the list is that it is listed by the sheet's own controls like everything else except for
 * one, where the correct behaviour is the opposite of the sheet's default. These cases are that rule and the
 * three ways a row is found, asked of [payloadRows] rather than of the screen, because the screen is a list of
 * items and what can go wrong here is which items.
 *
 * The toggle is the case worth having a test for: `showOnlyMyDevice` is **on by default**, and it filters on
 * `matches`, which asks a profile about a phone. A universal row is not a profile and belongs to no device, so
 * a version of this that handed the toggle to both kinds would compile, look right in a screenshot, and hide
 * this entire flow on the default state of the sheet for every phone whose catalog has no entry which is the
 * phone this flow exists for.
 */
class PayloadChoiceTest {

    private val mine = TargetProfile(
        profileId = "pa2q-S9360ZHSCCZG1",
        displayName = "Galaxy S25 series",
        models = setOf("SM-S9360"),
        kernelVersions = setOf("6.6.98"),
        exploit = RemoteArtifact("https://example.invalid/exploit", 1),
        kernelSu = RemoteArtifact("https://example.invalid/ksud", 1),
        sourceLabel = "Root-My-Galaxy-Payloads",
    )
    private val otherDevice = mine.copy(
        profileId = "pa3q-S9210ZSACCZG1",
        displayName = "Galaxy S24 series",
        models = setOf("SM-S9210"),
    )
    private val otherKernel = mine.copy(
        profileId = "pa2q-next-S9360ZHSCCZG1",
        kernelVersions = setOf("6.6.102"),
        flavor = KernelSuFlavor.KernelSuNext,
        sourceLabel = "payloads-next",
        kernelSuVersion = "3.4.0",
    )
    private val catalog = listOf(mine, otherDevice, otherKernel)
    private val device = snapshot(model = "SM-S9360", kernelRelease = "6.6.98-android15-8-build")

    private fun rows(
        profiles: List<TargetProfile> = catalog,
        fitsDeviceOnly: Boolean = false,
        query: String = "",
        flavor: KernelSuFlavor? = null,
        deviceCoverage: Set<KernelSuFlavor>? = null,
    ) = payloadRows(profiles, device, fitsDeviceOnly, query, flavor, deviceCoverage)

    @Test
    fun `the device toggle narrows the payloads and never the universal rows`() {
        // Both halves in one case, because the interesting fact is the difference between them: the same call,
        // the same toggle, two answers. Three device payloads become one; six universal rows stay six.
        assertEquals(3, rows(fitsDeviceOnly = false).device.size)
        assertEquals(1, rows(fitsDeviceOnly = true).device.size)
        assertEquals(6, rows(fitsDeviceOnly = false).universal.size)
        assertEquals(6, rows(fitsDeviceOnly = true).universal.size)
    }

    @Test
    fun `a sheet that could not read the catalog still lists every universal row`() {
        // What the sheet passes while the sources are being read and after a read that failed: no profiles at
        // all. The device group is empty and says so; the universal rows are untouched, which is the reason
        // this flow can be started on a phone whose catalog is unreachable - the run's own resolution reads
        // the feed, not this list.
        val none = rows(profiles = emptyList(), fitsDeviceOnly = true)
        assertTrue(none.device.isEmpty())
        assertEquals(6, none.universal.size)
        assertFalse(none.isEmpty)
    }

    @Test
    fun `one row per KernelSU per payload tier, in the order the flavour chips are drawn`() {
        // Flavour-major so the rows under a chip arrive together, and the tiers in their declared order so the
        // stronger pairing - the module built for this phone - is the one above.
        val listed = rows().universal.map { it.flavor to it.tier }
        assertEquals(
            listOf(
                KernelSuFlavor.KernelSu to PayloadTier.Device,
                KernelSuFlavor.KernelSu to PayloadTier.Generic,
                KernelSuFlavor.KernelSuNext to PayloadTier.Device,
                KernelSuFlavor.KernelSuNext to PayloadTier.Generic,
                KernelSuFlavor.ReSukiSU to PayloadTier.Device,
                KernelSuFlavor.ReSukiSU to PayloadTier.Generic,
            ),
            listed,
        )
        // The chips' own order, so a row is never under a tab it does not belong to.
        assertEquals(KernelSuFlavor.entries, listed.map { it.first }.distinct())
    }

    @Test
    fun `a device-tier row is listed only for a flavour the read sources cover`() {
        // The row's own sentence used to carry this, and the sheet hides the row instead: the device tier
        // stages a catalog entry or falls back to the generic daemon, so a flavour that resolves to nothing is
        // a row whose only content is the tier it already is - and the generic row under it says that.
        val nextOnly = rows(deviceCoverage = setOf(KernelSuFlavor.KernelSuNext))
        assertEquals(
            listOf(
                KernelSuFlavor.KernelSu to PayloadTier.Generic,
                KernelSuFlavor.KernelSuNext to PayloadTier.Device,
                KernelSuFlavor.KernelSuNext to PayloadTier.Generic,
                KernelSuFlavor.ReSukiSU to PayloadTier.Generic,
            ),
            nextOnly.universal.map { it.flavor to it.tier },
        )
        // The phone this flow exists for: no source has an entry at all, so the three device rows are gone and
        // the three generic ones stand - a sheet that is not empty, because something here can still root it.
        val noneCovered = rows(deviceCoverage = emptySet())
        assertEquals(3, noneCovered.universal.size)
        assertTrue(noneCovered.universal.all { it.tier == PayloadTier.Generic })
        assertFalse(noneCovered.isEmpty)
        // And an unread catalog covers nobody and hides nothing: the null is "not known", not "nothing", and
        // dropping rows over it would be the app answering from a read it never made.
        assertEquals(6, rows(deviceCoverage = null).universal.size)
        assertEquals(6, rows(profiles = emptyList(), deviceCoverage = null).universal.size)
        assertEquals(3, rows(profiles = emptyList(), deviceCoverage = emptySet()).universal.size)
    }

    @Test
    fun `the flavour lens is the one control that reaches the universal rows`() {
        // A row that loads KernelSU-Next is a KernelSU-Next row, so the chips apply to both groups - the lens
        // is a question about what a candidate stages, and a universal row stages a KernelSU like any other.
        val next = rows(fitsDeviceOnly = true, flavor = KernelSuFlavor.KernelSuNext)
        assertEquals(
            listOf(
                KernelSuFlavor.KernelSuNext to PayloadTier.Device,
                KernelSuFlavor.KernelSuNext to PayloadTier.Generic,
            ),
            next.universal.map { it.flavor to it.tier },
        )
        assertEquals(2, rows(flavor = KernelSuFlavor.ReSukiSU).universal.size)
        assertEquals(6, rows(flavor = null).universal.size)
    }

    @Test
    fun `a universal row is found by the exploit, its CVE, its flavour and its tier`() {
        // Four names for one row, because all four are real: "universal" is what the flow has been called
        // here, "DirtyFrag" is the technique, the number is the CVE the sheet prints in its header, and the
        // flavour and tier are the two facts the row itself carries. Someone who read about the bug has the
        // number, so a search that only knew the first word would find nothing for them.
        assertEquals(6, rows(query = "dirtyfrag").universal.size)
        assertEquals(6, rows(query = UniversalRootRun.CVE).universal.size)
        assertEquals(6, rows(query = "universal").universal.size)
        assertEquals(3, rows(query = "generic").universal.size)
        assertEquals(
            listOf(PayloadTier.Device, PayloadTier.Generic),
            rows(query = "resukisu").universal.map { it.tier },
        )
        // And by the flavour's id, which is what the feed writes and what somebody copying from one may type.
        assertEquals(2, rows(query = "kernelsu-next").universal.size)
    }

    @Test
    fun `only a filter the universal rows also fail can empty the whole sheet`() {
        // The sheet's empty state is drawn when the device group is empty either way, but which sentence it
        // uses depends on whether *anything* is still listed - so both states have to be reachable. This is
        // the one where nothing at all matches: a term no row carries.
        assertTrue(rows(query = "zzzz").isEmpty)
        assertTrue(rows(profiles = emptyList(), fitsDeviceOnly = true, query = "zzzz").isEmpty)
        // Another device's model is one of those terms rather than a case of its own. The search is not the
        // device toggle - it is a question about the text - so a universal row is hidden by it exactly as a
        // payload is, and a phone's owner typing a sibling's model gets the same answer from both groups.
        assertTrue(rows(fitsDeviceOnly = true, query = "S9210").isEmpty)
        // The other state the sheet tells apart: a device group emptied by the toggle while the universal rows
        // are still listed, which is the ordinary sheet on a phone no source has an entry for.
        val deviceEmpty = rows(profiles = listOf(otherDevice), fitsDeviceOnly = true, query = "")
        assertTrue(deviceEmpty.device.isEmpty())
        assertFalse(deviceEmpty.isEmpty)
    }

    @Test
    fun `every row in the sheet has a key of its own`() {
        // The two kinds share one selection, so their keys share one namespace. A collision is not a cosmetic
        // bug here: the list's keys would be duplicated and picking one row would draw another as picked.
        val all = rows().all
        assertEquals(9, all.size)
        assertEquals(all.size, all.map { it.key }.distinct().size)

        // A device row's key is the profile's own selection id, because that is what the run is started by;
        // a universal row's is not, and could not be read as one.
        assertEquals(mine.selectionId, all.filterIsInstance<PayloadChoice.Device>().first().key)
        all.filterIsInstance<PayloadChoice.Universal>().forEach { choice ->
            assertFalse(choice.key == choice.flavor.id)
            assertTrue(choice.key.startsWith("universal:"))
        }
    }

    @Test
    fun `the universal rows lead the list and the payloads follow them`() {
        // Order, and the chain is what leads: it needs no helper and no device-specific exploit, so it is the
        // one row that can always be run - including on a phone no source has an entry for. The payloads the
        // sources published follow it, in full, so nothing was lost by leading with it.
        val all = rows().all
        assertEquals(9, all.size)
        assertTrue(all.take(6).all { it is PayloadChoice.Universal })
        assertTrue(all.drop(6).all { it is PayloadChoice.Device })
    }

    private fun snapshot(model: String, kernelRelease: String) = DeviceSnapshot(
        manufacturer = "samsung",
        model = model,
        device = "unused",
        kernelRelease = kernelRelease,
        kernelVersionInfo = "#1 SMP PREEMPT",
        machine = "aarch64",
        buildId = "BP4A.251205.006.S938BCZG1",
        fingerprint = "samsung/example",
        androidRelease = "16",
        sdk = 36,
        abi = "arm64-v8a",
        pageSize = 4096,
    )
}
