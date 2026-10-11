package dev.busung.s25uroot

import android.content.Context
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * The universal root: the ported DFRoot chain, driven from this app.
 *
 * ## What makes it different from every other root this app offers
 *
 * Everything else the app does ends in the system-uid helper, and the helper is only there because a first
 * temporary root has already put it there. This path needs none of that. It runs from an ordinary app, with
 * no `sharedUserId`, no `packages.xml` edit and no reboot to re-read one, and it gets at the kernel through
 * an **unprivileged** `IpSecManager` transform the technique DirtyInit found and DFRoot used. On a phone
 * with no root at all and nothing installed, this is a root path that starts from this APK alone.
 *
 * ## The daemon comes from the payload, in one of two tiers
 *
 * The daemon is not bundled: it is downloaded from the payload repository for the flavour the run asks for,
 * and placed where the shellcode reads it. That is what keeps the argv and the daemon a matched pair the
 * chain passes `late-load --package-name <that flavour's manager>` and nothing else, so the daemon has to be
 * one built for that flavour's line.
 *
 * And because the KernelSU half of a payload is the one half that does **not** have to be device-specific,
 * there are two things it can be. They are offered as a choice rather than guessed at ([PayloadTier]):
 *
 * - **Device** the feed's entry for this exact phone, whose module was built for the kernel release it
 *   runs. The strongest pairing available, so it is the default.
 * - **Generic** the KMI-generic daemon for the same flavour, carrying a module per KMI and so covering a
 *   whole family of phones. What it has instead of a device-specific module is the daemon's own `vermagic`
 *   rewrite: `load_module()` replaces the module's vermagic with the value the running kernel requires and
 *   retries `init_module`, which is what lets one artifact cover a KMI family.
 *
 * Neither is used for the other's device behind anyone's back, and a tier that cannot serve this phone says
 * so *before* anything is downloaded: the device tier refuses when the feed has no entry for this phone, and
 * the generic tier refuses when its entry carries no module for this phone's KMI.
 *
 * ## Running it
 *
 * [run] blocks: the native chain waits on the exploit, and this process must stay alive while it runs the
 * daemon's bytes are handed over through a file descriptor this process owns. It is a worker-thread call,
 * and [report] is called from those worker threads as well as from the native side, so it has to be safe to
 * call from any of them.
 */
internal object UniversalRootRun {

    /**
     * The daemon's name, under the app's own data directory.
     *
     * A `ksud` for `me.weishu.kernelsu` that loads its kernel module itself, built by **our payload
     * repository** for this device's kernel rather than bundled in this APK. Owning the bytes is what makes
     * this path work: the daemon installs itself from the file the app stages, so nothing depends on a
     * manager APK's `libksud.so` being present - which is what left `/system/bin/su` at zero bytes when the
     * manager happened not to carry one.
     *
     * Upstream builds one daemon and their fork adds two options to it (`--ro-partitions`, `--soft-reboot`);
     * both of those things the app does itself, so the upstream build is what this drives - and since the
     * chain's privileged half moved into the kernel module, the command that runs it lives there: the module
     * names this path and passes `late-load --package-name <manager>` and nothing else, which is the same
     * three arguments the shellcode used to pass.
     */
    /**
     * The bug this chain uses, and what the project calls the technique.
     *
     * Named here once because two things outside this file read them: the payload sheet, which lists a row per
     * KernelSU per tier and has to say which chain that row is, and a test. The number is not this app's
     * finding both root paths here reach the kernel the same way, an unprivileged `IpSecManager` transform
     * whose ESP packets the kernel decrypts into the page cache of a file the exploit holds open, and the
     * helper's own DirtyFrag bridge states the CVE the technique is filed under. That statement is where this
     * comes from, and `UniversalRootContractTest` holds the two together: a CVE is a fact copied between files,
     * and a copy that drifts is a worse label than none at all.
     */
    internal const val CVE = "CVE-2026-43284"

    /** The same technique's name, for a row that has to say what it is without claiming a phone it does not own. */
    internal const val EXPLOIT_NAME = "DirtyFrag"

    private const val DAEMON = "ksud"

    /** The exploit's own mutex, which is how a hook that is already in this boot is read. */
    internal const val ARMED_MARKER = "/dev/df"

    /** What a run did, in the words the screen needs. */
    internal sealed interface Outcome {
        /** The chain ran and returned one of its own codes. */
        data class Ran(val code: Int) : Outcome

        /** Nothing was attempted, and here is the reason to show. */
        data class Refused(val because: String) : Outcome
    }

    /** Whether this boot is already hooked, which is the one thing a run cannot start through. */
    internal fun alreadyArmed(): Boolean = runCatching { File(ARMED_MARKER).exists() }.getOrDefault(false)

    /**
     * KernelSU's own `su`, which the daemon installs on the system partition when it completes.
     *
     * This is the signal that matters on this path, and it was found the hard way: an ordinary app cannot
     * read the kernel at all. Measured on the device with root live - `run-as <app> cat /proc/modules` and
     * `run-as <app> ls /sys/module` both answer nothing about `kernelsu`, so [RootStatusProbe]'s fast
     * reading is blind here however rooted the phone is. `/system/bin/su` is present exactly when the daemon
     * has installed itself and absent on a boot without it, and it is readable from an app.
     */
    private const val SU_PATH = "/system/bin/su"

    /**
     * The chain's own markers, and DFRoot's names and meaning for them: the module's command ends with
     * `touch /dev/dfm0` when the daemon it started returned, `touch /dev/dfm1` when it did not, and upstream's
     * run reads exactly these two files to decide `***SUCCESS***` or `***FAILED***`.
     *
     * On this project's daemon the success marker cannot arrive - `late-load` renames the stage file onto
     * `/data/adb/ksud` and *becomes* the KernelSU service, so the shell that would touch it does not survive to
     * do it - and the failure marker can, which is why the failure one is worth reading: it is the chain saying
     * it is over.
     */
    private const val MARKER_SUCCESS = "/dev/dfm0"

    private const val MARKER_FAILURE = "/dev/dfm1"

    /** How long a run waits for a root it cannot see yet, in milliseconds. */
    internal const val LATE_ROOT_WINDOW_MILLIS = 120_000L

    /** How often the phone is asked inside that window. */
    internal const val LATE_ROOT_POLL_MILLIS = 1_000L

    /** What this app can actually read about whether the run worked. */
    internal sealed interface Reading {
        /** The kernel says so, through a channel an app can only read once root has been granted. */
        data object Live : Reading

        /** The chain's own marker: the daemon it started returned. */
        data object Marker : Reading

        /** The daemon completed: KernelSU's `su` is on the system partition. */
        data object SuInstalled : Reading

        /** The chain's own marker says its command failed - there is nothing left to wait for. */
        data object Failed : Reading

        /** Nothing readable confirms it. */
        data object Nothing : Reading
    }

    /**
     * Whether this boot is rooted, in the strongest terms this app is able to read.
     *
     * The order is the point. [RootStatusProbe] is authoritative and asks the kernel but only a process
     * with root can, so on a phone where the grant has not happened yet it answers no on a phone that is
     * rooted. The `su` the daemon installs is readable from anywhere and is what is left when the kernel is
     * not: it is a weaker claim and it is named as one, rather than being dressed up as the kernel's answer.
     */
    internal fun read(): Reading = when {
        // The chain's own two markers first: a stat each, and the chain speaking rather than this app inferring.
        // A failure marker is an answer in its own right - the run does not get better by waiting.
        exists(MARKER_FAILURE) -> Reading.Failed
        exists(MARKER_SUCCESS) -> Reading.Marker
        // Then the kernel's own list, which is the earliest fact there is: the module appears when the daemon
        // `insmod`s it, minutes before that daemon finishes installing `su` on a device with a metamodule and
        // modules in the tree. [KernelSuRuntime.moduleLoaded] reads it directly when policy allows and through a
        // running Shizuku when it does not - the shell domain can read it where an app domain cannot - and `null`
        // there means it could not look, which is not an answer.
        KernelSuRuntime.moduleLoaded() == true -> Reading.Live
        // Bytes, not existence. A run whose daemon found nothing to install itself from left a 0-byte
        // `/system/bin/su` on this device, and an existence test called that a completed daemon and reported
        // the boot as rooted.
        runCatching { File(SU_PATH).length() > 0 }.getOrDefault(false) -> Reading.SuInstalled
        // Last, and most expensive: the native paths plus a `su` this app may not have been granted, which is a
        // three-second timeout each time it answers nothing.
        rootIsLive() -> Reading.Live
        else -> Reading.Nothing
    }

    private fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    /** Whether KernelSU is live in this boot, asked of the kernel rather than of the chain. */
    internal fun rootIsLive(): Boolean = runCatching { RootStatusProbe.isActive() }.getOrDefault(false)

    /**
     * Whether this app can look at the kernel at all.
     *
     * One question with one implementation - [KernelSuRuntime.moduleLoaded]'s shell route - because that is the
     * only reading a phone with no root grant does not hide: the module list is denied to app domains and
     * readable from the shell, and `/system/bin/su` is not visible to an app either. Both were measured on this
     * device with root live. So "can this app see root" is "is Shizuku running and has it granted this app",
     * and it is what decides whether a run waits for a reading or reports the chain's own steps.
     */
    internal fun canReadTheKernel(): Boolean =
        runCatching { ShizukuController.isRunning() && ShizukuController.isGranted() }.getOrDefault(false)

    /**
     * What a universal run came to.
     *
     * [Rooted] is a root this app read for itself. [RootedUnverified] is the case this flow has and no other
     * does: every step the chain can report is in, and nothing here can see the result - so the steps are the
     * evidence rather than a wait for an answer that cannot come. Reporting those runs as failures was the bug:
     * a rooted phone was called unrooted on every run, because the wait can only ever expire.
     */
    internal enum class Verdict { Rooted, RootedUnverified, Failed }

    /**
     * The rule, with nothing of the device in it.
     *
     * [canRead] comes from [canReadTheKernel] and is the hinge: with a shell this app could look and did not
     * find root, which is a failure; without one, nothing on this device can answer an app, and a chain whose
     * patches landed and whose daemon started is as much as is knowable from here.
     */
    internal fun verdict(code: Int, reading: Reading, canRead: Boolean): Verdict = when {
        reading == Reading.Failed -> Verdict.Failed
        reading != Reading.Nothing -> Verdict.Rooted
        !canRead && chainReachedTheDaemon(code) -> Verdict.RootedUnverified
        else -> Verdict.Failed
    }

    /**
     * Whether the chain's own account says it got as far as starting the daemon.
     *
     * `0` is "the patches applied and the daemon started"; `2` is "the daemon was started and I stopped waiting
     * for it", which is the shape *every* run has here - successful ones included - because this daemon becomes
     * the KernelSU service instead of returning. `1` and `3` are the failures: the daemon exited with an error,
     * and the patches did not land.
     */
    internal fun chainReachedTheDaemon(code: Int): Boolean = code == 0 || code == 2

    /**
     * [read], repeated until the phone shows something or [windowMillis] has run out.
     *
     * Every parameter is one because the window is the whole point and a test has to be able to spend it in a
     * millisecond: with a fake clock and a reader that answers on the fourth poll, this says whether a late root
     * is reported as a root; with one that never answers, that the window is not a retry loop.
     *
     * A zero window is meaningful and used: with no way to look at the kernel, the wait has nothing to wait for
     * (see [canReadTheKernel]), so the run reads once and its verdict is the chain's own account.
     */
    internal fun awaitRoot(
        windowMillis: Long = LATE_ROOT_WINDOW_MILLIS,
        pollMillis: Long = LATE_ROOT_POLL_MILLIS,
        now: () -> Long = { System.currentTimeMillis() },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        read: () -> Reading = ::read,
        report: (String) -> Unit = {},
    ): Reading {
        val deadline = now() + windowMillis
        var polls = 0
        while (true) {
            val reading = read()
            if (reading != Reading.Nothing) return reading
            if (now() >= deadline) return reading
            if (polls++ == 0) {
                report(
                    "waiting up to ${windowMillis / 1000}s for the phone to show the root it was just given: " +
                        "the chain cannot report it, because the daemon it starts becomes the KernelSU service " +
                        "and the shell that started it does not survive to write the chain's marker",
                )
            }
            sleep(pollMillis)
        }
    }

    /** What a reading means, said the same way in the log and on the screen. */
    internal fun describeReading(reading: Reading): String = when (reading) {
        Reading.Live -> "KernelSU is live in this boot, read from the kernel"
        Reading.Marker -> "the chain's own marker ($MARKER_SUCCESS) says the daemon it started returned"
        Reading.SuInstalled -> "KernelSU's su is installed at $SU_PATH, so the daemon completed"
        Reading.Failed ->
            "the chain's own marker ($MARKER_FAILURE) says its command failed - the daemon exited with an " +
                "error, so this boot was not rooted by this run"
        Reading.Nothing -> "nothing readable on this phone confirms root"
    }

    /**
     * What the chain's own return codes mean, so one place decides rather than three screens.
     *
     * These are the chain's account of *itself* and are worded that way on purpose: `0` is the patches
     * landing and the daemon starting, which is not the same fact as this boot being rooted see
     * [rootCheck], which is what turns one into the other.
     */
    internal fun describe(code: Int): String = when (code) {
        0 -> "the chain finished: the patches applied and the daemon started"
        1 -> "the daemon exited with an error"
        2 -> "the chain stopped waiting for the daemon to exit, so it cannot say - the phone decides"
        3 -> "the patches did not land"
        else -> "unexpected result code $code"
    }

    /**
     * What the phone says about the run's own answer.
     *
     * The chain's return code is its *own* account of what it did, and this is the one path in the app where
     * that is not good enough: a module the running kernel refuses still leaves a chain that got all the way
     * to the end and returned success. So a run that says it worked is asked again of the kernel and the
     * answer is printed beside it rather than instead of it. Nothing here is a verdict about the exploit: it
     * is the difference between "the chain finished" and "this boot is rooted", which are two facts and only
     * one of them is the one a person cares about.
     */
    internal fun rootCheck(): String {
        // No pre-judgement on the chain's code any more. It used to answer "nothing to verify: the chain stopped
        // before loading anything" for every code but 0 - which is the code *every* run here has, successful
        // ones included - so the line said the opposite of what the verdict then said. What the phone shows is
        // what this line reports; what it means is [verdict]'s.
        return when (read()) {
            Reading.Live -> "checked the phone: KernelSU is live in this boot"
            Reading.Marker -> "checked the phone: the chain's own marker says the daemon returned"
            Reading.SuInstalled -> "checked the phone: KernelSU's su is installed, so the daemon completed"
            Reading.Failed -> "checked the phone: the chain's own marker says its command failed"
            Reading.Nothing ->
                "checked the phone: nothing readable confirms root - no $SU_PATH, and an app cannot read " +
                    "the kernel. Read the steps above before believing this failed."
        }
    }

    /**
     * Which daemon a run stages, resolved but not yet downloaded.
     *
     * Resolving and downloading are two steps because the manager sits between them. The daemon is what says
     * which KernelSU release this run is about to load, and the manager has to be in place - and be the one
     * that matches - *before* the kernel starts answering: after that there is no app on the phone to grant
     * this one anything, so not even the reading at the end can ask the kernel. A plan answers "which
     * KernelSU" without a download, so the manager step is right even when the download is slow or fails.
     *
     * The type is sealed rather than a nullable profile plus flags, so a caller cannot stage a plan while
     * forgetting to record the flavour it resolved, or describe a generic daemon as a device one.
     */
    internal sealed class Plan {
        /** The root this run will load, which decides the manager. */
        abstract val flavor: KernelSuFlavor

        /** The KernelSU release the daemon was built from, when the feed declares one. */
        abstract val version: String?

        /** The artifact to fetch. The only difference between the tiers at this level. */
        abstract val artifact: RemoteArtifact

        /**
     * Which tier resolved this, which is what a boot run has to ask for again.
     *
     * Carried rather than inferred from the class by the caller, because a boot run has to name a tier to
     * [bootPlan] and the class is not something a preference can hold. For the two resolved tiers it is a
     * constant; [Cached] reads the one its record was written with.
         */
        abstract val tier: PayloadTier

        /** One line naming exactly what will be staged, for the log and the confirmation. */
        abstract val description: String

        /** What a person has to know about this choice before it is used, or null when there is nothing. */
        abstract val caveat: String?

        /** The feed's entry for this phone: a module built for the kernel release it runs. */
        class Device(val profile: TargetProfile) : Plan() {
            override val flavor: KernelSuFlavor get() = profile.flavor
            override val version: String? get() = profile.kernelSuVersion
            override val artifact: RemoteArtifact get() = profile.kernelSu
            override val tier: PayloadTier get() = PayloadTier.Device
            override val description: String
                get() = "device payload: ${profile.displayName} (${profile.flavor.label})"

            override val caveat: String?
                get() = when (profile.kernelMatch(DeviceSnapshot.current())) {
                    // Matched on the three-part version alone, so the entry documents this kernel *version*
                    // rather than this build: its module may be the one for a regional sibling. Worth saying,
                    // because it is exactly the case where the device tier is less specific than it sounds.
                    KernelMatch.Version ->
                        "this entry lists the kernel version rather than this build's exact release, so its " +
                            "module may be built for a sibling build of the same version"
                    else -> null
                }
        }

        /** A daemon carrying a module per KMI, so it covers a family of kernels rather than this phone. */
        class Generic(val daemon: GenericDaemon) : Plan() {
            override val flavor: KernelSuFlavor get() = daemon.flavor
            override val version: String? get() = daemon.version
            override val artifact: RemoteArtifact get() = daemon.daemon
            override val tier: PayloadTier get() = PayloadTier.Generic
            override val description: String
                get() = "generic payload: ${daemon.flavor.label}, carrying ${daemon.coverage}"
            override val caveat: String
                get() = "a shared build for every ${daemon.flavor.label} kernel in ${daemon.coverage}: the " +
                    "module it loads is chosen by the running kernel's KMI rather than built for this phone"
        }

        /**
         * The daemon this phone already staged, for a boot that has no feed to resolve against.
         *
         * A third kind rather than a flag on the other two, because what it describes is genuinely a different
         * thing: the other two are *resolutions* - a reading of what the sources publish right now, pinned to
         * the revision they were read at - and this is a memory of one that has already been used. A caller that
         * could not tell them apart could report "the sources publish X" about a daemon no source was asked
         * about on this boot.
         */
        class Cached(val remembered: CachedUniversalDaemon) : Plan() {
            override val flavor: KernelSuFlavor get() = remembered.flavor
            override val version: String? get() = remembered.version
            override val artifact: RemoteArtifact get() = remembered.artifact
            override val tier: PayloadTier get() = remembered.tier
            override val description: String
                get() = "staged payload: the ${remembered.flavor.label} ${remembered.tier.name.lowercase()} " +
                    "daemon this phone already has"
            override val caveat: String
                get() = "the daemon this device staged on an earlier run rather than one read from the sources " +
                    "just now, which is what a boot run can use: nothing here resolves a feed"
        }
    }

    /**
     * Resolves which daemon this run will stage. Downloads nothing.
     *
     * The device tier asks the catalog for an entry for this phone, and **finding none is not a refusal**: this
     * chain carries its own exploit, so the tier only decides where its *daemon* comes from, and a phone no
     * source has an entry for takes the generic daemon - the same answer the generic tier gives, and the whole
     * reason this flow is the one that runs on a phone the feed has never heard of. Refusing there was the bug
     * this fallback replaces: the sentence it threw told the reader to choose the generic payload, which is
     * exactly what the run now does for them.
     *
     * What can still refuse is the generic lookup, and it refuses for the one thing that is genuinely missing:
     * no daemon covering this kernel. That sentence is deliberately different from the device tier's old one,
     * because "the feed has no entry for this phone" and "its generic daemon carries no module for this kernel"
     * call for different things from whoever reads them.
     *
     * Downloads nothing, so a refusal costs the sentence and not a byte.
     */
    /**
     * The flavour whose KMI-generic daemon can serve this phone, or null when none of them can.
     *
     * The question a phone with no payload entry of its own has to have answered before the app calls that a
     * failure. The chain is this project's primary method and needs no entry: its daemon is chosen by the
     * **running kernel's KMI** rather than by a firmware string, so a phone a source has never heard of - a new
     * build, a region nobody ported - is still a phone it can root. Asking [plan] for the generic tier is the
     * same resolution a run makes, so the two cannot disagree about whether this phone is rootable.
     *
     * Every flavour is asked, in the order the app offers them, because the chain's rows let a person pick
     * which KernelSU to load: one flavour covering this kernel is enough for the card to stop reading as a
     * failure, and which one it is belongs on the row the run is started from rather than here.
     *
     * Downloads nothing - [plan] resolves the feed and stops there - and the null it returns is the honest
     * answer for a phone no published daemon carries a module for.
     */
    internal fun chainFlavor(context: Context): KernelSuFlavor? = KernelSuFlavor.entries.firstOrNull { flavor ->
        runCatching { plan(context, flavor, PayloadTier.Generic) }.isSuccess
    }

    internal fun plan(context: Context, flavor: KernelSuFlavor, tier: PayloadTier): Plan {
        val repository = PayloadRepository(context)
        val snapshot = DeviceSnapshot.current()
        if (tier == PayloadTier.Device) {
            val profile = repository.loadTargets().resolveFor(snapshot, flavor)
            if (profile != null) return Plan.Device(profile)
        }
        val loaded = repository.loadGenericDaemons()
        val covering = loaded.daemons.covering(flavor, snapshot.kmi)
        if (covering == null) {
            val published = loaded.daemons.firstOrNull { it.flavor == flavor }
            val because = when {
                published != null ->
                    "the generic ${flavor.label} daemon carries ${published.coverage}, and this " +
                        "phone's kernel is ${snapshot.kmi ?: snapshot.kernelRelease}, so it has no " +
                        "module for this phone"
                loaded.failures.isNotEmpty() -> loaded.failures.joinToString("\n")
                else -> "no generic ${flavor.label} daemon is published by the enabled sources"
            }
            throw IllegalStateException("No generic payload: $because")
        }
        return Plan.Generic(covering)
    }

    /**
     * The plan a boot run uses: the daemon this phone already staged, or the sources when it has none.
     *
     * The whole reason a boot can run this flow offline. [plan] reads the sources - the catalog for the device
     * tier, the generic feed for the other - and then downloads the daemon it chose, and neither half of that
     * works on a phone that has just restarted with no connectivity. This names the file the last run left in the
     * device-protected directory the shellcode reads, and asks nothing of the network.
     *
     * Two different noes, and the difference matters to whoever reads the log:
     *
     * - **nothing recorded** - a phone that has never staged a daemon, which is every phone whose last run was
     *   on a build before this record existed - falls back to [plan], because that is exactly what a boot run did
     *   before there was anything to stage. Resolving the sources there is not a regression, it is the previous
     *   behaviour; a phone with no network fails with the resolution's own sentence, as it always could.
     * - **recorded and wrong for this run** - another flavour's, another tier's, or a file that is no longer what
     *   was verified - is thrown, and not papered over with a download: it means the record and the run disagree,
     *   and the sentence names which of the three it is.
     */
    internal fun bootPlan(context: Context, flavor: KernelSuFlavor, tier: PayloadTier): Plan {
        val remembered = UniversalDaemonStore.describe(context) ?: return plan(context, flavor, tier)
        universalCacheRefusalReason(remembered, flavor, tier, daemonPath(context))?.let { reason ->
            throw IllegalStateException("No staged payload: $reason")
        }
        return Plan.Cached(remembered)
    }

    /**
     * Where the daemon the chain reads lives, which is also where a boot run's daemon already is.
     *
     * The app's own data directory rather than its `files` directory, and device-protected rather than
     * credential-encrypted: `/data/data` is not mounted until the user unlocks, and a boot run of this flow is
     * one of the two reasons the path is what it is. Named here rather than inline in [stage] because three
     * things now have to agree about it - the staging, the record a boot run verifies against, and the kernel
     * module whose `late-load` command names it. Since the module's half of the command is compiled in, that
     * last one is held where the module is built: the payload repository's workflow refuses a module that
     * names anything but this path, and `dfroot-lkm/README.md` there says why.
     */
    internal fun daemonPath(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir.parentFile, DAEMON)

    /**
     * Downloads the plan's daemon and puts it where the chain reads it, and makes it executable.
     *
     * The one path the shellcode knows is this app's own data directory, and placing the file there is the
     * app's job and only the app's: the chain cannot create a file under `/data` at all, which is what every
     * earlier attempt to have it stage its own daemon ran into.
     *
     * `.tmp` then rename, so a kill in the middle cannot leave a half-written daemon where the next run would
     * take it for a whole one. Overwritten every run.
     */
    internal fun stage(context: Context, plan: Plan, report: (String) -> Unit): File {
        // The chain's own path: the app's data directory, not its `files` directory - where the regular
        // flow's copies go - and not the temp directory, which no app may write on this platform. See
        // [daemonPath] for why it is that directory and that storage.
        val destination = daemonPath(context)

        // A boot run stages what is already here. Nothing is downloaded and nothing is copied - the file is
        // the destination - but it is still checked against the digest the feed declared, because the copy on
        // the phone could be another flavour's from a run since, and staging a daemon built for a different
        // manager is the mix-up the flavour exists to prevent.
        if (plan is Plan.Cached) {
            require(fileMatchesArtifact(destination, plan.artifact)) {
                context.getString(R.string.universal_cached_daemon_stale)
            }
            destination.setExecutable(true, false)
            report("daemon: the copy this phone staged earlier (${plan.artifact.sha256?.take(12) ?: "no digest"})")
            return destination
        }

        val repository = PayloadRepository(context)
        // The daemon alone, for both tiers. This path's exploit is the chain compiled into this APK, so a
        // device entry's exploit artifact would be fetched, verified and thrown away - and a failure in that
        // download would stop a run that never uses it.
        val source = repository.downloadDaemon(plan.artifact, plan.flavor) { line -> report(line) }
        val temporary = File(destination.path + ".tmp")
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("could not move the daemon into place")
        }
        destination.setExecutable(true, false)
        // Recorded so the next boot can stage this same daemon without a feed. After the rename, so a record can
        // never name a file a killed run left half-written, and only from a plan that came from a source - this
        // branch - so the record can only ever describe bytes the enabled sources published.
        UniversalDaemonStore.publish(context, plan, destination)
        return destination
    }

    /**
     * Hands the daemon to the chain and runs it.
     *
     * Nothing here decides whether to reboot: a soft reboot is the app's `restartAfterRoot` setting, and the
     * app performs it after the run - the chain is not told about it, which is what the flag it used to
     * receive was for before the module took over that half.
     */
    internal fun run(
        context: Context,
        daemon: File,
        flavor: KernelSuFlavor,
        report: (String) -> Unit,
    ): Outcome {
        if (alreadyArmed()) {
            return Outcome.Refused(
                "This boot is already hooked ($ARMED_MARKER exists). Reboot before running again.",
            )
        }
        val code = try {
            drive(context, daemon, flavor, report)
        } catch (error: Throwable) {
            return Outcome.Refused("The chain could not start: ${error.javaClass.simpleName}: ${error.message}")
        }
        report(describe(code))
        report(rootCheck())
        return Outcome.Ran(code)
    }

    /**
     * The `IpSecManager` half: the transform that makes the kernel do the writing.
     *
     * Ported from DFRoot's own driver, and unchanged in the parts that matter an unprivileged UDP
     * encapsulation socket, an SPI allocated on the loopback, one AES-CBC key and one HMAC key, and a
     * transport-mode transform whose ESP packets the kernel decrypts into the page cache of files the
     * exploit has opened for `splice()`. The keys are generated per run and never leave this process: the
     * native side needs them to compute the IVs, and nothing else does.
     *
     * Every handle is closed in a `finally`, because a leaked encapsulation socket is a port held on a phone
     * that is about to reboot into a different kernel.
     */
    private fun drive(
        context: Context,
        daemon: File,
        flavor: KernelSuFlavor,
        report: (String) -> Unit,
    ): Int {
        val ipsec = context.getSystemService(Context.IPSEC_SERVICE) as IpSecManager
        val encapsulation = ipsec.openUdpEncapsulationSocket()
        val loopback = InetAddress.getByName("127.0.0.1")
        val spi = ipsec.allocateSecurityParameterIndex(loopback)
        val random = SecureRandom()
        val aesKey = ByteArray(32).also(random::nextBytes)
        val macKey = ByteArray(32).also(random::nextBytes)

        // A port from a socket that is immediately closed: the encapsulation socket is the one that stays
        // open, and this only has to be a number the transform can address the sender with.
        val senderPort = DatagramSocket().let { socket ->
            val port = socket.localPort
            socket.close()
            port
        }

        val transform = IpSecTransform.Builder(context)
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey))
            .setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, macKey, ICV_BITS))
            .setIpv4Encapsulation(encapsulation, senderPort)
            .buildTransportModeTransform(loopback, spi)

        // Which vendor library gets patched, decided here so the log can name it: a run that fails to patch
        // has to say *what* it could not patch, because the list is per device.
        val koTarget = chooseKoTarget { File(it).exists() }

        return try {
            report("chain: starting (encap port ${encapsulation.port}, spi ${spi.spi})")
            report("ko target: $koTarget")
            UniversalRoot.nativeRunAll(
                reporter = UniversalRoot.Reporter { line -> report(line.trim()) },
                koTarget = koTarget,
                encapPort = encapsulation.port,
                spi = spi.spi,
                aesCbcKey = aesKey,
                hmacKey = macKey,
                icvLen = ICV_BITS / 8,
                senderPort = senderPort,
                // The manager this flavour's daemon serves. One library, so it travels as a value - see
                // [UniversalRoot.nativeRunAll].
                packageName = flavor.managerPackage,
            )
        } finally {
            runCatching { transform.close() }
            runCatching { spi.close() }
            runCatching { encapsulation.close() }
        }
    }

    /** The truncation the transform and the native side have to agree on, in bits. */
    private const val ICV_BITS = 128
}

/**
 * Upstream's vendor libraries, in their order: the one that is present on every Samsung this chain has been
 * run on first, then the two the devices with a different vendor set carry instead.
 */
internal val KO_TARGET_CANDIDATES = listOf(
    "/vendor/lib64/libbinderdebug.so",
    "/vendor/lib64/libstagefrighthw.so",
    "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
)

/**
 * Which of [KO_TARGET_CANDIDATES] this device will be patched through.
 *
 * [exists] rather than a read, because this is a path inside a directory an app may traverse and may not
 * list: existence is the strongest answer available here, and a candidate that is present but not loadable
 * fails later, at the patch, which names the file it could not write.
 *
 * The fallback is the first candidate rather than a refusal, and that is upstream's choice kept deliberately:
 * a phone whose vendor set is not on the list is a phone this chain has never been run on, and the attempt
 * costs a run that fails at the patch and says which file - where a refusal here would cost the same run
 * without ever trying. The chain checks what it wrote, so the attempt is not taken on trust.
 */
internal fun chooseKoTarget(exists: (String) -> Boolean): String =
    KO_TARGET_CANDIDATES.firstOrNull(exists) ?: KO_TARGET_CANDIDATES.first()
