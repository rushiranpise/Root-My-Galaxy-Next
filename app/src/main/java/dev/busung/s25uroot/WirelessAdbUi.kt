package dev.busung.s25uroot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What the wireless transport is, what it is doing, and the four things that can be done to it.
 *
 * The distinction the whole screen is built around: a stored pairing is a record, not a state. So the
 * card says only *paired* until a test has connected - the word that names what was stored - and says
 * *working* once one has. The test is offered right next to the pairing rather than hidden, because
 * "it says paired but nothing works" is the failure this screen exists to make visible before a run
 * fails on it. What a bare *paired* cannot say on a card is said in the dialog's detail line, which is
 * where the difference between the two words is spelled out.
 */
@Composable
internal fun WirelessAdbDialog(
    snapshot: WirelessAdbSnapshot?,
    busy: Boolean,
    writeSecureSettingsMissing: Boolean,
    onPair: (forceRepair: Boolean) -> Unit,
    onGrantPermission: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onTest: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wireless_adb_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.wireless_adb_pair_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (snapshot != null) {
                    Text(
                        text = wirelessAdbStateLabel(snapshot.authState),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = snapshot.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot.fingerprint?.let { fingerprint ->
                        Text(
                            text = stringResource(R.string.wireless_adb_fingerprint) + ": " + fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Said here rather than only as a state word on the card, because it is the difference
                // between a screen that can turn wireless debugging on by itself and one that needs the
                // setting already on: "Needs permission" without the way to get it is a dead end.
                if (writeSecureSettingsMissing) {
                    Text(
                        text = stringResource(R.string.wireless_adb_permission_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    Text(
                        text = stringResource(R.string.adb_pair_working),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            // The set this dialog asks with, in the same shape as every other screen's - see
            // [AppDialogActions] - which is what turned six stacked answers into two rows.
            AppDialogActions(
                listOfNotNull(
                    // First, because it is the one action that needs nothing already arranged on the
                    // device.
                    if (writeSecureSettingsMissing) {
                        AppAction(R.string.grant_action, AppActionRole.Standard, enabled = !busy) {
                            onGrantPermission()
                        }
                    } else {
                        null
                    },
                    // The answer this dialog exists for: the one filled cell in the set.
                    AppAction(
                        // With a key already stored, pairing again has to clear it first, which is the
                        // only way out of a pairing the device has forgotten.
                        label = if (snapshot?.keyPresent == true) {
                            R.string.wireless_adb_pair_again
                        } else {
                            R.string.wireless_adb_pair
                        },
                        role = AppActionRole.Priority,
                        enabled = !busy,
                    ) { onPair(snapshot?.keyPresent == true) },
                    // The screen the code is generated in, reachable without leaving with an instruction
                    // to find it: "open Developer options" as a sentence is what this button replaces.
                    AppAction(
                        R.string.adb_pair_open_developer_options,
                        AppActionRole.Standard,
                        !busy,
                    ) { onOpenDeveloperOptions() },
                    AppAction(R.string.wireless_adb_test, AppActionRole.Standard, !busy) { onTest() },
                    // The one answer here that takes something away rather than arranging it, and the
                    // reason it wears the error colours: forgetting the stored key ends the pairing.
                    if (snapshot?.keyPresent == true) {
                        AppAction(R.string.wireless_adb_forget, AppActionRole.Destructive, !busy) {
                            onForget()
                        }
                    } else {
                        null
                    },
                    AppAction(R.string.action_cancel) { onDismiss() },
                ),
            )
        },
        dismissButton = null,
    )
}

/** The one-line summary of the authorization state, for the card and the dialog. */
@Composable
internal fun wirelessAdbStateLabel(state: WirelessAdbAuthState): String = stringResource(
    when (state) {
        WirelessAdbAuthState.NoCredential -> R.string.settings_wireless_adb_none
        WirelessAdbAuthState.SavedUnverified -> R.string.settings_wireless_adb_unverified
        WirelessAdbAuthState.Valid -> R.string.settings_wireless_adb_valid
        WirelessAdbAuthState.PairingRejected -> R.string.settings_wireless_adb_rejected
        WirelessAdbAuthState.PortUnavailable -> R.string.settings_wireless_adb_port_missing
        WirelessAdbAuthState.PermissionRequired -> R.string.settings_wireless_adb_no_permission
        WirelessAdbAuthState.ConnectionFailed -> R.string.settings_wireless_adb_failed
    },
)
