package dev.busung.s25uroot

import android.content.Context
import dev.busung.s25uroot.dfr.DfrApk
import dev.busung.s25uroot.dfr.DfrHelperAvailability
import dev.busung.s25uroot.dfr.DfrHelperStanding
import dev.busung.s25uroot.dfr.DfrInstall

/**
 * The helper on this phone when it is a different build from the one this app ships.
 *
 * A system-uid helper is a package like any other, and Package Manager replaces it only when something
 * installs over it - so an app update that carries a newer helper leaves the phone running the older one,
 * silently, for as long as nobody re-installs it. That is not a cosmetic difference. What the helper does
 * is run the exploit, and the copy on the phone is the older build's shellcode, arguments and bugs -
 * [DfrFlow] already refuses to *start* a stale helper for exactly that reason, which is what turns this
 * state into a dead end: root at boot stops working, and the one flow that would fix it is a flow nobody
 * opens, because nothing on the screen says to.
 *
 * So it is said where it is seen - on the screen the app opens on - with the one action that ends it:
 * install the build this app carries over the one that is there. Package Manager keeps the shared-user
 * uid across that install, so the helper stays a system app and no part of the setup is undone.
 *
 * ## Three standings this is deliberately not about
 *
 * - **nothing installed** is not an update - there is nothing to replace - and installing a helper for the
 *   first time is the flow's own steps, with the certificate and the reboot they owe;
 * - **an ordinary app** under the helper's id is a setup that did not land as the system uid, so what it
 *   needs is the removal the flow offers, not a newer build of the same mistake;
 * - **the build this app ships** is the state the card exists to reach.
 *
 * Both version codes are required before anything is offered, because the card's whole claim is which
 * build is on the phone against which one is here: one of them missing cannot be told apart from a phone
 * whose APK could not be read, and installing on that would replace a helper to fix a number.
 */
internal data class HelperUpdate(val onPhone: Long, val inThisBuild: Long) {

    /**
     * Installs this build's helper over the one on the phone, and returns what happened as log lines.
     *
     * The daemon is staged first and best-effort, exactly as the flow's own install press does it: the
     * helper loads KernelSU's service out of the staged file, so a phone whose copy the last run consumed
     * has to have it written again - while a phone with no root at this moment is not a reason to refuse
     * the install itself, which `pm install -r` holds as the shell user's own permission.
     *
     * Deliberately **not** stamped the way that press stamps its own attempt. That stamp answers `has the
     * phone restarted since this app last installed the helper`, and it exists because a helper installed
     * for the first time is accepted as a system app only after a reboot. An install *over* an existing
     * system-uid helper owes no reboot - Package Manager never revisits the uid, and the code injected into
     * `system_server` drops its cached copy of the package on the next run - so stamping here would send
     * the flow's own step list to a restart that changes nothing.
     */
    fun install(context: Context): String {
        val bundled = DfrApk.bundled(context)
        val apk = bundled.file ?: return context.getString(
            if (bundled.availability == DfrHelperAvailability.Unwritable) {
                R.string.dfr_apk_unwritable
            } else {
                R.string.dfr_apk_default
            },
        )
        val staged = DfrInstall.stageDaemon(context)?.log
            ?: context.getString(R.string.dfr_stage_no_root)
        val action = DfrInstall.installStageTwo(apk)
            ?: return listOf(staged, context.getString(R.string.dfr_no_shell)).joinToString("\n")
        AppLog.info(
            AppLogTags.KERNEL_SU,
            "helper update: ${action.log.lineSequence().lastOrNull().orEmpty()}",
        )
        return listOf(staged, action.log).joinToString("\n")
    }

    companion object {
        /**
         * The update this phone warrants, or null when there is nothing to offer.
         *
         * Everything here is Package Manager's own answer - no shell, no root - which is what lets the
         * screen the app opens on ask before anything on the device is running.
         */
        fun read(context: Context): HelperUpdate? {
            // The file first, and not for its own sake: with no helper in this build there is no build to
            // compare the phone's against, and nothing to install over it either.
            val apk = DfrApk.bundled(context).file ?: return null
            val standing = DfrInstall.helperStanding(context, apk)
            val build = DfrInstall.readStageTwoBuild(context, apk)
            return of(standing, build.installed, build.bundled)
        }

        /** The decision, from the three readings it is made of. Pure, so every case can be checked. */
        fun of(standing: DfrHelperStanding, onPhone: Long?, inThisBuild: Long?): HelperUpdate? =
            if (standing == DfrHelperStanding.Stale && onPhone != null && inThisBuild != null) {
                HelperUpdate(onPhone, inThisBuild)
            } else {
                null
            }
    }
}
