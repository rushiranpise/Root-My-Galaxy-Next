package dev.busung.s25uroot

import android.content.Context
import androidx.annotation.StringRes
import dev.busung.s25uroot.dfr.DfrInstall

/**
 * The three places this app's work can leave something behind, and what it may do about each.
 *
 * One directory was never the whole picture. `/data/local/tmp` is where a *run* stages, and it was read
 * on its own because it was the only place a sweep had to keep clean. The rest of the app leaves things
 * elsewhere, and each in a directory that matters for a different reason:
 *
 * - **`/data/local/tmp`** - one directory for the whole device, mode `0771`, so any app can reach a path
 *   inside it by name. A detector does not need root to read the names this app stages there.
 * - **`/data/system`** - the opposite: no app can read it, which is exactly why the flow that installs a
 *   system helper puts things there (the daemon the exploit execs, and the inject's own copy of
 *   `packages.xml`). Root, and only root, sees this list.
 * - **`/data/adb`** - KernelSU's own directory, which holds the installed daemon and the user's modules.
 *
 * ## Why they are read differently, and why the third is read-only
 *
 * The temp directory is checked by name *and* listed, because the names this app stages are what a
 * detector matches on and the listing is what catches a name this app does not know. `/data/system` holds
 * hundreds of files and not one of them is this app's to enumerate, so it is read by name from a
 * catalogue - the names the flows in this repository actually write, and the other install's two, which
 * are listed because they are almost certainly residue rather than anything in use.
 *
 * `/data/adb` is listed and nothing else. Every entry there belongs to the root implementation: `ksud` is
 * the daemon the whole device's root runs through and `modules/` is what the user installed into it. An
 * app that offered to delete from this list would be offering to break the root it just obtained, so the
 * screen shows it and says so - the one thing a residue list does not have to be is a delete button.
 */
internal enum class ResidueScope(
    /** The section's heading. */
    @StringRes val titleRes: Int,
    /** One line under it, for why this place matters. */
    @StringRes val bodyRes: Int,
    val path: String,
    /** Whether a row here can be removed from this app. */
    val deletable: Boolean,
    /**
     * Whether this directory is cleaned by emptying it, rather than by naming paths in it.
     *
     * True for the temp directory alone. It is the one place holding names this app cannot account for,
     * so there is no catalogue to delete from: everything there belongs to somebody, and the sweep that
     * empties it takes the names it did not write along with its own. Everywhere else a delete names
     * paths, which is what keeps the rest of a platform directory - `/data/system` above all - out of it,
     * and why the button that names paths must not be described as emptying the directory.
     */
    val emptiedByGlob: Boolean,
    /** What a listed name here is, for the row that names it. */
    @StringRes val roleRes: Int,
) {
    TempDirectory(
        titleRes = R.string.residue_scope_temp,
        bodyRes = R.string.residue_scope_temp_body,
        path = StagedResidue.DIRECTORY,
        deletable = true,
        emptiedByGlob = true,
        roleRes = R.string.residue_role_other,
    ),
    SystemDirectory(
        titleRes = R.string.residue_scope_helper,
        bodyRes = R.string.residue_scope_helper_body,
        path = SYSTEM_DIRECTORY,
        deletable = true,
        emptiedByGlob = false,
        roleRes = R.string.residue_role_system,
    ),
    AdbDirectory(
        titleRes = R.string.residue_scope_adb,
        bodyRes = R.string.residue_scope_adb_body,
        path = ADB_DIRECTORY,
        deletable = false,
        emptiedByGlob = false,
        roleRes = R.string.residue_role_kernelsu,
    ),
    ;
}

/** The root-only directory the system-uid flow works in. */
internal const val SYSTEM_DIRECTORY = "/data/system"

/** KernelSU's own directory, which this app reads and never writes. */
internal const val ADB_DIRECTORY = "/data/adb"

/**
 * The daemon the system-uid helper stages for the exploit to exec.
 *
 * The same path as `KsudStage.DEST` in the `:dfr` module, which is a different APK that shares no code
 * with this one - so the two strings are held together by a test that reads both sources rather than by
 * either side being able to see the other.
 */
internal const val SYSTEM_STAGED_DAEMON = "$SYSTEM_DIRECTORY/rmgnext-ksud"

/**
 * The second copy of that daemon, for the one reader that cannot reach the first.
 *
 * The system-uid helper runs *inside* `system_server`, whose SELinux context may not read `shell_data_file`
 * - so the copy this app stages in `/data/local/tmp` is a file the helper can `stat` and cannot `open`.
 * That is not a permission this app can grant its way around: the type is on the directory, and the
 * directory is the shell user's. So the daemon is left a second time beside the first, under the same
 * `system_data_file` type the helper already writes to, where its own context may read it.
 *
 * The same path as `KsudStage.STAGED_BY_THE_APP` in the `:dfr` module, held there the way
 * [SYSTEM_STAGED_DAEMON] is held against `KsudStage.DEST` - by a test that reads both sources.
 */
internal const val SYSTEM_HELPER_DAEMON = "$SYSTEM_DIRECTORY/rmgnext-ksud.src"

/** The daemon DFReroot stages, which is its own name for its own flow. */
internal const val DFREROOT_STAGED_DAEMON = "$SYSTEM_DIRECTORY/dfreroot-ksud"

/** DFReroot's pre-inject copy of `packages.xml`, under its own suffix. */
internal const val DFREROOT_PACKAGES_BACKUP = "$SYSTEM_DIRECTORY/packages.xml.bak-df-installer"

/**
 * What this app's flows write into `/data/system`, plus the other install's two files.
 *
 * Read from the injector's own constants where they exist: the paths an inject writes are named in
 * `PackagesXml`, and a list that typed them out again could stop naming the file it writes. The two
 * daemon paths have no such neighbour - they are compiled into a different module's Kotlin - so they are
 * stated here as the app writes them and guarded by a test against that module's source.
 *
 * The other install's two are included because on any phone that ran DFReroot they are sitting there: a
 * six-megabyte daemon and a full copy of `packages.xml` from before it touched the file. Neither is this
 * app's, both are residue by any reading, and a list that omitted them would report a clean
 * `/data/system` beside a phone that has them.
 */
internal val SYSTEM_RESIDUE_PATHS: List<StagedPath> = listOf(
    StagedPath(DfrInstall.leftoverPaths[0], ResidueRole.Backup),
    StagedPath(DfrInstall.leftoverPaths[1], ResidueRole.Backup),
    StagedPath(SYSTEM_STAGED_DAEMON, ResidueRole.Daemon),
    StagedPath(SYSTEM_HELPER_DAEMON, ResidueRole.Daemon),
    StagedPath(DFREROOT_STAGED_DAEMON, ResidueRole.OtherInstall),
    StagedPath(DFREROOT_PACKAGES_BACKUP, ResidueRole.OtherInstall),
)

/**
 * One directory's reading, in the two shapes the directories come in.
 *
 * [named] is a catalogue this app can account for - every entry is a path some code in this repository
 * writes - and [entries] is a listing, which is names and nothing else. A scope uses one or the other,
 * and both are stat'd the same way, so a file that could not be read is reported as no answer rather
 * than as an absence whichever route found it.
 */
internal class ResidueSection(
    val scope: ResidueScope,
    val named: List<ResidueFinding> = emptyList(),
    val entries: List<TempEntry> = emptyList(),
    /**
     * Whether the directory was listed. False is the ordinary state with no shell answering, and it
     * changes what the section may claim: a clean answer taken by name is not a clean directory.
     */
    val listed: Boolean = true,
) {

    /** What is actually there, of each kind. */
    val presentNamed: List<ResidueFinding> get() = named.filter { it.reading is ResidueReading.Present }

    val presentEntries: List<TempEntry> get() = entries.filter { it.reading is ResidueReading.Present }

    /**
     * What the section shows: everything that is there, and everything it could not look at.
     *
     * A catalogued path that is [ResidueReading.Gone] is left out - a list of the paths this app writes,
     * most of which are absent on most phones, would bury the one that is not. Everything else is shown,
     * because "this app cannot read it" is a fact about the device rather than an absence, and it is the
     * one fact this whole reading exists to report correctly.
     */
    val visibleNamed: List<ResidueFinding> get() = named.filterNot { it.reading is ResidueReading.Gone }

    val visibleEntries: List<TempEntry> get() = entries

    /** The entries here that were found but could not be described, which is not the same as absent. */
    val unreadableNamed: List<ResidueFinding>
        get() = named.filter { it.reading is ResidueReading.Unreadable }

    val unreadableEntries: List<TempEntry>
        get() = entries.filter { it.reading is ResidueReading.Unreadable }

    /**
     * Whether this directory has anything in it at all.
     *
     * An entry that is there and unreadable counts, and that is the point: `/data/adb` is mode 0700 root
     * and every name inside it stats as denied to this app, so a section that counted only what it could
     * describe would report the directory holding the installed daemon as empty.
     */
    val anything: Boolean
        get() = visibleNamed.isNotEmpty() || visibleEntries.isNotEmpty()

    /** Everything found here, added up. */
    val totalBytes: Long
        get() = presentNamed.sumOf { (it.reading as ResidueReading.Present).sizeBytes } +
            presentEntries.sumOf { (it.reading as ResidueReading.Present).sizeBytes }

    /**
     * The paths here a delete should name, which is empty for a scope that is read-only.
     *
     * Only what is present: `rm -f` is silent about a path that was never there, so naming the absent
     * ones would put a list of paths in the command that the answer then reports as "not found" - a
     * complaint about a file nobody ever had.
     */
    val deletablePaths: List<String>
        get() = if (!scope.deletable) {
            emptyList()
        } else {
            (presentNamed.map { it.staged.path } + unreadableNamed.map { it.staged.path }) +
                (presentEntries.map { it.path } + unreadableEntries.map { it.path })
        }
}

/**
 * The second line of a folder's heading, as a string and its arguments.
 *
 * A string resource rather than a string because this is decided where there is no `Context`: the choice
 * of sentence is the part that has to be right - "empty" and "could not be listed" are different claims
 * about the same directory - and assembling it here is what lets both be tested.
 */
internal data class ResidueFolderSummary(@StringRes val res: Int, val args: List<Any> = emptyList())

/**
 * What a folder holds, said in one line, for the heading that is closed over it.
 *
 * This is the line that makes a collapsed list worth having: three folders that each answer "what is in
 * here" let the one a detector found something in stand out, where a list that opened itself would bury
 * it. The count is everything the folder is showing - an entry that could not be read included, because
 * "unreadable" is a fact about the device and an absence is not.
 *
 * The empty cases stay distinct on purpose. A listed directory can say it is empty; one read by name can
 * only say that none of the paths this app writes is there; and a directory that could not be listed says
 * neither, because nothing was looked at. Those are three different sentences and one word - "clean" -
 * would collapse them.
 */
internal fun ResidueSection.folderSummary(): ResidueFolderSummary = when {
    anything -> ResidueFolderSummary(
        R.string.residue_folder_tally,
        listOf(visibleNamed.size + visibleEntries.size, StagedResidue.sizeLabel(totalBytes)),
    )
    !listed -> ResidueFolderSummary(R.string.residue_scope_unlisted, listOf(scope.path))
    named.isEmpty() -> ResidueFolderSummary(R.string.residue_scope_clean_listed)
    else -> ResidueFolderSummary(R.string.residue_scope_clean, listOf(named.size))
}

/**
 * The same line for the temp directory, which keeps its own report.
 *
 * A listing and a catalogue in one folder, so "anything" here is the two of them together - and the
 * by-name wording is chosen for a directory that could not be listed, which is the one case where a clean
 * temp directory is a weaker claim than it looks.
 */
internal fun ResidueReport.folderSummary(): ResidueFolderSummary = when {
    present.isNotEmpty() || extras.isNotEmpty() -> ResidueFolderSummary(
        R.string.residue_folder_tally,
        listOf(present.size + extras.size, StagedResidue.sizeLabel(totalBytes)),
    )
    directoryListed -> ResidueFolderSummary(R.string.residue_scope_clean_listed)
    else -> ResidueFolderSummary(R.string.residue_scope_clean, listOf(findings.size))
}

/**
 * Everywhere this app has left something, as one reading.
 *
 * The temp directory keeps its own report rather than being flattened into a section: it is the one place
 * with five possible verdicts - clean, clean by name, blind, and the two two-part cases - and folding that
 * into a boolean would throw away the distinction this app has spent the most words on getting right.
 */
internal class ResidueSurvey(
    val temp: ResidueReport,
    val sections: List<ResidueSection>,
    /**
     * Whether the app this fork came from is installed.
     *
     * It changes nothing about the reading and one thing about the dialog: five paths in the temp
     * directory are the payload's rather than either app's, so while that install is present, a row there
     * may be a file another app is about to execute rather than this app's leftovers.
     */
    val siblingPresent: Boolean = false,
) {

    /** Whether anything at all was found, in any of the three. */
    val anything: Boolean
        get() = temp.present.isNotEmpty() || temp.extras.isNotEmpty() || sections.any { it.anything }

    /** Everything found, added up across the three. */
    val totalBytes: Long get() = temp.totalBytes + sections.sumOf { it.totalBytes }

    /**
     * The paths a "delete all" names, across every scope that allows deletion.
     *
     * The temp directory is not in here: emptying it is its own command, because it removes names this
     * app cannot account for as well - see [StagingSweep.clear]. This is the other half, the paths the
     * catalogues name.
     */
    val deletablePaths: List<String> get() = sections.flatMap { it.deletablePaths }

    /**
     * The card's value.
     *
     * One number when there is anything anywhere, and the temp directory's own sentence when there is
     * not - because that sentence is the only one that says *how* it knows (an empty directory, or a
     * check that could only be made by name), and a screen that replaced it with a bare "Clean" would be
     * claiming the stronger of the two readings on a device that only earned the weaker one.
     */
    fun summaryLine(context: Context): String =
        if (anything) StagedResidue.sizeLabel(totalBytes) else temp.summaryLine(context)

    /**
     * The reading, with the names, for the app log.
     *
     * One line per place, and only for the places with something in them: three lines saying nothing was
     * found would be the noise that makes the one that matters hard to find.
     */
    fun logLines(context: Context): List<String> = buildList {
        if (temp.present.isNotEmpty() || temp.extras.isNotEmpty()) add(temp.logLine(context))
        sections.filter { it.anything }.forEach { section ->
            add(
                context.getString(
                    R.string.residue_section_log,
                    section.scope.path,
                    section.visibleNamed.size + section.visibleEntries.size,
                    StagedResidue.sizeLabel(section.totalBytes),
                    namesIn(section),
                ),
            )
        }
    }

    /** The names a section's line carries, so a log entry is actionable rather than a count. */
    private fun namesIn(section: ResidueSection): String =
        (section.visibleNamed.map { it.staged.name } + section.visibleEntries.map { it.name })
            .joinToString(", ")
}

/** The readings for the two directories that are not the temp directory. */
internal object ResidueScopes {

    /** The scopes that are read as sections beside the temp directory, in the order they are shown. */
    val sections: List<ResidueScope> = listOf(ResidueScope.SystemDirectory, ResidueScope.AdbDirectory)

    /**
     * One directory's reading.
     *
     * `/data/system` is read by name only, and that is deliberate rather than a shortcut: it holds
     * hundreds of entries belonging to the platform and to every app on the phone, and listing it would
     * answer a question nobody asked while burying the two files that matter.
     *
     * `/data/adb` is read the other way round: listed, because it is small, and with no catalogue,
     * because none of it is this app's.
     */
    fun read(scope: ResidueScope): ResidueSection = when (scope) {
        ResidueScope.TempDirectory -> error("the temp directory has its own report")
        ResidueScope.SystemDirectory -> ResidueSection(
            scope = scope,
            named = SYSTEM_RESIDUE_PATHS.map { path ->
                ResidueFinding(path, StagedResidue.statReading(path.path).reading)
            },
        )
        ResidueScope.AdbDirectory -> {
            val listed = StagedDirectory.list(scope.path)
            ResidueSection(scope, entries = listed.orEmpty(), listed = listed != null)
        }
    }

    /** Reads both of them, for the screen that shows them beside the temp directory. */
    fun readAll(): List<ResidueSection> = sections.map(::read)
}

/**
 * Reads everything, in one call, for the Settings card and the dialog behind it.
 *
 * Blocking, and two shells deep at worst: the temp listing asks for one, and `/data/adb` asks for
 * another. Both callers already run this off the main thread, and the card is built on a reading that
 * was taken before it was drawn rather than on one it waits for.
 */
internal fun StagedResidue.survey(context: Context): ResidueSurvey = ResidueSurvey(
    temp = read(),
    sections = ResidueScopes.readAll(),
    siblingPresent = SiblingInstall.isPresent(context),
)
