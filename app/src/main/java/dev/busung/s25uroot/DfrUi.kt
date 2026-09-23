package dev.busung.s25uroot

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.busung.s25uroot.dfr.DfrFlow
import dev.busung.s25uroot.dfr.DfrInstall
import dev.busung.s25uroot.dfr.DfrMode
import dev.busung.s25uroot.dfr.DfrProbe
import dev.busung.s25uroot.dfr.DfrState
import dev.busung.s25uroot.dfr.DfrStep
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
    var picked by remember { mutableStateOf(DfrApk.file(context)) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { readState(context) }
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

    fun inject() = act("inject") {
        val result = DfrInstall.run(context, DfrMode.Inject, apkPath = picked?.absolutePath)
        if (result == null) return@act context.getString(R.string.dfr_no_root)
        if (result.ok) AppPreferences.setDfrInjectedAt(context, System.currentTimeMillis())
        result.log
    }

    fun install() = act("install") {
        val apk = picked ?: return@act context.getString(R.string.dfr_choose_apk)
        val action = DfrInstall.runAction(DfrInstall.installCommand(apk.absolutePath))
            ?: return@act context.getString(R.string.dfr_no_root)
        if (action.ok) AppPreferences.setDfrInstalledAt(context, System.currentTimeMillis())
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
        val removed = DfrInstall.run(context, DfrMode.Uninstall)
        DfrInstall.runAction(DfrInstall.uninstallCommand())
        AppPreferences.setDfrInjectedAt(context, null)
        AppPreferences.setDfrInstalledAt(context, null)
        removed?.log ?: context.getString(R.string.dfr_no_root)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { DfrApk.import(context, uri) }
            .onSuccess { file ->
                picked = file
                log = null
                // The one thing picking a file is for: the next step is the install, so it happens now
                // rather than after a second press of a button that only became enabled.
                if (reading?.step == DfrStep.InstallStageTwo) install()
            }
            .onFailure { failure -> log = failure.message ?: failure.javaClass.simpleName }
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
                reading?.let { current ->
                    Text(
                        stringResource(
                            R.string.dfr_measured,
                            // One answer, not a yes/no plus a qualifier: which identity the package has
                            // is only a question when there is a package, and "not installed (ordinary
                            // app)" is what a pair of fields says when nothing asks whether both apply.
                            stringResource(
                                when {
                                    !current.probe.installed -> R.string.dfr_stage_not_installed
                                    current.probe.isSystemUid -> R.string.dfr_stage_system
                                    else -> R.string.dfr_stage_ordinary
                                },
                            ),
                            stringResource(if (current.injected == true) R.string.dfr_yes else R.string.dfr_no),
                            stringResource(if (current.probe.armed) R.string.dfr_yes else R.string.dfr_no),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    picked?.let { stringResource(R.string.dfr_apk_set, it.name) }
                        ?: stringResource(R.string.dfr_apk_default),
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
                        DfrStep.Reboot, DfrStep.RebootAgain -> FilledTonalButton(
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
                        DfrStep.InstallStageTwo -> FilledTonalButton(
                            enabled = enabled && picked != null,
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
                    TextButton(enabled = !busy, onClick = { refresh() }) {
                        Text(stringResource(R.string.dfr_action_read_state))
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clickHaptic(view)
                    // Providers report an APK as the package mime type, as octet-stream, or as nothing
                    // usable, so the picker is left unfiltered and the copy validates it.
                    picker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                }) {
                    Text(stringResource(R.string.dfr_choose_apk))
                }
                TextButton(onClick = {
                    clickHaptic(view)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/** The step the phone is on, with what was measured to decide it. */
private class DfrReading(
    val step: DfrStep,
    val probe: DfrProbe,
    val injected: Boolean?,
)

/**
 * Measures the phone and asks the flow what is next, or null when no root shell answered at all.
 *
 * The probe and the inject check are separate commands because they are separate questions - one is
 * Package Manager's view of an installed app, the other is a parser's view of a file - and a device can
 * answer one and not the other.
 */
private fun readState(context: Context): DfrReading? {
    val probe = DfrInstall.probe() ?: return null
    val check = DfrInstall.run(context, DfrMode.Check)
    val injected = check?.allInjected
    val state = DfrState(
        keyInjected = injected,
        injectedAtMillis = AppPreferences.dfrInjectedAt(context),
        stageTwoInstalled = probe.installed,
        stageTwoIsSystemUid = probe.isSystemUid,
        installedAtMillis = AppPreferences.dfrInstalledAt(context),
        stageTwoArmed = probe.armed,
        nowMillis = System.currentTimeMillis(),
        uptimeMillis = DfrInstall.uptimeMillis(),
    )
    return DfrReading(DfrFlow.next(state), probe, injected)
}
