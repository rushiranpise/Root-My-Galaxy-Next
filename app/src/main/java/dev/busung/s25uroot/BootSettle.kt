package dev.busung.s25uroot

import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * How long after a boot a run waits before the exploit starts.
 *
 * The wait is measured from the boot, not from the moment the run was asked for: a device that has
 * already been up longer than the requirement waits not at all, and one that was rebooted ten seconds
 * ago waits the rest. That is the point of the gate, because what it is protecting is the state of a
 * freshly booted device, and a race attempted while the system is still settling fails for reasons the
 * payload cannot fix.
 *
 * The default is not zero. The exploit this app runs has a racy stage that a cold device makes worse,
 * and the wait costs two minutes once per boot against a failed attempt that costs the whole run. It
 * is a floor rather than a hard block: [InstallViewModel.skipBootSettle] ends the wait on the user's
 * word, because someone who knows their device just booted cleanly is better informed than a constant.
 */
internal object BootSettle {
    /** What a manual run waits for unless it is told otherwise. */
    const val DEFAULT_SECONDS = 120

    /**
     * What both unattended gates wait for, and deliberately not [DEFAULT_SECONDS].
     *
     * One value for two gates - Root on boot and Reroot at boot - because they are the same decision
     * about the same kind of boot: a boot that is acting with nobody at the screen. Two settings here
     * would be two claims about how settled a device has to be before either may act, and a phone that
     * came back unrooted because one of them was left at a value the other was not is not a thing
     * anyone could tell apart from the exploit failing.
     *
     * It is still not [DEFAULT_SECONDS], and the reason is the one thing the two gates do not share
     * with a manual run: an unattended gate is woken by `BOOT_COMPLETED`, so the part of the boot that
     * precedes it has already been waited out, and it is this boot's own elapsed time - not a pause
     * per attempt - that both read. If an unattended gate read the manual setting, then tuning
     * automation would silently rewrite what a manual run does next time.
     */
    const val GATE_DEFAULT_SECONDS = 60

    /**
     * What the setting offers. Rounded to these rather than free-form: a value nobody tested is not a
     * better one, and a round number is what makes the choice reviewable.
     */
    val allowedSeconds = listOf(0, 30, 60, 90, 120, 180, 300, 600)

    /**
     * The longest floor the setting can ask for, which is what a gate's own budget has to allow for.
     *
     * Derived from [allowedSeconds] rather than written out, because the two are one fact: a gate that
     * budgeted for less than the setting offers would be cut off by its own timeout part-way through a
     * wait the user had asked for, and it would report that as the gate giving up rather than as the
     * wait it was told to do.
     */
    val GATE_CEILING_MILLIS: Long = allowedSeconds.max() * 1_000L

    /**
     * The tick both gates' waits report on.
     *
     * Never read from the clock to count the wait down: each pass reads the device's own uptime again
     * through [remainingMillis], which is what makes a deep sleep during the wait cost nothing rather
     * than leaving a countdown to resume where it stopped.
     */
    const val TICK_MILLIS = 1_000L

    /** The offered value nearest to [seconds], so a stored number is always one of them. */
    fun normalize(seconds: Int): Int =
        allowedSeconds.minByOrNull { abs(it - seconds) } ?: DEFAULT_SECONDS

    /**
     * Milliseconds still to wait, given the boot's elapsed time.
     *
     * Returns zero rather than a negative number once the boot is old enough, so a caller can test the
     * result rather than having to know which side of the subtraction it is on.
     */
    fun remainingMillis(requiredSeconds: Int, elapsedRealtimeMillis: Long): Long =
        (normalize(requiredSeconds) * 1_000L - elapsedRealtimeMillis).coerceAtLeast(0L)

    /** Time since boot, which is what the gate is measured against and what survives a deep sleep. */
    fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    /**
     * `1:42` for a countdown.
     *
     * Rounded up, so a wait never reads `0:00` while it is still waiting: the last second of a
     * countdown is a second, and showing zero during it would say the run had started when it had not.
     */
    fun formatRemaining(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
        // The locale is pinned because `%d` is not: `Formatter` renders an integer in the default
        // locale's own digits, so on a device set to Arabic, Persian, Bengali or Devanagari a countdown
        // would read `١:٤٢`. A number counting down to a moment is a reading rather than prose, and the
        // one thing it must not do is change shape with a locale setting - the same reason the log
        // timestamps and the export filenames are pinned.
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /**
     * Waits out the floor, reporting the countdown, and says how it ended.
     *
     * The same shape as [NetworkReach.awaitConnected], and split out of the two services for the same
     * reason: both gates want one loop with one tick and one decision about a withdrawn setting, and
     * two copies of "is this boot settled enough to act unattended" is exactly how the two of them
     * come to disagree about it.
     *
     * [stillWanted] is asked on every pass and is not decoration: this is the whole of a boot gate's
     * first minute, and the setting it exists for can be turned off from the app while it runs. The
     * caller cannot end the wait from outside, because the wait does not return until it is over.
     *
     * The two endings are checked in a fixed order, and the order is the answer: a boot that is already
     * past the floor is reported as settled even if the caller has since changed its mind, because the
     * uptime is a fact about the device while the wait is a decision about this caller - and there is
     * nothing left for the decision to do.
     *
     * [tickMillis] and [uptimeMillis] are the seams the same loop is pinned through in a local JVM
     * test, where the passages of the loop are the subject rather than the second between them - and
     * where the clock has to be a fake one, because the device's real uptime is a `SystemClock` call no
     * such test can make. Both default to this object's own answer, which is the one every caller in the
     * app takes.
     */
    suspend fun awaitFloor(
        requiredSeconds: Int,
        onWaiting: (remainingMillis: Long) -> Unit = {},
        stillWanted: () -> Boolean = { true },
        tickMillis: Long = TICK_MILLIS,
        uptimeMillis: () -> Long = { elapsedMillis() },
    ): BootSettleWait {
        while (true) {
            val left = remainingMillis(requiredSeconds, uptimeMillis())
            if (left <= 0L) return BootSettleWait.Settled
            if (!stillWanted()) return BootSettleWait.Abandoned
            onWaiting(left)
            delay(tickMillis)
        }
    }

    /** The setting's own label for a value, as the chooser and the run plan show it. */
    fun label(seconds: Int): String {
        val normalized = normalize(seconds)
        val minutes = normalized / 60
        val rest = normalized % 60
        return when {
            normalized == 0 -> "Off"
            minutes == 0 -> "$rest s"
            rest == 0 -> "$minutes min"
            else -> "$minutes min $rest s"
        }
    }
}

/**
 * How a gate's wait for the boot-settle floor ended.
 *
 * Two answers rather than a boolean, for the reason [NetworkWait] has three: "the device was settled"
 * and "nobody wants this anymore" are different outcomes with different consequences - one lets the
 * gate act, the other means the boot's work was withdrawn mid-wait and the gate must stop quietly
 * rather than report a failure it did not have.
 */
internal enum class BootSettleWait {
    /** The device had been up for the floor, so a gate may act. */
    Settled,

    /** The setting behind the gate was turned off during the wait, so nothing may be done. */
    Abandoned,
}
