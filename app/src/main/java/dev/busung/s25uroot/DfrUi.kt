package dev.busung.s25uroot

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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

    fun install() = act("install") {
        val file = bundledApk ?: return@act context.getString(helperRefusalRes())
        val action = DfrInstall.runAction(DfrInstall.installCommand(file.absolutePath))
            ?: return@act context.getString(R.string.dfr_no_root)
        // Stamped on the attempt, for the same reason as the inject above: `pm install` prints more than
        // one word beginning with Failure, and a stamp that only moves on a clean verdict leaves the
        // second reboot indistinguishable from one that has already happened.
        AppPreferences.setDfrInstalledAt(context, System.currentTimeMillis())
        action.log
    }

    fun removeStageTwo() = act("remove stage two") {
        val action = DfrInstall.runAction(DfrInstall.uninstallCommand())
            ?: return@act context.getString(R.string.dfr_no_root)
        if (action.ok) AppPreferences.setDfrInstalledAt(context, null)
        action.log
    }

    fun open() = act("open stage two") {
        DfrInstall.runAction(DfrInstall.launchCommand())?.log
            ?: context.getString(R.string.dfr_no_root)
    }

    fun reboot() = act("soft reboot") {
        val outcome = runRecoveryAction(context, RecoveryTool.SoftReboot)
        if (outcome.accepted) {
            context.getString(R.string.recovery_action_soft_reboot)
        } else {
            outcome.detail
        }
    }

    fun cleanUp() = act("clean up") {
        // The two things this flow put on the device: the key in the shared user, and the helper
        // installed under it. The files an inject leaves in /data/system - the pre-inject copy of
        // packages.xml and the staged copy a failed rename swap writes - are deliberately not touched
        // here. Deleting them is what the residue screen is for, where they are listed with everything
        // else the app left behind and can be removed one at a time or together.
        //
        // Each record is cleared by its own half of the undo and by nothing else - see
        // [DfrCleanUpOutcome]. A half that did not land, including both halves on a phone with no root
        // shell, leaves its record, so the next reading of this screen shows that step rather than the
        // first one.
        val removed = DfrInstall.run(context, DfrMode.Uninstall)
        val helper = DfrInstall.runAction(DfrInstall.uninstallCommand())
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
                    .heightIn(max = 520.dp)
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
                log?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !busy
                    when (step) {
                        DfrStep.Inject -> FilledTonalButton(enabled = enabled, onClick = { inject() }) {
                            Text(stringResource(R.string.dfr_inject))
                        }
                        // The same action as the two reboot steps: applying a removal is a restart, and the
                        // screen's own word for that is the one the other two use.
                        DfrStep.Reboot, DfrStep.RebootAgain, DfrStep.ApplyRemoval -> FilledTonalButton(
                            enabled = enabled,
                            onClick = { reboot() },
                        ) {
                            Text(stringResource(R.string.dfr_action_reboot))
                        }
                        DfrStep.RemoveStageTwo -> FilledTonalButton(
                            enabled = enabled,
                            onClick = { removeStageTwo() },
                        ) {
                            Text(stringResource(R.string.dfr_action_remove_stage2))
                        }
                        // No condition on the helper here any more: a build without one never reaches this
                        // step, because the flow refuses before it - see [DfrStep.NoHelper].
                        DfrStep.InstallStageTwo -> FilledTonalButton(
                            enabled = enabled,
                            onClick = { install() },
                        ) {
                            Text(stringResource(R.string.dfr_action_install_stage2))
                        }
                        DfrStep.OpenStageTwo -> FilledTonalButton(enabled = enabled, onClick = { open() }) {
                            Text(stringResource(R.string.dfr_action_open_stage2))
                        }
                        // Nothing to press when the work is done, and nothing to press when nothing could
                        // be read - reading again is the action for that, and it is below.
                        DfrStep.Ready -> Unit
                        else -> Unit
                    }
                    // Not offered on the refusal: nothing on the phone decides whether this build has a
                    // helper in its assets, so reading again can only produce the same answer. A button
                    // that cannot change anything is how a refusal starts to look like a step.
                    if (step != DfrStep.NoHelper) {
                        TextButton(enabled = !busy, onClick = { refresh() }) {
                            Text(stringResource(R.string.dfr_action_read_state))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { cleanUp() }) {
                        Text(stringResource(R.string.dfr_clean_up))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                clickHaptic(view)
                onDismiss()
            }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

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
        nowMillis = System.currentTimeMillis(),
        uptimeMillis = DfrInstall.uptimeMillis(),
    )
    AppLog.info(
        AppLogTags.KERNEL_SU,
        "system uid flow read: key=$injected removedAt=${state.keyRemovedAtMillis} " +
            "now=${state.nowMillis} uptime=${state.uptimeMillis} -> ${DfrFlow.next(state)}",
    )
    return DfrReading(DfrFlow.next(state), probe, injected)
}
