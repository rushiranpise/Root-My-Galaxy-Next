package dev.busung.s25uroot

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The tap feedback every press in this app answers with.
 *
 * One definition rather than one per screen: it was a private copy in the main window and another in the
 * run window, and anything shared by those two - like the button that returns a long screen to its top -
 * needed a third. Confirmation where the platform has it, and the older long-press pulse below API 30
 * where it does not.
 */
internal fun clickHaptic(view: View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

/**
 * Whether a run that has just ended should buzz the phone.
 *
 * Only a failure, and only one somebody was there for. The other way a run can end without root is not this:
 * a stop is something the user asked for, so buzzing it would be the phone arguing with a decision they just
 * made. A boot gate is the third ending and it ran with nobody at the screen - a phone that buzzed in a
 * pocket after a restart would be telling someone about a run they never started.
 *
 * What is left is the case this exists for: the run failed while its owner was not watching it, which is the
 * same case the result notification is posted for. The notification sits in the shade; this is the half that
 * reaches a phone in a pocket.
 *
 * Pure, so the endings can be checked without a device.
 */
internal fun shouldBuzzRun(verdict: RunVerdict, unattended: Boolean): Boolean =
    verdict == RunVerdict.Failed && !unattended

/** How long the failure buzz runs: a nudge, not an alarm, and over before a hand reaches the phone. */
internal const val FAILURE_BUZZ_MILLIS = 120L

/**
 * Buzzes once for a run that failed, and does nothing at all for a run that ended any other way.
 *
 * The platform's own vibrator rather than the failure notification's channel, and that is the choice worth
 * writing down: a channel's vibration is a setting the system remembers per app, so a phone that once had it
 * off would keep this quiet for good with nothing in this app to say why. A short one-shot is still governed
 * by the phone's own haptic strength and system settings, and it is the same kind of feedback a press in this
 * app already answers with.
 *
 * `VibratorManager` and not the older `Context.VIBRATOR_SERVICE`: this app's floor is API 33, which is past
 * the level the manager arrived at, and the manager is what reports the device's default vibrator rather
 * than one being named by hand. A phone with no vibrator does nothing, which is not a failure.
 */
internal fun runFailureBuzz(context: Context, verdict: RunVerdict, unattended: Boolean) {
    if (!shouldBuzzRun(verdict, unattended)) return
    val vibrator = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull() ?: return
    if (!vibrator.hasVibrator()) return
    runCatching {
        vibrator.vibrate(
            VibrationEffect.createOneShot(FAILURE_BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }
}
