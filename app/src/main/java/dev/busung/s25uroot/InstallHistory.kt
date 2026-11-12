package dev.busung.s25uroot

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class InstallRunResult {
    Running,
    Succeeded,

    /**
     * The exploit got root and the run was told not to load KernelSU, so root was all it produced.
     *
     * Its own result rather than Succeeded, because the two mean different things to whoever reads the
     * history later: one says the device is rooted with KernelSU, the other says the payload worked and
     * nothing was loaded on top of it.
     */
    RootOnly,
    Failed,

    /**
     * The user stopped the run before it finished.
     *
     * Not [Failed]: nothing about the run went wrong, and whoever reads the history later is asking
     * which runs did not complete rather than which ones broke. The stage it was stopped in is recorded
     * with it, because that is the part with consequences - a payload stopped mid-exploit may still be
     * running on the device.
     */
    Stopped,
}

data class InstallHistoryEntry(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val result: InstallRunResult,
    val log: String,
    val profileId: String? = null,
    /**
     * The catalog a run took its payload from, and the commit it was read at. Recorded because the
     * same payload id can be offered by several sources: without this a result can say which
     * payload ran but not which catalog defined it.
     */
    val sourceId: String? = null,
    val sourceLabel: String? = null,
    val sourceCommit: String? = null,
    val usedShizuku: Boolean = false,
    /**
     * Where a failed run stopped, so the detail screen can say more than "Failed".
     *
     * A [FailureStage] rather than one flow's enumeration: the payload flow's steps and the universal root's are
     * different steps, and which one a record holds depends only on which flow the run was - see [RunKind].
     */
    val failureStage: FailureStage? = null,
    val failureReason: String? = null,
    /**
     * Where a run in flight has got to, written as it moves and cleared when it ends.
     *
     * The log is what a reader of the record gets; this is the part a *bar* needs, and it is here for one
     * case: a run in another process - the boot gate's - cannot be shown by any screen in the UI process,
     * whose run screen draws its progress from the view model. Publishing the phase is what lets that screen
     * follow a run it does not own and draw the same bar, instead of sending the reader to a log.
     *
     * Null for a run that has ended, and for every record written before this existed: a record with no phase
     * is one nothing can draw a bar for, which is not the same as a run at its first step.
     */
    val phase: InstallPhase? = null,
    /**
     * Which flow the run was - see [RunKind].
     *
     * Recorded for the same reason the phase is: a run is read back by a screen that did not start it, and
     * what that screen says about it is drawn from the record. Without this the two flows are one, and a
     * universal run is read back as a payload run - which draws the payload flow's four steps over a run that
     * fetched no payload, and offers the payload flow's Retry on a run that cannot be retried in this boot.
     *
     * **Null is not a payload run.** It is "this record does not say which flow it was", which is the honest
     * reading of every entry written before the flow joined the record - and it is not a detail: one of those
     * runs on this phone was a DirtyFrag run, so a row that named the payload flow's exploit over it would be
     * this app stating a fact about a real run that the file does not contain. Only [decode] can produce the
     * null; every entry the app writes passes a kind, so this is a question about reading old files and not
     * about what a run is.
     */
    val kind: RunKind? = RunKind.Payload,
    /**
     * How long the run took to root the phone, or null for one that did not, or that predates this.
     *
     * The interval from the exploit starting to root being confirmed - see [RootStopwatch] - and the one fact
     * in this record that is a *measurement* rather than a timestamp. It is what makes two runs comparable: the
     * clock times above say when they happened, and this says which one was quicker.
     *
     * Both flows record it, because both pass through the same two phases to get here.
     */
    val rootedInMillis: Long? = null,
) {
    /**
     * The exploit this record's run spent its time on.
     *
     * Read from the record rather than from [kind] alone, because timer runs were written as [RunKind.Payload]
     * before the timer flow had a name of its own: their profile id is the only thing that still says which CVE
     * they ran. Newer records say it in [kind], and this is the same answer.
     *
     * Only call this for a record whose kind is present. A null kind means the file does not say, and naming the
     * payload flow over it would state a fact the record does not contain.
     */
    val flowName: String
        get() {
            val flow = kind ?: return RunKind.Payload.flowName
            val timer = flow == RunKind.Payload &&
                profileId?.contains(TimerVulnerability.CVE, ignoreCase = true) == true
            return if (timer) RunKind.Timer.flowName else flow.flowName
        }
}

/**
 * The entries a launch closes, given the run that is still in flight.
 *
 * Pure, so the rule can be checked without a device or a filesystem - and the rule is worth checking, because
 * getting it wrong in one direction leaves a list claiming an install that is not happening and in the other
 * marks a run that is going perfectly well as failed.
 *
 * [holder] is the record two processes share. An entry still marked [InstallRunResult.Running] is a run that
 * died with its process - unless it is the entry that record names, which is the run that is still writing it.
 */
internal fun List<InstallHistoryEntry>.closingInterruptedRuns(
    holder: RunHolder?,
): List<InstallHistoryEntry> = map { entry ->
    if (entry.result == InstallRunResult.Running && entry.id != holder?.entryId) {
        entry.copy(
            completedAtMillis = System.currentTimeMillis(),
            result = InstallRunResult.Failed,
        )
    } else {
        entry
    }
}

class InstallHistoryStore(private val context: Context) {
    private val directory = File(context.filesDir, "install-history").apply { mkdirs() }

    fun load(): List<InstallHistoryEntry> = directory
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull(::decodeOrQuarantine)
        .sortedByDescending(InstallHistoryEntry::startedAtMillis)

    /**
     * Closes the runs that were interrupted, and leaves the one that is still going alone.
     *
     * An entry still marked [InstallRunResult.Running] when nothing is running is a run that died with its
     * process: closing it is what keeps the list from claiming an install that is not happening.
     *
     * [holder] is what keeps that from being wrong in the other direction. A run can be started by this
     * app's other process - the boot gate installs from `:autoroot_gate` - so opening the app while one is
     * in flight used to close *that* run's entry and mark it failed, on a run that was still going and
     * about to write its own result. The record names the entry its owner is writing, so the entry a live
     * run owns is the one entry this must not touch; when the record names no entry, nothing is spared and
     * every unfinished one is closed as before.
     */
    internal fun closeInterruptedRuns(holder: RunHolder? = null): List<InstallHistoryEntry> {
        val stored = load()
        val closed = stored.closingInterruptedRuns(holder)
        closed.forEachIndexed { index, entry -> if (entry != stored[index]) save(entry) }
        return closed
    }

    fun create(): InstallHistoryEntry = InstallHistoryEntry(
        id = UUID.randomUUID().toString(),
        startedAtMillis = System.currentTimeMillis(),
        completedAtMillis = null,
        result = InstallRunResult.Running,
        log = "",
        usedShizuku = AppPreferences.shizukuMode(context),
    ).also(::save)

    /**
     * One entry, by its id.
     *
     * For the screen that follows a run: it is handed the id a notification named and needs the record, and
     * reading the whole directory once a second to pick one file out of it would be a scan per tick.
     */
    fun entry(id: String): InstallHistoryEntry? =
        File(directory, "$id.json").takeIf(File::isFile)?.let(::decodeOrQuarantine)

    fun save(entry: InstallHistoryEntry) {
        val target = File(directory, "${entry.id}.json")
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(entry).toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
    }

    private fun encode(entry: InstallHistoryEntry) = JSONObject()
        .put("id", entry.id)
        .put("startedAtMillis", entry.startedAtMillis)
        .put("completedAtMillis", entry.completedAtMillis ?: JSONObject.NULL)
        .put("result", entry.result.name)
        .put("log", entry.log)
        .put("profileId", entry.profileId ?: JSONObject.NULL)
        .put("sourceId", entry.sourceId ?: JSONObject.NULL)
        .put("sourceLabel", entry.sourceLabel ?: JSONObject.NULL)
        .put("sourceCommit", entry.sourceCommit ?: JSONObject.NULL)
        .put("usedShizuku", entry.usedShizuku)
        .put("failureStage", entry.failureStage?.name ?: JSONObject.NULL)
        .put("failureReason", entry.failureReason ?: JSONObject.NULL)
        .put("phase", entry.phase?.name ?: JSONObject.NULL)
        // Absent rather than written as a default when a record does not say, so a file this app rewrites
        // cannot invent a flow for a run it did not record.
        .put("kind", entry.kind?.name)
        .put("rootedInMillis", entry.rootedInMillis ?: JSONObject.NULL)

    private fun decodeOrQuarantine(file: File): InstallHistoryEntry? = try {
        decode(AtomicFile(file).openRead().use { it.readBytes() })
    } catch (_: Throwable) {
        val quarantined = File(directory, "${file.name}.corrupt")
        quarantined.delete()
        file.renameTo(quarantined)
        null
    }

    private fun decode(bytes: ByteArray): InstallHistoryEntry {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        return InstallHistoryEntry(
            id = value.getString("id"),
            startedAtMillis = value.getLong("startedAtMillis"),
            completedAtMillis = if (value.isNull("completedAtMillis")) {
                null
            } else {
                value.getLong("completedAtMillis")
            },
            result = InstallRunResult.valueOf(value.getString("result")),
            log = value.getString("log"),
            profileId = value.optionalString("profileId"),
            sourceId = value.optionalString("sourceId"),
            sourceLabel = value.optionalString("sourceLabel"),
            sourceCommit = value.optionalString("sourceCommit"),
            usedShizuku = value.optBoolean("usedShizuku", false),
            failureStage = FailureStage.fromName(value.optionalString("failureStage")),
            failureReason = value.optionalString("failureReason"),
            // Read the same way, and forgiving for the same reason: a phase this build does not know is a
            // record written by another one, and a bar that cannot be drawn is not a record that cannot be read.
            phase = value.optionalString("phase")
                ?.let { name -> InstallPhase.entries.firstOrNull { it.name == name } },
            // And the flow, read the same forgiving way - with one difference: an *absent* field stays absent
            // (null) rather than becoming a payload run. An unknown name still falls back to the payload run,
            // because a record that cannot be read is a run that is hidden; a record that never said is a
            // record that never said, and the two are told apart here so a screen can draw the difference.
            kind = value.optionalString("kind")?.let { RunKind.fromName(it) },
            // Absent in every record written before this, and in one whose run did not root the phone: both
            // read as "no measurement" rather than as zero, which a duration of 0 could not be told from.
            rootedInMillis = if (value.isNull("rootedInMillis")) {
                null
            } else {
                value.getLong("rootedInMillis").takeIf { it >= 0L }
            },
        )
    }

    /**
     * A key that this store writes as JSON null when it has no value. `optString` would hand back
     * the text "null" for those, which is a value the rest of the app would then try to display.
     */
    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
}
