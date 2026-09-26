package dev.busung.s25uroot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * The two things that move the floating bar out of the way: a page being scrolled, and a step that has taken
 * the whole window.
 *
 * The bar is the app shell's, but the thing that decides where it goes is the screen underneath - and the
 * shell has no idea what any screen is doing: the list, its state and its direction all belong to the page.
 * The one mechanism that crosses that line without threading a parameter through five screens is nested
 * scroll, which a scrolling list already dispatches to every ancestor that asks for it. So the shell asks,
 * and a page does not have to know that anything is listening.
 *
 * Attached to the pages rather than to the whole screen, which matters: the sheets and dialogs are drawn as
 * siblings of the pages, and scrolling a payload list inside one of them is not a page moving under the bar.
 *
 * A step inside a page has no scroll to be read from and says so directly instead - see [FullScreenStep].
 */

/**
 * Which way a page is moving, as the bar cares about it.
 *
 * Named for the page's own direction rather than the finger's, because that is the thing the bar is following
 * and the two are opposites: a finger dragged up the screen moves the page toward its end.
 */
internal enum class PageScroll {
    /** Back toward the top of the page, which is where the bar belongs. */
    TowardTop,

    /** Deeper into the page, which is where the bar gets in the way. */
    TowardEnd,

    /** Nothing moved, or the scroll is settling. Not a direction, so not a decision. */
    Still,
}

/**
 * The direction a scroll delta is asking for.
 *
 * Compose reports a scroll as the distance the *content* should move: positive is content coming back down -
 * a finger or a fling heading for the top of the list - and negative is going deeper into it.
 */
internal fun pageScrollOf(delta: Float): PageScroll = when {
    delta > 0f -> PageScroll.TowardTop
    delta < 0f -> PageScroll.TowardEnd
    else -> PageScroll.Still
}

/**
 * Whether the bar is away after a page moves [direction].
 *
 * Any movement back toward the top brings it back, and any movement deeper takes it away - no threshold and
 * no accumulator, because the smallest upward movement is the user looking for the thing that left. A settle
 * keeps what it was, so a fling does not flicker the bar in and out as its tail decays.
 */
internal fun navBarHiddenAfter(hidden: Boolean, direction: PageScroll): Boolean = when (direction) {
    PageScroll.TowardEnd -> true
    PageScroll.TowardTop -> false
    PageScroll.Still -> hidden
}

/**
 * The connection that turns a page's scrolling into that decision.
 *
 * [report] is called only when the answer changes - a scroll is dozens of events a frame and the bar has two
 * states - and the amount consumed is read rather than the amount available: available includes the scroll a
 * list at its end refuses, and the bar should not leave because someone pulled at a page that had nowhere
 * left to go. Nothing is consumed here; this only watches.
 */
internal fun navBarScrollConnection(report: (Boolean) -> Unit): NestedScrollConnection {
    var hidden = false
    return object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val next = navBarHiddenAfter(hidden, pageScrollOf(consumed.y))
            if (next != hidden) {
                hidden = next
                report(next)
            }
            return Offset.Zero
        }
    }
}

/**
 * The bar's answer to a screen that has taken the whole window, for a step that is not a page.
 *
 * The scroll rule above hides the bar because the page underneath is moving: the bar is in the way of a list,
 * and the list is what is being read, so the page itself is the thing that decides. A step opened over a page
 * is the same problem with nothing to notice it by. It owns every edge of the window, so the bar is not
 * covering a page - it is covering the step's own footer - and a page that fits its screen has no scroll to
 * be read from: on the payload sources screen the pill sat on Cancel and Save with nothing below them that
 * could be pulled up.
 *
 * So the step says so instead, and the way it says so is [FullScreenStep]: the window is claimed for as long as
 * the step is composed and handed back when it leaves. Provided by the shell, because where the bar goes is
 * the shell's decision and this only reports what the screen is doing.
 */
internal val LocalFullScreenStep = staticCompositionLocalOf<(Boolean) -> Unit> { { _ -> } }

/**
 * Takes the window from the floating bar until the step being composed is gone.
 *
 * Called by a step rather than by a page: a page is what the bar navigates between, and one that hid the bar
 * would hide the way to the next one. Placed in the step's own content, so it lasts exactly as long as the
 * step does - released on the way out however the step is left, including when something else closes it.
 */
@Composable
internal fun FullScreenStep() {
    val claim = LocalFullScreenStep.current
    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }
}
