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
import dev.busung.s25uroot.dfr.DfrState
import dev.busung.s25uroot.dfr.DfrStep
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
                readState(context, helper.availability)
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
        val action = DfrInstall.stageDaemon(context) ?: return context.getString(R.string.dfr_no_root)
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
        val action = DfrInstall.runAction(DfrInstall.installCommand(file.absolutePath))
            ?: return@act listOf(staged, context.getString(R.string.dfr_no_root)).joinToString("\n")
        // Stamped on the attempt, for the same reason as the inject above: `pm install` prints more than
        // one word beginning with Failure, and a stamp that only moves on a clean verdict leaves the
        // second reboot indistinguishable from one that has already happened.
        AppPreferences.setDfrInstalledAt(context, System.currentTimeMillis())
        listOf(staged, action.log).joinToString("\n")
    }

    fun removeStageTwo() = act("remove stage two") {
        val action = DfrInstall.runAction(DfrInstall.uninstallCommand())
            ?: return@act context.getString(R.string.dfr_no_root)
        if (action.ok) AppPreferences.setDfrInstalledAt(context, null)
        action.log
    }

    fun open() = act("open stage two") {
        // Rewritten on the way in as well as at the install: the helper keeps whatever it finds at that
        // path, so this call is what makes the daemon it uses the one for this boot - and for a phone
        // whose flavour was changed since the last run, this is the call that replaces the old one.
        val staged = stageDaemon()
        val action = DfrInstall.runAction(DfrInstall.launchCommand())
            ?: return@act listOf(staged, context.getString(R.string.dfr_no_root)).joinToString("\n")
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
     * So the uninstall runs again immediately before the restart, where nothing can get between them. On a
     * file that is still clean it writes nothing and says so - which is the same answer, for free, and it
     * is why this does not need to ask first whether the key came back.
     */
    fun applyRemoval() = act("apply removal") {
        val again = DfrInstall.run(context, DfrMode.Uninstall)
            ?: return@act context.getString(R.string.dfr_no_root)
        // A removal that had to be written again is a new instant, for the same reason a clean-up stamps
        // one: this phone owes a restart for a file that changed.
        if (again.keyTakenOut) AppPreferences.setDfrKeyRemovedAt(context, System.currentTimeMillis())
        val outcome = runRecoveryAction(context, RecoveryTool.SoftReboot)
        val restart = if (outcome.accepted) {
            context.getString(R.string.recovery_action_soft_reboot)
        } else {
            outcome.detail
        }
        listOf(again.log, restart).joinToString("\n")
    }

    fun cleanUp() = act("clean up") {
        // The two things this flow put on the device: the key in the shared user, and the helper
        // installed under it. The files an inject leaves in /data/system - the pre-inject copy of
        // packages.xml and the staged copy a failed rename swap writes - are deliberately not touched
        // here. Deleting them is what the residue screen is for, where they are listed with everything
        // else the app left behind and can be removed one at a time or together.
        //
        // **The helper goes first and the key comes out last, and that order is the whole of this
        // method.** `pm uninstall` is a Package Manager write, and Package Manager writes
        // `packages.xml` from *its own memory* - the copy it read at boot, which still holds our key -
        // so an uninstall running after the key removal puts the key straight back into the file. That
        // is not a theory: measured on this device, a clean-up that removed the key first and then the
        // helper left the key in the file within the same second, the screen read the key as present
        // and asked for the reboot step again, and the reboot could not help - the file it booted from
        // had the key in it. The next clean-up worked only because the helper was already gone, so
        // nothing rewrote the file after the removal. Removing the helper first makes the key's write
        // the last one, which is the only state a restart can make true.
        //
        // Each record is cleared by its own half of the undo and by nothing else - see
        // [DfrCleanUpOutcome]. A half that did not land, including both halves on a phone with no root
        // shell, leaves its record, so the next reading of this screen shows that step rather than the
        // first one.
        val helper = DfrInstall.runAction(DfrInstall.uninstallCommand())
        val removed = DfrInstall.run(context, DfrMode.Uninstall)
        val outcome = DfrCleanUpOutcome.of(removed, helper)
        if (outcome.keyGone) AppPreferences.setDfrInjectedAt(context, null)
        if (outcome.helperGone) AppPreferences.setDfrInstalledAt(context, null)
        // Recorded only when this run changed the file: an uninstall that found nothing to remove has
        // nothing waiting on a restart. What it earns is [DfrStep.ApplyRemoval] - the key is out of the
        // file and still live in the Package Manager that started before the change.
        if (removed?.keyTakenOut == true) {
            AppPreferences.setDfrKeyRemovedAt(context, System.currentTimeMillis())
        }
        removed?.log ?: context.getString(R.string.dfr_no_root)
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
                // What was measured, as three answers rather than only the step drawn from them: the step
                // is this app's conclusion, and a wrong conclusion is only arguable against these.
                // Only when something was measured: with no helper the flow refuses before it opens a
                // shell, so a line about the package, the certificate and the hooks would be three claims
                // nobody made.
                reading?.probe?.let { probe ->
                    Text(
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
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                log?.let { output ->
                    Text(
                        stringResource(R.string.dfr_log),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A fixed height with its own scroll, which is what the install screen's log panel
                    // does and for the same reason: what the injector prints is a report of arbitrary
                    // length - its whole transform, then a verify line per target - and as plain text in
                    // this dialog it grew the screen until the step list and the actions were below the
                    // fold. The screen's job is to say which step the phone is on, so the log may not be
                    // what decides how tall it is.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DFR_LOG_HEIGHT),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = output,
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
                        // No condition on the helper here: a build without one never reaches this step,
                        // because the flow refuses before it - see [DfrStep.NoHelper].
                        DfrStep.InstallStageTwo -> AppAction(
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
            confirmButton = {
                if (removals.isEmpty()) {
                    TextButton(onClick = { confirmCleanUp = false }) {
                        Text(stringResource(R.string.dfr_clean_up_close))
                    }
                } else {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            clickHaptic(view)
                            confirmCleanUp = false
                            cleanUp()
                        },
                    ) {
                        Text(stringResource(R.string.dfr_clean_up_confirm))
                    }
                }
            },
            // One button when there is nothing to remove: a Close beside a Cancel is the same press twice.
            dismissButton = if (removals.isEmpty()) {
                null
            } else {
                {
                    TextButton(onClick = { confirmCleanUp = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

/**
 * How tall the dialog's own content may be before it scrolls.
 *
 * A cap rather than "whatever is left", because the answers are not in this area at all: they are the
 * AlertDialog's own, laid out below everything here, and [AppDialogActions] puts this screen's six of them
 * in two rows rather than six. 440 dp is what is left for the step list, the measurement and the log once
 * those two rows, a title and the platform's padding have taken their share of the 780 dp screen this was
 * written against - and that difference is the reason the answers are a grid at all.
 */
private val DFR_TEXT_HEIGHT = 440.dp

/**
 * How much of the dialog the process log may take.
 *
 * Fixed rather than a maximum, because the log is the one field here whose length nothing bounds: an
 * injector run prints its whole transform and a verify line per target, and the clean-up prints two files'
 * worth of it. See the panel in the dialog for what it looked like as plain text.
 */
private val DFR_LOG_HEIGHT = 140.dp

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
    DfrStep.RemoveStageTwo, DfrStep.InstallStageTwo, DfrStep.OpenStageTwo -> DfrAction.StageTwo
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
)

/**
 * Measures the phone and asks the flow what is next, or null when no root shell answered at all.
 *
 * The probe and the inject check are separate commands because they are separate questions - one is
 * Package Manager's view of an installed app, the other is a parser's view of a file - and a device can
 * answer one and not the other.
 */
private fun readState(context: Context, helper: DfrHelperAvailability): DfrReading? {
    // Answered before the phone is asked anything, and that order is the point: the helper is this app's
    // own asset rather than a reading, so a build that cannot produce it refuses on a device where no
    // shell answers at all - which is exactly the device a mis-built APK gets tried on.
    if (helper != DfrHelperAvailability.Ready) {
        return DfrReading(DfrFlow.next(DfrFlow.refusalState(helper)), probe = null, injected = null)
    }
    val probe = DfrInstall.probe() ?: return null
    val check = DfrInstall.run(context, DfrMode.Check)
    val injected = check?.allInjected
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
            "framework=${state.frameworkUptimeMillis} -> ${DfrFlow.next(state)}",
    )
    return DfrReading(DfrFlow.next(state), probe, injected)
}
