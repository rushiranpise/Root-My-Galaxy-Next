package dev.busung.s25uroot

/**
 * Whether the System UID install can be set up on this phone, and it is one question with one reading.
 *
 * The helper loads KernelSU's service **out of this phone's verified payload** - never out of whichever
 * KernelSU is installed, which belongs to another build and panics the kernel when it is exec'd, and never
 * out of a manager's bundled copy, which belongs to whichever KernelSU that manager is. So the daemon has
 * to have been taken from the payload this app verified for this device, and only the regular payload
 * method leaves one behind: it resolves the entry for this phone, downloads the exploit and the daemon,
 * and keeps the verified copy.
 *
 * The universal (DirtyFrag) chain leaves none of that. It carries its own exploit inside this APK and
 * caches only a daemon of its own, so a phone rooted that way - and a phone that has never run anything -
 * has no payload for the helper to load. The writes the setup needs are root-only as well: the certificate
 * inject edits `packages.xml`, and the staging ends by making the daemon system-owned at mode 0700 in
 * `/data/system`. A chain root is not a root shell this app holds, so the two facts agree rather than
 * merely coexist - there is nothing here to set up, whichever way the root arrived.
 *
 * ## Why this is not "which flow rooted"
 *
 * A remembered flow is a claim about the past, and it goes stale in both directions: a payload root whose
 * verified copy the user has since forgotten, and a chain root on a phone whose payload is still cached
 * and perfectly usable. The payload's own presence is the durable fact, it is the same reading the staging
 * itself makes before it writes anything, and it cannot come apart from the answer the run would give.
 *
 * ## What is deliberately not held to it
 *
 * Only the presses that write. The readings and the clean-up keep working with no payload at all, and the
 * clean-up above all: it is the only way to take an installed helper off the phone, and the phone this
 * answer refuses is exactly the phone whose verified payload has been forgotten - which is a state a
 * refusal on the whole screen would leave with no way back out of.
 */
internal enum class HelperSetup {
    /** A verified payload for this device is on the phone, so the helper has a daemon to load. */
    Ready,

    /** Nothing verified is cached here, so there is no daemon for the helper to load. */
    NoPayload,
    ;

    /** Whether the presses that write may run. Readings and the clean-up never consult this. */
    val ready: Boolean get() = this == Ready

    companion object {
        /**
         * The decision, from the one reading it is made of: whether this app holds a verified payload for
         * this device. Pure, so the rule can be checked without a phone.
         */
        fun of(hasVerifiedPayload: Boolean): HelperSetup =
            if (hasVerifiedPayload) Ready else NoPayload
    }
}
