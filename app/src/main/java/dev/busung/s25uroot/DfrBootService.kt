package dev.busung.s25uroot

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.busung.s25uroot.dfr.DfrApk
import dev.busung.s25uroot.dfr.DfrBootDecision
import dev.busung.s25uroot.dfr.DfrHelperStanding
import dev.busung.s25uroot.dfr.DfrInstall
import dev.busung.s25uroot.dfr.dfrBootDecision
import dev.busung.s25uroot.dfr.isWorthReporting
import dev.busung.s25uroot.dfr.startsTheHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reroot-at-boot path: a boot with no root asks the stage-two helper to put it back.
 *
 * A phone rooted through the system-uid flow wakes up unrooted, because the KernelSU in its kernel comes
 * from an exploit that runs at boot rather than from anything written to disk. The helper is installed as a
 * system-uid app, so the whole of what is needed is one `am start` with the autorun extra - and the reason
 * this is a service with a gate rather than a line in the boot receiver is that "one `am start`" is only
 * true when the phone is in the right state to receive it. [dfrBootDecision] is that state, as a rule.
 *
 * **The transport is Shizuku's plain shell.** With no root there is exactly one thing on the device that can
 * run a command: the `shell` user, which is what a running Shizuku server answers as. So the launch goes
 * through [KernelSuRuntime.unprivilegedShell], and the same shell is what reads the exploit's own marker -
 * the one fact about this boot that no app-side read can answer. A boot where Shizuku is not up yet is
 * therefore not a failure but a wait: the app's own Shizuku start is doing the same boot's work at the same
 * time, and a reroot refused because the shell was two seconds behind its own starter would be a feature that
 * never works.
 *
 * **It waits for the same boot-settle floor Root on boot waits for.** The helper's run is an exploit attempt on
 * a boot that is still settling, which is the state the floor exists for, and it is the same decision about the
 * same kind of boot - so it is the same setting, read through [AppPreferences.bootGateSettleSeconds] and waited
 * out by [BootSettle.awaitFloor]. The wait is on the device's uptime, so it overlaps the payload's own window
 * over the same boot instead of stacking on top of it, and the decision is asked again once it is over: a
 * settle is a minute in which a run started by hand can root the phone, and starting the exploit on a phone
 * that is already rooted is the one thing this gate must never do.
 *
 * **It waits, it does not start Shizuku itself.** Starting the server is [ShizukuBootService]'s job, decided
 * by the user's own setting and run by the boot receiver; a second starter racing it would be two servers
 * asked for, and the routes that work are the ones that service already knows about.
 *
 * **What it does after a success is the part that makes the *next* boot work.** The daemon's own `late-load`
 * renames `/data/local/tmp/.ksud-stage` onto `/data/adb/ksud` and so *consumes* it, and the app writes that
 * file as root - which a boot with no root cannot do. So the moment root is live in this boot is the moment to
 * write it again, and that is done here, once, best-effort: without it the next boot would start the helper
 * and the helper's daemon would abort on a missing stage file, which reads as the exploit having failed.
 */
class DfrBootService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gateJob: Job? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        BootServiceStart.ensureChannel(this, CHANNEL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (gateJob?.isActive == true) return START_NOT_STICKY
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.dfr_boot_checking), ongoing = true),
        )
        gateJob = scope.launch {
            try {
                runGate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // The same last line of defence the payload gate has, for the same reason: everything the
                // gate does can throw, and an uncaught throw here is a crash dialog after a reboot and no
                // notification at all - which reads as the app being broken.
                AppLog.error(AppLogTags.BOOT, "Reroot at boot aborted before it could report", error)
                runCatching {
                    finish(getString(R.string.dfr_boot_failed, error.message ?: error.javaClass.simpleName))
                }.onFailure { teardownQuietly() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    private suspend fun runGate() {
        val bootToken = AutoRootSupport.currentBootToken()
        if (bootToken == null) {
            AppLog.warn(AppLogTags.BOOT, "Reroot at boot skipped: the kernel boot id could not be read")
            stopWithoutResult()
            return
        }
        val wakeLock = acquireGateWakeLock()
        try {
            // The helper's own standing is read once, before any waiting: it is a fact about the phone, and
            // nothing that happens during a two-minute wait can change it.
            val helper = withContext(Dispatchers.IO) {
                DfrInstall.helperStanding(this@DfrBootService, DfrApk.bundled(this@DfrBootService).file)
            }
            var decision = decide(bootToken, helper)
            if (decision.startsTheHelper()) {
                // The settle, before anything is launched and after the decision that says there is
                // something to launch: a boot that is going to be told to sit this one out is not made to
                // sit through a wait first. A withdrawal during it is the setting being turned off while
                // the gate waits, which is the one ending that is not a failure and not worth a
                // notification: nothing was asked for any more.
                if (awaitSettledFloor() == BootSettleWait.Abandoned) {
                    AppLog.info(
                        AppLogTags.BOOT,
                        "Reroot at boot stood down during the settle: the setting was turned off",
                    )
                    stopWithoutResult()
                    return
                }
                decision = decide(bootToken, helper)
            }
            if (decision == DfrBootDecision.NoShell && awaitShell()) {
                // Asked again rather than assumed: a wait of two minutes is long enough for the shell to
                // arrive, for Shizuku to be switched off, and for a run started by hand to root the phone in
                // the meantime - and each of those is a different answer from the one the wait started on.
                decision = decide(bootToken, helper)
            }
            AppLog.info(
                AppLogTags.BOOT,
                "Reroot at boot decision ${decision.name} (helper=$helper, " +
                    "KernelSU active=${RootStatusProbe.isActive()})",
            )
            when {
                decision.startsTheHelper() -> startTheHelper(bootToken)
                decision.isWorthReporting() -> finish(
                    reasonFor(decision),
                    // The way out of the one refusal a minute of waiting could still fix: the helper is fine
                    // and the kernel is clean, so a second ask now that the shell is up is a run rather than
                    // a repetition.
                    offerStartNow = decision == DfrBootDecision.NoShell,
                )
                else -> stopWithoutResult()
            }
        } finally {
            releaseQuietly(wakeLock)
        }
    }

    /**
     * The decision for this boot, measured now.
     *
     * A function rather than one reading, because the gate asks twice: once as it finds the phone, and once
     * after waiting for a shell. Both readings are of the same instant it is called at, which is the point -
     * what changed between the two calls is the phone.
     */
    private suspend fun decide(
        bootToken: String,
        helper: DfrHelperStanding,
    ): DfrBootDecision = dfrBootDecision(
        enabled = AppPreferences.rerootAtBoot(this),
        // The authoritative reading, off the main thread: the native paths can be denied by policy while
        // root is live, and on this path a wrong no spends the boot starting an exploit that cannot run.
        kernelSuActive = RootStatusProbe.isActive(),
        attemptedBootToken = bootToken.takeIf { AutoRootSupport.hasAttemptedRerootBoot(this, it) },
        bootToken = bootToken,
        helper = helper,
        // The marker, through the shell that would do the launching: null is "no shell to ask", which is
        // both the reading this cannot make and the means this does not have.
        armed = DfrInstall.probeWithoutRoot()?.armed,
    )

    /**
     * Waits out the boot-settle floor - the shared one, on the shared loop.
     *
     * See [BootSettle.GATE_DEFAULT_SECONDS] for why both boot gates read one value, and
     * [DfrBootService]'s own note for why this gate waits at all.
     *
     * [BootSettleWait.Abandoned] is passed through to the caller rather than answered here, because what
     * to do about a withdrawn setting is the gate's decision: for this one it is to stop quietly, since
     * a boot nobody asked to have rerooted is not a failure to report.
     */
    private suspend fun awaitSettledFloor(): BootSettleWait = BootSettle.awaitFloor(
        requiredSeconds = AppPreferences.bootGateSettleSeconds(this),
        onWaiting = { left ->
            notifyOngoing(getString(R.string.status_boot_settle, BootSettle.formatRemaining(left)))
        },
        // Checked on every pass for the reason awaitShell checks it: this wait is minutes in which the
        // setting can be turned off, and waiting for something no longer wanted is only a delay.
        stillWanted = { AppPreferences.rerootAtBoot(this) },
    )

    /**
     * Waits for a shell to start the helper with, reporting itself.
     *
     * Bounded, and the countdown is the notification's, on the same terms as the payload gate's waits: an
     * unattended boot has nobody to tell, and a silent two minutes behind a notification that says "checking"
     * looks exactly like a gate that has hung.
     *
     * True as soon as the shell answers, which is read by making it do something: `ShizukuController` can say
     * whether a server is running and whether this app may use it, and neither of those is a shell that
     * answers.
     */
    private suspend fun awaitShell(): Boolean {
        var waited = 0L
        while (waited < SHELL_WAIT_MILLIS) {
            if (shellAnswers()) return true
            // The setting can be turned off while this waits - it is minutes in which somebody may open the
            // app - and waiting for something no longer wanted is only a delay. Checked here rather than
            // outside, because this is where the wait stops being short.
            if (!AppPreferences.rerootAtBoot(this)) return false
            notifyOngoing(
                getString(
                    R.string.dfr_boot_waiting_for_shizuku,
                    BootSettle.formatRemaining(SHELL_WAIT_MILLIS - waited),
                ),
            )
            delay(SHELL_TICK_MILLIS)
            waited += SHELL_TICK_MILLIS
        }
        return shellAnswers()
    }

    private fun shellAnswers(): Boolean = KernelSuRuntime.unprivilegedShell("id") != null

    /**
     * Starts the helper and watches for what it did.
     *
     * The attempt is claimed here rather than when the boot was first considered, which is the one place
     * this differs from the payload gate above it: a claim made early would spend the boot's one attempt on
     * a launch that never happened, and the notification's own retry - the answer offered when there was no
     * shell - is only reachable while the attempt is still unspent.
     */
    private suspend fun startTheHelper(bootToken: String) {
        if (!AutoRootSupport.claimRerootAttempt(this, bootToken)) {
            AppLog.warn(AppLogTags.BOOT, "Reroot at boot skipped: this boot's reroot attempt is already spent")
            stopWithoutResult()
            return
        }
        notifyOngoing(getString(R.string.dfr_boot_starting))
        val launch = DfrInstall.launchWithoutRoot(
            autorun = true,
            // Told, so the helper's own boot row can say what this app's setting is. It is this app's
            // setting: the boot receipt and the once-per-boot rule are on this side.
            rerootAtBoot = AppPreferences.rerootAtBoot(this),
            // And the flavour, for the same reason and a different subject: which KernelSU this boot ends
            // up carrying is a fact about the payload this app resolved, so the helper's own manager row
            // has to be told it rather than left to guess between three installed managers.
            flavor = AppPreferences.kernelsuFlavor(this),
        )
        if (launch == null) {
            AppLog.warn(AppLogTags.BOOT, "Reroot at boot could not start the helper: no shell answered")
            finish(getString(R.string.dfr_boot_no_shell), offerStartNow = true)
            return
        }
        AppLog.info(
            AppLogTags.BOOT,
            "Reroot at boot started the helper: ${launch.log.lineSequence().firstOrNull().orEmpty()}",
        )
        if (!launch.ok) {
            finish(getString(R.string.dfr_boot_start_refused, launch.log.lineSequence().firstOrNull().orEmpty()))
            return
        }
        notifyOngoing(getString(R.string.dfr_boot_watching))
        if (awaitRoot()) {
            // Root is live in this boot: the daemon that was just loaded consumed the stage file it needs
            // to be late-loaded again, so it is written back now - while there is a root shell to write it
            // with. A failure here is logged and not reported: the phone is rooted, and the next boot's
            // problem is not this notification's to fail over.
            val staged = DfrInstall.stageDaemon(this)
            AppLog.info(
                AppLogTags.BOOT,
                "Reroot at boot succeeded; the stage file for the next boot was " +
                    if (staged?.ok == true) "written again" else "not written",
            )
            finish(getString(R.string.dfr_boot_rerooted))
            return
        }
        AppLog.warn(AppLogTags.BOOT, "Reroot at boot: the helper ran and KernelSU is not loaded")
        finish(getString(R.string.dfr_boot_no_root))
    }

    /**
     * Whether KernelSU came up, watched for as long as the exploit can plausibly take.
     *
     * Polled rather than listened for: the load happens in another app's process and there is no callback to
     * register - the same reason the run screen polls the device for the daemon it just loaded. The reading
     * is the app's own, which needs no shell: what is being asked is about the kernel, and that is the one
     * question this app can answer while everything else is still waiting for a transport.
     */
    private suspend fun awaitRoot(): Boolean {
        var waited = 0L
        while (waited < REROOT_WATCH_MILLIS) {
            if (RootStatusProbe.isActive()) return true
            delay(SHELL_TICK_MILLIS)
            waited += SHELL_TICK_MILLIS
        }
        return RootStatusProbe.isActive()
    }

    /**
     * The sentence for a refusal, from the decision that produced it.
     *
     * A `when` over the enum with no `else`, so a reason added later cannot reach the notification as a
     * decision with nothing to say. The two silent decisions never get here - see [isWorthReporting] - and
     * are listed anyway, because "this has no sentence"
     * and "this is not reported" being the same line is how a new reason comes to be silent by accident.
     */
    private fun reasonFor(decision: DfrBootDecision): String = getString(
        when (decision) {
            DfrBootDecision.SkipArmed -> R.string.dfr_boot_armed
            DfrBootDecision.NoHelper -> R.string.dfr_boot_no_helper
            DfrBootDecision.NotSystemUid -> R.string.dfr_boot_ordinary_helper
            DfrBootDecision.StaleHelper -> R.string.dfr_boot_stale_helper
            DfrBootDecision.NoShell -> R.string.dfr_boot_no_shell
            DfrBootDecision.SkipDisabled,
            DfrBootDecision.SkipAlreadyRooted,
            DfrBootDecision.SkipAttempted,
            DfrBootDecision.Reroot,
            -> R.string.dfr_boot_nothing_to_do
        },
    )

    private fun notifyOngoing(message: String) {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message, ongoing = true),
        )
    }

    /** The gate is over: the notification stops being ongoing and says how it went. */
    private fun finish(message: String, offerStartNow: Boolean = false) {
        if (stopping) return
        stopping = true
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message, ongoing = false, offerStartNow = offerStartNow),
        )
        stopForegroundCompat()
        stopSelf()
    }

    /** Nothing to report, and nothing to leave behind: used when the gate decides not to run. */
    private fun stopWithoutResult() {
        if (stopping) return
        stopping = true
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardownQuietly() {
        stopping = true
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        runCatching { stopForegroundCompat() }
        runCatching { stopSelf() }
    }

    /**
     * A wake lock is an optimisation here, exactly as it is for the payload gate: without one the waits
     * simply become suspendable, and the permission is enforced by a binder call whose `SecurityException`
     * would kill this process from the inside. Never allowed to decide whether the boot gets its reroot.
     */
    private fun acquireGateWakeLock(): PowerManager.WakeLock? = runCatching {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:DfrBootGate")
            .also { it.acquire(GATE_LIMIT_MILLIS) }
    }.onFailure {
        AppLog.warn(
            AppLogTags.BOOT,
            "No wake lock for the reroot gate (${it.javaClass.simpleName}: ${it.message}); carrying on without one",
        )
    }.getOrNull()

    private fun releaseQuietly(wakeLock: PowerManager.WakeLock?) {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    /**
     * [offerStartNow] is the way out of the one refusal that is only about *when*: no shell was up in the
     * minute this gate waited, and by the time anyone reads the notification there may be one. It re-runs the
     * gate rather than launching the helper from a broadcast, so the same rule decides again - a phone that
     * has been rooted by hand in the meantime is told so instead of being sent a second exploit.
     */
    private fun buildNotification(
        message: String,
        ongoing: Boolean,
        offerStartNow: Boolean = false,
    ) = NotificationCompat
        .Builder(this, CHANNEL.id)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(getString(R.string.dfr_boot_title))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        // The app, which is where the flow is: every sentence above is either about the helper's standing or
        // about a restart, and both are answered on that screen rather than from the shade.
        .setContentIntent(runRecordPendingIntent(this, null))
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply {
            if (offerStartNow) {
                addAction(
                    0,
                    getString(R.string.dfr_boot_action_start_now),
                    PendingIntent.getBroadcast(
                        this@DfrBootService,
                        0,
                        Intent(this@DfrBootService, DfrBootActionReceiver::class.java)
                            .setAction(DfrBootActionReceiver.ACTION_START_NOW),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        .build()

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        /**
         * Starts the gate, which decides for itself whether this boot gets a reroot.
         *
         * A boot broadcast is one of the places Android may refuse the start outright - the background-start
         * rules, or the app sitting in a restricted bucket - and [BootServiceStart] answers that with a
         * notification rather than a log line, because a phone that reboots unrooted with nothing in the
         * shade is what the feature being switched off looks like from the outside.
         */
        fun start(context: Context) {
            BootServiceStart.start(
                context = context,
                service = DfrBootService::class.java,
                channel = CHANNEL,
                notificationId = NOTIFICATION_ID,
                titleRes = R.string.dfr_boot_not_started_title,
                textRes = R.string.dfr_boot_not_started,
            )
        }

        /**
         * Ends a gate that is already running, for the moment the setting is turned off.
         *
         * The waits check the setting themselves - see [awaitShell] - so this is not what makes the
         * decision notice; it is what stops a service that is between two of those checks from
         * finishing the boot's work after the user has said not to. The notification goes with it,
         * because the gate that posted it is the only thing that knows what it was about to say.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, DfrBootService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        /**
         * The channel the gate reports on, and the one a refused start is reported on: see
         * [BootServiceStart.ensureChannel] for why the second case can make it itself.
         */
        private val CHANNEL = BootServiceStart.Channel(
            id = "dfr_boot",
            nameRes = R.string.dfr_boot_channel_name,
            descriptionRes = R.string.dfr_boot_channel_description,
        )

        /** Its own id, so a reroot result and an install result do not replace each other in the shade. */
        private const val NOTIFICATION_ID = 0x44465242

        /** How long the gate waits for a shell, which is how long the boot's own Shizuku start has. */
        private const val SHELL_WAIT_MILLIS = 120_000L

        /** How long the exploit is given to show up in the kernel before it is called a failure. */
        private const val REROOT_WATCH_MILLIS = 90_000L

        /** The tick for both waits: the countdown's step and how often the two readings are taken. */
        private const val SHELL_TICK_MILLIS = 2_000L

        /**
         * The wake lock's own ceiling, above everything the gate waits for.
         *
         * The settle is in here through [BootSettle.GATE_CEILING_MILLIS], the longest the setting can
         * ask for, rather than through the value in force at this boot: a budget that followed the
         * setting would expire on a phone whose owner had asked for a long wait and report it as the
         * gate giving up.
         */
        private val GATE_LIMIT_MILLIS =
            (BootSettle.GATE_CEILING_MILLIS + SHELL_WAIT_MILLIS + REROOT_WATCH_MILLIS + 30_000L)
    }
}

/**
 * The one action the reroot notification offers: ask the gate again, now.
 *
 * A broadcast to a service start rather than a launch from here, so the decision is made by the same rule
 * that made it at boot - see [DfrBootService.start]. The notification it replaces is this service's own,
 * which is why nothing is cancelled here: the gate posts its first line into the same id a moment later.
 */
class DfrBootActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_NOW) return
        AppLog.info(AppLogTags.BOOT, "The reroot gate was asked again from the notification")
        DfrBootService.start(context)
    }

    companion object {
        const val ACTION_START_NOW = "dev.busung.s25uroot.action.REROOT_NOW"
    }
}
