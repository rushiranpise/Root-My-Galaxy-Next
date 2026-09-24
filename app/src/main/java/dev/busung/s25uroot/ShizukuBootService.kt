package dev.busung.s25uroot

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts Shizuku's server at boot, by whichever of the two routes this device has.
 *
 * Shizuku is normally started by hand over adb, which means the transport this app stage payloads
 * through is gone after every reboot until someone finds a cable. Root is one way to avoid that, so a
 * boot where KernelSU is already active brings Shizuku back on its own - and a stored start token is
 * the other, which is the route for a boot with no root at all. The service does not choose between
 * them: [ShizukuStarter] decides from the root shell it is handed and the token in settings, so a
 * boot, the settings screen and an automatic install all take the same route.
 *
 * The service is foreground because a boot-time start has to outlive the broadcast, but its channel
 * is silent and low importance: succeeding is the normal case and is not worth a notification, so it
 * only announces itself while it is working and once when it fails. It never retries beyond a
 * bounded number of attempts and never asks for the network, because nothing here needs one.
 */
class ShizukuBootService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        BootServiceStart.ensureChannel(this, CHANNEL)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_shizuku_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            // A cold boot is a busy moment for the system, and a root shell asked for too early
            // fails for reasons that have nothing to do with KernelSU.
            delay(SETTLE_DELAY_MILLIS)
            val outcome = startShizuku()
            notifyOutcome(outcome)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * One attempt per route, and a retry only where retrying is a different attempt.
     *
     * The routes are not equivalent under repetition. A root starter races a boot that is still
     * settling, so it is worth trying again a moment later; the authenticated start is a request to
     * the Shizuku app that has already been delivered, and sending it three times asks the same
     * question three times. The route is therefore decided first and the retries are for the root one.
     */
    private suspend fun startShizuku(): ShizukuStartOutcome {
        // Reported rather than acted on: when Shizuku starts itself at boot there is nothing to do
        // here, and knowing that is the difference between "my boot start is broken" and "it was
        // never needed".
        if (runCatching { ShizukuIntentStarter.ownBootReceiverEnabled(this) }.getOrDefault(false)) {
            Log.i(TAG, "Shizuku starts itself on boot on this device; this app only waits for it")
        }
        val rootShell = kernelSuRootShell(this)
        val rootAvailable = rootShell("id").exitCode != NO_ROOT_SHELL_EXIT

        var last = ShizukuStarter.start(
            context = this,
            shell = rootShell,
        )
        if (last.started || !rootAvailable) return last

        repeat(START_ATTEMPTS - 1) {
            delay(RETRY_DELAY_MILLIS)
            last = ShizukuStarter.start(
                context = this,
                shell = rootShell,
            )
            if (last.started) return last
        }
        return last
    }

    private fun notifyOutcome(outcome: ShizukuStartOutcome) {
        if (outcome.started) {
            notify(getString(R.string.status_shizuku_started))
            return
        }
        notify(getString(R.string.shizuku_not_running_title), outcome.detail.take(160))
    }

    private fun notify(title: String, text: String = "") {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String = "") =
        NotificationCompat.Builder(this, CHANNEL.id)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launcherPendingIntent())
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun launcherPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "RootMyGalaxyShizuku"
        /**
         * The channel this service reports on, and the one a refused start is reported on: see
         * [BootServiceStart.ensureChannel] for why the second case can make it itself.
         */
        private val CHANNEL = BootServiceStart.Channel(
            id = "shizuku_boot",
            nameRes = R.string.notification_channel_shizuku,
        )

        /** One id, so the outcome of a start replaces the line that announced it. */
        private const val NOTIFICATION_ID = 0x53484b5a
        private const val SETTLE_DELAY_MILLIS = 20_000L
        private const val RETRY_DELAY_MILLIS = 15_000L
        private const val START_ATTEMPTS = 3

        /**
         * Starts the service, which brings Shizuku up by whichever route this device has.
         *
         * Started from the boot broadcast and from two places that follow one, and refused by Android in the
         * same conditions either way; [BootServiceStart] turns that refusal into a notification, because a
         * phone that comes back with no Shizuku and no explanation is what the setting being off looks like.
         */
        fun start(context: Context) {
            BootServiceStart.start(
                context = context,
                service = ShizukuBootService::class.java,
                channel = CHANNEL,
                notificationId = NOTIFICATION_ID,
                titleRes = R.string.shizuku_not_started_title,
                textRes = R.string.shizuku_not_started,
            )
        }
    }
}
