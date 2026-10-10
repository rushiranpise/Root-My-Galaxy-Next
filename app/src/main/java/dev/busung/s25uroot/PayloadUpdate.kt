package dev.busung.s25uroot

/**
 * A payload the sources publish for this phone now, where this phone was rooted with a different one.
 *
 * The gap this closes is that nothing tells a rooted phone that its payload has moved on. A payload run
 * caches what it verified ([CachedPayload]), and that cache is what an offline run and the staging both read
 * - so a phone that rooted last week keeps using last week's exploit and last week's daemon, and the only
 * thing that would say otherwise is somebody opening the sheet and noticing a row that looks slightly
 * different. Meanwhile the payload repository is the half of this project that is updated most often.
 *
 * ## What counts as an update, and what does not
 *
 * Two readings, and only the second is what the feed's own vocabulary would suggest:
 *
 * - **the entry is a different one** ([CachedPayload.profileId] against [TargetProfile.profileId]). A source
 *   that replaces the payload for a phone usually publishes it under a new id - the id carries the firmware
 *   and the KernelSU release - so a changed id for this device is a changed payload.
 * - **the daemon bytes are different**, compared by hash where the feed declares one and by url and size
 *   where it does not. This is the reading that catches a payload rebuilt for the same phone at the same id,
 *   which is what a fix to the same target looks like.
 *
 * A newer KernelSU *version string* is deliberately **not** the test on its own. Two builds of one release
 * are the normal case in this repository - the fingerprint on the daemon is the same string across several
 * rebuilds - and a card that appeared because a string moved would be a card that tells people to re-root
 * for nothing. The version is still carried, because it is what the card says out loud.
 *
 * ## What it does not know
 *
 * It cannot tell a phone rooted by the chain from one rooted by a payload: the cache it reads is written by
 * payload runs, and a phone that has run both has one. The card's instruction is the same either way - root
 * again, which is what a payload run is - so the difference does not change what it says.
 */
internal data class PayloadUpdate(
    /** What the sources call the payload they publish for this phone now. */
    val offeredName: String,
    /** The KernelSU release the payload on this phone was built from, when the feed declared one. */
    val heldVersion: String?,
    /** The KernelSU release the offered one is built from, when the feed declares one. */
    val offeredVersion: String?,
) {
    companion object {
        /**
         * The update worth telling somebody about, or null when there is nothing to say.
         *
         * Null covers three cases that have to be told apart from an update and cannot be told apart from
         * each other here: nothing cached (this phone has never run a payload), nothing offered (no enabled
         * source has an entry for this phone at all - the case the chain exists for), and two readings that
         * agree. Pure, so every one of them is a test rather than a phone.
         */
        fun of(held: CachedPayload?, offered: TargetProfile?): PayloadUpdate? {
            if (held == null || offered == null) return null
            if (held.profileId == offered.profileId && sameArtifact(held.kernelSu, offered.kernelSu)) return null
            return PayloadUpdate(
                offeredName = offered.displayName,
                heldVersion = held.kernelSuVersion,
                offeredVersion = offered.kernelSuVersion,
            )
        }

        /**
         * Whether two entries in the feed are the same bytes.
         *
         * The hash where both declare one, because that is the reading that can actually prove it; url and
         * size where they do not, which is the best a feed without hashes can say. Comparing a hash against a
         * missing one is not attempted: an entry that declares none is reported as the different thing it is
         * rather than assumed equal.
         */
        private fun sameArtifact(held: RemoteArtifact, offered: RemoteArtifact): Boolean =
            if (held.sha256 != null && offered.sha256 != null) {
                held.sha256.equals(offered.sha256, ignoreCase = true)
            } else {
                held.url == offered.url && held.size == offered.size
            }
    }
}
