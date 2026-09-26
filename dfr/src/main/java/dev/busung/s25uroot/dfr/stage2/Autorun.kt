package dev.busung.s25uroot.dfr.stage2

import android.app.Activity
import android.os.Build
import android.os.Process

/**
 * Whether this launch may ask for a run on its way in.
 *
 * The extra was the whole of the answer, and that is the hole this closes. This screen is exported because
 * the app starts it through a shell, and an exported component that acts on an extra acts on *anyone's*
 * extra: any app on the phone could put `rmg.autorun` in an intent, start this activity and spend the
 * boot's one attempt. An attempt is the whole budget - the exploit's first act is to arm itself, and
 * everything it does to the kernel outlives the process that did it - so a boot whose attempt was spent
 * this way cannot be rooted again without a restart, and nothing on the screen would say why.
 *
 * ## What the answer is made of
 *
 * Not a secret the app carries, because there is nowhere to put one: the launch happens while the phone
 * has no root, so it is made by the `shell` user through Shizuku, and a token written for this boot would
 * have to live somewhere both APKs can reach. `/data/system` is the only such place this helper can read -
 * `system_server` is denied `shell_data_file`, which is why the daemon has a copy there - and writing into
 * it takes root, which is the one thing a boot that needs rerooting does not have. A token is therefore
 * unavailable exactly where it is needed.
 *
 * So the question is asked of the platform instead: *who launched this activity*. The app's own launches
 * go through a shell, always - every one of them is an `am start` run by `ShizukuController` or by
 * `su` - so root or the `shell` user is the whole of the legitimate answer, and neither is an identity an
 * ordinary app can produce. A launch is authorized when the kernel says one of them sent it, and the
 * extra is then what it always claimed to be: the app saying a run was wanted, checked against the one
 * fact an app cannot forge.
 *
 * ## The hole in that, said plainly
 *
 * [Activity.getLaunchedFromUid] arrived in API 34, and this module's floor is API 33. A build that cannot
 * ask answers [AutorunVerdict.Unanswerable] and refuses the run, which is the right direction - a helper
 * on an older phone still opens and still runs when a person presses the button, and the boot path loses
 * its autorun rather than the phone losing its refusal. It is a real cost and it is said in the log
 * rather than left to look like the app's own gate having decided not to run.
 */
internal object Autorun {

    /**
     * The API level the launched-from reading arrived at.
     *
     * Written down because the check is a hard floor rather than a preference: below it this asks nothing
     * and refuses, so a build that grew a fallback would have to change this number deliberately.
     */
    const val READING_API = 34

    /**
     * The identities a launch may be authorized with, and the reason there are two rather than one.
     *
     * Root, because the app's launches are `am start` from a root shell whenever the phone has one - the
     * cheap route, and the one a run that is already rooted takes.
     *
     * The `shell` user, because the boot this exists for has no root: the reroot-at-boot launch is the same
     * `am start` sent by Shizuku, whose processes run as the `shell` user. Nothing else is here - not this
     * helper's own uid, which could only reach it by launching itself, and not the system uid, which is the
     * platform and needs no permission from an app.
     */
    val TRUSTED_UIDS: Set<Int> = setOf(0, Process.SHELL_UID)

    /**
     * The verdict for a launch, from what it asked for and who sent it.
     *
     * Pure, so every case can be checked without a device - and [launchedFromUid] nulls the reading rather
     * than guessing at it, so "this build cannot ask" and "this build asked and the answer is not one this
     * accepts" are decided in one place instead of two.
     */
    fun verdict(requested: Boolean, launchedFromUid: Int?): AutorunVerdict = when {
        !requested -> AutorunVerdict.NotRequested
        launchedFromUid == null -> AutorunVerdict.Unanswerable
        launchedFromUid in TRUSTED_UIDS -> AutorunVerdict.Granted
        else -> AutorunVerdict.Refused
    }

    /**
     * The uid of the process that started [activity], or null when this build cannot ask.
     *
     * Null below [READING_API] and null when the platform refuses the reading at all, because the two are
     * the same answer to the only question asked of it: an unreadable launcher is not one this may
     * authorize, and a helper that guessed would be back to trusting the extra.
     */
    fun launchedFromUid(activity: Activity): Int? =
        if (Build.VERSION.SDK_INT >= READING_API) {
            runCatching { activity.launchedFromUid }.getOrNull()
        } else {
            null
        }
}

/**
 * What a launch asked for, once the launcher has been weighed.
 *
 * Four cases rather than a boolean, and both of the refusing ones are separate because the log has to say
 * which happened: an app that sent a run it may not send is a phone with something else on it, while a
 * helper on a build that cannot ask is a phone that needs an update or a restart. One word for both would
 * send a reader to the wrong end of it.
 */
internal enum class AutorunVerdict {
    /** The extra asked for a run and the launcher is one that may ask for one. */
    Granted,

    /** No extra, or one that was not set: somebody opened this screen. */
    NotRequested,

    /** A run was asked for by a launcher that is neither root nor the shell. */
    Refused,

    /** A run was asked for and this build cannot read who launched it. */
    Unanswerable,
    ;

    /** Whether the run the launch asked for may start. */
    val runs: Boolean get() = this == Granted
}
