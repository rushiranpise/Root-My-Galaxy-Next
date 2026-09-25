package dev.busung.s25uroot

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import org.json.JSONArray
import org.json.JSONObject

enum class AccentColor(val storedValue: String) {
    Dynamic("dynamic"),
    Blue("blue"),
    Violet("violet"),
    Green("green"),
    Orange("orange");

    companion object {
        fun fromStoredValue(value: String?): AccentColor =
            entries.firstOrNull { it.storedValue == value } ?: Dynamic
    }
}

enum class AppThemeMode(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

object AppPreferences {
    private const val PREFERENCES = "appearance"
    private const val ACCENT_COLOR = "accent_color"
    private const val THEME_MODE = "theme_mode"
    private const val DISABLE_KSU_MODULES = "disable_ksu_modules"
    private const val LOAD_KERNEL_SU = "load_kernel_su"
    private const val KERNEL_SU_FLAVOR = "kernel_su_flavor"
    private const val MANAGER_VERSION_PREFIX = "manager_version_"
    private const val PAYLOAD_KERNEL_SU_VERSION_PREFIX = "payload_ksu_version_"
    private const val MANAGER_PACKAGE_PREFIX = "manager_package_"
    private const val LOADED_FLAVOR = "loaded_flavor"
    private const val LOADED_FLAVOR_BOOT = "loaded_flavor_boot"
    private const val SHIZUKU_MODE = "shizuku_mode"
    private const val BOOT_ROOT_MODE = "boot_root_mode"
    private const val RESTART_AFTER_ROOT = "restart_after_root"
    private const val RETRY_AFTER_REBOOT = "retry_after_reboot_boot"
    private const val SHIZUKU_BOOT_MODE = "shizuku_boot_mode"
    private const val BOOT_SETTLE_SECONDS = "boot_settle_seconds"
    private const val RUN_STALL_SECONDS = "run_stall_seconds"
    private const val RUN_TOTAL_SECONDS = "run_total_seconds"
    private const val RUN_HELPER_SECONDS = "run_helper_seconds"
    private const val EXPLOIT_OVERRIDE_ENABLED = "exploit_override_enabled"
    private const val EXPLOIT_OVERRIDE_ATTEMPTS = "exploit_override_attempts"
    private const val EXPLOIT_OVERRIDE_ATTEMPT_TIMEOUT = "exploit_override_attempt_timeout"
    private const val EXPLOIT_OVERRIDE_SLIDE_ROUTE = "exploit_override_slide_route"
    private const val EXPLOIT_OVERRIDE_P0_WINDOW = "exploit_override_p0_window"

    /**
     * What the stored p0 window says when the user left it on the payload's own.
     *
     * A separate sentinel rather than 0, because 0 is a value the payload accepts and a base of zero
     * microseconds is a different experiment from not making one. Kept private to this file: nothing
     * outside it should be able to write a preference that means "chosen" for a value nobody chose.
     */
    private const val P0_WINDOW_NOT_CHOSEN = -1
    // Both boot gates' floor. The key predates the second gate and keeps its name on purpose: see
    // [bootGateSettleSeconds].
    private const val AUTO_ROOT_SETTLE_SECONDS = "auto_root_settle_seconds"
    private const val DFR_REROOT_AT_BOOT = "dfr_reroot_at_boot"
    private const val SHIZUKU_AUTOMATION_TOKEN = "shizuku_automation_token"
    private const val PARTITION_READ_ONLY_MODE = "partition_read_only_mode"
    private const val READ_ONLY_PROTECTED_BOOT = "partition_read_only_protected_boot"
    private const val READ_ONLY_PROTECTED_DEVICES = "partition_read_only_protected_devices"
    private const val ADB_PAIRED = "adb_paired"
    private const val WIRELESS_ADB_OURS = "wireless_adb_owned"
    private const val PAYLOAD_MODE = "payload_mode"
    private const val BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    private const val LOCAL_PAYLOAD_NAME = "local_payload_name"

    // When this app performed the two steps of the system-uid flow. Ordering only: each step's own tool
    // is what says whether it is done, and these are read to answer "and has it rebooted since?".
    private const val DFR_INJECTED_AT = "dfr_injected_at"
    private const val DFR_INSTALLED_AT = "dfr_installed_at"
    // The third one, and it is the same kind of fact: a clean-up changes packages.xml, and Package
    // Manager reads that file only when it starts - so between the two, the removal is written down and
    // not yet in force. Recorded when the file was actually changed, which is the one thing the file
    // itself cannot say afterwards.
    private const val DFR_KEY_REMOVED_AT = "dfr_key_removed_at"
    private const val PAYLOAD_SOURCES = "payload_sources"
    // Superseded by the source list; read once so an existing selection survives the upgrade.
    private const val LEGACY_PAYLOAD_REPOSITORY = "payload_repository"
    private const val LEGACY_PAYLOAD_BRANCH = "payload_branch"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"
    // Named for the collapsed set rather than the open one: this replaced a key that stored which sections
    // were open, and the two are opposite readings of the same names, so the old one is left unread rather
    // than migrated - a page that comes up with everything open is the page this change is for.
    private const val CLOSED_SETTINGS_SECTIONS = "closed_settings_sections"
    private const val TARGET_FITS_DEVICE = "target_fits_device"

    /**
     * The settings sections the user has collapsed, by name.
     *
     * Stored rather than kept on the page because the page is rebuilt every time the tab is left and
     * returned to, and a section that reopened itself on the way back would undo what was asked for.
     *
     * The collapsed set and not the open one, because an empty preference has to mean the page is whole:
     * every section shows what it holds unless it was closed, so nothing stored is the full page and
     * collapsing all eight is still a state that can be stored and come back.
     */
    internal fun closedSettingsSections(context: Context): Set<SettingsSection> =
        SettingsSection.named(prefs(context).getStringSet(CLOSED_SETTINGS_SECTIONS, emptySet()).orEmpty().toSet())

    internal fun setClosedSettingsSections(context: Context, sections: Set<SettingsSection>) {
        prefs(context).edit()
            .putStringSet(CLOSED_SETTINGS_SECTIONS, sections.map { it.name }.toSet())
            .apply()
    }

    /**
     * Whether the target sheet offers only what fits this phone.
     *
     * Stored rather than remembered on the sheet, because it is a standing preference and not a
     * question asked once: whoever turns it off is testing other devices' payloads, and having to
     * turn it off again at every run is the sort of errand that ends in picking the wrong target.
     */
    internal fun targetFitsDeviceOnly(context: Context): Boolean =
        prefs(context).getBoolean(TARGET_FITS_DEVICE, true)

    internal fun setTargetFitsDeviceOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(TARGET_FITS_DEVICE, enabled).apply()
    }

    fun payloadSources(context: Context): List<PayloadSource> {
        val stored = prefs(context).getString(PAYLOAD_SOURCES, null)
        if (stored == null) return defaultPayloadSources(context)
        // Saved and empty is the same answer as never saved: a list with nothing in it is one the app
        // could not read a payload from, so the defaults are what it gets rather than a sheet it can only
        // be rescued from by typing.
        return decodePayloadSources(stored).ifEmpty { defaultPayloadSources(context) }
    }

    fun setPayloadSources(context: Context, sources: List<PayloadSource>) {
        prefs(context).edit()
            .putString(PAYLOAD_SOURCES, encodePayloadSources(sources))
            .remove(LEGACY_PAYLOAD_REPOSITORY)
            .remove(LEGACY_PAYLOAD_BRANCH)
            .apply()
    }

    /**
     * The list a device that has not saved one starts from.
     *
     * [PayloadSource.DEFAULTS] with the older single-repository preference folded in: a build before the
     * list stored one repository and one branch, and a device that set them did so on purpose, so that
     * source keeps its place at the front. The official catalog is added beside it rather than in place of
     * it - the defaults are what a device starts from, not what it is forced back to.
     */
    private fun defaultPayloadSources(context: Context): List<PayloadSource> {
        val preferences = prefs(context)
        val chosen = PayloadSource.create(
            repository = preferences.getString(LEGACY_PAYLOAD_REPOSITORY, null)
                ?: PayloadSource.DEFAULT_REPOSITORY,
            branch = preferences.getString(LEGACY_PAYLOAD_BRANCH, null)
                ?: PayloadSource.DEFAULT_BRANCH,
        ) ?: PayloadSource.DEFAULT
        return listOf(chosen).withSourcesAdded(PayloadSource.DEFAULTS)
    }

    private fun encodePayloadSources(sources: List<PayloadSource>): String {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("repository", source.repository)
                    .put("branch", source.branch)
                    .put("enabled", source.enabled)
                    .put("pinnedCommit", source.pinnedCommit),
            )
        }
        return array.toString()
    }

    private fun decodePayloadSources(stored: String): List<PayloadSource> = try {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val source = PayloadSource.create(
                    repository = item.optString("repository"),
                    branch = item.optString("branch"),
                    enabled = item.optBoolean("enabled", true),
                ) ?: continue
                // A stored pin is trusted only if it is a full commit; anything else would either
                // fail later or silently read the wrong revision.
                val stored = item.optString("pinnedCommit")
                val pinned = if (PayloadSource.isCommitValid(stored)) {
                    source.copy(pinnedCommit = stored.trim())
                } else {
                    source
                }
                if (none { it.id == pinned.id }) add(pinned)
            }
        }
    } catch (error: Throwable) {
        emptyList()
    }

    fun accentColor(context: Context): AccentColor = AccentColor.fromStoredValue(
        prefs(context).getString(ACCENT_COLOR, null),
    )

    fun setAccentColor(context: Context, color: AccentColor) {
        prefs(context).edit()
            .putString(ACCENT_COLOR, color.storedValue)
            .apply()
    }

    fun themeMode(context: Context): AppThemeMode = AppThemeMode.fromStoredValue(
        prefs(context).getString(THEME_MODE, null),
    )

    fun setThemeMode(context: Context, themeMode: AppThemeMode) {
        prefs(context).edit()
            .putString(THEME_MODE, themeMode.storedValue)
            .apply()
    }

    fun disableKsuModules(context: Context): Boolean =
        prefs(context).getBoolean(DISABLE_KSU_MODULES, false)

    fun setDisableKsuModules(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(DISABLE_KSU_MODULES, enabled)
            .apply()
    }

    /**
     * Whether a run loads KernelSU once the exploit has root.
     *
     * On by default, because loading it is what this app is for. Off is for a device where the module
     * is deliberately not wanted - another root solution is doing that job, or the load itself is the
     * thing that misbehaves - and the run then ends at the root the exploit won and says so rather
     * than reporting an install it did not make.
     *
     * A preference rather than a per-run choice: everything that depends on the load (root on boot,
     * the recovery actions, keeping the modules out of the way) reads the same answer, so it has to be
     * the same answer for all of them. A run freezes it at its start, like the transport.
     */
    fun loadKernelSu(context: Context): Boolean = prefs(context).getBoolean(LOAD_KERNEL_SU, true)

    fun setLoadKernelSu(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(LOAD_KERNEL_SU, enabled)
            .apply()
    }

    /**
     * Which KernelSU a run installs.
     *
     * An id this build no longer knows falls back to the default rather than refusing: the only way
     * that happens is a downgrade, and a phone that had the other flavour selected is not a reason to
     * show an empty list.
     */
    fun kernelsuFlavor(context: Context): KernelSuFlavor {
        val stored = prefs(context).getString(KERNEL_SU_FLAVOR, null)
        return KernelSuFlavor.fromId(stored) ?: KernelSuFlavor.Default
    }

    fun setKernelsuFlavor(context: Context, flavor: KernelSuFlavor) {
        prefs(context).edit()
            .putString(KERNEL_SU_FLAVOR, flavor.id)
            .apply()
    }

    /**
     * The manager version to offer for [flavor], when the user has named one.
     *
     * Null means the app's own offer, which is what keeps this from being a second place a version is
     * written down: the app stores only the deliberate choice, so an offer that changes - because the
     * payload changed, or because a later build knows a newer release - changes for everyone who never
     * made one.
     */
    fun managerVersion(context: Context, flavor: KernelSuFlavor): String? =
        prefs(context).getString(MANAGER_VERSION_PREFIX + flavor.id, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    fun setManagerVersion(context: Context, flavor: KernelSuFlavor, version: String?) {
        val editor = prefs(context).edit()
        val key = MANAGER_VERSION_PREFIX + flavor.id
        if (version.isNullOrBlank()) editor.remove(key) else editor.putString(key, version.trim())
        editor.apply()
    }

    /**
     * The KernelSU version the payload the app last resolved for [flavor] declares.
     *
     * Written whenever a run resolves its payload, whenever a payload is picked by hand, and whenever
     * one is cached - the three moments the app learns which payload this device will run. It is a
     * record of a decision rather than of the catalog: nothing here re-reads the sources, so a phone
     * with no network still offers the manager that matches the daemon it is holding.
     *
     * Null means no payload has declared one, which is every entry written before the feed carried the
     * field. The manager offer falls back to the flavour's own release there rather than guessing.
     */
    fun payloadKernelSuVersion(context: Context, flavor: KernelSuFlavor): String? =
        prefs(context).getString(PAYLOAD_KERNEL_SU_VERSION_PREFIX + flavor.id, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    fun setPayloadKernelSuVersion(context: Context, flavor: KernelSuFlavor, version: String?) {
        val editor = prefs(context).edit()
        val key = PAYLOAD_KERNEL_SU_VERSION_PREFIX + flavor.id
        if (version.isNullOrBlank()) editor.remove(key) else editor.putString(key, version.trim())
        editor.apply()
    }

    /**
     * The package the app should open for [flavor], when the user named one.
     *
     * Stored because a manager's package cannot always be known ahead of time: KernelSU-Next's
     * spoofed build rewrites its own to three random words on every release, so the only thing that
     * can address it is a choice made on the phone that has it installed.
     */
    fun managerPackage(context: Context, flavor: KernelSuFlavor): String? =
        prefs(context).getString(MANAGER_PACKAGE_PREFIX + flavor.id, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    fun setManagerPackage(context: Context, flavor: KernelSuFlavor, packageName: String?) {
        val editor = prefs(context).edit()
        val key = MANAGER_PACKAGE_PREFIX + flavor.id
        if (packageName.isNullOrBlank()) editor.remove(key) else editor.putString(key, packageName.trim())
        editor.apply()
    }

    /**
     * The flavour this boot has already loaded, or null when nothing was loaded into it.
     *
     * Stored with the boot it happened in, because the promise it feeds is about *this* boot: a
     * late-loaded module is gone after a restart, so a record that outlived one would refuse a run on
     * a phone that is perfectly able to make it.
     */
    fun loadedFlavor(context: Context): KernelSuFlavor? {
        val preferences = prefs(context)
        val boot = preferences.getString(LOADED_FLAVOR_BOOT, null) ?: return null
        if (boot != AutoRootSupport.currentBootToken()) return null
        return KernelSuFlavor.fromId(preferences.getString(LOADED_FLAVOR, null))
    }

    fun setLoadedFlavor(context: Context, flavor: KernelSuFlavor?, bootToken: String?) {
        val editor = prefs(context).edit()
        if (flavor == null || bootToken == null) {
            editor.remove(LOADED_FLAVOR).remove(LOADED_FLAVOR_BOOT)
        } else {
            editor.putString(LOADED_FLAVOR, flavor.id).putString(LOADED_FLAVOR_BOOT, bootToken)
        }
        editor.commit()
    }

    fun shizukuMode(context: Context): Boolean =
        prefs(context).getBoolean(SHIZUKU_MODE, false)

    fun setShizukuMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(SHIZUKU_MODE, enabled)
            .apply()
    }

    fun bootRootMode(context: Context): Boolean =
        prefs(context).getBoolean(BOOT_ROOT_MODE, false)

    /**
     * Turns the payload's boot gate on or off, and the helper's gate off with it.
     *
     * The two are alternatives, not companions - see [rerootAtBoot] - so switching this one on switches
     * the other one off. Only on the way on: turning this gate off says nothing about the other, and the
     * recovery flow below relies on that when it puts this one back after a reboot it could not make.
     */
    fun setBootRootMode(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(BOOT_ROOT_MODE, enabled)
        if (enabled) editor.putBoolean(DFR_REROOT_AT_BOOT, false)
        editor.apply()
    }

    /**
     * Whether a boot with no root should ask the stage-two helper to reroot.
     *
     * Off by default, and the other way a boot can be asked to gain root: root on boot loads the payload
     * this app would load from the payload sheet, while this one starts the helper - which is the phone
     * this setting exists for, where the KernelSU in the kernel comes from the exploit the helper runs.
     * They are alternatives rather than companions even so, because both are unattended and both decide
     * what a boot with no root does: with both on, one boot is two runs racing for the same kernel, and
     * which of them wins is whichever reads the boot first. So each setter turns the other gate off, and a
     * boot has exactly one answer to what it should do about root.
     */
    fun rerootAtBoot(context: Context): Boolean =
        prefs(context).getBoolean(DFR_REROOT_AT_BOOT, false)

    /**
     * Turns the helper's boot gate on or off, and the payload's gate off with it when this one comes on.
     */
    fun setRerootAtBoot(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(DFR_REROOT_AT_BOOT, enabled)
        if (enabled) editor.putBoolean(BOOT_ROOT_MODE, false)
        editor.apply()
    }

    /**
     * Whether a run that loaded KernelSU should hand the userspace over before it reports done.
     *
     * On by default, because the load is not the whole of the job: KernelSU mounts its modules, and
     * nothing already running sees them until the userspace is built again, so a run that stops at the
     * load leaves a phone that is rooted and behaving as if it were not. What that costs is the restart
     * itself, which closes everything that is open - that is why it is a setting rather than something
     * a run always does. What it buys: KernelSU's own soft reboot walks the module lifecycle in its
     * normal order, so a run that started from a phone whose modules were inert ends with them loaded
     * rather than with an instruction to restart.
     */
    fun restartAfterRoot(context: Context): Boolean =
        prefs(context).getBoolean(RESTART_AFTER_ROOT, true)

    fun setRestartAfterRoot(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(RESTART_AFTER_ROOT, enabled)
            .apply()
    }

    /**
     * Arms one retry of the install for the *next* boot, or forgets one.
     *
     * Stored as the boot it was armed in rather than as a flag, because the whole promise is "after a
     * reboot": a run that was armed while the phone was up must not be started by the same kernel boot,
     * or the user would get the same clean-window attempt they declined when they chose to reboot.
     * Passing null is what consumes it.
     */
    fun setRetryAfterReboot(context: Context, armedForBoot: String?) {
        val editor = prefs(context).edit()
        if (armedForBoot == null) {
            editor.remove(RETRY_AFTER_REBOOT)
        } else {
            editor.putString(RETRY_AFTER_REBOOT, armedForBoot)
        }
        // Committed rather than applied: the retry is armed before a reboot is asked for, and an
        // asynchronous write that had not landed would lose the whole decision.
        editor.commit()
    }

    fun retryArmedInBoot(context: Context): String? =
        prefs(context).getString(RETRY_AFTER_REBOOT, null)?.takeIf(String::isNotBlank)

    /** Whether a retry is armed and this is not the boot it was armed in. */
    fun retryPendingForBoot(context: Context, bootToken: String?): Boolean {
        val armed = retryArmedInBoot(context) ?: return false
        return armed != bootToken
    }

    /** Whether a retry is armed at all, whatever boot armed it. */
    fun retryArmed(context: Context): Boolean = retryArmedInBoot(context) != null

    /**
     * How many image partitions a run set read-only in this boot, and zero when this boot set none.
     *
     * The setting says what a *run* would do; this says what one actually did, and the two are not the
     * same question: `blockdev --setro` is cleared by a reboot, so a read-only refusal in a boot that
     * never ran anything cannot be this app's doing, and naming the switch for it would send someone to
     * turn off a protection that is not there. Scoped to the boot it was recorded in for that reason,
     * the same way an armed retry is.
     */
    fun readOnlyProtectedDevices(context: Context, bootToken: String?): Int {
        val storedBoot = prefs(context).getString(READ_ONLY_PROTECTED_BOOT, null) ?: return 0
        if (bootToken == null || storedBoot != bootToken) return 0
        return prefs(context).getInt(READ_ONLY_PROTECTED_DEVICES, 0)
    }

    fun setReadOnlyProtectedDevices(context: Context, bootToken: String?, devices: Int) {
        val editor = prefs(context).edit()
        if (bootToken == null || devices <= 0) {
            editor.remove(READ_ONLY_PROTECTED_BOOT).remove(READ_ONLY_PROTECTED_DEVICES)
        } else {
            editor.putString(READ_ONLY_PROTECTED_BOOT, bootToken)
                .putInt(READ_ONLY_PROTECTED_DEVICES, devices)
        }
        // Committed, not applied: what this records is used to decide whether a failure gets blamed on
        // the protection, and a write that had not landed would answer "not this boot" about a boot that
        // did set devices.
        editor.commit()
    }

    /**
     * Where a run takes its payload from. Online is the default because it is the mode that follows
     * the sources the user configured; Offline is what makes a run possible with no network.
     */
    fun payloadMode(context: Context): PayloadMode {
        val stored = prefs(context).getString(PAYLOAD_MODE, PayloadMode.Online.name)
        return PayloadMode.entries.firstOrNull { it.name == stored } ?: PayloadMode.Online
    }

    fun setPayloadMode(context: Context, mode: PayloadMode) {
        prefs(context).edit()
            .putString(PAYLOAD_MODE, mode.name)
            .apply()
    }

    /**
     * How long a run waits after a boot before the exploit starts. See [BootSettle]: the wait is
     * measured from the boot, so it is a floor on the device's uptime and not a delay per run.
     */
    fun bootSettleSeconds(context: Context): Int = BootSettle.normalize(
        prefs(context).getInt(BOOT_SETTLE_SECONDS, BootSettle.DEFAULT_SECONDS),
    )

    fun setBootSettleSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(BOOT_SETTLE_SECONDS, BootSettle.normalize(seconds))
            .apply()
    }

    /**
     * The three ceilings a run is given unless the user chose otherwise.
     *
     * Normalized on the way in as well as on the way out, so a value that is not one of the offered ones
     * is never stored in the first place - the settings only offer the values, and everything downstream
     * is entitled to assume it.
     */
    fun runStallSeconds(context: Context): Int = RunLimits.normalizeStallSeconds(
        prefs(context).getInt(RUN_STALL_SECONDS, RunLimits.DEFAULT_STALL_SECONDS),
    )

    fun setRunStallSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(RUN_STALL_SECONDS, RunLimits.normalizeStallSeconds(seconds))
            .apply()
    }

    fun runTotalSeconds(context: Context): Int = RunLimits.normalizeTotalSeconds(
        prefs(context).getInt(RUN_TOTAL_SECONDS, RunLimits.DEFAULT_TOTAL_SECONDS),
    )

    fun setRunTotalSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(RUN_TOTAL_SECONDS, RunLimits.normalizeTotalSeconds(seconds))
            .apply()
    }

    fun runHelperSeconds(context: Context): Int = RunLimits.normalizeHelperSeconds(
        prefs(context).getInt(RUN_HELPER_SECONDS, RunLimits.DEFAULT_HELPER_SECONDS),
    )

    fun setRunHelperSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(RUN_HELPER_SECONDS, RunLimits.normalizeHelperSeconds(seconds))
            .apply()
    }

    /** The three ceilings as one value, which is how every reader wants them. */
    internal fun runLimits(context: Context): RunLimitsSettings = RunLimitsSettings(
        totalSeconds = runTotalSeconds(context),
        stallSeconds = runStallSeconds(context),
        helperSeconds = runHelperSeconds(context),
    )

    internal fun setRunLimit(context: Context, limit: RunLimit, seconds: Int) {
        when (limit) {
            RunLimit.Total -> setRunTotalSeconds(context, seconds)
            RunLimit.Stall -> setRunStallSeconds(context, seconds)
            RunLimit.Helper -> setRunHelperSeconds(context, seconds)
        }
    }

    /**
     * The app's own numbers for the payload's exploit, off by default.
     *
     * Normalized on the way in and on the way out, like the three ceilings and for the same reason: the
     * settings only offer the values, and the run and the run plan are entitled to assume that a stored
     * number is one a user could have picked.
     */
    internal fun exploitOverride(context: Context): ExploitOverrideSettings {
        val stored = prefs(context)
        return ExploitOverrideSettings(
            enabled = stored.getBoolean(EXPLOIT_OVERRIDE_ENABLED, false),
            attempts = ExploitOverride.normalizeAttempts(
                stored.getInt(EXPLOIT_OVERRIDE_ATTEMPTS, ExploitRoutePolicy.DEFAULT_ATTEMPTS),
            ),
            attemptTimeoutSec = ExploitOverride.normalizeTimeout(
                stored.getInt(
                    EXPLOIT_OVERRIDE_ATTEMPT_TIMEOUT,
                    ExploitRoutePolicy.DEFAULT_ATTEMPT_TIMEOUT_SEC,
                ),
            ),
            slideRoute = ExploitOverride.normalizeRoute(
                SlideRoute.parse(stored.getString(EXPLOIT_OVERRIDE_SLIDE_ROUTE, null)),
            ),
            p0WindowDelayUsec = stored.getInt(EXPLOIT_OVERRIDE_P0_WINDOW, P0_WINDOW_NOT_CHOSEN)
                .takeIf { it != P0_WINDOW_NOT_CHOSEN }
                ?.let(ExploitOverride::normalizeP0WindowDelay),
        )
    }

    internal fun setExploitOverride(context: Context, override: ExploitOverrideSettings) {
        prefs(context).edit()
            .putBoolean(EXPLOIT_OVERRIDE_ENABLED, override.enabled)
            .putInt(EXPLOIT_OVERRIDE_ATTEMPTS, ExploitOverride.normalizeAttempts(override.attempts))
            .putInt(
                EXPLOIT_OVERRIDE_ATTEMPT_TIMEOUT,
                ExploitOverride.normalizeTimeout(override.attemptTimeoutSec),
            )
            .putString(
                EXPLOIT_OVERRIDE_SLIDE_ROUTE,
                ExploitOverride.normalizeRoute(override.slideRoute).name,
            )
            .putInt(
                EXPLOIT_OVERRIDE_P0_WINDOW,
                ExploitOverride.normalizeP0WindowDelay(override.p0WindowDelayUsec)
                    ?: P0_WINDOW_NOT_CHOSEN,
            )
            .apply()
    }

    /**
     * The shared floor for both unattended gates, stored under the name it has always had.
     *
     * See [BootSettle.GATE_DEFAULT_SECONDS]: Root on boot and Reroot at boot read this one value, so
     * the two of them cannot come to disagree about how settled a device has to be before either acts
     * with nobody at the screen - and a person tuning it must not be changing the wait a manual run
     * does, which is why it is not [bootSettleSeconds].
     *
     * The stored key keeps the name it was first written under rather than following the accessors.
     * A preference key is a fact about phones in the field, not a name in this code: renaming it would
     * silently reset everyone who has ever chosen a value back to the default.
     */
    fun bootGateSettleSeconds(context: Context): Int = BootSettle.normalize(
        prefs(context).getInt(AUTO_ROOT_SETTLE_SECONDS, BootSettle.GATE_DEFAULT_SECONDS),
    )

    fun setBootGateSettleSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(AUTO_ROOT_SETTLE_SECONDS, BootSettle.normalize(seconds))
            .apply()
    }

    /**
     * The token a Shizuku build may require before it honours an authenticated start request.
     *
     * Stored only so the app can include it in that one broadcast, and never written to the log or to
     * the run history: it is the credential that lets this app ask for a privileged process to be
     * started, so it is treated like one.
     */
    fun shizukuAutomationToken(context: Context): String =
        prefs(context).getString(SHIZUKU_AUTOMATION_TOKEN, "").orEmpty()

    fun setShizukuAutomationToken(context: Context, token: String) {
        prefs(context).edit()
            .putString(SHIZUKU_AUTOMATION_TOKEN, token.trim())
            .apply()
    }

    /**
     * Whether a run marks the image partitions read-only once bootstrap root is in hand.
     *
     * On unless turned off: see [PartitionReadOnly] - it guards the window between bootstrap root
     * and the first verified boot, which is where a mistaken write leaves a device that has to be
     * recovered from download mode. Turning it off is a decision about the device, so an existing
     * choice is never overwritten: a stored value wins over this default.
     */
    fun partitionReadOnlyMode(context: Context): Boolean =
        prefs(context).getBoolean(PARTITION_READ_ONLY_MODE, true)

    fun setPartitionReadOnlyMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(PARTITION_READ_ONLY_MODE, enabled)
            .apply()
    }

    /**
     * Whether a wireless-debugging pairing has ever succeeded.
     *
     * A record of an event, not a statement about now: adbd can forget this app's key without
     * anything here changing, so anything that depends on the transport asks the device instead of
     * reading this.
     */
    fun adbPaired(context: Context): Boolean = prefs(context).getBoolean(ADB_PAIRED, false)

    fun setAdbPaired(context: Context, paired: Boolean) {
        prefs(context).edit().putBoolean(ADB_PAIRED, paired).apply()
    }

    /**
     * Whether the wireless-debugging switch is on because this app turned it on.
     *
     * Persisted, not held in memory: the process that flipped it is often gone by the time the failsafe
     * alarm turns it back off, and a flag that died with that process would leave the switch on with
     * nobody left to restore it. It is also what keeps the app off a session the user started
     * themselves - the same setting serves a cable-free adb session this app knows nothing about.
     */
    fun wirelessAdbOwnedByApp(context: Context): Boolean =
        prefs(context).getBoolean(WIRELESS_ADB_OURS, false)

    fun setWirelessAdbOwnedByApp(context: Context, owned: Boolean) {
        prefs(context).edit()
            .putBoolean(WIRELESS_ADB_OURS, owned)
            .commit()
    }

    /** Whether Shizuku is started at boot through KernelSU, once the device already has root. */
    fun shizukuBootMode(context: Context): Boolean =
        prefs(context).getBoolean(SHIZUKU_BOOT_MODE, false)

    fun setShizukuBootMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(SHIZUKU_BOOT_MODE, enabled)
            .apply()
    }

    /**
     * Whether the one-shot battery-optimisation prompt has been shown. Persisted so declining it
     * is a decision rather than something the app re-asks on every launch.
     */
    fun batteryPromptShown(context: Context): Boolean =
        prefs(context).getBoolean(BATTERY_PROMPT_SHOWN, false)

    fun setBatteryPromptShown(context: Context, shown: Boolean) {
        prefs(context).edit()
            .putBoolean(BATTERY_PROMPT_SHOWN, shown)
            .apply()
    }

    /** Name of the imported payload, kept for display only; the file itself is in app storage. */
    fun localPayloadName(context: Context): String? =
        prefs(context).getString(LOCAL_PAYLOAD_NAME, null)

    fun setLocalPayloadName(context: Context, name: String?) {
        val editor = prefs(context).edit()
        if (name == null) editor.remove(LOCAL_PAYLOAD_NAME) else editor.putString(LOCAL_PAYLOAD_NAME, name)
        editor.apply()
    }

    /** When this app last injected the certificate, or null when it never did or has no record. */
    fun dfrInjectedAt(context: Context): Long? =
        prefs(context).getLong(DFR_INJECTED_AT, -1L).takeIf { it >= 0L }

    fun setDfrInjectedAt(context: Context, at: Long?) {
        val editor = prefs(context).edit()
        if (at == null) editor.remove(DFR_INJECTED_AT) else editor.putLong(DFR_INJECTED_AT, at)
        editor.apply()
    }

    /** When this app last installed the stage two, or null. */
    fun dfrInstalledAt(context: Context): Long? =
        prefs(context).getLong(DFR_INSTALLED_AT, -1L).takeIf { it >= 0L }

    fun setDfrInstalledAt(context: Context, at: Long?) {
        val editor = prefs(context).edit()
        if (at == null) editor.remove(DFR_INSTALLED_AT) else editor.putLong(DFR_INSTALLED_AT, at)
        editor.apply()
    }

    /**
     * When this app last took its key out of `packages.xml`, or null when it never did.
     *
     * Only written when the uninstall changed the file: an uninstall that found nothing to remove leaves
     * this alone, because there is nothing waiting on a restart to take effect.
     */
    fun dfrKeyRemovedAt(context: Context): Long? =
        prefs(context).getLong(DFR_KEY_REMOVED_AT, -1L).takeIf { it >= 0L }

    fun setDfrKeyRemovedAt(context: Context, at: Long?) {
        val editor = prefs(context).edit()
        if (at == null) editor.remove(DFR_KEY_REMOVED_AT) else editor.putLong(DFR_KEY_REMOVED_AT, at)
        editor.apply()
    }

    @Synchronized
    fun consumeInstallRequest(context: Context, requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val preferences = prefs(context)
        if (preferences.getString(CONSUMED_INSTALL_REQUEST, null) == requestId) return false
        return preferences.edit()
            .putString(CONSUMED_INSTALL_REQUEST, requestId)
            .commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun languageTag(context: Context): String {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    fun setLanguage(context: Context, languageTag: String) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
    }
}
