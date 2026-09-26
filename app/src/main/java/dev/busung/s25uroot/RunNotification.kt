package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The run, in the shade.
 *
 * A run takes up to fifteen minutes and the phone is usually in a pocket while it does, which is the whole
 * reason this exists: the stage it has reached and the two things worth being able to do from there - stop it,
 * and keep what it has printed - without opening the app. The screen's own transport for stopping a run
 * already existed; the notification is what makes it reachable from where the person actually is.
 *
 * Deliberately not a foreground service. A run started from the screen runs in this app's own process, and
 * what keeps it alive is the process the user is looking at; promoting it to a service would be a promise this
 * change is not making - that a run survives the app being swiped away - and the app has never made it. What
 * this does promise is the two actions, and they work while the process does.
 *
 * One notification id, so every phase replaces the last rather than stacking. Cleared by the run itself, and
 * swept at launch: a notification whose run was killed with its process would otherwise sit there claiming an
 * install that is not happening, which is worse than no notification at all.
 *
 * Where the platform has a live update to give, this is also asked for - see [LIVE_UPDATE_API]. The system
 * then promotes the same notification into a status bar chip, a card at the top of the shade and a lock screen
 * card, so how far along a run is is readable from a pocket without opening anything. It is an ask and not a
 * claim: a phone without the API, without the permission, or with live updates switched off posts exactly the
 * notification it posted before.
 */
internal object RunNotification {

    private const val CHANNEL_ID = "run"

    /**
     * The API that promotes an ongoing notification to a live update.
     *
     * Android 16, and the three calls that shape one - `ProgressStyle`, `setShortCriticalText` and
     * `setRequestPromotedOngoing` - are all from it, so each of them is behind this and not only the
     * promotion: on an older phone they are a no-op the framework would ignore at best.
     */
    private const val LIVE_UPDATE_API = 36

    /** Read by the receiver, which updates the same notification after acting on it. */
    internal const val NOTIFICATION_ID = 0x52554e31

    /** Whether this process has already made the channel. Idempotent server-side, wasteful per post. */
    @Volatile
    private var channelReady = false

    /**
     * Posts or replaces the notification for the stage a run has reached.
     *
     * Progress is the run's own fraction along its stages - the same one the status card and the run's bar
     * draw - so what the shade says and what the screen says cannot come apart. [phase] comes with it because
     * the live update's chip needs a word for the same stage, and the two are one fact: a fraction the chip
     * could not name is not a stage anybody would recognise in the status bar. The permission is checked by
     * whether the post throws: an app that has been denied notifications should not have its run fail, and the
     * first failure is logged once rather than on every phase.
     */
    fun post(
        context: Context,
        message: String,
        phase: InstallPhase,
        progress: Float,
        runId: String?,
    ) {
        ensureChannel(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                builder(
                    context = context,
                    message = message,
                    verdict = RunVerdict.Running,
                    withActions = true,
                    runId = runId,
                    stage = Stage(progress, phase),
                ).build(),
            )
        }.onFailure { error ->
            warnOnce(context, "the run notification could not be posted", error)
        }
    }

    /**
     * Leaves the outcome in the shade instead of taking the notification away.
     *
     * A run is usually spent with the phone in a pocket, and the screen it was started from is not what
     * anyone is looking at when it ends. So a run that did not simply succeed stays: it wears its verdict's
     * own colour and word, it is no longer ongoing, and it goes when it is tapped. A success still clears -
     * the card on Home is the account of that, and a notification saying "done" about the thing you just did
     * is noise.
     */
    fun finish(context: Context, message: String, verdict: RunVerdict, runId: String?) {
        ensureChannel(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                builder(context, message, verdict, withActions = false, runId = runId).apply {
                    setOngoing(false)
                    setAutoCancel(true)
                }.build(),
            )
        }.onFailure { error ->
            warnOnce(context, "the run's result could not be posted", error)
        }
    }

    /**
     * Says something in the notification's own place, without the progress bar.
     *
     * Used by the two actions: the run is at some stage, and what a person needs after tapping is that the tap
     * was taken. The next phase posts the bar again, so the bar being absent for a moment says nothing wrong.
     */
    fun note(context: Context, message: String, runId: String?) {
        ensureChannel(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                builder(context, message, RunVerdict.Running, withActions = true, runId = runId).build(),
            )
        }.onFailure { error ->
            warnOnce(context, "the run notification could not be updated", error)
        }
    }

    /** Takes it away, which nothing but the run's own end should do. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * Clears a notification no run is behind.
     *
     * At launch, because a run killed with its process cannot clear its own - and a notification claiming an
     * install that is not happening is the one state worse than silence: it would keep someone waiting, and
     * its Stop button would do nothing. [RunInFlight] is the record that answers this for every process, not
     * just this one, so a boot run in flight keeps its notification.
     */
    fun clearStale(context: Context) {
        if (RunInFlight.holder(context) == null) clear(context)
    }

    /**
     * The notification, in the one shape both of its lives need.
     *
     * [withActions] is false only for an outcome, where there is nothing left to stop or to watch: a pair of
     * buttons that act on a run that has ended is worse than no buttons, and the Stop one would be the last
     * thing anyone tapped.
     *
 * [runId] is the run both are about, and it is what the tap carries - an outcome included, because the
 * run screen is the better place to land for an outcome too: it holds the failure card with the stage,
 * the reason and the three answers, where the record holds the log and the verdict. The screen hands the
 * tap on to the record when it is not the screen for that run, so the case an outcome outlives - the
 * process that produced it is gone - ends up in the same place it would have gone to directly.
 *
 * [stage] is the difference between the two lives this has: a step of a run carries one, and an outcome or a
 * note about a tap carries none, because there is no longer a bar to draw or a chip to name. Read with
 * [post], so the second bar the platform draws and the one the app draws are the same number.
 */
    private fun builder(
        context: Context,
        message: String,
        verdict: RunVerdict,
        withActions: Boolean,
        runId: String?,
        stage: Stage? = null,
    ) = NotificationCompat
        .Builder(context, CHANNEL_ID)
        // The verdict's own colour and glyph, so the shade says the same thing the card does - words
        // included, since a colour is not available to everyone reading a notification.
        .setColor(VerdictTint.of(verdict))
        .setSmallIcon(verdictSmallIcon(verdict))
        .setContentTitle(context.getString(verdict.label))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        // One destination for both lives, because [InstallActivity] is the thing that knows whether this
        // process has that run: it shows it when it does, and hands the tap to the run's record when it
        // does not. Choosing here would mean this file deciding from a flag what only the screen can see.
        .setContentIntent(liveRunPendingIntent(context, runId))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply {
            // The bar, and where the platform has one, the live update. Both live in [live], because the
            // boot gate's notification is about the same run and is the one a phone in a pocket actually
            // shows after a reboot: it asks for its own with the same call.
            stage?.let { live(this, context.getString(chipLabel(it.phase)), it.fraction) }
            if (!withActions) return@apply
            addAction(
                0,
                context.getString(R.string.action_stop_run),
                actionPendingIntent(context, RunActionReceiver.ACTION_STOP, requestCode = 1),
            )
            addAction(
                0,
                context.getString(R.string.logs_copy),
                actionPendingIntent(context, RunActionReceiver.ACTION_COPY_LOG, requestCode = 2),
            )
        }

    /**
     * What a live notification adds to an ordinary one: how far the run has come, and the phase that says it.
     *
     * One value rather than two arguments, because a fraction with no word and a word with no fraction are
     * the same mistake - a live update the platform cannot draw - and the two are one fact about one stage.
     */
    private data class Stage(val fraction: Float, val phase: InstallPhase)

    /**
     * The bar, and where this phone has one, the live update - for any notification about the run.
     *
     * Here rather than in either caller because there are two of them: the run's own, which this object
     * builds, and the boot gate's, which is the one a phone in a pocket actually shows after a reboot. Two
     * live updates for one run that disagreed about how far along it was would be worse than one nobody
     * promoted, and the platform's rules for one are a single list - the style it draws, the chip word, the
     * request, and the API level all three of them come from - so they are kept in one place.
     *
     * [chip] is the status bar chip's word, which the platform draws only where it fits 96dp, and [fraction]
     * is the run's own progress: null for a wait that cannot be measured in one, which is still promoted - a
     * live update may carry no bar at all.
     */
    internal fun live(builder: NotificationCompat.Builder, chip: String, fraction: Float?) {
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        if (Build.VERSION.SDK_INT >= LIVE_UPDATE_API) {
            // ProgressStyle and not the builder's own progress, because the style is what the platform reads:
            // an ongoing notification is only promoted when its style is one of the five it draws, and this
            // is the one a bar belongs in. It writes the ordinary progress itself from the same value, so
            // asking for both would be two claims about one bar.
            if (percent != null) {
                builder.setStyle(NotificationCompat.ProgressStyle().setProgress(percent))
            }
            builder.setShortCriticalText(chip)
            builder.setRequestPromotedOngoing(true)
            return
        }
        // No live update on this phone, so the same bar in the ordinary notification.
        if (percent != null) builder.setProgress(100, percent, false)
    }

    /**
     * The word the status chip gets for a phase, which is the one place in the app a phase is not a sentence.
     *
     * The chip is 96dp wide and the system draws text in it only when the text fits, so the message the shade
     * is given would arrive as a fragment or as nothing at all. Every phase has a word rather than only the
     * ones a run passes through, because a phase that was added without one would post a live update with an
     * empty chip - and the compiler asking for this line is what stops that.
     */
    internal fun chipLabel(phase: InstallPhase): Int = when (phase) {
        InstallPhase.Probing, InstallPhase.Ready -> R.string.run_chip_starting
        InstallPhase.Checking -> R.string.run_chip_checking
        InstallPhase.Settling -> R.string.run_chip_waiting
        InstallPhase.Downloading -> R.string.run_chip_download
        InstallPhase.Exploiting -> R.string.run_chip_exploit
        InstallPhase.LoadingKernelSu -> R.string.run_chip_loading
        InstallPhase.Installed, InstallPhase.RootOnly -> R.string.run_chip_rooted
        InstallPhase.Failed -> R.string.run_chip_failed
        InstallPhase.Stopped -> R.string.run_chip_stopped
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RunActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        channelReady = true
        runCatching {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.run_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.run_notification_channel_description)
                },
            )
        }
    }

    /** A denied notification permission is one fact, not one per phase. */
    private fun warnOnce(context: Context, message: String, error: Throwable) {
        if (warned) return
        warned = true
        AppLog.warn(AppLogTags.RUN, "$message: ${error.javaClass.simpleName}")
        // Deliberately silent about it on screen: a run is not failing here, and the notification is a
        // convenience rather than a part of the install.
    }

    /**
     * The system glyph for a verdict.
     *
     * The framework's own drawables rather than this app's, because a notification icon is drawn as a
     * silhouette on a background the app does not control - and because the shapes read as the outcomes: a
     * download while it works, a finished one, and the warning triangle for everything that ended without a
     * loaded KernelSU.
     */
    private fun verdictSmallIcon(verdict: RunVerdict): Int = when (verdict) {
        RunVerdict.Succeeded -> android.R.drawable.stat_sys_download_done
        RunVerdict.Running -> android.R.drawable.stat_sys_download
        RunVerdict.Idle, RunVerdict.RootOnly, RunVerdict.Failed, RunVerdict.Stopped ->
            android.R.drawable.stat_notify_error
    }

    @Volatile
    private var warned = false
}
