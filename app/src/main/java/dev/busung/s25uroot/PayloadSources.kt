package dev.busung.s25uroot

/**
 * A GitHub repository serving a payload catalog. Several sources can be configured at once;
 * each is fetched on its own and every target keeps the identity of the source that provided
 * it, so two sources may offer the same payload without either one shadowing the other.
 */
data class PayloadSource(
    val repository: String,
    /** Branch, tag, or commit that [pinnedCommit] was resolved from, and the ref followed when unpinned. */
    val branch: String,
    val enabled: Boolean = true,
    /**
     * Full commit SHA this source is frozen at, or empty to follow [branch] on every load.
     *
     * A pin is what stops a catalog changing under a test: the branch head is resolved once, at the
     * moment it is pinned, and every later load reads that revision instead. It also means a pinned
     * source needs no GitHub API call to load, so it keeps working when the API is rate limited.
     */
    val pinnedCommit: String = "",
) {
    val isPinned: Boolean
        get() = pinnedCommit.isNotEmpty()

    /**
     * Identifies the catalog, not just the repository: a pinned revision and the branch it came from
     * are two different catalogs, so they can be configured side by side and compared.
     */
    val id: String
        get() = "$repository@${if (isPinned) pinnedCommit else branch}"

    val label: String
        get() = if (isPinned) "$repository @ $branch @ ${pinnedCommit.take(7)}" else "$repository @ $branch"

    /** How the ref line reads in the settings sheet. */
    val refLabel: String
        get() = if (isPinned) "$branch at ${pinnedCommit.take(7)}" else branch

    companion object {
        /**
         * This fork's payload catalog.
         *
         * Its own rather than the upstream one, because a fresh install has to be able to see the
         * flavours and builds this fork publishes - the upstream catalog never will. It is only the
         * default: a source list already saved on the device is what a run reads, and this changes
         * only what a device with no list yet starts from.
         */
        const val DEFAULT_REPOSITORY = "rushiranpise/Root-My-Galaxy-Payloads"
        const val DEFAULT_BRANCH = "main"

        /**
         * The catalog this fork's payloads were copied from, artifacts and all.
         *
         * Named here because a manifest records where its own artifacts live, and a fork's manifest is
         * the original one with a different owner: every entry it was copied with still names this
         * repository. A reader that only accepted the source's own prefix therefore refused the whole
         * catalog on its first artifact, which is what this fork's own feed did until this existed.
         * Reading one keeps the path and re-points it at the source that was actually read, so nothing
         * is ever fetched from here.
         */
        const val LEGACY_REPOSITORY = "BuSung-dev/Root-My-Galaxy-Payloads"
        const val LEGACY_BRANCH = "main"

        val COMMIT_PATTERN = Regex("^[0-9a-f]{40}$")

        val DEFAULT = PayloadSource(
            repository = DEFAULT_REPOSITORY,
            branch = DEFAULT_BRANCH,
            enabled = true,
        )

        fun isCommitValid(commit: String): Boolean = COMMIT_PATTERN.matches(commit.trim())

        /**
         * Builds a source from raw input, or null when either field is empty.
         *
         * What was typed is taken as written: a pattern cannot tell a repository that exists from one
         * that does not, and the only thing that can is reading it - which is what adding a source does.
         * A shape the reader cannot use comes back as that reader's own answer, naming what it could not
         * reach, rather than as a rule about the field before anyone has tried.
         */
        fun create(
            repository: String,
            branch: String,
            enabled: Boolean = true,
        ): PayloadSource? {
            val owner = repository.trim()
            val ref = branch.trim()
            if (owner.isEmpty() || ref.isEmpty()) return null
            return if (isCommitValid(ref)) {
                PayloadSource(owner, ref, enabled, pinnedCommit = ref)
            } else {
                PayloadSource(owner, ref, enabled)
            }
        }
    }
}

private const val SELECTION_SEPARATOR = "|"

/**
 * Identifies a target across sources. A source id cannot contain the separator (it is built
 * from the repository and branch patterns), so the first separator always splits the two parts.
 */
fun selectionIdFor(sourceId: String, profileId: String): String =
    if (sourceId.isEmpty()) profileId else "$sourceId$SELECTION_SEPARATOR$profileId"

fun sourceFromSelectionId(selectionId: String): String? {
    val index = selectionId.indexOf(SELECTION_SEPARATOR)
    return if (index <= 0) null else selectionId.substring(0, index)
}

fun profileFromSelectionId(selectionId: String): String {
    val index = selectionId.indexOf(SELECTION_SEPARATOR)
    return if (index <= 0) selectionId else selectionId.substring(index + 1)
}

fun List<PayloadSource>.enabledSources(): List<PayloadSource> = filter { it.enabled }

fun List<PayloadSource>.withSourceAdded(source: PayloadSource): List<PayloadSource> =
    if (any { it.id == source.id }) this else this + source

fun List<PayloadSource>.withSourceRemoved(sourceId: String): List<PayloadSource> =
    filterNot { it.id == sourceId }

fun List<PayloadSource>.withSourceEnabled(
    sourceId: String,
    enabled: Boolean,
): List<PayloadSource> = map { if (it.id == sourceId) it.copy(enabled = enabled) else it }

/** Freezes a source at [commit]. Its id changes, since a pinned revision is a different catalog. */
fun List<PayloadSource>.withSourcePinned(
    sourceId: String,
    commit: String,
): List<PayloadSource> = map { if (it.id == sourceId) it.copy(pinnedCommit = commit) else it }

/** Puts a source back on its branch, resolving the ref again on every load. */
fun List<PayloadSource>.withSourceUnpinned(sourceId: String): List<PayloadSource> =
    map { if (it.id == sourceId) it.copy(pinnedCommit = "") else it }
