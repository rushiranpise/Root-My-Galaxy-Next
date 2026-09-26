package dev.busung.s25uroot

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.busung.s25uroot.dfr.DfrApk
import dev.busung.s25uroot.dfr.DfrCleanUpOutcome
import dev.busung.s25uroot.dfr.DfrFlow
import dev.busung.s25uroot.dfr.DfrHelperAvailability
import dev.busung.s25uroot.dfr.DfrInstall
import dev.busung.s25uroot.dfr.DfrMode
import dev.busung.s25uroot.dfr.DfrProbe
import dev.busung.s25uroot.dfr.DfrStageReading
import dev.busung.s25uroot.dfr.DfrState
import dev.busung.s25uroot.dfr.DfrStep
import dev.busung.s25uroot.dfr.StageTwoBuild
import dev.busung.s25uroot.dfr.StageTwoBuildReading
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The flow that ends with root arriving without the exploit, driven from here.
 *
 * This is the same mechanism the app has always had - two edits and two reboots - and what changed is who
 * does them. All six steps are one command each, four of which this app can run, so the screen reads the
 * phone, names the step it is on, and offers that step's action instead of describing the order and
 * leaving it to the user. The two reboots are the app's too, on the soft-reboot path it already uses.
 *
 * **What it can still do with no root.** The readings and three of the actions here are commands the
 * `shell` user holds by itself, so they are taken through Shizuku when KernelSU answers nothing - and that
 * matters most on this screen of all of them, because the phone this flow produces has no root at all after
 * a reboot. Opening the helper by hand is the press that puts root back, removing it is a delete the shell
 * user holds, and installing it is `pm install` - which is how `adb install` works with no root either - so
 * a phone whose helper is another build's, or missing altogether, can be repaired here before it has ever
 * been rerooted. The two writes that genuinely need root are the inject and the key removal, and they still
 * refuse with a sentence rather than being retried somewhere they cannot land.
 *
 * The step list is shown in full, because the value of this screen is knowing where you are: five of these
 * six steps fail in a way that looks like a different problem, and the one that fails silently - an app
 * installed under a shared user before its key was in the list - is indistinguishable from success
 * without the third row.
 *
 * ## What is measured, and what is remembered
 *
 * "Is it injected", "is it installed", "which uid does it run as" and "are the hooks armed" are read from
 * Package Manager and the kernel, every time this opens. The only remembered things are the two instants
 * this app acted at, and they are used for one question - whether a reboot has happened since - because
 * nothing on the device records that.
 */
@Composable
internal fun DfrInstallDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var reading by remember { mutableStateOf<DfrReading?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    // The stage two this app ships, resolved on the IO thread rather than at composition because it is
    // unpacked out of the app's own assets.
    var bundledApk by remember { mutableStateOf<File?>(null) }
    // Why there is no file, when there is none: the two refusals differ in what they tell somebody to do,
    // so which one this is has to survive the read rather than be assumed from the missing file.
    var helperRefusal by remember { mutableStateOf<DfrHelperAvailability?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    // The clean-up asks first: it is the one action here that writes a file the phone boots from, and it
    // does two unrelated things, so which of them this press will take off is named before it runs.
    var confirmCleanUp by remember { mutableStateOf(false) }

    /**
     * The sentence for the refusal this build is in.
     *
     * Taken from the reason the read recorded rather than from the missing file, because an absent helper
     * is the one case here with two answers - and both actions that can be pressed name it, so no two
     * lines about the same disk can end up disagreeing about why there is nothing to install.
     */
    fun helperRefusalRes(): Int = when (helperRefusal) {
        DfrHelperAvailability.Unwritable -> R.string.dfr_helper_unwritable
        else -> R.string.dfr_no_helper
    }

    fun refresh() {
        busy = true
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                DfrApk.discardPickedCopy(context)
                val helper = DfrApk.bundled(context)
                bundledApk = helper.file
                helperRefusal = helper.availability.takeIf { it != DfrHelperAvailability.Ready }
                readState(context, helper.availability, helper.file)
            }
            reading = next
            readFailed = next == null
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun act(name: String, block: suspend () -> String) {
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { block() }
            log = outcome
            AppLog.info(
                AppLogTags.KERNEL_SU,
                "system uid flow: $name - ${outcome.lineSequence().firstOrNull().orEmpty()}",
            )
            refresh()
        }
    }

    // Every earlier record is written over by this one: an inject puts the key back in the file, so there
    // is no longer a removal for a restart to apply.
    fun inject() = act("inject") {
        // Refused here as well as by the step the screen shows, because this is the action that writes to
        // the file the phone boots from: with no helper in the APK, the inject would put a certificate into
        // android.uid.system that nothing on the device can spend, and undoing it is another flow. The
        // sentence names which of the two refusals this is, so a full disk is not reported as a missing APK.
        val helper = bundledApk ?: return@act context.getString(helperRefusalRes())
        // The bundled helper's own certificate, read from the file that will be installed rather than
        // assumed from this app's signer - the two are one key by construction, and the file is what
        // Package Manager will actually check the shared user against.
        val result = DfrInstall.run(context, DfrMode.Inject, apkPath = helper.absolutePath)
        if (result == null) return@act context.getString(R.string.dfr_no_root)
        // Stamped because the inject *ran*, not because it reported success. The stamp answers one
        // question - has this phone rebooted since the app last tried - and whether the write landed is
        // answered by reading the file, which the flow does. Keying the stamp on the injector's own
        // verdict is what made the flow skip its own reboot step: an inject that landed while the
        // command's output looked like a failure left a stamp from a previous boot, and an older stamp
        // reads as "already rebooted", so the flow went straight to the install.
        AppPreferences.setDfrInjectedAt(context, System.currentTimeMillis())
        AppPreferences.setDfrKeyRemovedAt(context, null)
        result.log
    }

    /**
     * The flavour's daemon, where the helper will look for it, with its line for the log.
     *
     * Done here rather than left to the helper because the helper cannot get it right: it runs as the
     * system uid, the installed daemon is mode 0700 root, and what is left to it is another app's copy
     * of some other KernelSU. See [DfrInstall.stageDaemon] for the measurement.
     */
    fun stageDaemon(): String {
        // Root only, and the one place in this screen where that is a fact about the command rather than a
        // shortcut: the staging ends by making the file system-owned at mode 0700, and a shell that cannot
        // chown would leave a daemon the module's policy refuses - which fails inside the exploit. So the
        // sentence names the step rather than the phone: the install below it does not need root at all.
        val action = DfrInstall.stageDaemon(context)
            ?: return context.getString(R.string.dfr_stage_no_root)
        AppLog.info(
            AppLogTags.KERNEL_SU,
            "system uid flow: stage daemon - ${action.log.lineSequence().lastOrNull().orEmpty()}",
        )
        return action.log
    }

    fun install() = act("install") {
        val file = bundledApk ?: return@act context.getString(helperRefusalRes())
        // Before the helper exists, because the helper is what would otherwise settle for the wrong
        // daemon - it keeps whatever it finds at that path.
        val staged = stageDaemon()
        // Through whichever shell this phone has, which is the difference between a phone that has rebooted
        // into no root being repairable here and that phone being told to come back with root: `pm install`
        // is the shell user's own permission, and the copy it needs is staged for it - see
        // [DfrInstall.installStageTwo].
        val action = DfrInstall.installStageTwo(file)
            ?: return@act listOf(staged, context.getString(R.string.dfr_no_shell)).joinToString("\n")
        // Stamped on the attempt, for the same reason as the inject above: `pm install` prints more than
        // one word beginning with Failure, and a stamp that only moves on a clean verdict leaves the
        // second reboot indistinguishable from one that has already happened.
        AppPreferences.setDfrInstalledAt(context, System.currentTimeMillis())
        listOf(staged, action.log).joinToString("\n")
    }

    fun removeStageTwo() = act("remove stage two") {
        // Through whichever shell this phone has, which on a boot with no root is Shizuku's: `pm uninstall`
        // is a delete the shell user holds, and the helper being removed is often refused to an app that
        // cannot escalate - so refusing this for want of root would be refusing it for a permission the
        // command never needed.
        val action = DfrInstall.uninstallStageTwo()
            ?: return@act context.getString(R.string.dfr_no_shell)
        if (action.ok) AppPreferences.setDfrInstalledAt(context, null)
        action.log
    }

    fun open() = act("open stage two") {
        // Rewritten on the way in as well as at the install: the helper keeps whatever it finds at that
        // path, so this call is what makes the daemon it uses the one for this boot - and for a phone
        // whose flavour was changed since the last run, this is the call that replaces the old one.
        //
        // Staged first even on a phone with no root, where it can only refuse: the refusal is a line in the
        // log this action already shows, and skipping the step would leave the one thing the run needs
        // unsaid. What matters is that the line under it is a launch rather than a second refusal - the
        // helper is what puts root back, so on a boot that has none this press is the way out of it.
        val staged = stageDaemon()
        // The flavour goes with it, because the helper's manager row and its one action are about the
        // KernelSU this phone is set to run - which is this side's fact. Opening the helper by hand and
        // getting a manager row about a flavour the next run would not load would be worse than no row.
        // And the window's colours go with it, so the screen that opens is drawn in the one the person
        // was already looking at: the helper cannot see this app's scheme, and its own theme is the
        // platform's. [HelperTint.value] is null if no screen has drawn yet, which is not a case this
        // press can get to - it is a button on one.
        val action = DfrInstall.launch(
            flavor = AppPreferences.kernelsuFlavor(context),
            tint = HelperTint.value,
        )
            ?: return@act listOf(staged, context.getString(R.string.dfr_no_shell)).joinToString("\n")
        listOf(staged, action.log).joinToString("\n")
    }

    fun reboot() = act("soft reboot") {
        val outcome = runRecoveryAction(context, RecoveryTool.SoftReboot)
        if (outcome.accepted) {
            context.getString(R.string.recovery_action_soft_reboot)
        } else {
            outcome.detail
        }
    }

    /**
     * The restart that makes a removal true, with the removal re-made underneath it.
     *
     * This is what the [DfrStep.ApplyRemoval] step's button does, and the reason it is not simply a
     * restart is the window between the two: a clean-up left `packages.xml` without our key, and Package
     * Manager's own rewrite of the file from its memory had the key back in it 45 s later - both measured,
     * see the report that made this action exist. A restart asked for after that boots the file *with* the
     * key in it, which is a restart that cannot help, and offering one is what the screen used to do.
     *
     * The two are **one root command** rather than two calls, and that is the correction this press
     * needed: a re-made removal followed by a separately-issued restart leaves the very window it is
     * closing, however fast the second call follows the first - and the window is not this app's to hold,
     * because the writer that matters is Package Manager's own, on its own schedule. One command has no room
     * in it for anything else. See [DfrInstall.removalAndRestartCommand] for the daemon being reached
     * directly here rather than through the recovery keeper, and for why a removal that did not get through
     * restarts nothing.
     *
     * The instant is stamped *before* the command runs, which is the one place in this screen where that is
     * right: the command ends by taking down the framework this app is running in, so an instant written
     * after the answer is an instant that is often never written - and a phone that restarts owes it whether
     * or not the app survived to say so. The one answer that can arrive and be wrong is the one where the
     * daemon was never reached, so that answer puts the previous value back.
     */
    fun applyRemoval() = act("apply removal") {
        val previous = AppPreferences.dfrKeyRemovedAt(context)
        AppPreferences.setDfrKeyRemovedAt(context, System.currentTimeMillis())
        // No answer at all, which on this action is what a restart in flight looks like as well as what a
        // phone with no root shell looks like - so the line says both rather than picking one.
        val result = DfrInstall.removeAndRestart(context)
            ?: return@act context.getString(R.string.dfr_removal_no_answer)
        if (!result.restartRequested) {
            AppPreferences.setDfrKeyRemovedAt(context, previous)
            return@act result.log
        }
        result.log
    }

    fun cleanUp() = act("clean up") {
        // The two things this flow put on the device: the key in the shared user, and the helper
        // installed under it. The files an inject leaves in /data/system - the pre-inject copy of
        // packages.xml and the staged copy a failed rename swap writes - are deliberately not touched
        // here. Deleting them is what the residue screen is for, where they are listed with everything
        // else the app left behind and can be removed one at a time or together.
        //
        // **The key comes out here and the helper does not, and that is the order the whole action is.**
        // `pm uninstall` is a Package Manager write, and Package Manager writes `packages.xml` from *its own
        // memory* - the copy it read at boot, which still holds our key - on its own schedule. Measured on
        // this device: a removal that had landed was back in the file 45 s later. Ordering the two commands
        // cannot beat a write neither of them issued and neither of them can wait for, so the helper waits
        // for the restart the key removal owes and the flow offers it as the step after that one - see
        // [DfrStep.RemoveStageTwoAfterCleanUp]. The earlier order did the opposite and produced a clean-up
        // that had to be run twice: the first press left the key in the file the phone booted from, because
        // the helper's own uninstall rewrote it after the removal, and only the second press - with nothing
        // left to uninstall - stuck.
        //
        // A file that was already clean is the case where there is no such restart to wait for, so the
        // helper comes off in this press after all: nothing was written to the file, so a Package Manager
        // write cannot undo anything.
        //
        // Each record is cleared by its own half of the undo and by nothing else - see
        // [DfrCleanUpOutcome]. A half that did not land, including both halves on a phone with no root
        // shell, leaves its record, so the next reading of this screen shows that step rather than the
        // first one.
        // Through the shell the command belongs to, so the half of a clean-up that does not need root can
        // land on a phone that has none: a phone that has not been rerooted yet can still have this app's
        // helper taken off it. The key half below is the one that still refuses there, and its record is
        // kept until it lands - see [DfrCleanUpOutcome].
        val removed = DfrInstall.run(context, DfrMode.Uninstall)
        // Read from the key's own verdict rather than from the helper half's result, because that result
        // answers null for "no shell" as well: one of those two states leaves a helper installed on purpose
        // and the other leaves one installed because nothing could take it off, and only the first is a
        // clean-up that goes on after the restart.
        val deferred = removed?.keyTakenOut == true
        val helper = if (deferred) null else DfrInstall.uninstallStageTwo()
        val outcome = DfrCleanUpOutcome.of(removed, helper)
        if (outcome.keyGone) AppPreferences.setDfrInjectedAt(context, null)
        if (outcome.helperGone) AppPreferences.setDfrInstalledAt(context, null)
        // Recorded only when this run changed the file: an uninstall that found nothing to remove has
        // nothing waiting on a restart. What it earns is [DfrStep.ApplyRemoval] - the key is out of the
        // file and still live in the Package Manager that started before the change.
        if (deferred) AppPreferences.setDfrKeyRemovedAt(context, System.currentTimeMillis())
        val log = removed?.log ?: context.getString(R.string.dfr_no_root)
        // Said out loud rather than left to the step list: the confirmation promised the helper, and a press
        // that does not touch it has to account for itself somewhere.
        if (deferred) {
            listOf(log, context.getString(R.string.dfr_clean_up_helper_waiting_run)).joinToString("\n")
        } else {
            log
        }
    }

    val step = reading?.step
    val index = step?.let { DfrFlow.order.indexOf(it) } ?: -1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dfr_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DFR_TEXT_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    when {
                        readFailed -> stringResource(R.string.dfr_step_read_detail)
                        step == null -> stringResource(R.string.dfr_reading)
                        index >= 0 -> stringResource(R.string.dfr_progress, index + 1, DfrFlow.order.size)
                        // A detour has no position in the order, so it is named rather than numbered - the
                        // header must not claim a progress the phone has not made.
                        else -> stringResource(step.label)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                DfrFlow.order.forEachIndexed { position, row ->
                    val marker = when {
                        index < 0 -> "○"
                        position < index -> "✓"
                        position == index -> "●"
                        else -> "○"
                    }
                    Text(
                        "$marker ${stringResource(row.label)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (position == index) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // The current step's own line, which is where the copy explains the one thing that is easy
                // to get wrong about it - and it is the same line whether the step was reached, refused or
                // could not be read at all.
                // Each step carries its own line, including the two that are about a helper rather than
                // about the phone: [DfrStep.StaleStageTwo] is not [DfrStep.InstallStageTwo] with a
                // different button, because "there is no helper" and "the helper is another build" are
                // different things to have to do something about, and only one of them is visible.
                val detail = when {
                    readFailed -> R.string.dfr_step_read_detail
                    step != null -> step.detail
                    else -> R.string.dfr_step_read_detail
                }
                Text(
                    stringResource(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // Three lines rather than two, for the same reason the refusal is two steps: "this app
                    // ships no helper" and "it ships one that could not be unpacked" send somebody to two
                    // different places, and the line under the step list is where that is read.
                    stringResource(
                        when (helperRefusal) {
                            DfrHelperAvailability.Unwritable -> R.string.dfr_apk_unwritable
                            DfrHelperAvailability.NotInBuild -> R.string.dfr_apk_default
                            else -> R.string.dfr_apk_bundled
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The panel holds the flow's evidence rather than only the last command's output. The
                // helper's two build numbers are the ground the step list stands on - the one judgement here
                // that is about two files rather than about the phone - and they were written down nowhere
                // but the app log, so a screen arguing about the step it chose could not be read against the
                // numbers it chose it from.
                //
                // One line per verdict rather than only the disagreement: "the helper is this build",
                // "nothing is installed to compare" and "the APK in this app could not be read" are
                // different answers that all leave the step list looking the same, and the numbers are
                // printed only where the verdict guarantees them - an absent helper has no code on the
                // phone, and an unreadable APK none in this app.
                val helperBuild = reading?.build?.let { build ->
                    when (build.verdict) {
                        // Both codes are non-null in these two by the comparison itself: it answers Absent
                        // on a missing installed build and Unreadable on a missing bundled one before it
                        // compares anything, so a code that is read here is a code that was read there.
                        StageTwoBuild.Current -> stringResource(
                            R.string.dfr_log_build_current,
                            build.installed ?: 0L,
                            build.bundled ?: 0L,
                        )
                        StageTwoBuild.Different -> stringResource(
                            R.string.dfr_log_build_different,
                            build.installed ?: 0L,
                            build.bundled ?: 0L,
                        )
                        StageTwoBuild.Absent -> stringResource(R.string.dfr_log_build_absent)
                        StageTwoBuild.Unreadable -> stringResource(
                            R.string.dfr_log_build_unreadable,
                            build.installed ?: 0L,
                        )
                    }
                }
                // What the device answered, as the three answers rather than only the step drawn from
                // them: the step is this app's conclusion, and a wrong conclusion is only arguable
                // against these. Only when something was measured - with no helper the flow refuses
                // before it opens a shell, so a line about the package, the certificate and the hooks
                // would be three claims nobody made, and the daemon's line would be a fourth.
                val measured = reading?.probe?.let { probe ->
                    stringResource(
                        R.string.dfr_measured,
                        // One answer, not a yes/no plus a qualifier: which identity the package has
                        // is only a question when there is a package, and "not installed (ordinary
                        // app)" is what a pair of fields says when nothing asks whether both apply.
                        stringResource(
                            when {
                                !probe.installed -> R.string.dfr_stage_not_installed
                                probe.isSystemUid -> R.string.dfr_stage_system
                                else -> R.string.dfr_stage_ordinary
                            },
                        ),
                        stringResource(
                            if (reading?.injected == true) R.string.dfr_yes else R.string.dfr_no,
                        ),
                        stringResource(if (probe.armed) R.string.dfr_yes else R.string.dfr_no),
                    )
                }
                // The daemon the next boot and the next run both need, in this panel for the same reason
                // the helper's builds are: what a press prints is what *its* staging attempt did, and the
                // state it started from is what a reader argues with. It also says the one thing no
                // action's output can - whether the file the next boot's late-load reads is already in
                // place, and when it is not, why. The words are the Settings readout's own, from the same
                // enum, so the two screens cannot come to different accounts of one file.
                val stage = reading?.stage?.let { stage ->
                    stringResource(
                        R.string.dfr_log_stage,
                        stringResource(stage.label),
                        stringResource(stage.detail),
                    )
                }
                // The readings in the order the step list is argued from - what the phone is, which
                // build's helper is on it, what the next boot's late-load will find - and then whatever
                // the press printed. The panel is shown when there is any of them: what a press printed
                // is not the only evidence this screen has, and waiting for one before saying what the
                // helper is would hide the readings behind an action nobody has taken yet.
                val panel = listOfNotNull(measured, helperBuild, stage, log).joinToString("\n")
                if (panel.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dfr_panel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A fixed height with its own scroll, which is what the install screen's log panel
                    // does and for the same reason: what the injector prints is a report of arbitrary
                    // length - its whole transform, then a verify line per target - and as plain text in
                    // this dialog it grew the screen until the step list and the actions were below the
                    // fold. The screen's job is to say which step the phone is on, so the panel - readings
                    // and output together - may not be what decides how tall it is.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DFR_PANEL_HEIGHT),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = panel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Every answer this screen has, in the one set the app shows answers in - see
            // [AppDialogActions] for the rows and the fills.
            //
            // The set is also what reports progress: only the answer the step the phone is on is waiting
            // for is [AppActionRole.Priority], so "press this next" is answered by the fill before any
            // label is read. Two answers are outside that rule on purpose - the clean-up is
            // [AppActionRole.Destructive] whatever the step is, because it is the one press here that
            // takes the flow off the phone, and the inject is disabled rather than quiet while the reading
            // says the key is already in the list, where an inject could only print a refusal.
            val enabled = !busy
            val asked = askedAction(step)
            fun roleFor(action: DfrAction): AppActionRole =
                if (action == asked) AppActionRole.Priority else AppActionRole.Standard
            AppDialogActions(
                listOfNotNull(
                    // First because it is the one that changes nothing: every other answer acts on what
                    // this one read. Not offered on a refusal, where the answer cannot change - nothing on
                    // the phone decides whether this build carries a helper.
                    if (step == DfrStep.NoHelper) {
                        null
                    } else {
                        AppAction(R.string.dfr_action_read_state, roleFor(DfrAction.Read), enabled) {
                            refresh()
                        }
                    },
                    AppAction(
                        R.string.dfr_inject,
                        roleFor(DfrAction.Inject),
                        enabled && reading?.injected != true,
                    ) { inject() },
                    // This one covers three steps - both restarts and the removal a restart applies -
                    // because they are one answer with one word in this screen: [DfrStep.ApplyRemoval] is
                    // a restart.
                    AppAction(R.string.dfr_action_reboot, roleFor(DfrAction.Reboot), enabled) {
                        // On the step that is a removal waiting for its restart, the restart carries the
                        // removal with it rather than trusting the file to still be clean - see
                        // [applyRemoval] for the two measurements that put it there.
                        if (step == DfrStep.ApplyRemoval) applyRemoval() else reboot()
                    },
                    // The stage-two answers share one cell rather than getting one each: no reading can
                    // have two of them true at once.
                    when (step) {
                        DfrStep.RemoveStageTwo -> AppAction(
                            R.string.dfr_action_remove_stage2,
                            roleFor(DfrAction.StageTwo),
                            enabled,
                        ) { removeStageTwo() }
                        // The same press with the other reason under it: this helper is not an install that
                        // landed wrong, it is the clean-up's own second half - the certificate is already out
                        // of the file and the phone has restarted, so there is nothing to reinstall and its
                        // own line says so rather than this list.
                        DfrStep.RemoveStageTwoAfterCleanUp -> AppAction(
                            R.string.dfr_action_remove_stage2,
                            roleFor(DfrAction.StageTwo),
                            enabled,
                        ) { removeStageTwo() }
                        // No condition on the helper here: a build without one never reaches this step,
                        // because the flow refuses before it - see [DfrStep.NoHelper].
                        //
                        // [DfrStep.StaleStageTwo] shares this answer because it is the same press: what
                        // differs between a phone with no helper and a phone with somebody else's is the
                        // reason in the step's own line, not the command. `pm install -r` over the copy
                        // that is there is how the second one is fixed, and it keeps the system uid the
                        // package was given when it was first installed.
                        DfrStep.InstallStageTwo, DfrStep.StaleStageTwo -> AppAction(
                            R.string.dfr_action_install_stage2,
                            roleFor(DfrAction.StageTwo),
                            enabled,
                        ) { install() }
                        DfrStep.OpenStageTwo -> AppAction(
                            R.string.dfr_action_open_stage2,
                            roleFor(DfrAction.StageTwo),
                            enabled,
                        ) { open() }
                        else -> null
                    },
                    AppAction(R.string.dfr_clean_up, AppActionRole.Destructive, enabled) {
                        confirmCleanUp = true
                    },
                    // Quiet by hand rather than by the rule: dismissing is never what a step waits for,
                    // so nothing can make this answer loud. It is the way out, not the way on.
                    AppAction(R.string.action_cancel, AppActionRole.Standard) {
                        clickHaptic(view)
                        onDismiss()
                    },
                ),
            )
        },
        // Cancel is in the set with everything else, so a second dismissal here would be the same press
        // twice.
        dismissButton = null,
    )

    if (confirmCleanUp) {
        // Read from the measurement the dialog already has rather than asked for again: the question is
        // about what is on the phone now, and a second reading could answer about a different phone than
        // the one the step list was drawn from.
        val removals = DfrFlow.cleanUpRemovals(
            keyInjected = reading?.injected,
            helperInstalled = reading?.probe?.installed == true,
        )
        val cleanUpAnswers = if (removals.isEmpty()) {
            listOf(
                AppAction(R.string.dfr_clean_up_close, AppActionRole.Priority) {
                    confirmCleanUp = false
                },
            )
        } else {
            listOf(
                AppAction(
                    label = R.string.dfr_clean_up_confirm,
                    role = AppActionRole.Destructive,
                    enabled = !busy,
                ) {
                    clickHaptic(view)
                    confirmCleanUp = false
                    cleanUp()
                },
                AppAction(R.string.action_cancel) { confirmCleanUp = false },
            )
        }
        AlertDialog(
            onDismissRequest = { confirmCleanUp = false },
            title = { Text(stringResource(R.string.dfr_clean_up_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (removals.isEmpty()) {
                        // Nothing to confirm: both answers are that it is not there, and a confirm button
                        // for a removal that would change nothing is how a refusal starts to look like an
                        // action. The row below is the one that can still tell somebody what will happen.
                        Text(stringResource(R.string.dfr_clean_up_nothing))
                    } else {
                        removals.forEach { removal ->
                            Text("\u2022 " + stringResource(removal))
                        }
                    }
                    Text(
                        stringResource(R.string.dfr_clean_up_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // Built as one set rather than branched inside the slot, because the two shapes are the same
            // question: with nothing to remove there is a single answer and it is the filled one, and with
            // something to remove there are two - the clean-up itself, which takes the helper and the key
            // off the phone and therefore wears the error colours, beside a cancel that is deliberately
            // live while a clean-up runs, so a slow one can still be abandoned.
            confirmButton = { AppDialogActions(cleanUpAnswers) },
            // One answer when there is nothing to remove: a Close beside a Cancel is the same press twice.
            dismissButton = null,
        )
    }
}

/**
 * How tall the dialog's own content may be before it scrolls.
 *
 * A cap rather than "whatever is left", because the answers are not in this area at all: they are the
 * AlertDialog's own, laid out below everything here, and [AppDialogActions] puts this screen's six of them
 * in two rows rather than six. 440 dp is what is left for the step list, the readings and the output once
 * those two rows, a title and the platform's padding have taken their share of the 780 dp screen this was
 * written against - and that difference is the reason the answers are a grid at all.
 */
private val DFR_TEXT_HEIGHT = 440.dp

/**
 * How much of the dialog the panel may take - the readings first, then what a press printed.
 *
 * Fixed rather than a maximum, because this is the one field here whose length nothing bounds: an injector
 * run prints its whole transform and a verify line per target, the clean-up prints two files' worth of it,
 * and the readings above those have no bound either - the daemon's line is a whole sentence. See the panel
 * in the dialog for what any of it looked like as plain text.
 */
private val DFR_PANEL_HEIGHT = 140.dp

/**
 * Which of the grid's actions a step is waiting for, or null when it is waiting for none of them.
 *
 * The two refusals give different answers and both are deliberate: a build that carries no helper has
 * nothing to press at all, which is why [DfrStep.NoHelper] is null, while an APK that is in the assets and
 * could not be unpacked is a phone to free space on - and the reading again that [DfrStep.HelperUnwritable]
 * asks for is what fixes it. The three stage-two steps share one action because they share one cell, and
 * the clean-up has no step at all: it is not part of the order, so it is never the answer this gives.
 */
private fun askedAction(step: DfrStep?): DfrAction? = when (step) {
    DfrStep.ReadState, DfrStep.HelperUnwritable -> DfrAction.Read
    DfrStep.Inject -> DfrAction.Inject
    DfrStep.Reboot, DfrStep.RebootAgain, DfrStep.ApplyRemoval -> DfrAction.Reboot
    DfrStep.RemoveStageTwo, DfrStep.RemoveStageTwoAfterCleanUp, DfrStep.InstallStageTwo,
    DfrStep.StaleStageTwo,
    DfrStep.OpenStageTwo,
    -> DfrAction.StageTwo
    else -> null
}

/**
 * The answers the grid offers, named so that a step's own answer can be compared against them.
 *
 * Deliberately not [DfrStep]: two of the grid's cells - the clean-up and cancel - are not steps of the
 * flow, and [StageTwo] is three steps that share one cell, so the mapping runs one way and this is what it
 * runs to. Which of these is the loud one is the dialog's decision, made from [askedAction].
 */
private enum class DfrAction { Read, Inject, Reboot, StageTwo }

/** The step the phone is on, with what was measured to decide it. */
private class DfrReading(
    val step: DfrStep,
    /**
     * What was measured, or null when nothing was: a build with no helper refuses before it opens a shell,
     * and a reading that invented a probe would put three unmeasured claims on the screen.
     */
    val probe: DfrProbe?,
    val injected: Boolean?,
    /**
     * The two helper builds, which are read alongside the probe because they are what decides whether the
     * helper on the phone can be driven at all - see [DfrStep.StaleStageTwo].
     */
    val build: StageTwoBuildReading,
    /**
     * What the next boot's late-load would find, or null when no shell was opened to ask.
     *
     * Null means the same here as it does for [probe], and it is deliberately not a reading of its own:
     * the refusal path never reaches this question, and a panel line saying "no shell answered" about a
     * question nobody asked would be this screen inventing a measurement - which is the one thing the
     * panel is there not to do.
     */
    val stage: DfrStageReading?,
)

/**
 * Measures the phone and asks the flow what is next, or null when no shell answered at all - root or the
 * plain one Shizuku offers, since every reading here is a thing the `shell` user may do itself.
 *
 * The probe, the inject check and the daemon's state are separate commands because they are separate
 * questions - one is Package Manager's view of an installed app, one is a parser's view of a file, and one
 * is the staged daemon being asked what build it is - and a device can answer one and not the others.
 */
private fun readState(
    context: Context,
    helper: DfrHelperAvailability,
    bundled: File?,
): DfrReading? {
    // Answered before the phone is asked anything, and that order is the point: the helper is this app's
    // own asset rather than a reading, so a build that cannot produce it refuses on a device where no
    // shell answers at all - which is exactly the device a mis-built APK gets tried on.
    if (helper != DfrHelperAvailability.Ready) {
        return DfrReading(
            step = DfrFlow.next(DfrFlow.refusalState(helper)),
            probe = null,
            injected = null,
            build = DfrInstall.readStageTwoBuild(context, bundled),
            stage = null,
        )
    }
    val probe = DfrInstall.probe() ?: return null
    // Through whichever shell this phone has, and the reading the missing-helper case turns on: with no root
    // the only thing that can say whether this app's certificate is still in the list is the injector's own
    // parser, and a phone whose helper was removed can only be handed its install step if that read answers.
    // A refusal is null, which is exactly the state this screen was in before the fallback existed.
    val check = DfrInstall.checkInjected(context)
    val injected = check?.allInjected
    val build = DfrInstall.readStageTwoBuild(context, bundled)
    // The third question, and the one no action's output can stand in for: a press stages the daemon and
    // prints what *that* attempt did, where this is what is there before it runs - see the panel.
    val stage = DfrInstall.readDaemonStage(context)
    val state = DfrState(
        keyInjected = injected,
        injectedAtMillis = AppPreferences.dfrInjectedAt(context),
        stageTwoInstalled = probe.installed,
        stageTwoIsSystemUid = probe.isSystemUid,
        installedAtMillis = AppPreferences.dfrInstalledAt(context),
        // The third instant, and the only one about a change still waiting on a restart: without it a
        // clean-up reads as a phone that was never injected, and the screen offers the inject it just undid.
        keyRemovedAtMillis = AppPreferences.dfrKeyRemovedAt(context),
        stageTwoArmed = probe.armed,
        // The helper on the phone against the one in this APK, which no other reading can answer: Package
        // Manager knows the installed package, and the file just unpacked out of the assets is the only
        // place the shipped build exists to be asked about.
        stageTwoBuild = build.verdict,
        // Reached only when the read said Ready, which is what got this far.
        helper = helper,
        // The clock that can see the restart this screen's own button performs. The kernel's uptime is
        // still read beside it, as the fallback for a device whose framework could not be asked.
        frameworkUptimeMillis = probe.frameworkUptimeMillis,
        nowMillis = System.currentTimeMillis(),
        uptimeMillis = DfrInstall.uptimeMillis(),
    )
    AppLog.info(
        AppLogTags.KERNEL_SU,
        // The framework's age is in the line because it is the reading that decides the restart steps:
        // a report about this flow that omits it cannot be argued against when the step is wrong.
        "system uid flow read: key=$injected removedAt=${state.keyRemovedAtMillis} " +
            "now=${state.nowMillis} uptime=${state.uptimeMillis} " +
            "framework=${state.frameworkUptimeMillis} " +
            // The two codes and not only the verdict they came to: this is the one judgement here that is
            // about two files rather than about the phone, so a report of the flow that says "the helper
            // was stale" without the numbers cannot be argued with.
            "helper=${build.verdict}(${build.installed}/${build.bundled}) " +
            "-> ${DfrFlow.next(state)}",
    )
    return DfrReading(DfrFlow.next(state), probe, injected, build, stage)
}
