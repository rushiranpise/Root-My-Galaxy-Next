package dev.busung.s25uroot

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * How this app shows a set of answers.
 *
 * Every screen here ends by asking one question with two to six answers - confirm and cancel, proceed or
 * go back, one of three ways to retry - and each of those was laid out on its own, which is how the same
 * question came to look like a row of text in one dialog, a stack of full-width buttons in another, and a
 * list of four in a third. The rules that turned out to matter are these, and they are rules rather than
 * taste because each of them is something a person has to work out at the moment they are asked:
 *
 * **One answer is loud and the rest are quiet.** The fill says which one the screen is recommending, so
 * the question "which of these do I want" is answered before the labels are read: the recommended one is
 * filled with the theme's primary and everything else is flat against the dialog in
 * `surfaceContainerHighest`. Two answers are never both loud, because a screen with two primary buttons has
 * no primary button.
 *
 * **A destructive answer is in the error colours and is never the loud one.** It is the only fill here
 * that carries meaning beyond emphasis, and it must survive whatever the calling screen decides to
 * recommend - a recommendation to delete something is still a deletion.
 *
 * **At most three answers in a row, and the rows are filled evenly.** Three is where a full-width dialog
 * gives each answer a third of a phone, which is what a two-word label needs to stay on one line; more than
 * three is a stack nobody reads to the end. The rows are balanced rather than packed - four answers are two
 * and two, not three and one - so no row has a lone button stretched to the full width beside a crowded
 * one, and each row fills the width it is given rather than leaving a hole where an answer is not offered.
 *
 * **The answers are always the same height and never sized by their own label.** These labels are the flow's
 * own words; a button that grew to fit "Install stage 2" and shrank for "Cancel" would make one set of
 * answers look like two.
 */
internal enum class AppActionRole {
    /** The answer this screen recommends: the filled one, and only one per set. */
    Priority,

    /** Every other answer: legible, quiet, flat against the dialog. */
    Standard,

    /** An answer that takes something away, in the error colours whatever else is recommended. */
    Destructive,
}

/**
 * One answer: what it says, how loud it is, and what it does.
 *
 * A label rather than a string, because every one of these is a resource and the caller should not be the
 * place that decides how to resolve it.
 */
internal class AppAction(
    @StringRes val label: Int,
    val role: AppActionRole = AppActionRole.Standard,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * How many answers go in each row, for [count] of them.
 *
 * Balanced rather than packed, and never more than [ACTIONS_PER_ROW]: four answers are two rows of two, and
 * five are three and two - so a row is never left with one stretched button beside a crowded one. Counts
 * that the rule cannot balance are impossible here (any count fits in `ceil(count / 3)` rows of at most
 * three), which is why this returns sizes that always add up to [count] and never return an empty row.
 */
internal fun actionRowSizes(count: Int): List<Int> {
    if (count <= 0) return emptyList()
    val rows = (count + ACTIONS_PER_ROW - 1) / ACTIONS_PER_ROW
    val perRow = count / rows
    val remainder = count % rows
    return List(rows) { index -> perRow + if (index < remainder) 1 else 0 }
}

/** [actionRowSizes]' answer, applied to the actions themselves. */
internal fun <T> actionRows(items: List<T>): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    var from = 0
    for (size in actionRowSizes(items.size)) {
        rows += items.subList(from, from + size)
        from += size
    }
    return rows
}

/**
 * A set of answers, laid out by [actionRowSizes] and filled by [AppAction.role].
 *
 * The caller supplies the order it wants read, not a layout: which answer is loud is [AppActionRole], and
 * how many go in a row is this file's rule, so a five-answer question and a two-answer one agree about
 * both without either of them spelling it out.
 */
@Composable
internal fun AppDialogActions(actions: List<AppAction>, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ACTION_SPACING),
    ) {
        actionRows(actions).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING),
            ) {
                row.forEach { action ->
                    AppActionButton(action, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One answer, in the fill its [AppAction.role] asks for.
 *
 * Public to the package rather than private, because a screen with an answer that is not part of a set -
 * a single "Close" under a list, the one action a step is waiting for - still has to wear the same fill as
 * the sets do, and a second implementation of that is how the two come apart.
 */
@Composable
internal fun AppActionButton(action: AppAction, modifier: Modifier = Modifier) {
    Button(
        onClick = action.onClick,
        modifier = modifier.height(ACTION_HEIGHT),
        enabled = action.enabled,
        contentPadding = ACTION_PADDING,
        colors = when (action.role) {
            AppActionRole.Priority -> ButtonDefaults.buttonColors()
            AppActionRole.Destructive -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            AppActionRole.Standard -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(
            stringResource(action.label),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** How tall every answer is, whatever it says. */
private val ACTION_HEIGHT = 44.dp

/** The gap between two answers, across and down. */
private val ACTION_SPACING = 6.dp

/**
 * How much room a label is given inside its button.
 *
 * Well under the platform's own 24 dp a side, which is sized for a button that has a dialog's whole width:
 * an answer in a row of three has a third of it, and that padding comes straight out of the words.
 */
private val ACTION_PADDING = PaddingValues(horizontal = 6.dp, vertical = 4.dp)

/** The most answers read across in one row. */
private const val ACTIONS_PER_ROW = 3
