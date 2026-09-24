package dev.busung.s25uroot

import android.content.Context
import androidx.annotation.StringRes
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Which shell - or which hand - put the manager on the phone.
 *
 * The four are ordered by how little they need from anyone: root reads the APK out of app storage as
 * itself, Shizuku's `shell` uid may not open app storage and so needs a second copy in the directory it
 * owns, the device's own adbd is the same `shell` uid reached a different way, and the installer is the
 * only one that needs a person to press something.
 */
internal enum class ManagerInstallRoute(
    /** What the run's log calls it, because "installed" without "by what" is not a report. */
    @StringRes val prose: Int,
) {
    Root(R.string.manager_route_root),
    Shell(R.string.manager_route_shell),
    WirelessAdb(R.string.manager_route_wireless),
    Installer(R.string.manager_route_installer),
}

/** How an attempt to get the manager onto the phone ended. */
internal enum class ManagerInstallVerdict {
    /** Nothing was needed: this flavour's manager is already on the phone, at any version. */
    AlreadyInstalled,

    /** It is installed now, and a fresh read of Package Manager is what says so. */
    Installed,

    /**
     * The phone's own installer has it, and whether it lands is the user's to decide.
     *
     * Its own verdict rather than [Failed] or [Installed], because it is the one outcome that is neither:
     * nothing has gone wrong, and nothing can be claimed about the phone until the package appears.
     */
    Requested,

    /** The download, the release, the shell or the tap did not produce an installed manager. */
    Failed,
}

/**
 * One attempt, as the screens and the run log need it.
 *
 * [version] is the release that was fetched and not the version the phone now reports - the phone's own
 * `versionName` is read by whoever shows it, from the package itself, which is the same rule the Settings
 * row follows: a manager installed by hand or left from an older release reads as itself.
 */
internal data class ManagerInstallOutcome(
    val flavor: KernelSuFlavor,
    val verdict: ManagerInstallVerdict,
    val route: ManagerInstallRoute? = null,
    val version: String? = null,
    val detail: String? = null,
) {
    /** Whether a manager of this flavour is on the phone as a result of this. */
    val installed: Boolean
        get() = verdict == ManagerInstallVerdict.AlreadyInstalled || verdict == ManagerInstallVerdict.Installed
}

/**
 * One sentence for somebody who asked for the manager by hand, from the outcome itself.
 *
 * Phrased here rather than at the caller, because there are three callers of the same attempt - the
 * run-plan sheet, the Settings card and the run's own step - and three sentences for one outcome is how
 * the same fact comes to be described differently on three screens.
 */
internal fun managerOutcomeMessage(context: Context, outcome: ManagerInstallOutcome): String =
    managerOutcomeLine(outcome, context.getString(R.string.manager_version_unread)).let { line ->
        context.getString(line.label, *line.args.toTypedArray())
    }

/** A sentence to say, as the string and its arguments: what a caller can decide without a `Context`. */
internal data class ManagerSentence(@StringRes val label: Int, val args: List<Any> = emptyList())

/**
 * Which sentence an outcome gets, chosen without a `Context` so the choice itself can be tested.
 *
 * [versionUnread] is the app's own words for a version Package Manager would not answer for, passed in
 * because this half is deliberately context-free: an installed manager whose version could not be read is
 * the one outcome whose sentence has to name the absence rather than a number.
 */
internal fun managerOutcomeLine(outcome: ManagerInstallOutcome, versionUnread: String): ManagerSentence =
    when (outcome.verdict) {
        ManagerInstallVerdict.AlreadyInstalled -> ManagerSentence(
            R.string.manager_present,
            listOf(outcome.flavor.label, outcome.version ?: versionUnread),
        )
        ManagerInstallVerdict.Installed ->
            ManagerSentence(R.string.manager_installed_toast, listOf(outcome.flavor.label))
        ManagerInstallVerdict.Requested -> ManagerSentence(R.string.manager_handed_over_toast)
        ManagerInstallVerdict.Failed -> ManagerSentence(
            R.string.manager_failed_toast,
            listOf(outcome.flavor.label, outcome.detail.orEmpty()),
        )
    }

/**
 * Installing the flavour's manager on a phone that has none.
 *
 * The manager is not part of the payload and the kernel does not need it: it is the plain app that talks
 * to the loaded module over KernelSU's socket, and the daemon the payload stages can be driven by any
 * version of it. What it *is* needed for is everything after the run - granting root to an app, mounting a
 * module, reading the log - and a phone that has just been rooted with no manager anywhere is a phone whose
 * root nothing on it can use. That is the state this exists for: a fresh install, no root yet, and nothing
 * of this flavour on the phone.
 *
 * ## It is the same manager the Settings row offers
 *
 * The version comes from [offeredManager], so what a run installs and what the Manager row would install are
 * one decision rather than two: the payload's own KernelSU when the resolved entry declares one, a version
 * the user named over that, and the flavour's own release when nothing says. The APK comes from the release
 * the same lookup resolves, so a version whose file name carries a build number the version does not
 * (`KernelSU_Next_v3.4.0_33294-release.apk`) is downloaded by asking for the release rather than by
 * guessing a name.
 *
 * ## Every transport, and what each one costs
 *
 * | route | what it needs | what it leaves behind |
 * |---|---|---|
 * | root | a live root shell | nothing - app storage is readable as root |
 * | Shizuku | the binder and its grant | a copy of the APK in `/data/local/tmp`, catalogued |
 * | wireless ADB | a pairing, and wireless debugging on | the same copy, pushed by adbd |
 * | installer | a tap | nothing |
 *
 * The copy is the one thing worth watching: `pm install` reads the APK **as whoever asked for it**, and
 * this app's own storage is mode 0700 under its own uid, which the `shell` user cannot open. So the two
 * shell routes stage a second copy in the one directory that user owns - the same thing the DFR flow does
 * with the helper APK, and catalogued in [StagedResidue] for the same reason: it is world-readable residue
 * with this app's name on it, and the residue screen has to be able to name it.
 *
 * ## Wireless ADB is opt-in per call
 *
 * [allowWirelessAdb] exists because opening that session is not a read: it turns wireless debugging on if
 * it is off, which is a device setting and a second adbd beside whatever the caller is doing. A run passes
 * false and hands the manager to the phone's installer instead - one tap, and the run waits - because the
 * step before a payload is not the place to bring up another transport.
 */
internal object ManagerInstall {

    /**
     * Under [Context.getCacheDir], and declared in the FileProvider's paths.
     *
     * The declaration is not decoration: the phone's installer is reached with a `content://` URI built by
     * the FileProvider, and a file outside the paths it serves is an `IllegalArgumentException` at the
     * moment of handing it over - which is the one route a phone with no root and no Shizuku always has.
     */
    internal const val APK_DIRECTORY = "managers"

    /**
     * Where the copy the `shell` user installs from is written.
     *
     * A name of this project's own rather than the payload's, for the reason the helper's copy has one: a
     * run rewrites its own staging on every run, and this file may sit there for a while. Held here and
     * named from here in [StagedResidue], because the install that reads it and the list that reports it
     * must not be able to disagree.
     */
    const val SHELL_APK_PATH = "/data/local/tmp/rmgnext-manager.apk"

    /** The APK this app downloaded for one release, whether or not it still exists. */
    fun apkFor(context: Context, flavor: KernelSuFlavor, version: String): File =
        File(File(context.cacheDir, APK_DIRECTORY), apkFileName(flavor.id, version))

    /**
     * The name one flavour's one release is kept under.
     *
     * Both facts and not one: two flavours are two different projects' apps, and two versions of one flavour
     * are two different downloads - one file for either would have a run install the manager that was
     * downloaded first rather than the one this app offers now. Pure, because the name is also the cache key
     * and the two have to be built from the same facts to stay one key.
     */
    internal fun apkFileName(flavorId: String, version: String): String = "$flavorId-$version.apk"

    /**
     * `pm install` of a manager APK, on whichever shell this phone has.
     *
     * `-r` so a copy left by an earlier attempt is replaced rather than refused, and `-d` because the
     * version installed can legitimately be older than the one on the phone: the version field exists so a
     * manager from a line upstream has not published can be named, and Android refuses a downgrade without
     * this flag. Neither weakens the check that decides whether the kernel accepts the manager - that is the
     * module's own signature table, and it is not a flag.
     */
    internal fun installCommand(apkPath: String): String =
        "/system/bin/pm install -r -d --user 0 '" + apkPath + "'"

    /**
     * Gets [flavor]'s manager onto the phone, or says why it could not.
     *
     * Reads before it does anything, because the usual answer is that a manager is already there - at any
     * version, from any source. Nothing here rejects the manager a user installed themselves, which is the
     * same rule the Manager row follows.
     *
     * [onLog] is called as it goes rather than only at the end: a download is megabytes over a phone
     * connection and an installer route waits for a person, and both of those are minutes during which a run
     * with nothing to say looks like a run that has hung.
     *
     * [waitForInstall] is what makes the installer route usable inside a run: the phone's installer is
     * started and this then waits, up to [USER_INSTALL_WAIT_MILLIS], for the package to appear. False
     * returns as soon as the installer is open, which is what a screen with its own button wants - the
     * screen stays there and the user comes back to it.
     *
     * [handToInstaller] is the other half of that, and it is false for a run nobody is watching: the boot
     * gate runs with the screen off, so opening an installer there would be a dialog on a phone with
     * nobody in front of it, waiting on nothing. Such a run reports that it has no manager and goes on.
     */
    suspend fun install(
        context: Context,
        flavor: KernelSuFlavor,
        allowWirelessAdb: Boolean,
        handToInstaller: Boolean,
        waitForInstall: Boolean,
        onLog: (String) -> Unit = {},
    ): ManagerInstallOutcome {
        KernelSuManager.installedFor(context, flavor)?.let { installed ->
            return ManagerInstallOutcome(
                flavor = flavor,
                verdict = ManagerInstallVerdict.AlreadyInstalled,
                route = null,
                version = installed.versionName,
            )
        }

        val offer = offeredManager(context, flavor)
        val release = withContext(Dispatchers.IO) { resolveRelease(context, flavor, offer) }
            ?: return failed(flavor, offer.version, context.getString(R.string.manager_error_no_release, flavor.label, offer.version))

        val apk = download(context, flavor, release, onLog)
            ?: return failed(flavor, release.version, context.getString(R.string.manager_error_download))

        // Root first when this boot has any: it installs straight out of app storage, so nothing is staged
        // and the phone keeps no second copy of a manager it now has. [RootStatusProbe] rather than a shell
        // attempt, because on a phone with no root a `su` is a wait that ends in a refusal - and package
        // manager work is not worth one.
        if (RootStatusProbe.isActive()) {
            val rooted = KernelSuRuntime.rootShell(installCommand(apk.absolutePath), INSTALL_TIMEOUT_SECONDS)
            installedAfter(context, flavor)?.let { return installed(flavor, release, ManagerInstallRoute.Root, it) }
            if (rooted != null) {
                AppLog.warn(AppLogTags.KERNEL_SU, "pm install of the ${flavor.label} manager refused: ${tail(rooted.output)}")
            }
        }

        if (ShizukuController.isRunning() && ShizukuController.isGranted()) {
            val staged = stageForShell(apk)
            if (staged != null) {
                KernelSuRuntime.unprivilegedShell(installCommand(staged))?.let { result ->
                    if (!result.ok()) {
                        AppLog.warn(
                            AppLogTags.KERNEL_SU,
                            "pm install of the ${flavor.label} manager over Shizuku refused: ${tail(result.output)}",
                        )
                    }
                }
                installedAfter(context, flavor)?.let { return installed(flavor, release, ManagerInstallRoute.Shell, it) }
            }
        }

        if (allowWirelessAdb && AdbCredentialStore.hasStoredKey(context) && AppPreferences.adbPaired(context)) {
            val pushed = pushOverWirelessAdb(context, apk)
            if (pushed != null) {
                installedAfter(context, flavor)?.let {
                    return installed(flavor, release, ManagerInstallRoute.WirelessAdb, it)
                }
            }
        }

        // No shell answered, so the phone's own installer is the way - and it is a real way rather than a
        // consolation: it is the only route that needs nothing else running, which makes it the one a phone
        // with no root and no Shizuku always has. A run with nobody watching does not take it.
        if (!handToInstaller) {
            return failed(flavor, release.version, context.getString(R.string.manager_error_no_shell))
        }
        onLog(context.getString(R.string.manager_handing_over, flavor.label))
        if (!AppUpdater.installApk(context, apk)) {
            return failed(flavor, release.version, context.getString(R.string.manager_error_no_installer))
        }
        if (!waitForInstall) {
            return ManagerInstallOutcome(
                flavor = flavor,
                verdict = ManagerInstallVerdict.Requested,
                route = ManagerInstallRoute.Installer,
                version = release.version,
            )
        }
        return awaitUserInstall(context, flavor, release, onLog)
    }

    /**
     * Waits for a manager the user installed to appear, reporting how long is left rather than going quiet.
     *
     * The waiting is the point of this route inside a run - the manager is checked for *before* the payload
     * is staged, so a run that waited here is a run that then installs a manager AND roots the phone - but
     * it must not be able to hold a run forever, so it ends at [USER_INSTALL_WAIT_MILLIS] and the run goes on
     * either way. A phone with no manager still loads KernelSU; what it cannot do is use the root afterwards.
     */
    private suspend fun awaitUserInstall(
        context: Context,
        flavor: KernelSuFlavor,
        release: ManagerRelease,
        onLog: (String) -> Unit,
    ): ManagerInstallOutcome {
        val deadline = System.nanoTime() + USER_INSTALL_WAIT_MILLIS * 1_000_000L
        var reported = 0L
        while (System.nanoTime() < deadline) {
            installedAfter(context, flavor)?.let { return installed(flavor, release, ManagerInstallRoute.Installer, it) }
            delay(USER_INSTALL_POLL_MILLIS)
            val waited = (USER_INSTALL_WAIT_MILLIS * 1_000_000L - (deadline - System.nanoTime())) / 1_000_000L
            if (waited - reported >= USER_INSTALL_REPORT_MILLIS) {
                reported = waited
                onLog(
                    context.getString(
                        R.string.manager_waiting,
                        flavor.label,
                        (USER_INSTALL_WAIT_MILLIS - waited) / 1000,
                    ),
                )
            }
        }
        return ManagerInstallOutcome(
            flavor = flavor,
            verdict = ManagerInstallVerdict.Requested,
            route = ManagerInstallRoute.Installer,
            version = release.version,
            detail = context.getString(
                R.string.manager_error_not_installed_yet,
                flavor.label,
                USER_INSTALL_WAIT_MILLIS / 1000,
            ),
        )
    }

    /** The release to download for [offer], resolved through the API only when its file name is unknown. */
    private fun resolveRelease(
        context: Context,
        flavor: KernelSuFlavor,
        offer: ManagerOffer,
    ): ManagerRelease? = if (offer.assetNameKnown) {
        // Nothing to ask: the flavour's own release, whose file name this app knows.
        flavor.defaultManagerRelease
    } else {
        KernelSuManager.resolve(context, flavor, offer.version)
    }

    /**
     * The APK, from the cache when it is already there.
     *
     * Cached rather than deleted after the install, and per version rather than one file: the same attempt
     * is made on every run of a phone that cannot be installed to - an installer the user dismissed, a shell
     * that came back later - and re-downloading megabytes each time is a waste the cache removes. Nothing
     * checks it against a digest: GitHub serves the release over TLS, the file is the one this app asked for
     * by name, and Package Manager verifies the APK's own signature when it installs it.
     */
    private suspend fun download(
        context: Context,
        flavor: KernelSuFlavor,
        release: ManagerRelease,
        onLog: (String) -> Unit,
    ): File? {
        val target = apkFor(context, flavor, release.version)
        if (target.isFile && target.length() > 0L) return target
        onLog(context.getString(R.string.manager_downloading, flavor.label, release.version))
        AppLog.info(AppLogTags.KERNEL_SU, "Downloading the ${flavor.label} ${release.version} manager")
        return withContext(Dispatchers.IO) {
            runCatching {
                val directory = target.parentFile?.apply { mkdirs() }
                require(directory != null && directory.isDirectory) { "app storage is not writable" }
                val partial = File(directory, target.name + ".part")
                val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RootMyGalaxyNext/${BuildConfig.VERSION_NAME}")
                    connect()
                    require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
                }
                try {
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    }
                } finally {
                    connection.disconnect()
                }
                require(partial.length() > 0L) { "the answer was empty" }
                partial.renameTo(target)
                target
            }.onFailure { error ->
                AppLog.warn(
                    AppLogTags.KERNEL_SU,
                    "The ${flavor.label} ${release.version} manager could not be downloaded: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }.getOrNull()?.takeIf(File::isFile)
        }
    }

    /**
     * How much of the answer a failure is allowed to put in a log line.
     *
     * `pm`'s own words are the whole point - "INSTALL_FAILED_UPDATE_INCOMPATIBLE" says which fix is needed
     * and "Failure" says nothing - but an app_process stack trace under it is not, and the lines that
     * follow a refusal on some builds are pages of one.
     */
    private fun tail(output: String): String =
        output.lines().map(String::trim).lastOrNull(String::isNotEmpty)?.take(LOG_TAIL_CHARS)
            ?: "no output"

    /** The manager on the phone now, or null when Package Manager does not see one yet. */
    private fun installedAfter(context: Context, flavor: KernelSuFlavor): InstalledManager? =
        runCatching { KernelSuManager.installedFor(context, flavor) }.getOrNull()

    private fun installed(
        flavor: KernelSuFlavor,
        release: ManagerRelease,
        route: ManagerInstallRoute,
        installed: InstalledManager,
    ) = ManagerInstallOutcome(
        flavor = flavor,
        verdict = ManagerInstallVerdict.Installed,
        route = route,
        version = release.version,
        detail = installed.versionName,
    )

    private fun failed(flavor: KernelSuFlavor, version: String?, detail: String) =
        ManagerInstallOutcome(
            flavor = flavor,
            verdict = ManagerInstallVerdict.Failed,
            route = null,
            version = version,
            detail = detail,
        )

    /**
     * The copy of the manager APK the `shell` user may read, or null when it could not be written.
     *
     * Written on every attempt rather than compared first, which is the one place this differs from a run's
     * own staging: `/data/local/tmp` is the `shell` user's directory and mode 0771, so this app cannot read
     * it to compare against anything. The cost is one APK transfer on a route that only happens when there
     * was no manager; what it buys is that the file installed is always the one in this app's cache.
     */
    private fun stageForShell(apk: File): String? = runCatching {
        ShizukuController.writeFile(SHELL_APK_PATH, "644", apk.inputStream())
        SHELL_APK_PATH
    }.onFailure { error ->
        AppLog.warn(
            AppLogTags.KERNEL_SU,
            "The manager APK could not be staged for an install without root: ${error.message}",
        )
    }.getOrNull()

    /**
     * Pushes the APK over the device's own adbd and installs it there.
     *
     * Null on any failure, and the failures are expected rather than exceptional on a phone whose wireless
     * debugging was turned off since it was paired. Every one of them is logged by [WirelessAdbSession]
     * itself, and the caller's next route is the installer - which always works - so there is nothing here
     * to report twice.
     */
    private fun pushOverWirelessAdb(context: Context, apk: File): String? = runCatching {
        WirelessAdbSession.open(context).use { session ->
            session.push(apk, SHELL_APK_PATH)
            session.shell(installCommand(SHELL_APK_PATH))
        }
        SHELL_APK_PATH
    }.onFailure { error ->
        AppLog.warn(
            AppLogTags.WIRELESS_ADB,
            "The manager could not be installed over the pairing: ${error.message}",
        )
    }.getOrNull()

    /** A shell's answer, read the way this app reads one: the exit code and the tool's own word. */
    private fun ShizukuController.ShellResult.ok(): Boolean =
        exitCode == 0 && !output.contains(FAILURE)

    private const val FAILURE = "Failure"

    /** Longer than a usual shell window: `pm install` of a 20 MB APK is not instant on a phone. */
    private const val INSTALL_TIMEOUT_SECONDS = 180L

    /** How long a run waits for a manager the user has to install by hand. */
    private const val USER_INSTALL_WAIT_MILLIS = 180_000L

    private const val USER_INSTALL_POLL_MILLIS = 1_500L

    /** How often that wait says how long is left, so a screen waiting on a person is not silent. */
    private const val USER_INSTALL_REPORT_MILLIS = 15_000L

    private const val LOG_TAIL_CHARS = 400
}
