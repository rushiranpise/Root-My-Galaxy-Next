package dev.busung.s25uroot

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 *
 * **An answer that is working says so itself.** A press that starts something slow has to answer "is it
 * doing anything" before the thing it started has anything to say, and the answer belongs in the button
 * that was pressed rather than beside it - see [AppAction.progress]. It replaced a hand-made button that
 * carried its own spinner, which meant the one screen with a slow answer was also the one screen whose
 * buttons did not match the rest of the app's.
 *
 * **And it is not only dialogs.** These rules are about a labelled button - one that names what it does -
 * wherever it is drawn: a dialog's answers, a run's Stop and Retry in the bar that floats over its log, the
 * way out of an empty history, the fix offered under a warning. All of them are [AppActionButton] now, which
 * is why it is drawn here rather than inside the dialog vocabulary: before, a screen's own buttons were the
 * platform's `Button` at the platform's padding and label size, so a screen's Stop and a dialog's Cancel
 * were two different objects that happened to both be buttons. The three roles above are the whole of the
 * emphasis available anywhere in the app.
 *
 * What is deliberately *not* in this vocabulary, because a role and a label are not what they have to say:
 * icon-only buttons and the floating buttons that sit over a list. Those are about a thing rather than about
 * a decision - a pin, a row's own delete, a jump to the top - and they are drawn by their own rules.
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
 * One answer: what it says, how loud it is, whether it is working, and what it does.
 *
 * A label rather than a string, because every one of these is a resource and the caller should not be the
 * place that decides how to resolve it.
 */
internal class AppAction(
    @StringRes val label: Int,
    val role: AppActionRole = AppActionRole.Standard,
    val enabled: Boolean = true,
    /**
     * Whether this answer is working, rather than merely available.
     *
     * Deliberately separate from [enabled], because the two are not the same statement and the screens
     * that need this need them apart: the answer that started a slow attempt is both working and
     * unpressedable, while the answer *beside* it - "run it without that, then" - is still live and not
     * working at all. What this adds is the spinner, drawn in the button's own content colour so it reads
     * on any of the three fills, and it is the caller's vocabulary rather than its own: the label it sits
     * beside is still the caller's, which is how one action can say "Start" and then "Starting…".
     */
    val progress: Boolean = false,
    /**
     * Arguments for a label that has placeholders in it, in the order the string names them.
     *
     * Empty for every label that is a fixed sentence, which is most of them. It exists because two of
     * these answers name the value they act on - "Reset to 3.4.0" - and a set that could only say the
     * unformatted string would either drop the number or push that answer back out of the set and into a
     * hand-built button, which is the thing this file is here to stop.
     */
    val labelArgs: List<Any> = emptyList(),
    /**
     * An icon for an action whose meaning its label does not carry on its own, or null for the many that do
     * not need one.
     *
     * Rare on purpose, and last because of it: an action here is a sentence, and a symbol beside every one
     * of them is a row of pictures competing with the words. It is wanted where a pair of actions are each
     * other's opposite - pinning this revision against giving the branch up - and the label alone leaves the
     * two being read twice to tell which is which.
     */
    val icon: ImageVector? = null,
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
        colors = appActionColors(action.role),
    ) {
        AppActionLabel(action)
    }
}

/**
 * The other shape an action takes: a bare label, with no fill of its own.
 *
 * For an action drawn inside something the app itself put up - a settings card, a form row, the notice that
 * a setting is not in the state it wants - where a filled answer would either be the same colour as what it
 * is drawn on or would outrank the answer that screen is actually asking for. One screen's "use the running
 * version" and another's "open setting" are the same control: this one.
 *
 * It draws the same words as [AppActionButton] - one label style, one way of wrapping, the same spinner
 * beside a slow one - so an action does not change how it reads by changing where it sits.
 */
@Composable
internal fun AppTextAction(
    action: AppAction,
    modifier: Modifier = Modifier,
    /**
     * The colour of the words, when the caller is drawn on a container the theme did not hand it.
     *
     * Primary is what a link is everywhere else, and a card that is an error is the case that needs the
     * other: a fixed palette over a coloured surface is one of them reading wrong, and there the surface
     * wins - the caller passes what it is drawn on.
     */
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = action.onClick,
        modifier = modifier,
        enabled = action.enabled,
        contentPadding = ACTION_PADDING,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
    ) {
        AppActionLabel(action)
    }
}

/**
 * What an action says, drawn once for both shapes.
 *
 * The spinner comes before the label rather than after it, so the words keep the same place whether or not
 * the action is working: one that pushed the label across would make the control jump at the moment it was
 * pressed.
 */
@Composable
private fun AppActionLabel(action: AppAction) {
    action.icon?.let { icon ->
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(ACTION_ICON_SIZE),
        )
        Spacer(Modifier.width(ACTION_ICON_GAP))
    }
    if (action.progress) {
        LoadingIndicator(
            modifier = Modifier.size(ACTION_PROGRESS_SIZE),
            color = LocalContentColor.current,
        )
        Spacer(Modifier.width(ACTION_PROGRESS_GAP))
    }
    Text(
        if (action.labelArgs.isEmpty()) {
            stringResource(action.label)
        } else {
            stringResource(action.label, *action.labelArgs.toTypedArray())
        },
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

/**
 * The three fills, in one place, because they are read in two.
 *
 * Every answer in the app is a [Button] wearing one of these, and one screen builds its own button anyway:
 * the retry dialog's three answers carry a second line saying what each one buys, which is a taller and
 * left-aligned answer than [AppActionButton] draws. That screen used to spell the fills out again, which is
 * how it would have kept a shade that the rest of the app had moved on from - so the fills live here and it
 * wears them.
 */
@Composable
internal fun appActionColors(role: AppActionRole): ButtonColors = when (role) {
    AppActionRole.Priority -> ButtonDefaults.buttonColors()
    AppActionRole.Destructive -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    AppActionRole.Standard -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * The spinner an in-flight answer carries.
 *
 * Small on purpose: it has to fit beside a label inside [ACTION_HEIGHT] without making the button taller
 * than the answers next to it, and at this size it still reads as motion in the corner of the eye.
 */
private val ACTION_PROGRESS_SIZE = 16.dp

/** The gap between that spinner and the label it belongs to. */
private val ACTION_PROGRESS_GAP = 8.dp

/** The size of an action's icon, which is the spinner's size with room for a symbol that has to be read. */
private val ACTION_ICON_SIZE = 18.dp

/** The gap between an icon and the words it belongs to. */
private val ACTION_ICON_GAP = 8.dp

/** The most answers read across in one row. */
private const val ACTIONS_PER_ROW = 3
