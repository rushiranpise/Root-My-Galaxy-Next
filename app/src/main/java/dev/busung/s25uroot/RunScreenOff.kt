package dev.busung.s25uroot

/**
 * The screen, while a run is happening.
 *
 * A run spends its minutes holding kernel memory it has already freed, racing whatever else wakes up in
 * the kernel while it does. The display is the largest of those things on a phone - pixels coming back,
 * GPU work queued behind them, and the worklist that runs with it - and the project this was ported from
 * put an awake screen at the top of the causes of runs that died there, having driven the same payload
 * family. So a run can put the screen out before the payload starts and bring it back when the run is over,
 * which is off unless [AppPreferences.screenOffDuringRun] asks for it, and the notification the run already
 * posts is what tells the result in the meantime.
 *
 * The power key rather than the platform's own sleep, and that is the point rather than a shortcut: putting
 * a device to sleep is an act the framework licenses to the system, while a key press is something the
 * shell this run already holds can send. The cost is that a run with no shell has nothing to press with,
 * which is [Decision.NoShell] and is said in the log rather than left to look like the setting doing
 * nothing.
 *
 * The key is also a *toggle*, which is what makes [decision] and [shouldWake] more than a constant. A phone
 * whose screen is already out would be woken by the press meant to put it out - and then put out again at
 * the end, so the run would finish with the screen dark and the person who pressed Run looking at nothing.
 * Each press is therefore asked for on its own terms, from the screen read at that moment.
 */
internal object RunScreenOff {

    /**
     * The power key, in the one shape a shell runs it.
     *
     * Absolute, because the transport that sends it is not a shell with a `PATH` in every case: Shizuku
     * hands a process straight to the system, with no interpreter in between.
     */
    internal const val POWER_KEY_COMMAND = "/system/bin/input keyevent 26"

    /**
     * How long the key is given before the run carries on without it.
     *
     * The payload is waiting behind this, so it is short: a key that has not been sent in five seconds is a
     * transport that is not going to send it, and a run held open any longer loses more than the screen it
     * was trying to save.
     */
    internal const val POWER_KEY_TIMEOUT_MILLIS = 5_000L

    /** What a run should do about the screen before the exploit, from the two facts that decide it. */
    internal enum class Decision {
        /** Press it: the screen is on, and this run has something that can send the key. */
        Press,

        /** The screen is already out, so there is nothing to do - and a press would undo it. */
        AlreadyOut,

        /** This run has no shell, so it cannot press anything and the screen stays on. */
        NoShell,
    }

    /**
     * The decision, as a function of the screen's state and whether this run can press the key.
     *
     * Pure, so the three cases can be checked without a device - and the one that matters is the second: it
     * is the difference between a phone that goes dark for the exploit and a phone that wakes up to watch
     * one happen.
     */
    internal fun decision(interactive: Boolean, canPress: Boolean): Decision = when {
        !canPress -> Decision.NoShell
        !interactive -> Decision.AlreadyOut
        else -> Decision.Press
    }

    /**
     * Whether the wake should press the key, from the state the screen is in when the run is over.
     *
     * The same reading one press later: a screen that is already on is one somebody woke to look at, and
     * pressing then would put it out rather than bring it back.
     */
    internal fun shouldWake(interactive: Boolean): Boolean = !interactive
}
