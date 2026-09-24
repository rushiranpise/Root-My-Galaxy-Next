package dev.busung.s25uroot

import java.io.File
import kotlin.math.ceil

/** What a recovery action ended as: whether it was accepted, and what to tell the user. */
internal data class RecoveryOutcome(
    val accepted: Boolean,
    val detail: String,
    /**
     * True when the read-only protection this app set up in this boot is what refused the action.
     *
     * Carried rather than left to the caller to infer from the detail: the caller's next move depends
     * on it - a switch to offer, straight from the dialog that reported the failure - and two callers
     * both deciding it from a string would be two places for the rule to drift apart.
     */
    val readOnlyWall: Boolean = false,
)

/**
 * Why no recovery action could run, which is two different failures that used to share one message.
 *
 * The message was "KernelSU root is not available for this boot" for any refusal, and it was printed
 * when the app could not get a root shell - which is not the same claim. A phone with KernelSU loaded
 * and working, where this app simply has not been granted root yet, is not a phone without root, and
 * telling its owner otherwise sends them looking for a problem in the wrong place: the fix is a grant
 * in the KernelSU manager or a running Shizuku, not another install.
 */
internal enum class RecoveryRefusal {
    /** Nothing in this boot has KernelSU in the kernel: there is no root for anyone here. */
    RootMissing,

    /** KernelSU is loaded; what is missing is this app's own way to run a command as root. */
    ShellMissing,

    /**
     * Shizuku's shell is usable and this action still needs root, which Shizuku cannot supply here.
     *
     * Its own words matter because the advice is different from the other two: nothing is missing that
     * a grant or an install would supply, and the user is one reboot away from a boot that can. Sending
     * them to look for a missing permission instead would be the same mistake [ShellMissing] exists to
     * stop.
     */
    ShizukuNeedsRoot,
}

/** Which of the two refusals applies, from whether KernelSU is loaded in this boot. */
internal fun recoveryRefusal(rootLoadedInThisBoot: Boolean): RecoveryRefusal =
    if (rootLoadedInThisBoot) RecoveryRefusal.ShellMissing else RecoveryRefusal.RootMissing

/**
 * Which refusal an action that cannot run should show.
 *
 * Pure, because the three answers are the whole of what makes a refusal useful and they used to be one
 * message: a phone with KernelSU loaded and this app ungranted needs a grant, a phone without KernelSU
 * needs a boot that has it, and a phone with only Shizuku's shell needs to know that a shell is not
 * root - which is the one case where the app must not offer to help.
 */
internal fun recoveryRefusalFor(
    tier: ShellTier,
    tool: RecoveryTool,
    rootLoadedInThisBoot: Boolean,
): RecoveryRefusal = when {
    // Asked only when the action cannot run, so a shell that is merely not root is the whole story:
    // nothing is missing here that a grant or an install would supply.
    tier == ShellTier.Unprivileged && !tier.canRun(tool) -> RecoveryRefusal.ShizukuNeedsRoot
    else -> recoveryRefusal(rootLoadedInThisBoot)
}

/**
 * What the installed KernelSU daemon can be asked to do, as its own help output states it.
 *
 * This exists because the daemon is not the same across payload feeds. The daemon the feed this app
 * ships with serves is a patched build whose own command table lists `late-load`, `soft-reboot`
 * ("Emulate system reboot"), `insmod` and `unload`, but a feed may serve a daemon without
 * `soft-reboot`, and offering a button that can only fail is worse than not offering it. So the
 * action is offered only when the installed daemon's own help lists it, and the match is whole-token
 * because the same binary mentions `emulated-soft-reboot` elsewhere - a name that must not be read as
 * the command.
 */
internal data class KsudCapabilities(
    val softReboot: Boolean = false,
    val lateLoad: Boolean = false,
) {
    val any: Boolean get() = softReboot || lateLoad
}

/**
 * Reads a daemon's command list out of its help output.
 *
 * Tokens are matched whole: `emulated-soft-reboot` is a feature name and must not be read as the
 * `soft-reboot` command.
 */
internal fun parseKsudCapabilities(help: String): KsudCapabilities = KsudCapabilities(
    softReboot = help.containsWord("soft-reboot"),
    lateLoad = help.containsWord("late-load"),
)

private fun String.containsWord(word: String): Boolean =
    Regex("(?<![A-Za-z0-9_-])${Regex.escape(word)}(?![A-Za-z0-9_-])").containsMatchIn(this)

/** The kernel's boot id, which every action here is scoped to. */
internal fun kernelBootToken(): String? = runCatching {
    File("/proc/sys/kernel/random/boot_id")
        .readText(Charsets.US_ASCII)
        .trim()
        .takeIf(String::isNotBlank)
}.getOrNull()

/**
 * Explicit post-root repair actions.
 *
 * These are the actions that are only possible once KernelSU is verified, and they are deliberately
 * kept away from the exploit path: nothing here acquires bootstrap root, replays the exploit, or
 * stages a payload. Every one of them runs its real work in a detached root shell that validates the
 * conditions itself - root, the same kernel boot, and a live Zygote for the restart - and then writes
 * an acknowledgement the app reads back. A successful fork is deliberately *not* enough: the app must
 * not report an action as scheduled when the child found the boot had already changed, or that the
 * framework was not running to restart.
 */
internal object RootRecovery {

    /** Where the verified late-load leaves the daemon. */
    private const val KSUD_PATH = "/data/adb/ksud"

    private const val ACCEPTED_MARKER = "RMG_RECOVERY_ACCEPTED"
    internal const val ACCEPT_POLL_ATTEMPTS = 100

    /**
     * The shell's own wait between polls, as the number it is written as and as the interval it is.
     *
     * Both forms exist because one is a shell literal and the other is arithmetic: an iteration costs
     * *at least* this, since the launcher spawns `cat`, `rm` and `[` as well as sleeping.
     */
    internal const val ACCEPT_POLL_INTERVAL_SECONDS = 0.1
    internal val ACCEPT_POLL_INTERVAL_SEC: String = ACCEPT_POLL_INTERVAL_SECONDS.toString()

    /** Slack between the child's own deadline and the point the app gives up on it. */
    private const val HANDOFF_SLACK_SECONDS = 5.0

    /**
     * How many polls a child whose own deadline is [childDeadlineSeconds] has to be given.
     *
     * The rule is that the window an action is launched with outlasts the child's own worst case, and
     * the trap is that the two are written in different units: the child counts *iterations* of its own
     * waits while the window is measured in wall-clock seconds here. Sized independently they disagreed,
     * and the restart's did: its child waits up to [MODULE_SERVICE_WAIT_SECONDS] seconds before it can
     * report a missing module service, and the app gave up after ten, so a refusal that had already been
     * written arrived as "did not acknowledge the request" - and the child then went on to act on an
     * action the app had reported as failed.
     *
     * Deriving the window from the child's deadline is what keeps them in step. An iteration costs at
     * least [ACCEPT_POLL_INTERVAL_SECONDS], so the count computed here is a *lower bound* on the real
     * window: the app never gives up early, only late.
     */
    internal fun acceptPollAttemptsFor(childDeadlineSeconds: Double): Int =
        ceil((childDeadlineSeconds + HANDOFF_SLACK_SECONDS) / ACCEPT_POLL_INTERVAL_SECONDS).toInt()

    /** What the restart child's checks other than its wait can cost. */
    private const val RESTART_CHILD_READS_SECONDS = 3.0

    /**
     * The restart child's own worst case before it answers *at all*: the bounded wait for the module
     * services, whose iterations cost a reading of the process table each, and the reads around it.
     */
    internal val restartZygoteChildDeadlineSeconds: Double
        get() = MODULE_SERVICE_WAIT_SECONDS * MODULE_SERVICE_WAIT_ITERATION_ALLOWANCE_SECONDS +
            RESTART_CHILD_READS_SECONDS

    /** The window the restart is launched with, from [restartZygoteChildDeadlineSeconds]. */
    internal val restartZygoteAcceptPollAttempts: Int
        get() = acceptPollAttemptsFor(restartZygoteChildDeadlineSeconds)

    /** The soft reboot child's own bounded waits, which its window is sized from. */
    internal const val SOFT_REBOOT_BOOT_WAIT_ITERATIONS = 10
    internal const val SOFT_REBOOT_DAEMON_WATCH_ITERATIONS = 8

    /** What the soft reboot child's checks other than its two waits can cost. */
    private const val SOFT_REBOOT_CHILD_READS_SECONDS = 3.0

    /** The soft reboot child's own worst case, in the same shape as the restart's. */
    internal val softRebootChildDeadlineSeconds: Double
        get() = (SOFT_REBOOT_BOOT_WAIT_ITERATIONS + SOFT_REBOOT_DAEMON_WATCH_ITERATIONS) *
            MODULE_SERVICE_WAIT_ITERATION_ALLOWANCE_SECONDS + SOFT_REBOOT_CHILD_READS_SECONDS

    /** The window the soft reboot is launched with, from [softRebootChildDeadlineSeconds]. */
    internal val softRebootAcceptPollAttempts: Int
        get() = acceptPollAttemptsFor(softRebootChildDeadlineSeconds)

    /**
     * How many iterations the reload child watches the daemon's own `late-load` for.
     *
     * Generous where the soft reboot's watch is short, because the two wait on different things: a
     * soft reboot hands a userspace transition to a detached worker and returns, while a late-load
     * *is* the work - it re-runs the stage scripts, loads `system.prop` and walks the metamodule mount
     * script in the foreground, and a device with modules that do work in those stages is the normal
     * case rather than a slow one.
     */
    internal const val RELOAD_DAEMON_WATCH_ITERATIONS = 45

    /** What the reload child's checks other than its watch can cost. */
    private const val RELOAD_CHILD_READS_SECONDS = 3.0

    /** The reload child's own worst case before it answers at all, in the restart's shape. */
    internal val reloadModulesChildDeadlineSeconds: Double
        get() = RELOAD_DAEMON_WATCH_ITERATIONS * MODULE_SERVICE_WAIT_ITERATION_ALLOWANCE_SECONDS +
            RELOAD_CHILD_READS_SECONDS

    /** The window the reload is launched with, from [reloadModulesChildDeadlineSeconds]. */
    internal val reloadModulesAcceptPollAttempts: Int
        get() = acceptPollAttemptsFor(reloadModulesChildDeadlineSeconds)

    /**
     * The window the reboot is launched with, and the one child whose cost cannot be derived.
     *
     * Every other action waits on a bounded loop of its own, so its window is a function of that loop.
     * This one waits on nothing: it deletes whatever two directories happen to hold, which on a phone
     * with a full module store is seconds of I/O and on an empty one is none. So the app is given two
     * minutes instead - and the failure this buys off is the worst one this action has. The child
     * publishes its acknowledgement *after* the wipe and waits for the app to read it before it
     * restarts, so an app that had already given up on an accepted-looking window would leave a phone
     * that had been emptied and not restarted, with the user told it was on its way.
     */
    internal const val REBOOT_WIPE_ACCEPT_POLL_ATTEMPTS = 1200

    /**
     * A detached child's own words, read back from the acknowledgement.
     *
     * The marker is the *first* line and not merely somewhere in the output, which is also what the
     * launcher requires before it exits zero: a shell prints the acknowledgement file whole, and the two
     * have to agree about where in it the answer starts.
     *
     * Anything after the marker is the child's own account of what it did - one action leaves its
     * account of the wipe there - and it becomes this outcome's detail, because the accepted line is the
     * only channel the protocol has. An accepted outcome with an empty detail keeps its own sentence from
     * the screen, which is what every action other than the wipe does.
     */
    internal fun parseHandoff(output: String): RecoveryOutcome {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() == ACCEPTED_MARKER) {
            return RecoveryOutcome(
                accepted = true,
                detail = lines.drop(1).joinToString("\n"),
            )
        }
        val childError = lines.firstOrNull { it.startsWith("error:") }?.removePrefix("error:")
        return RecoveryOutcome(
            accepted = false,
            detail = childError?.let(::refusalDetail)
                ?: lines.lastOrNull()
                ?: "The recovery action did not answer",
        )
    }

    /**
     * What a child's refusal means, in words about the phone rather than the name of a check.
     *
     * The child speaks in tokens - `module-services-not-ready` - because a shell script has to keep
     * saying the same thing while this app's wording changes, and because the token is what identifies
     * the case. The explanation is the app's side of it, and it is here, next to the scripts, because a
     * token like that one needs a sentence: the modules are installed and enabled, and what is wrong is
     * that restarting now would bring the framework back without them.
     *
     * Anything after a colon is the child's own detail and is kept as it wrote it, because which module
     * is missing is something only the child knows.
     */
    internal fun refusalDetail(refusal: String): String {
        val token = refusal.substringBefore(':').trim()
        val detail = refusal.substringAfter(':', "").trim()
        val reason = HANDOFF_REASONS[token] ?: token.replace('-', ' ')
        return if (detail.isEmpty()) reason else "$reason ($detail)"
    }

    private val HANDOFF_REASONS = mapOf(
        "not-root" to "The shell that would run the action was not root",
        "boot-changed" to "The phone rebooted before the action could run, so it was abandoned",
        "zygote-not-running" to "Android's Zygote service is not running, so there is no framework to restart",
        "zygote-secondary-restart-failed" to "init refused to restart the secondary Zygote",
        "modules-not-mounted" to "No KernelSU module is mounted, so a restart would come back with nothing new",
        "module-services-not-ready" to
            "A module that injects into Zygote is enabled but its service is not running, so a restart " +
                "now would bring the framework back without it",
        "installed-ksud-missing" to
            "The installed KernelSU daemon is missing, so KernelSU's own soft reboot cannot be asked for",
        "another-soft-reboot-owns-this-boot" to "A soft reboot this app started is already running for this boot",
        "lock-failed" to "Another soft reboot holds this boot's lock",
        "boot-not-completed" to "Android had not finished booting, so there was nothing to restart in order",
        "ksud-soft-reboot-timed-out" to
            "KernelSU's soft reboot did not return in time, so it was stopped rather than left to fire later",
        "reboot-command-missing" to "This device has no reboot command to run",
        "daemon-has-no-late-load" to
            "The installed KernelSU has no late-load command, so it cannot re-apply the module lifecycle",
        "another-reload-owns-this-boot" to "A module reload this app started is already running for this boot",
        "ksud-stage-copy-failed" to "The installed KernelSU daemon could not be staged for the reload",
        "ksud-stage-chmod-failed" to "The staged copy of the KernelSU daemon could not be made executable",
        "ksud-stage-hash-mismatch" to
            "The staged copy of the KernelSU daemon did not match the installed one, so nothing was reloaded",
        "ksud-late-load-timed-out" to
            "KernelSU's late-load did not return in time, so it was stopped rather than left running",
        "modules-still-not-mounted" to
            "The modules are still not mounted after the reload, so re-applying the lifecycle did not " +
                "change anything",
    )

    /** How long a child waits for the app to read its handoff before giving up on being heard. */
    private const val HANDOFF_CONSUMED_WAIT_SECONDS = 3.0
    private const val HANDOFF_CONSUMED_POLL_SECONDS = 0.2

    /**
     * The child's own check that its verdict was read, which is not the same as having been written.
     *
     * The app's launcher removes the acknowledgement as it reads it, so an acknowledgement that is
     * still on disk after the child publishes one means nobody read it: the app gave up waiting or
     * died. Both of the dangerous outcomes are avoided by not acting - a framework restart or a reboot
     * that happens after the user was told the action failed is the worst version of this bug, and the
     * window is bounded so a child whose app is gone always exits.
     *
     * It is a definition rather than a guard so each action can decide what "not heard" means for it:
     * the two that change the running system abort, and the soft reboot's own transition has already
     * been handed to the daemon by that point, so it only declines to report itself as accepted.
     */
    internal fun handoffConsumedSnippet(): String {
        val iterations = (HANDOFF_CONSUMED_WAIT_SECONDS / HANDOFF_CONSUMED_POLL_SECONDS).toInt()
        return """
        rmg_handoff_consumed() {
            rmg_handoff_waited=0
            while [ "${'$'}rmg_handoff_waited" -lt $iterations ]; do
                [ -e "${'$'}ACCEPTED" ] || return 0
                sleep $HANDOFF_CONSUMED_POLL_SECONDS
                rmg_handoff_waited=${'$'}((rmg_handoff_waited + 1))
            done
            return 1
        }
        """.trimIndent()
    }

    /** Asks the installed daemon what it supports; null when there is no daemon to ask. */
    internal fun capabilities(
        shell: (String) -> ShizukuController.ShellResult,
    ): KsudCapabilities? {
        val probe = shell("test -x $KSUD_PATH && $KSUD_PATH --help 2>&1 || true")
        val help = probe.output
        if (probe.exitCode != 0 && help.isBlank()) return null
        if (!help.containsWord("late-load") && !help.containsWord("soft-reboot")) return null
        return parseKsudCapabilities(help)
    }

    /**
     * Recreates the Android runtime through init, which is the only supported way to make an already
     * mounted module take effect without a reboot: `setprop ctl.restart zygote` asks init to restart
     * the service it owns, where killing Zygote from here would leave init to notice and recover by
     * accident. Running apps are closed, which is the price of the restart and is stated in the UI.
     *
     * The secondary Zygote, when the device runs one, is restarted first: it is the one that can be
     * restarted without the framework going down, so a failure there is still reportable.
     */
    suspend fun restartZygote(
        shell: (String) -> ShizukuController.ShellResult,
        bootToken: String,
        /**
         * Where the child writes what the framework came back with, in a place the app can read
         * without a shell.
         *
         * Passed in rather than fixed here because it is the app's own files directory, and this object
         * deliberately knows nothing about a `Context`: the verification's record is the one thing this
         * action produces that the app has to be able to read on its own, after the restart has taken
         * the process that started it.
         */
        reportPath: String,
    ): RecoveryOutcome {
        // The restart's purpose is to make already-mounted modules take effect, so a module that is
        // enabled but not mounted is a reason not to spend the restart: the framework would go down
        // and come back without it. Refused here in words the user can read, and checked again inside
        // the child, which is where the decision actually has to hold.
        KernelSuReadiness.refusal(KernelSuReadiness.probe(shell), bootToken)?.let { reason ->
            return RecoveryOutcome(accepted = false, detail = reason)
        }
        return runDetached(
        shell = shell,
        scriptPath = "/data/local/tmp/rmgnext-restart-zygote.sh",
        logPath = "/data/local/tmp/rmgnext-restart-zygote.log",
        acceptedPath = "/data/local/tmp/.rmgnext-restart-zygote-accepted",
        script = restartZygoteScript(
            bootToken = bootToken,
            acceptedPath = "/data/local/tmp/.rmgnext-restart-zygote-accepted",
            reportPath = reportPath,
        ),
        // Outlasts the child's own wait for the module services, so a refusal is read as a refusal
        // rather than as silence.
        acceptPollAttempts = restartZygoteAcceptPollAttempts,
    )
    }

    /**
     * Hands the userspace transition to KernelSU's own `soft-reboot`, which stops and restarts the
     * Android userspace and walks the module lifecycle in its normal order.
     *
     * The keeper is a single owner per kernel boot: it takes a lock carrying the boot id, so a second
     * request in the same boot is told that the first one already owns it rather than racing it. The
     * daemon is never replaced and late-load is never replayed from here - consuming the daemon the
     * verified load installed is the whole point.
     */
    suspend fun softReboot(
        shell: (String) -> ShizukuController.ShellResult,
        bootToken: String,
        capabilities: KsudCapabilities,
    ): RecoveryOutcome {
        if (!capabilities.softReboot) {
            return RecoveryOutcome(
                accepted = false,
                detail = "The installed KernelSU has no soft-reboot command",
            )
        }
        return runDetached(
            shell = shell,
            scriptPath = "/data/local/tmp/rmgnext-soft-reboot-keeper.sh",
            logPath = "/data/local/tmp/rmgnext-soft-reboot.log",
            acceptedPath = "/data/local/tmp/.rmgnext-soft-reboot-accepted",
            script = softRebootScript(bootToken, "/data/local/tmp/.rmgnext-soft-reboot-accepted"),
            acceptPollAttempts = softRebootAcceptPollAttempts,
        )
    }

    /**
     * Restarts the phone with root switched off and everything that says it was rooted removed.
     *
     * KernelSU is loaded into the running kernel, so nothing about it survives a reboot by itself - what
     * brings it back is this app's own root-on-boot setting, which is cleared *before* the reboot is
     * requested because a reboot that happened first would come back rooted. That alone is an unroot, and
     * it leaves the *evidence*: KernelSU's own directory, whose module store, superuser grants and daemon
     * all live in `/data/adb`, and the shared temp directory every root solution and every tool on the
     * phone writes into. Those go with it, in the same action, while there is still a root shell to
     * remove them with - which is the last chance: after the restart nothing here can be deleted any more.
     *
     * Both directories are emptied, not removed: they are created by KernelSU and by init with modes the
     * platform relies on. What could not be removed is reported rather than treated as a failure - a
     * restart with one busy file left behind is still the unroot that was asked for.
     */
    suspend fun rebootAndUnroot(
        shell: (String) -> ShizukuController.ShellResult,
        bootToken: String,
        requiresRoot: Boolean = true,
    ): RecoveryOutcome = runDetached(
        shell = shell,
        scriptPath = "/data/local/tmp/rmgnext-reboot.sh",
        logPath = "/data/local/tmp/rmgnext-reboot.log",
        acceptedPath = "/data/local/tmp/.rmgnext-reboot-accepted",
        script = rebootScript(
            bootToken = bootToken,
            acceptedPath = "/data/local/tmp/.rmgnext-reboot-accepted",
            summaryPath = "/data/local/tmp/.rmgnext-reboot-wipe",
            requiresRoot = requiresRoot,
        ),
        acceptPollAttempts = REBOOT_WIPE_ACCEPT_POLL_ATTEMPTS,
        summaryPath = "/data/local/tmp/.rmgnext-reboot-wipe",
    )

    private suspend fun runDetached(
        shell: (String) -> ShizukuController.ShellResult,
        scriptPath: String,
        logPath: String,
        acceptedPath: String,
        script: String,
        acceptPollAttempts: Int = ACCEPT_POLL_ATTEMPTS,
        summaryPath: String? = null,
    ): RecoveryOutcome {
        val command = detachedLaunchCommand(
            script = script,
            scriptPath = scriptPath,
            logPath = logPath,
            acceptedPath = acceptedPath,
            acceptPollAttempts = acceptPollAttempts,
            summaryPath = summaryPath,
        )
        val launch = runCatching { shell(command) }.getOrElse { error ->
            return RecoveryOutcome(
                accepted = false,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
        val outcome = parseHandoff(launch.output)
        if (outcome.accepted) return outcome
        return RecoveryOutcome(
            accepted = false,
            detail = outcome.detail.ifBlank { "The recovery action was refused (exit ${launch.exitCode})" },
        )
    }

    /**
     * Installs [script] and starts it detached, then waits for the child's own acknowledgement.
     *
     * The launch half is careful about ordering for two reasons: the script is written to a temporary
     * name and moved into place so a half-written script can never be executed, and the
     * acknowledgement file is removed *before* the child starts, so a marker left by an earlier attempt
     * cannot be mistaken for this one's.
     */
    internal fun detachedLaunchCommand(
        script: String,
        scriptPath: String,
        logPath: String,
        acceptedPath: String,
        acceptPollAttempts: Int = ACCEPT_POLL_ATTEMPTS,
        /**
         * A second file an action may leave beside its acknowledgement, printed to the app once the
         * acknowledgement has been read.
         *
         * Fixed-prefix rather than free-form: `cat`-ing it then removing it is the whole protocol, and
         * the app turns what it contains into words. Null for every action that has nothing to add.
         */
        summaryPath: String? = null,
    ): String = buildString {
        append("set -eu\n")
        append("script=${shellQuote(scriptPath)}\n")
        append("accepted=${shellQuote(acceptedPath)}\n")
        append("summary=${shellQuote(summaryPath ?: "")}\n")
        append("tmp=\"\$script.tmp.\$\$\"\n")
        append("cat > \"\$tmp\" <<'RMG_RECOVERY_EOF'\n")
        append(script)
        if (!script.endsWith('\n')) append('\n')
        append("RMG_RECOVERY_EOF\n")
        append("chmod 0700 \"\$tmp\"\n")
        append("mv -f \"\$tmp\" \"\$script\"\n")
        append("rm -f -- \"\$accepted\"\n")
        append(": > ${shellQuote(logPath)}\n")
        append("chmod 0666 ${shellQuote(logPath)} 2>/dev/null || true\n")
        append("setsid sh \"\$script\" >>${shellQuote(logPath)} 2>&1 < /dev/null &\n")
        append("i=0\n")
        append("while [ \"\$i\" -lt $acceptPollAttempts ]; do\n")
        append("  if [ -s \"\$accepted\" ]; then\n")
        append("    ack=\"\$(cat \"\$accepted\" 2>/dev/null || true)\"\n")
        append("    rm -f -- \"\$accepted\"\n")
        append("    printf '%s\\n' \"\$ack\"\n")
        append("    [ \"\$ack\" = '$ACCEPTED_MARKER' ] || exit 78\n")
        // Read after the acknowledgement, because that is the order the child writes them in: what it
        // reports is what it has already done. Removed as it is read, so nothing is left for the next
        // launch to mistake for this one's.
        append("    if [ -n \"\$summary\" ] && [ -s \"\$summary\" ]; then\n")
        append("      cat \"\$summary\"\n")
        append("      rm -f -- \"\$summary\"\n")
        append("    fi\n")
        append("    exit 0\n")
        append("  fi\n")
        append("  i=\$((i + 1))\n")
        append("  sleep $ACCEPT_POLL_INTERVAL_SEC\n")
        append("done\n")
        append("rm -f -- \"\$accepted\"\n")
        append("echo 'the recovery action did not acknowledge the request' >&2\n")
        append("tail -n 8 ${shellQuote(logPath)} >&2 2>/dev/null || true\n")
        append("exit 78\n")
    }

    /**
     * The restart's own side of the contract: root, the same kernel boot, and a live Zygote, all
     * checked before anything is restarted, and the acknowledgement written only once those hold.
     *
     * The short sleep before the restart is not decoration: the app has to be able to read the
     * acknowledgement and persist what it says before the framework it is running in goes away.
     *
     * The child also stays behind after asking init for the restart, because it is the only thing left
     * that can see the result: the acceptance says the request was made, and the framework that would
     * report what came back is the one being replaced. What it finds is written to [reportPath], where
     * the app reads it on its next run - which is what turns "Scheduled" into an account of the
     * framework that actually came back.
     */
    internal fun restartZygoteScript(
        bootToken: String,
        acceptedPath: String,
        reportPath: String,
    ): String = """
        #!/system/bin/sh
        EXPECTED_BOOT=${shellQuote(bootToken)}
        ACCEPTED=${shellQuote(acceptedPath)}
        ACCEPTED_VALUE='$ACCEPTED_MARKER'
        current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
        publish_handoff() {
            printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
            chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
        }
        reject_handoff() {
            publish_handoff "error:${'$'}1"
            rm -f -- "${'$'}0"
            exit 0
        }
        ${handoffConsumedSnippet()}

        [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
        [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
        [ "${'$'}(getprop init.svc.zygote 2>/dev/null)" = "running" ] || reject_handoff 'zygote-not-running'

        # The restart exists to load mounted modules, so it checks its own mount result rather than
        # trusting the app's reading taken a moment ago: a module mounted between the two only makes
        # the restart more likely to be worth it, and one unmounted makes it a framework outage for
        # nothing. When the mounts cannot be read at all the restart proceeds, as it does app-side.
        ${KernelSuReadiness.variables()}
        ${KernelSuReadiness.mountsPresentCondition()} || reject_handoff 'modules-not-mounted'

        # Mounted is not the same as up: the modules that inject into Zygote have their own services,
        # and a Zygote created before those are running comes back without them - the restart would
        # then do the opposite of what it was asked for. Bounded, so this always answers inside the
        # window the app is waiting in.
        ${moduleServiceWaitSnippet()}
        # Which module is missing is the child's to report and the app's to explain: the ids are the
        # child's own readings, and a bare token would leave the user with a check's name instead of
        # the name of the module to look at.
        [ -z "${'$'}rmg_missing_services" ] || reject_handoff "module-services-not-ready:${'$'}{rmg_missing_services# }"

        if [ "${'$'}(getprop init.svc.zygote_secondary 2>/dev/null)" = "running" ]; then
            setprop ctl.restart zygote_secondary || reject_handoff 'zygote-secondary-restart-failed'
        fi

        publish_handoff "${'$'}ACCEPTED_VALUE"
        # What this restart is replacing, read while the app is reading the acknowledgement: after the
        # request there is no "before" left to compare the framework that comes back against.
        ${ZygoteRestartReport.baselineSnippet(reportPath)}
        # Being heard is not the same as being acknowledged: the app removes the acknowledgement as it
        # reads it, so one still sitting there means the app is gone - and an action the user has
        # already been told failed must not go on to restart the framework under them.
        rmg_handoff_consumed || {
            rm -f -- "${'$'}0"
            exit 0
        }

        sleep 0.75
        rm -f -- "${'$'}0"
        setprop ctl.restart zygote

        # The request is not the result, and this child is the only witness left to it: the app that
        # started it is one of the processes the restart just ended, while this script was detached from
        # that process's session. So the framework that replaces the one above is checked here, and its
        # record is left where the app can read it without a shell.
        ${ZygoteRestartReport.verificationSnippet()}
    """.trimIndent() + "\n"

    /**
     * The soft reboot's single owner for this kernel boot.
     *
     * It waits for `sys.boot_completed` because a userspace transition requested before Android is up
     * has nothing to stop in order, and it refuses to run when the boot id has changed under it. The
     * daemon must exist and be executable: a missing `/data/adb/ksud` means the verified load did not
     * leave one, which is a failure to report rather than a reason to fetch or stage another daemon
     * from here.
     *
     * Two waits are bounded on purpose, so that the child always answers *inside* the window the app
     * is waiting in, and never acts after the app has given up on it: the wait for Android to come up
     * is shorter than that window, and the daemon itself is watched, so a `soft-reboot` that has not
     * returned is stopped and reported instead of being left to fire a userspace transition the app
     * already reported as failed.
     *
     * The lock records its owner's pid as well as the boot, and only a lock whose owner is still
     * running is an owner: a keeper that was killed mid-transition would otherwise refuse every later
     * attempt for the rest of the boot. The check fails safe - if the pid has since been reused by an
     * unrelated process, the lock is respected and the request is refused rather than doubled.
     */
    internal fun softRebootScript(bootToken: String, acceptedPath: String): String = """
        #!/system/bin/sh
        EXPECTED_BOOT=${shellQuote(bootToken)}
        ACCEPTED=${shellQuote(acceptedPath)}
        ACCEPTED_VALUE='$ACCEPTED_MARKER'
        LOCK='/data/local/tmp/.rmgnext-soft-reboot-owner'
        KSUD_OUT='/data/local/tmp/rmgnext-soft-reboot-ksud.log'
        KSUD=$KSUD_PATH

        log() { echo "[keeper] ${'$'}(date +%s 2>/dev/null) ${'$'}*"; }
        current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
        publish_handoff() {
            printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
            chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
        }
        reject_handoff() {
            publish_handoff "error:${'$'}1"
            rm -f -- "${'$'}0"
            exit 0
        }
        ${handoffConsumedSnippet()}

        [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
        [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
        [ -x "${'$'}KSUD" ] || reject_handoff 'installed-ksud-missing'

        if ! mkdir "${'$'}LOCK" 2>/dev/null; then
            LOCK_BOOT="${'$'}(cat "${'$'}LOCK/boot_id" 2>/dev/null)"
            LOCK_PID="${'$'}(cat "${'$'}LOCK/pid" 2>/dev/null)"
            if [ "${'$'}LOCK_BOOT" = "${'$'}EXPECTED_BOOT" ] && [ -n "${'$'}LOCK_PID" ] && \
               kill -0 "${'$'}LOCK_PID" 2>/dev/null; then
                reject_handoff 'another-soft-reboot-owns-this-boot'
            fi
            # A lock whose owner is gone is stale, not an owner: a keeper killed mid-transition would
            # otherwise lock this boot out of every later attempt. Taking it over is safe because a
            # request that is no longer running cannot be part-way through one.
            log "taking over a lock left by a keeper that is no longer running (pid ${'$'}LOCK_PID)"
            rm -rf -- "${'$'}LOCK" 2>/dev/null
            mkdir "${'$'}LOCK" 2>/dev/null || reject_handoff 'lock-failed'
        fi
        printf '%s\n' "${'$'}EXPECTED_BOOT" > "${'$'}LOCK/boot_id" 2>/dev/null
        printf '%s\n' "${'$'}${'$'}" > "${'$'}LOCK/pid" 2>/dev/null
        cleanup() { rm -rf -- "${'$'}LOCK" 2>/dev/null; }
        trap cleanup EXIT INT TERM

        i=0
        while [ "${'$'}i" -lt $SOFT_REBOOT_BOOT_WAIT_ITERATIONS ]; do
            [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
            i=${'$'}((i + 1))
            sleep 1
        done
        [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ] || reject_handoff 'boot-not-completed'

        [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'

        # The daemon's own account of what it did is what a failure has to show: a bare exit code
        # sends the user looking for a cause that the daemon already printed.
        : > "${'$'}KSUD_OUT" 2>/dev/null || true
        chmod 0666 "${'$'}KSUD_OUT" 2>/dev/null || true
        ksud_words() { tail -n 1 "${'$'}KSUD_OUT" 2>/dev/null | tr -d '\"' | cut -c 1-160; }

        log "requesting KernelSU native soft reboot"
        "${'$'}KSUD" soft-reboot >>"${'$'}KSUD_OUT" 2>&1 &
        KSUD_PID=${'$'}!
        n=0
        while kill -0 "${'$'}KSUD_PID" 2>/dev/null && [ "${'$'}n" -lt $SOFT_REBOOT_DAEMON_WATCH_ITERATIONS ]; do
            n=${'$'}((n + 1))
            sleep 1
        done
        if kill -0 "${'$'}KSUD_PID" 2>/dev/null; then
            kill "${'$'}KSUD_PID" 2>/dev/null
            wait "${'$'}KSUD_PID" 2>/dev/null
            reject_handoff "ksud-soft-reboot-timed-out ${'$'}(ksud_words)"
        fi
        wait "${'$'}KSUD_PID"
        RC=${'$'}?
        [ "${'$'}RC" = "0" ] || reject_handoff "ksud-soft-reboot-failed-rc-${'$'}RC ${'$'}(ksud_words)"

        # The daemon hands the transition to a detached worker and returns, so a zero here means the
        # request was accepted - which is the only thing the app may report as scheduled. It is run
        # watched rather than plainly: a daemon that has not returned is stopped and reported, because
        # a transition that starts after the app gave up on it would be an action nobody asked for.
        publish_handoff "${'$'}ACCEPTED_VALUE"
        # The daemon hands the transition to a detached worker and has already been told to go; what
        # this guards is the app's own accounting, so a transition nobody read is not one this app
        # reports as scheduled. The cleanup below still runs either way.
        rmg_handoff_consumed || true
        log "KernelSU native soft reboot accepted"
    """.trimIndent() + "\n"

    /**
     * Re-applies the module lifecycle without touching the kernel module, the daemon or the framework.
     *
     * This is the one action that costs the user nothing: no app closes, the screen does not change,
     * and the daemon that was already verified is asked again rather than replaced. It exists because
     * mounting and running are different things - a module whose mounts are in place but whose
     * `late-load` stage has not run is not doing anything yet, and re-running those stages is the
     * cheap way to find out whether that was the problem before spending a framework restart on it.
     *
     * Safe to ask for because of what the daemon does on a second call: a late-load into a kernel that
     * already carries KernelSU skips the kernel module entirely - it says so and carries on - and goes
     * straight to the parts this reload is for. Nothing here can load a second module, and nothing here
     * stages a daemon other than the one already installed.
     */
    suspend fun reloadModules(
        shell: (String) -> ShizukuController.ShellResult,
        bootToken: String,
        capabilities: KsudCapabilities,
    ): RecoveryOutcome {
        if (!capabilities.lateLoad) {
            return RecoveryOutcome(
                accepted = false,
                detail = "The installed KernelSU has no late-load command",
            )
        }
        return runDetached(
            shell = shell,
            scriptPath = "/data/local/tmp/rmgnext-reload-modules.sh",
            logPath = "/data/local/tmp/rmgnext-reload-modules.log",
            acceptedPath = "/data/local/tmp/.rmgnext-reload-modules-accepted",
            script = reloadModulesScript(bootToken, "/data/local/tmp/.rmgnext-reload-modules-accepted"),
            acceptPollAttempts = reloadModulesAcceptPollAttempts,
        )
    }

    /**
     * The reload's own side of the contract: root, this boot, an installed daemon that has the
     * command, and the module mounts read back afterwards.
     *
     * The stage file is written before the daemon is asked for anything. A late-load consumes
     * `/data/local/tmp/.ksud-stage` - it copies the daemon out of it before the load changes this
     * process's security context - and refuses to start without one, so a reload that skipped this
     * would fail in the daemon's words for a reason that has nothing to do with the modules. The copy
     * is the *installed* daemon and is compared byte for byte with it, which is what keeps this action
     * from ever introducing a second build of the daemon the verified load installed.
     *
     * The mounts are read before and after rather than only after. A count that is short afterwards is
     * only this action's failure if the reload did not improve it: a device whose metamodule mounts
     * cannot be applied at all has a short count before the reload too, and refusing there would report
     * the phone's own limit as a failed action - while a count that *fell* is something this action
     * did, and it has to say so.
     */
    internal fun reloadModulesScript(bootToken: String, acceptedPath: String): String = """
        #!/system/bin/sh
        EXPECTED_BOOT=${shellQuote(bootToken)}
        ACCEPTED=${shellQuote(acceptedPath)}
        ACCEPTED_VALUE='$ACCEPTED_MARKER'
        LOCK='/data/local/tmp/.rmgnext-reload-modules-owner'
        KSUD_OUT='/data/local/tmp/rmgnext-reload-modules-ksud.log'
        KSUD=$KSUD_PATH
        STAGE='/data/local/tmp/.ksud-stage'

        log() { echo "[reload] ${'$'}(date +%s 2>/dev/null) ${'$'}*"; }
        current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
        publish_handoff() {
            printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
            chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
        }
        reject_handoff() {
            publish_handoff "error:${'$'}1"
            rm -f -- "${'$'}0"
            exit 0
        }
        ${handoffConsumedSnippet()}

        [ "${'$'}(id -u 2>/dev/null)" = "0" ] || reject_handoff 'not-root'
        [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
        [ -x "${'$'}KSUD" ] || reject_handoff 'installed-ksud-missing'
        # Checked here as well as app-side: this is where the command actually has to run, and a
        # daemon that cannot do it should say so itself rather than fail with a usage message.
        "${'$'}KSUD" --help 2>&1 | grep -q late-load || reject_handoff 'daemon-has-no-late-load'

        if ! mkdir "${'$'}LOCK" 2>/dev/null; then
            LOCK_BOOT="${'$'}(cat "${'$'}LOCK/boot_id" 2>/dev/null)"
            LOCK_PID="${'$'}(cat "${'$'}LOCK/pid" 2>/dev/null)"
            if [ "${'$'}LOCK_BOOT" = "${'$'}EXPECTED_BOOT" ] && [ -n "${'$'}LOCK_PID" ] && \
               kill -0 "${'$'}LOCK_PID" 2>/dev/null; then
                reject_handoff 'another-reload-owns-this-boot'
            fi
            rm -rf -- "${'$'}LOCK" 2>/dev/null
            mkdir "${'$'}LOCK" 2>/dev/null || reject_handoff 'lock-failed'
        fi
        printf '%s\n' "${'$'}EXPECTED_BOOT" > "${'$'}LOCK/boot_id" 2>/dev/null
        printf '%s\n' "${'$'}${'$'}" > "${'$'}LOCK/pid" 2>/dev/null
        cleanup() { rm -rf -- "${'$'}LOCK" 2>/dev/null; }
        trap cleanup EXIT INT TERM

        rm -f -- "${'$'}STAGE"
        /system/bin/cp "${'$'}KSUD" "${'$'}STAGE" || reject_handoff 'ksud-stage-copy-failed'
        chmod 0755 "${'$'}STAGE" || reject_handoff 'ksud-stage-chmod-failed'
        INSTALLED_HASH="${'$'}(sha256sum "${'$'}KSUD" 2>/dev/null)"
        INSTALLED_HASH="${'$'}{INSTALLED_HASH%% *}"
        STAGE_HASH="${'$'}(sha256sum "${'$'}STAGE" 2>/dev/null)"
        STAGE_HASH="${'$'}{STAGE_HASH%% *}"
        [ -n "${'$'}INSTALLED_HASH" ] && [ "${'$'}INSTALLED_HASH" = "${'$'}STAGE_HASH" ] || reject_handoff 'ksud-stage-hash-mismatch'

        ${KernelSuReadiness.variables()}
        rmg_got_before=${'$'}rmg_got

        # The daemon's own account of what it did is what a failure has to show: `late-load` prints
        # each stage it walks, and a bare exit code sends the user looking for a cause it already wrote.
        : > "${'$'}KSUD_OUT" 2>/dev/null || true
        chmod 0666 "${'$'}KSUD_OUT" 2>/dev/null || true
        ksud_words() { tail -n 1 "${'$'}KSUD_OUT" 2>/dev/null | tr -d '\"' | cut -c 1-160; }

        log "re-applying the module lifecycle through the installed daemon"
        "${'$'}KSUD" late-load >>"${'$'}KSUD_OUT" 2>&1 &
        KSUD_PID=${'$'}!
        n=0
        while kill -0 "${'$'}KSUD_PID" 2>/dev/null && [ "${'$'}n" -lt $RELOAD_DAEMON_WATCH_ITERATIONS ]; do
            n=${'$'}((n + 1))
            sleep 1
        done
        if kill -0 "${'$'}KSUD_PID" 2>/dev/null; then
            kill "${'$'}KSUD_PID" 2>/dev/null
            wait "${'$'}KSUD_PID" 2>/dev/null
            reject_handoff "ksud-late-load-timed-out ${'$'}(ksud_words)"
        fi
        wait "${'$'}KSUD_PID"
        RC=${'$'}?
        [ "${'$'}RC" = "0" ] || reject_handoff "ksud-late-load-failed-rc-${'$'}RC ${'$'}(ksud_words)"

        # The daemon's exit code says its stages finished, not that anything is mounted, and the mounts
        # are what this action exists to re-apply - so they are read back here, in the same reading the
        # restart refuses on, and a count that stayed short is reported with its numbers.
        ${KernelSuReadiness.variables()}
        if [ "${'$'}rmg_ns" != unavailable ] && [ "${'$'}rmg_got" -lt "${'$'}rmg_want" ] && \
           [ "${'$'}rmg_got" -le "${'$'}rmg_got_before" ]; then
            # The counts ride along after the colon, which is the part [refusalDetail] keeps as the
            # child wrote it: the sentence beside them is the app's, and which numbers decided it is
            # the child's.
            reject_handoff "modules-still-not-mounted:want=${'$'}{rmg_want} got=${'$'}{rmg_got} before=${'$'}{rmg_got_before}"
        fi

        publish_handoff "${'$'}ACCEPTED_VALUE"
        # Nothing below changes the system, so a reload nobody read is only a reload this app does not
        # get to report as scheduled - which is why it does not abort here as the two restarting
        # actions do.
        rmg_handoff_consumed || true
        log "module lifecycle re-applied"
    """.trimIndent() + "\n"

    /**
     * `sync` first, so what the app persisted before the reboot is on disk when it happens.
     *
     * [requiresRoot] is the difference between the two transports this action can be reached through,
     * and it is the only difference. A root shell issues `/system/bin/reboot`, which a plain Shizuku
     * shell may not execute - but it does not have to: the `shell` user holds the reboot permission,
     * which is how `adb reboot` works, so the same action is asked for through `svc power reboot` and
     * the privilege check becomes the platform's rather than this script's. A refusal is then the
     * device's own words and is reported like any other, instead of the app claiming a missing root
     * for an action that never needed one.
     */
    internal fun rebootScript(
        bootToken: String,
        acceptedPath: String,
        summaryPath: String,
        requiresRoot: Boolean = true,
    ): String {
        val privilegeCheck = if (requiresRoot) {
            "[ \"${'$'}(id -u 2>/dev/null)\" = \"0\" ] || reject_handoff 'not-root'"
        } else {
            ": # no root: this reboot is asked for with the shell user's own permission"
        }
        val reboot = if (requiresRoot) {
            "/system/bin/reboot"
        } else {
            "/system/bin/svc power reboot 2>/dev/null || /system/bin/reboot"
        }
        val lastMile = rebootLastMile(reboot)
        return """
        #!/system/bin/sh
        EXPECTED_BOOT=${shellQuote(bootToken)}
        ACCEPTED=${shellQuote(acceptedPath)}
        SUMMARY=${shellQuote(summaryPath)}
        ACCEPTED_VALUE='$ACCEPTED_MARKER'
        current_boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
        publish_handoff() {
            printf '%s\n' "${'$'}1" > "${'$'}ACCEPTED" || exit 79
            chmod 0666 "${'$'}ACCEPTED" 2>/dev/null || true
        }
        reject_handoff() {
            publish_handoff "error:${'$'}1"
            rm -f -- "${'$'}0"
            exit 0
        }
        ${handoffConsumedSnippet()}

        $privilegeCheck
        [ "${'$'}(current_boot)" = "${'$'}EXPECTED_BOOT" ] || reject_handoff 'boot-changed'
        [ -x /system/bin/reboot ] || reject_handoff 'reboot-command-missing'

        # `sync` first, so what the app persisted before the reboot is on disk when it happens - and the
        # wipe below makes that matter more, not less: the removal itself has to have reached the disk it
        # is removing things from.
        sync
        """.trimIndent() + "\n" + "\nRMG_LAST_MILE='" + lastMile + "'\n\neval \"${'$'}RMG_LAST_MILE\"\n"
    }

    /**
     * Everything after the point of no return: the wipe, the account of it, and the restart.
     *
     * It travels as one string that the script has already read into a variable, because it empties
     * `/data/local/tmp` - the directory the script itself lives in. A shell reads a script as it goes, so
     * a line after that point is a line that may no longer be there: assembled here and evaluated at the
     * end, everything this does is in memory before the first entry is removed.
     *
     * The order is the whole design. The wipe runs first, then the acknowledgement is published with the
     * report beside it, and only then does the child wait to be heard and restart - so an accepted outcome
     * is never a promise about work that has not happened yet, and a phone whose files are already gone is
     * never restarted by a child nobody is listening to.
     *
     * Assigned single-quoted by [rebootScript], so its text cannot contain a single quote; the `require`
     * is what makes that a build-time certainty instead of a shell syntax error on a user's phone.
     */
    internal fun rebootLastMile(reboot: String): String {
        val body = """
        rmg_report=""
        rmg_left() {
            rmg_report="${'$'}rmg_report${'$'}(printf "left %s\n" "${'$'}1")"
        }
        # Whether a path is itself a mount point, read from the kernel rather than asked of a tool that
        # may not be on the phone. Evaluated on absolute paths, so the escapes /proc/mounts uses for
        # spaces cannot reach here: nothing this wipe walks is allow to be a mount to begin with.
        rmg_is_mount() {
            while read -r rmg_dev rmg_mnt rmg_rest; do
                [ "${'$'}rmg_mnt" = "${'$'}1" ] && return 0
            done < /proc/mounts
            return 1
        }
        rmg_wipe_dir() {
            rmg_dir="${'$'}1"
            rmg_removed=0
            rmg_total=0
            if [ -d "${'$'}rmg_dir" ] && [ ! -L "${'$'}rmg_dir" ]; then
                # Contents only, and one entry at a time: the directories themselves are created by
                # KernelSU and by init with modes the platform relies on.
                for rmg_entry in "${'$'}rmg_dir"/* "${'$'}rmg_dir"/.[!.]* "${'$'}rmg_dir"/..?*; do
                    [ -e "${'$'}rmg_entry" ] || [ -L "${'$'}rmg_entry" ] || continue
                    rmg_total=${'$'}((rmg_total + 1))
                    # A mount is detached before anything is removed from it, and never removed through:
                    # rm -rf follows a mount point into the filesystem mounted on it, which would empty
                    # somebody else tree through an entry that happens to sit in one of these directories.
                    if rmg_is_mount "${'$'}rmg_entry"; then
                        umount -l "${'$'}rmg_entry" 2>/dev/null
                        if rmg_is_mount "${'$'}rmg_entry"; then
                            rmg_left "${'$'}rmg_entry"
                            continue
                        fi
                    fi
                    rm -rf -- "${'$'}rmg_entry" 2>/dev/null
                    if [ -e "${'$'}rmg_entry" ] || [ -L "${'$'}rmg_entry" ]; then
                        rmg_left "${'$'}rmg_entry"
                    else
                        rmg_removed=${'$'}((rmg_removed + 1))
                    fi
                done
            fi
            rmg_report="${'$'}rmg_report${'$'}(printf "wipe %s removed=%s total=%s\n" "${'$'}rmg_dir" "${'$'}rmg_removed" "${'$'}rmg_total")"
        }
        rmg_wipe_dir /data/adb
        rmg_wipe_dir /data/local/tmp

        # The report is written before the acknowledgement, and read after it: what the app is told has
        # already happened by the time it is told.
        : > "${'$'}SUMMARY" 2>/dev/null || true
        chmod 0666 "${'$'}SUMMARY" 2>/dev/null || true
        [ -n "${'$'}rmg_report" ] && printf "%s" "${'$'}rmg_report" > "${'$'}SUMMARY"

        publish_handoff "${'$'}ACCEPTED_VALUE"
        # Being heard is not the same as being acknowledged: the app removes the acknowledgement as it
        # reads it, so one still sitting there means the app is gone - and an action the user has already
        # been told failed must not go on to restart the phone under them. The report goes with it, since
        # nobody is left to read that either.
        rmg_handoff_consumed || {
            rm -f -- "${'$'}0" "${'$'}SUMMARY"
            exit 0
        }

        sleep 0.75
        rm -f -- "${'$'}0"
        $reboot
        """.trimIndent() + "\n"
        require(!body.contains('\'')) {
            "the last mile is assigned single-quoted, so a single quote in it would end the assignment " +
                "early - and this is the one script whose failure mode is a phone that is emptied and not " +
                "restarted"
        }
        return body
    }
}
