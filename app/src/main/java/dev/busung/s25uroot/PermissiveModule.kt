package dev.busung.s25uroot

import java.io.File

/**
 * The permissive module, from the app's side.
 *
 * `lkm/permissive` clears `struct selinux_state.enforcing` so SELinux goes permissive - which is what
 * lifts the permission denials, our own `selinux_hide` forge among them, that stop KernelSU's own soft
 * reboot from restarting userspace. The module is loaded with `insmod`, which every Android build
 * carries at `/system/bin/insmod` and which passes `key=value` options through to the module, so no
 * loader binary is needed: the root shell this app already has is enough.
 *
 * Two things make this more than a shell call.
 *
 * The first is a precondition that cannot be checked from inside the kernel. `enforcing` exists only
 * when `CONFIG_SECURITY_SELINUX_DEVELOP` is on; without it the first byte of the struct is
 * `initialized`, and a caller that assumed otherwise would clear that instead and tell the kernel
 * SELinux was never initialised. `/sys/fs/selinux/enforce` exists exactly when DEVELOP is on, so that
 * node is the check - the module's own layout test is a second line of defence, not a substitute for
 * it.
 *
 * The second is that a successful load does not return success. The module writes its byte and then
 * returns `-E2BIG` on purpose, so nothing is left in `/proc/modules`; `insmod` therefore reports
 * failure for a write that worked. The account of what happened is the line the module logs, and this
 * file reads that line rather than the exit code.
 */

/** What the device answered about the module's requirements. */
internal data class PermissiveSupport(
    /**
     * Whether the root route answered at all.
     *
     * Separate from the node below, and first, because "could not look" and "looked and it is not
     * there" need different words: one is a grant to give, the other is a kernel with no switch.
     */
    val rootShell: Boolean,
    /** Contents of `/sys/fs/selinux/enforce`, or null when the node could not be read. */
    val enforceNode: String?,
    /** `CONFIG_SECURITY_SELINUX_DEVELOP` as `/proc/config.gz` states it, null when it says neither. */
    val develop: Boolean?,
    /** Path from `command -v insmod`, or null when the device has none. */
    val insmod: String?,
) {
    val availability: PermissiveAvailability
        get() = permissiveAvailability(this)
}

/** Why the lever can or cannot be used, as one value the UI can word. */
internal enum class PermissiveAvailability {
    /** Every requirement answered: the module can be loaded. */
    Ready,

    /** No route to root, so nothing could be read or run. */
    NoRoot,

    /** No `enforce` node, so this kernel has no runtime switch to write. */
    NoEnforceNode,

    /** The config says DEVELOP is off, which the absence of the node also means. */
    DevelopOff,

    /** No `insmod` on the device. */
    NoInsmod,
}

/**
 * The decision, as a pure function of the readings.
 *
 * The `enforce` node decides on its own when the config could not be read, because it is the same
 * fact from the other side: the node is created only under DEVELOP. Only an explicit `is not set`
 * from the config is a refusal of its own.
 */
internal fun permissiveAvailability(support: PermissiveSupport): PermissiveAvailability = when {
    !support.rootShell -> PermissiveAvailability.NoRoot
    support.enforceNode == null -> PermissiveAvailability.NoEnforceNode
    support.develop == false -> PermissiveAvailability.DevelopOff
    support.insmod == null -> PermissiveAvailability.NoInsmod
    else -> PermissiveAvailability.Ready
}

/** Whether the node reads as enforcing or permissive, or null for anything else it might say. */
internal fun parseEnforceValue(node: String): Boolean? = when (node.trim()) {
    "0" -> false
    "1" -> true
    else -> null
}

/**
 * `CONFIG_SECURITY_SELINUX_DEVELOP` from `/proc/config.gz`, or null when the line says neither way.
 *
 * A kernel config prints its unset options as a comment, so `=y` and `is not set` are the two
 * answers; anything else - an absent file, a build without embedded config - is "did not answer".
 */
internal fun parseDevelopSetting(configOutput: String): Boolean? = configOutput.lineSequence()
    // The unset form is a comment - `# CONFIG_SECURITY_SELINUX_DEVELOP is not set` - so the `#` is
    // part of reading the line rather than a reason to skip it.
    .firstOrNull { it.trim().removePrefix("#").trim().startsWith("CONFIG_SECURITY_SELINUX_DEVELOP") }
    ?.let { line ->
        when {
            line.trim().endsWith("=y") -> true
            line.contains("is not set") -> false
            else -> null
        }
    }

/**
 * What the module's own log line says it did.
 *
 * [token] is the word the module prints after `result=`, kept beside the constant because it is the
 * contract with `rmg_permissive.c`: a report is read by that token, and a summary that fed the enum's
 * own name back would say `verifyfailed` where the module says `verify-failed`.
 */
internal enum class ModuleVerdict(val token: String) {
    Applied("applied"),
    Noop("noop"),
    DryRun("dry-run"),
    Refused("refused"),
    VerifyFailed("verify-failed"),
}

/** The module's log line, read as fields rather than as prose. */
internal data class ModuleReport(
    val verdict: ModuleVerdict,
    val reason: String? = null,
    val offset: Int? = null,
    val before: Int? = null,
    val after: Int? = null,
    val value: Int? = null,
    val symbol: String? = null,
) {
    /** One line for the app log, built from the fields the module actually printed. */
    fun summary(): String = buildString {
        append(verdict.token)
        reason?.let { append(": ").append(it) }
        offset?.let { append(" offset=").append(it) }
        before?.let { append(" before=").append(it) }
        after?.let { append(" after=").append(it) }
    }
}

private const val REPORT_PREFIX = "rmg_permissive:"

/**
 * The module's line out of whatever a kernel log or `logcat` handed back.
 *
 * The last one wins: the log may carry earlier loads from this boot, and only the newest is this
 * load's account. Null means no line at all, which is a different answer from a refusal - a refusal
 * is the module having run and declined.
 */
internal fun parseModuleReport(output: String): ModuleReport? {
    val line = output.lineSequence()
        .filter { it.contains(REPORT_PREFIX) }
        .lastOrNull()
        ?.substringAfter(REPORT_PREFIX)
        ?: return null

    val fields = line.trim().split(Regex("\\s+"))
        .mapNotNull { field ->
            val at = field.indexOf('=')
            if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
        }
        .toMap()

    // Read through the enum's own tokens, so the parser and the wording cannot drift apart. A line
    // without a result accounts for nothing, so it counts as no line rather than as a verdict.
    val verdict = ModuleVerdict.entries.firstOrNull { it.token == fields["result"] } ?: return null

    return ModuleReport(
        verdict = verdict,
        reason = fields["reason"],
        offset = fields["offset"]?.toIntOrNull(),
        before = fields["before"]?.toIntOrNull(),
        after = fields["after"]?.toIntOrNull(),
        value = fields["value"]?.toIntOrNull(),
        symbol = fields["symbol"],
    )
}

/** How the load ended, as one value, with the words to show for it. */
internal enum class PermissiveState {
    /** The node reads 0 after the load. */
    Permissive,

    /** The module reports the write, and the reading that would confirm it could not be made. */
    PermissiveUnconfirmed,

    /** The module ran and declined to write, or its own post-condition failed. */
    Refused,

    /** `insmod` failed without the module logging anything. */
    NotLoaded,

    /** The device does not meet the requirements, so nothing was attempted. */
    Unavailable,
}

internal data class PermissiveOutcome(
    val state: PermissiveState,
    val summary: String,
)

/**
 * The verdict, from what the load produced.
 *
 * Pure, so every combination is testable without a rooted device - which matters here because the
 * interesting cases (a write whose confirmation could not be read, a refusal only the log explains, an
 * `insmod` that failed before the module ran) cannot be produced on demand.
 *
 * The module's exit status is deliberately not a verdict on its own: it returns -E2BIG after a write
 * that worked, so `insmod` reporting failure is the expected shape of success.
 */
internal fun permissiveOutcome(
    support: PermissiveSupport,
    report: ModuleReport?,
    enforceAfter: String?,
): PermissiveOutcome = when (support.availability) {
    PermissiveAvailability.NoRoot -> PermissiveOutcome(
        PermissiveState.Unavailable,
        "no root shell, so the module could not be loaded",
    )

    PermissiveAvailability.NoEnforceNode -> PermissiveOutcome(
        PermissiveState.Unavailable,
        "no /sys/fs/selinux/enforce: this kernel has no runtime SELinux switch to write",
    )

    PermissiveAvailability.DevelopOff -> PermissiveOutcome(
        PermissiveState.Unavailable,
        "CONFIG_SECURITY_SELINUX_DEVELOP is off: there is no enforcing field to clear",
    )

    PermissiveAvailability.NoInsmod -> PermissiveOutcome(
        PermissiveState.Unavailable,
        "no insmod on this device, so the module cannot be loaded",
    )

    PermissiveAvailability.Ready -> when {
        // The log is the account. Without it, a refusal and a module that never ran are the same
        // reading, and they need different words: one is this device's layout, the other is a load
        // that did not happen.
        report == null -> PermissiveOutcome(
            PermissiveState.NotLoaded,
            "the module logged nothing, so it did not reach its own check",
        )

        report.verdict == ModuleVerdict.Refused || report.verdict == ModuleVerdict.VerifyFailed ->
            PermissiveOutcome(PermissiveState.Refused, report.summary())

        parseEnforceValue(enforceAfter.orEmpty()) == false ->
            PermissiveOutcome(PermissiveState.Permissive, report.summary())

        enforceAfter == null -> PermissiveOutcome(
            PermissiveState.PermissiveUnconfirmed,
            report.summary() + "; the enforce node could not be read back",
        )

        else -> PermissiveOutcome(
            PermissiveState.Refused,
            report.summary() + "; the enforce node still reads ${enforceAfter.trim()}",
        )
    }
}

/**
 * The live readings behind [PermissiveSupport], and the load itself.
 *
 * Every command runs through [KernelSuRuntime.rootShell], the app's own root route (Shizuku first,
 * then `su`) rather than the payload's bootstrap handoff: the handoff socket exists to cross the
 * pre-KernelSU boundary, and a Samsung kernel may refuse new connects to it while KernelSU is healthy.
 */
internal object PermissiveLever {
    internal const val NODE = "/sys/fs/selinux/enforce"

    // Internal rather than private so the test can hold the command to them, rather than to copies
    // of them that would drift.
    internal const val NODE_MARK = "#rmg-node"
    internal const val CONFIG_MARK = "#rmg-config"
    internal const val INSMOD_MARK = "#rmg-insmod"

    /**
     * Reads the requirements in one shell round trip.
     *
     * Three process launches through the root route is not worth it for a row that is waiting for an
     * answer, and the markers are what keeps the empty answers apart: an absent node prints nothing,
     * and so does a config the shell cannot read.
     */
    fun support(): PermissiveSupport {
        val output = run(supportCommand())
            ?: return PermissiveSupport(rootShell = false, enforceNode = null, develop = null, insmod = null)

        return PermissiveSupport(
            rootShell = true,
            enforceNode = section(output, NODE_MARK, CONFIG_MARK)
                .trim()
                .takeIf { it == "0" || it == "1" },
            develop = parseDevelopSetting(section(output, CONFIG_MARK, INSMOD_MARK)),
            insmod = section(output, INSMOD_MARK, null).trim().takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Loads [module] with `insmod`, then reads back the two things that say what happened: the line
     * the module logged, and the enforce node.
     *
     * [offset] is the byte offset of the field, passed only when the caller has one. The module may
     * carry a stamp derived at build time from the kernel it was built for, and an app sending a
     * remembered number would replace a value measured against this kernel with one measured against
     * another - which is the mistake that cost this project a boot once already. The stamp's
     * derivation is `tools/btf_selinux_layout.py` in the payload repository.
     */
    fun load(module: File, offset: Int? = null): PermissiveOutcome {
        val support = support()
        if (support.availability != PermissiveAvailability.Ready) {
            return permissiveOutcome(support, report = null, enforceAfter = null)
        }

        val option = offset?.let { " offset=$it" }.orEmpty()
        run("${support.insmod} ${shellQuote(module.absolutePath)}$option value=0")

        // The module unloads itself by returning an error, so its line is in the kernel log and not
        // in insmod's output. `dmesg` is the direct read; the kernel ring buffer in logcat is the one
        // that survives a policy denying dmesg, which this device's shell domain is subject to.
        val log = run("dmesg 2>/dev/null | grep -a $REPORT_PREFIX")
            ?.takeIf { it.contains(REPORT_PREFIX) }
            ?: run("logcat -b kernel -d -s rmg_permissive 2>/dev/null")

        return permissiveOutcome(support, parseModuleReport(log.orEmpty()), run("cat $NODE 2>/dev/null"))
    }

    /**
     * Runs [command] as root, or null when there is no route to root at all.
     *
     * stderr arrives merged into the output, which is what a caller wants here: `insmod` writes its
     * reason to stderr, and a caller reading only stdout would take a failed load for a silent one.
     */
    private fun run(command: String): String? = KernelSuRuntime.rootShell(command)?.output

    /**
     * The one-read command, built where a test can read it.
     *
     * It exists as a separate function because of a bug this file shipped with: the markers were
     * appended unquoted, so the shell read `echo #rmg-node` as `echo` followed by a comment, printed
     * nothing, and every section came back empty - which a healthy kernel was reported as having no
     * enforce node at all. A test over this string is what makes that unrepeatable.
     */
    internal fun supportCommand(): String = buildString {
        append("echo '").append(NODE_MARK).append("'; cat ").append(NODE).append(" 2>/dev/null")
        append("; echo '").append(CONFIG_MARK).append("'")
        append("; zcat /proc/config.gz 2>/dev/null | grep -m1 CONFIG_SECURITY_SELINUX_DEVELOP")
        append("; echo '").append(INSMOD_MARK).append("'; command -v insmod")
    }

    /** The text between two markers, either of which may be missing. */
    private fun section(output: String, start: String, end: String?): String {
        val from = output.indexOf(start)
        if (from < 0) return ""
        val begin = from + start.length
        val to = end?.let { output.indexOf(it, begin) }?.takeIf { it >= 0 } ?: output.length
        return output.substring(begin, to)
    }
}
