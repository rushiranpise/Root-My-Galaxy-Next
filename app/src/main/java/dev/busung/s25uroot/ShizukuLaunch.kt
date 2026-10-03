package dev.busung.s25uroot

/**
 * The Shizuku grant this app asks for on the way in, as a rule rather than as a side effect of opening.
 *
 * Asking at launch is a convenience for the person, not for the run: every path that needs Shizuku - the
 * payload flow, the helper flow's boot launch, the steps that read the control channel - has its own check and
 * its own words for a missing grant, and each of those happens where it matters. What none of them can do is
 * get the dialog out of the way early, which is all this is: the grant is per-app and per-install, so it is
 * asked for once rather than before every run.
 *
 * Three facts decide it, and each has a reason:
 *
 * - **the app's Shizuku setting.** A dialog raised on a phone that is set never to use Shizuku is a question
 *   with no purpose - and the answer would not be used.
 * - **the service running.** The permission belongs to the service: with it down there is nothing to ask, the
 *   binder is absent, and `ShizukuController.requestPermission` answers false without showing anything. Said
 *   rather than silently skipped, because a launch that asked for nothing looks like a launch that forgot.
 * - **not granted already.** A grant survives until it is revoked in the Shizuku app, and re-asking would put
 *   the dialog in front of someone who has already answered.
 *
 * The once-per-launch half of the rule is [takeFirstAsk], and the ask and its answer are logged by
 * `ShizukuController.requestPermission` itself - which is where they belong, because that is the call that raises
 * the dialog - so nothing here or at the call site repeats them. See
 * `MainActivity.maybeRequestShizukuPermission`.
 */
internal object ShizukuLaunch {

    /**
     * Whether this is the launch's first ask, and the answer for every later one.
     *
     * Per **process**, not per activity: `onCreate` runs again for a rotation, a theme change and a second
     * window, and the dialog is per-install rather than per-screen - so the flag cannot live with the activity,
     * which is what put two "asking" lines and, on a phone that had not answered yet, two dialogs on screen.
     */
    @Volatile
    private var asked = false

    fun takeFirstAsk(): Boolean {
        if (asked) return false
        asked = true
        return true
    }

    /** Whether a launch should raise the Shizuku permission dialog. */
    fun shouldAsk(shizukuMode: Boolean, running: Boolean, granted: Boolean): Boolean =
        shizukuMode && running && !granted

    /**
     * What a launch that asked for nothing says about it.
     *
     * Two sentences rather than one, because the two facts need different things done about them: a phone set
     * not to use Shizuku needs the setting, and a phone whose service is down needs the service started - which
     * this app offers from its own screen, and a dialog here could not.
     */
    fun nothingToAskLine(shizukuMode: Boolean, running: Boolean): String = when {
        !shizukuMode -> "No Shizuku permission asked for: this app is set not to use Shizuku"
        !running -> "No Shizuku permission asked for: the Shizuku service is not running"
        else -> "No Shizuku permission asked for: this app already has it"
    }
}
