package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Starting a boot service, and what is left to say when Android refuses.
 *
 * Every service this app starts at boot is a foreground service, which on a modern Android makes its start a
 * request the system can turn down. The refusals differ - the background-start rules, the per-type rules, the
 * restricted bucket - but the shape of the failure does not: the exception arrives at the caller, the service
 * never runs, and the boot is over. Left where it lands, that is indistinguishable from the feature being
 * switched off. The phone comes back unrooted, without Shizuku, and with nothing anywhere saying why, which
 * is the same thing the user sees when the app was never installed at all.
 *
 * So the start is asked for here, and a refusal is answered with the one thing a phone with nobody at the
 * keyboard can still do: a notification. It is posted on the channel and the id the service itself would have
 * used, so a boot that works replaces the complaint instead of stacking beside it, and a tap opens the app -
 * the screen where the same work can be asked for by hand.
 *
 * The framework's own sentence about types and buckets goes in the expanded form: it is worth reading, but
 * only after the line above it has been understood.
 */
internal object BootServiceStart {

    /** Where a boot service announces itself, and therefore where its refusal is said. */
    internal data class Channel(
        val id: String,
        val nameRes: Int,
        val descriptionRes: Int? = null,
    )

    /**
     * Starts [service] for this boot, and reports a refusal in the shade rather than letting it vanish.
     *
     * The caller is a `BOOT_COMPLETED` receiver, which is the one place this cannot be left to the caller to
     * catch: the exception would take the receiver's process down with it, and the work the service was
     * started for had this boot as its only chance.
     */
    fun start(
        context: Context,
        service: Class<out Service>,
        channel: Channel,
        notificationId: Int,
        titleRes: Int,
        textRes: Int,
    ) {
        try {
            context.startForegroundService(Intent(context, service))
        } catch (refused: Exception) {
            // Logged with the framework's own words: the notification says what the user lost, and this is
            // the only place the type, the bucket and the rule that applied are recorded.
            AppLog.error(
                AppLogTags.BOOT,
                "${service.simpleName} was refused this boot",
                refused,
            )
            report(context, channel, notificationId, titleRes, textRes, refused)
        }
    }

    /**
     * Creates the channel a boot service posts on.
     *
     * Called both by the service as it comes up and by [start] when it does not: the refusal has to be
     * sayable on a channel the service never had the chance to make, and creating one that already exists is
     * idempotent - the second call only re-states what the first one said.
     */
    fun ensureChannel(context: Context, channel: Channel) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                channel.id,
                context.getString(channel.nameRes),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                channel.descriptionRes?.let { description = context.getString(it) }
            },
        )
    }

    /**
     * The refusal, in the shade.
     *
     * Losing the boot is worth reading, so it is posted as an alerting notification rather than with the
     * quiet priority the same service uses while it is working: the notification is the whole of what this
     * path produces, and a boot that quietly did nothing is the failure being reported.
     */
    private fun report(
        context: Context,
        channel: Channel,
        notificationId: Int,
        titleRes: Int,
        textRes: Int,
        refused: Throwable,
    ) {
        ensureChannel(context, channel)
        val text = context.getString(textRes)
        val detail = "${refused.javaClass.simpleName}: ${refused.message.orEmpty()}".take(DETAIL_LIMIT)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                notificationId,
                NotificationCompat.Builder(context, channel.id)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$detail"))
                    // The app, which is where the work can be asked for again by hand.
                    .setContentIntent(runRecordPendingIntent(context, null))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
        }.onFailure { failure ->
            // Nothing left but the log. This is the last thing the boot path can do, and it must not throw
            // inside the broadcast that called it.
            AppLog.warn(
                AppLogTags.BOOT,
                "The refused start could not be reported either (${failure.javaClass.simpleName})",
            )
        }
    }

    /** How much of the framework's sentence is worth carrying into a notification. */
    private const val DETAIL_LIMIT = 200
}
