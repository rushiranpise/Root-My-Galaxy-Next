package dev.busung.s25uroot

import android.content.Context
import android.content.SharedPreferences

/**
 * A kernel check that was still running when this app last went away.
 *
 * The probe can already see a restart from inside an attempt, by watching the device's uptime go backwards -
 * but the usual restart is the one that takes the app with it. A kernel that panics because the trigger
 * damaged it does not stop our process politely and let us write down what happened: the machine goes down,
 * and this app is one of the things that stops existing. So the observation has to be made *afterwards*, from
 * the next launch, which means the fact that a check was in flight has to be on disk before it starts.
 *
 * That is the same shape as [AttemptedPayloadStore] and for the same reason - write the intent before the
 * dangerous part, because a record written on the way out is a record of the attempts that did not need one -
 * and the boot token is [P0Cache]'s, the kernel's own `/proc/sys/kernel/random/boot_id`, which is what makes
 * "was there a restart" answerable at all.
 *
 * Nothing here decides anything: it records that a test was running and which boot it was running in, and the
 * comparison is [restartedSince] so the rule can be read and tested without a device or a restart.
 */
internal object KernelCheckRecord {

    private const val PREFERENCES = "kernel_check"
    private const val BOOT_TOKEN = "boot_token"
    private const val STARTED_AT = "started_at"
    private const val ATTEMPTS = "attempts"

    /** A check that was left unfinished, as the next launch finds it. */
    data class Pending(
        /** The boot the check was running in, or null when the kernel's token could not be read. */
        val bootToken: String?,
        /** Wall-clock milliseconds, for the log line and for nothing else. */
        val startedAtMillis: Long,
        val attempts: Int,
    )

    /**
     * Records that a check is starting, before the first attempt.
     *
     * Committed rather than applied, because the failure this exists for is the process being taken by the
     * kernel: an edit that is still queued when that happens is an edit that never reaches the disk, and the
     * whole point of the record is to outlive the process that wrote it.
     */
    @Synchronized
    fun begin(context: Context, bootToken: String?, startedAtMillis: Long, attempts: Int) {
        preferences(context).edit()
            .putString(BOOT_TOKEN, bootToken)
            .putLong(STARTED_AT, startedAtMillis)
            .putInt(ATTEMPTS, attempts)
            .commit()
    }

    /** Forgets the check, which is what makes the next launch see nothing unfinished. */
    @Synchronized
    fun finish(context: Context) {
        preferences(context).edit().clear().commit()
    }

    /** The check left unfinished, or null when the last one was closed properly. */
    fun pending(context: Context): Pending? {
        val stored = preferences(context)
        if (!stored.contains(STARTED_AT)) return null
        return Pending(
            bootToken = stored.getString(BOOT_TOKEN, null),
            startedAtMillis = stored.getLong(STARTED_AT, 0L),
            attempts = stored.getInt(ATTEMPTS, 0),
        )
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/**
 * Whether the phone restarted between a recorded check and the launch that is reading the record.
 *
 * Two different tokens mean two different boots, and the kernel's own token is the only thing in this app
 * that changes when that happens. The two null cases are deliberately *not* a restart: an unreadable token is
 * a reading this app does not have, and the one answer a check must never give by accident is that the phone
 * is vulnerable. A record whose boot cannot be compared is reported as a test that did not finish - true, and
 * harmless - rather than as a kernel the bug was found in.
 */
internal fun restartedSince(recordedBootToken: String?, currentBootToken: String?): Boolean =
    recordedBootToken != null && currentBootToken != null && recordedBootToken != currentBootToken
