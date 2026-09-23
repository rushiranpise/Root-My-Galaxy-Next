package dev.busung.s25uroot

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Import a module and load it, with what the device answered about it in front of the button.
 *
 * The precondition is shown before anything is offered rather than discovered by a failed load,
 * because the two ways this can be impossible are facts about the kernel - no `enforce` node, or
 * DEVELOP off - and neither changes by trying. It is read on open, off the main thread: it is a root
 * shell, and on a device that has not answered its grant prompt that is a wait, not a failure.
 *
 * The result shown is the module's own line rather than this app's opinion of it. A load that works
 * still makes `insmod` report failure - the module returns an error to unload itself - so the account
 * has to come from the kernel log, and repeating it here is what makes the outcome checkable against
 * `dmesg` afterwards.
 */
@Composable
internal fun PermissiveModuleDialog(
    initialName: String?,
    onDismiss: () -> Unit,
    onNameChanged: (String?) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var name by remember(initialName) { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    var availability by remember { mutableStateOf<PermissiveAvailability?>(null) }
    var outcome by remember { mutableStateOf<PermissiveOutcome?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        availability = withContext(Dispatchers.IO) { PermissiveLever.support().availability }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { LocalModule.import(context, uri) }
            .onSuccess { imported ->
                name = imported
                error = null
                outcome = null
                onNameChanged(imported)
            }
            .onFailure { failure ->
                // The previously imported module is still in place; only the message changes.
                error = failure.message ?: failure.javaClass.simpleName
            }
    }

    val current = name
    val ready = availability == PermissiveAvailability.Ready

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permissive_module)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.permissive_module_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    availability?.let { stringResource(availabilityRes(it)) }
                        ?: stringResource(R.string.permissive_availability_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (availability == null || ready) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    if (current == null) {
                        stringResource(R.string.permissive_module_no_file)
                    } else {
                        stringResource(R.string.permissive_module_file, current)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                outcome?.let { result ->
                    Text(
                        stringResource(R.string.permissive_module_result, result.summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.state == PermissiveState.Permissive) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clickHaptic(view)
                    // Providers report a module as octet-stream, as an ELF, or as nothing usable, so
                    // the picker is left unfiltered and the import validates what comes back.
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                }) {
                    Text(
                        stringResource(
                            if (current == null) R.string.permissive_module_choose
                            else R.string.permissive_module_replace,
                        ),
                    )
                }
                FilledTonalButton(
                    enabled = ready && current != null && !loading,
                    onClick = {
                        clickHaptic(view)
                        val file = LocalModule.file(context) ?: return@FilledTonalButton
                        loading = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { PermissiveLever.load(file) }
                            outcome = result
                            loading = false
                            AppLog.info(
                                AppLogTags.KERNEL_SU,
                                "permissive module: ${result.state.name.lowercase()} - ${result.summary}",
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (loading) R.string.permissive_module_loading
                            else R.string.permissive_module_load,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    TextButton(onClick = {
                        clickHaptic(view)
                        LocalModule.clear(context)
                        name = null
                        outcome = null
                        onNameChanged(null)
                    }) {
                        Text(stringResource(R.string.permissive_module_remove))
                    }
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

/** The wording for one availability, which is the reason a load is or is not offered. */
private fun availabilityRes(availability: PermissiveAvailability): Int = when (availability) {
    PermissiveAvailability.Ready -> R.string.permissive_availability_ready
    PermissiveAvailability.NoRoot -> R.string.permissive_availability_no_root
    PermissiveAvailability.NoEnforceNode -> R.string.permissive_availability_no_node
    PermissiveAvailability.DevelopOff -> R.string.permissive_availability_develop_off
    PermissiveAvailability.NoInsmod -> R.string.permissive_availability_no_insmod
}
