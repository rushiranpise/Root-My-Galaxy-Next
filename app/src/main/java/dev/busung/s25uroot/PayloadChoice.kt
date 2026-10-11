package dev.busung.s25uroot

/**
 * One row of the payload sheet.
 *
 * The sheet used to hold one kind of row a payload a source published for a device and the universal root
 * lived beside it as a card on the home screen that asked two questions and then ran. The card read as a third
 * thing to learn next to the install card, and one of its two questions ("universal root") made a claim about
 * the phone that is not true: this chain needs a payload like any other, it just does not need a *helper*. So
 * it is a row here instead the same list, the same controls, the same Next and both of the questions the
 * card asked are answered by the row itself: one row per KernelSU per [PayloadTier], which is six.
 *
 * ## Why the universal rows are never narrowed by the device toggle
 *
 * [PayloadChoice.Device] rows are what the sources publish, so everything the sheet narrows by applies to them:
 * the device toggle, the flavour lens and the text. The universal rows belong to no device and are published
 * by no source, so the device toggle has nothing to say about them and it is on by default, so a filter that
 * hid them would hide them on the default state of the sheet. That would be the exact inversion of what this
 * path is for: it is the one flow that works on a phone no source has an entry for. The flavour lens does
 * apply, because a row that loads KernelSU-Next is a KernelSU-Next row and the chips are a lens on what a
 * candidate stages.
 *
 * ## What the row says, where the card had a dialog
 *
 * The card asked for the tier behind a second dialog whose answers were live resolutions a network read of
 * the catalog and of the generic feed so that a tier which cannot serve this phone could say so before the
 * run. The list keeps that rule and moves where the answer comes from:
 *
 * - the **device** tier's entry is resolved here, from the catalog the sheet already holds, by the same
 *   [resolveFor] call the run makes so the row names what it would stage. Resolving does not fetch anything,
 *   which is what makes it safe to do on a list, and a flavour that resolves to nothing is a row the sheet
 *   does not list: see [visibleUniversalChoices].
 * - the **generic** tier's coverage is not in this app's hands at this point: it comes from a feed the sheet
 *   does not read, and reading it here would be a network call per row. Its row describes the tier instead,
 *   and a run on a kernel the daemon has no module for refuses with the feed's own sentence after the tap,
 *   before a byte is downloaded, which is the trade the row makes rather than a promise it cannot keep.
 */
internal sealed interface PayloadChoice {

    /**
     * Identity for the sheet's selection and for the list's keys.
     *
     * Both kinds are drawn from one list, so the two have to be in one namespace or a selection could name two
     * rows at once. A device row is keyed by the profile's own [TargetProfile.selectionId], which is what the
     * run is started by; a universal row is keyed by [universalChoiceKey], which is in a shape no profile id
     * has and a test holds the composed list's keys distinct rather than trusting the two shapes, because a
     * duplicate here is a list where tapping one row selects another.
     */
    val key: String

    /** Which KernelSU this row would load, which is what the flavour lens reads. */
    val flavor: KernelSuFlavor

    /** Whether this row is what someone typed. */
    fun matchesQuery(query: String): Boolean

    /** One entry a source published, for one device the sheet's original and still its usual row. */
    data class Device(val profile: TargetProfile) : PayloadChoice {
        override val key: String get() = profile.selectionId
        override val flavor: KernelSuFlavor get() = profile.flavor
        override fun matchesQuery(query: String): Boolean = profile.matchesQuery(query)
    }

    /**
     * The DirtyFrag chain with one flavour's daemon, in one of the two tiers.
     *
     * Both of the card's questions, as one value: [flavor] is which KernelSU this row loads and [tier] is which
     * build of it. Nothing here says which device it is for, because the answer is none that is the row.
     */
    data class Universal(
        override val flavor: KernelSuFlavor,
        val tier: PayloadTier,
    ) : PayloadChoice {
        override val key: String get() = universalChoiceKey(flavor, tier)

        /**
         * What someone arriving at this row types.
         *
         * The row's own words first the flavour and the tier, which is what is on it and then the three
         * things this path is known by rather than named: what it is, the exploit's name, and the CVE. All
         * three are real names for it in this project's own files, and someone who read about the bug will
         * have the number, not "universal root".
         */
        override fun matchesQuery(query: String): Boolean {
            val needle = query.trim()
            if (needle.isEmpty()) return true
            return listOf(
                flavor.id,
                flavor.label,
                tier.name,
                "${tier.name} payload",
                "universal",
                UniversalRootRun.EXPLOIT_NAME,
                UniversalRootRun.CVE,
            ).any { it.contains(needle, ignoreCase = true) }
        }
    }
}

/**
 * The id a universal row is keyed by, in a shape no [TargetProfile.selectionId] has.
 *
 * Profile ids are `profileId` or `source|profileId`, so the colon and the prefix both keep this out of their
 * way deliberately, since the two kinds share one selection: a key that could be read as a source-qualified
 * profile id is one that could be handed to something that resolves profiles.
 */
internal fun universalChoiceKey(flavor: KernelSuFlavor, tier: PayloadTier): String =
    "universal:$UNIVERSAL_KEY_MARK:${flavor.id}:${tier.name}"

private const val UNIVERSAL_KEY_MARK = "dirtyfrag"

/**
 * Every universal row the sheet can show: one per KernelSU per tier.
 *
 * Flavour-major, and in the order the flavour chips are drawn, so the rows under a chip arrive together rather
 * than interleaved by tier. The tiers are in their declared order, device first the stronger pairing first,
 * which is also the order the card's dialog offered them in.
 */
internal val allUniversalChoices: List<PayloadChoice.Universal> =
    KernelSuFlavor.entries.flatMap { flavor ->
        PayloadTier.entries.map { tier -> PayloadChoice.Universal(flavor, tier) }
    }

/**
 * The universal rows to show, narrowed by the two controls that apply to them and by what one tier stages.
 *
 * No device parameter, and that is the whole of the "always shown" rule: the toggle narrows what the sources
 * publish, and this is not published by a source. Threading the toggle in here would be the change that made
 * the sheet hide this path on a phone with no entry of its own which is `PayloadChoiceTest`'s first case.
 *
 * [deviceCoverage] is the one thing that does take a row away, and it is a fact about the device tier alone:
 * the flavours the read sources have an entry for. That tier stages an entry out of a catalog and nothing else,
 * so a flavour with none is a row whose only outcome is the refusal the run produces after the tap - and the
 * sheet hides it rather than leading someone to that refusal by name. Null is "the sources have not been
 * read", which is the state the sheet holds while a read is in flight and after one that failed: nothing is
 * known about this phone then, and every row is listed rather than the app blaming a device for a read it
 * never made.
 */
internal fun visibleUniversalChoices(
    flavor: KernelSuFlavor?,
    query: String,
    deviceCoverage: Set<KernelSuFlavor>? = null,
): List<PayloadChoice.Universal> = allUniversalChoices.filter { choice ->
    (flavor == null || choice.flavor == flavor) &&
        choice.matchesQuery(query) &&
        choice.hasPayloadFor(deviceCoverage)
}

/**
 * Whether the sheet has something behind this row.
 *
 * Only the device tier can answer no, because only it stages what the app does not already hold everywhere:
 * the generic daemon is a feed read the run makes for itself, and a row for it is a row on any phone. A device
 * row is an entry in a catalog the sheet has read, so a flavour that entry is missing for is a row with
 * nothing behind it - and the tier's engine, not the row, is what covers such a phone.
 */
private fun PayloadChoice.Universal.hasPayloadFor(deviceCoverage: Set<KernelSuFlavor>?): Boolean =
    tier != PayloadTier.Device || deviceCoverage == null || flavor in deviceCoverage

/**
 * The sheet's two groups, in the order it draws them. The universal rows lead, then what the sources publish.
 *
 * The chain is the flow that works on a phone no source has an entry for - it is the one that needs no helper
 * and no device-specific exploit, which is why it is the answer when the feed says nothing about this phone.
 * Leading with it says that: the first row on the sheet is the one that can always be run, and the payloads a
 * source published follow it rather than standing in front of it.
 *
 * The device rows are still there in full, and the device toggle, the flavour lens and the search reach both
 * kinds as before - see [payloadRows] for which control applies to which.
 */
internal data class PayloadRows(
    val universal: List<PayloadChoice.Universal>,
    val device: List<PayloadChoice.Device>,
) {
    /** Whether the sheet has nothing to offer at all, which is the case its empty state explains. */
    val isEmpty: Boolean get() = universal.isEmpty() && device.isEmpty()

    /** Everything listed, in the order drawn - which is what [payloadRows] composes and the sheet walks. */
    val all: List<PayloadChoice> get() = universal + device
}

/**
 * What the payload sheet lists, given its controls.
 *
 * The one place the two groups are composed, so the rule about which controls reach which kind is a line of
 * code rather than a claim about the screen: [fitsDeviceOnly] is handed to [visibleTargets] and not to
 * [visibleUniversalChoices], and the tests ask this function with the toggle on.
 */
internal fun payloadRows(
    profiles: List<TargetProfile>,
    device: DeviceSnapshot,
    fitsDeviceOnly: Boolean,
    query: String,
    flavor: KernelSuFlavor?,
    /** The flavours the read sources cover, or null while they have not been read - see [visibleUniversalChoices]. */
    deviceCoverage: Set<KernelSuFlavor>? = null,
): PayloadRows = PayloadRows(
    universal = visibleUniversalChoices(flavor, query, deviceCoverage),
    device = visibleTargets(profiles, device, fitsDeviceOnly, query, flavor)
        .map(PayloadChoice::Device),
)
