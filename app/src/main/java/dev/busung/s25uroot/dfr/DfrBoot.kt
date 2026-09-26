package dev.busung.s25uroot.dfr

/**
 * Asking the helper for a reroot at boot, as a rule rather than as a sequence of steps.
 *
 * A phone rooted through the system-uid flow has no root after a hard reboot, and the thing that puts it
 * back is the helper: installed as a system-uid app, it runs the exploit from inside `system_server` and
 * late-loads the daemon. That is one `am start` away at every boot - and it is one `am start` that has to be
 * *right*, because what it starts is an exploit nobody is watching, on a phone whose state decides whether
 * running it again is a reroot or a second attempt at a kernel that has already been patched once this boot.
 *
 * [dfrBootDecision] is that whole judgement in one place, and its order is the reason it is a function rather
 * than four `if`s in a service: every refusal below is a different thing to say, and each of them comes
 * before the ones under it because it is the more specific fact about this boot.
 */

/**
 * What the phone has, as the helper goes.
 *
 * Read from Package Manager alone - no shell, no root - which is what makes this the one part of the
 * decision a boot with nothing running can still answer.
 */
internal enum class DfrHelperStanding {
    /** Nothing is installed under the helper's application id. */
    Missing,

    /** Installed, but not as the shared user: the flow's install landed before its reboot, or never landed. */
    Ordinary,

    /** Installed as `android.uid.system`, which is the whole point of the inject. */
    System,

    /**
     * Installed as the system uid, but built by a different version of this app.
     *
     * The run this would start is the *other* build's run: its shellcode, its arguments, its bugs. Checked
     * here as well as on the flow's own screen because at boot there is nobody to notice a failure that
     * looks like the exploit's fault.
     */
    Stale,
}

/** What a boot with no root does about the helper. */
internal enum class DfrBootDecision {
    /** Start the helper's run with no screen and nobody watching. */
    Reroot,

    /** The setting is off: nothing asked for this, so nothing is reported either. */
    SkipDisabled,

    /** KernelSU is live in this boot already, so there is nothing to reroot. */
    SkipAlreadyRooted,

    /** This boot has already started the helper once. */
    SkipAttempted,

    /**
     * The exploit's hooks are already in this boot's kernel.
     *
     * The one state a rerun cannot fix: arming them again takes a hard reboot, and the hooks are what the
     * exploit sets - so this is where a boot that ran the helper and did not end up rooted comes to rest.
     * It is worth saying rather than skipping, because the phone is not going to reroot itself until someone
     * restarts it.
     */
    SkipArmed,

    /** No helper is installed, so there is nothing to start. */
    NoHelper,

    /** The helper is installed as an ordinary app, so the exploit would not run where it has to. */
    NotSystemUid,

    /** The helper is another build's, so the run it starts would be another build's run. */
    StaleHelper,

    /** The helper could not be started: no shell is available to start it with. */
    NoShell,
}

/**
 * The rule, with nothing of the device in it.
 *
 * [armed] is the reading of the exploit's marker, and it is `null` exactly when no shell could be asked -
 * which is also the answer to "is there a shell to start the helper with". One field carrying both is
 * deliberate: they are one reading of one thing, and a second boolean would be a second chance for the two
 * to disagree.
 *
 * The order is what the reasons are:
 *
 * 1. [DfrBootDecision.SkipDisabled], because an explicit setting outranks everything: a boot nobody asked
 *    for help on is not a boot to report on.
 * 2. [DfrBootDecision.SkipAlreadyRooted], because a rooted boot needs nothing - and it covers the userspace
 *    restart that re-emits `BOOT_COMPLETED`, where KernelSU is still in the kernel.
 * 3. [DfrBootDecision.SkipAttempted], because one attempt per boot is the rule the whole path is built on:
 *    the exploit is a race, and a second attempt tells the user nothing the first did not.
 * 4. The helper's own standing, before the kernel is asked anything: a phone with no helper cannot be fixed
 *    by a boot, so the answer is a sentence about setting the flow up rather than about this boot.
 * 5. [DfrBootDecision.SkipArmed], after the standing and not before it: an armed kernel on a phone whose
 *    helper is missing is a phone with two problems, and the helper is the one the user can act on.
 * 6. [DfrBootDecision.NoShell] last, because it is the only refusal that a *later* boot (or the same boot a
 *    minute later) can turn into a run: Shizuku may still be coming up.
 */
internal fun dfrBootDecision(
    enabled: Boolean,
    kernelSuActive: Boolean,
    attemptedBootToken: String?,
    bootToken: String,
    helper: DfrHelperStanding,
    armed: Boolean?,
): DfrBootDecision = when {
    !enabled -> DfrBootDecision.SkipDisabled
    kernelSuActive -> DfrBootDecision.SkipAlreadyRooted
    attemptedBootToken == bootToken -> DfrBootDecision.SkipAttempted
    helper == DfrHelperStanding.Missing -> DfrBootDecision.NoHelper
    helper == DfrHelperStanding.Ordinary -> DfrBootDecision.NotSystemUid
    helper == DfrHelperStanding.Stale -> DfrBootDecision.StaleHelper
    armed == null -> DfrBootDecision.NoShell
    armed -> DfrBootDecision.SkipArmed
    else -> DfrBootDecision.Reroot
}

/**
 * Whether a decision means the helper is about to be started.
 *
 * Written out rather than written as `== Reroot` at the call site, because the service has three things to
 * do about a decision - nothing, report it, or run - and which of the three this is belongs next to the
 * reasons rather than in the code that reacts to them.
 */
internal fun DfrBootDecision.startsTheHelper(): Boolean = this == DfrBootDecision.Reroot

/**
 * Whether a refusal is worth a notification.
 *
 * The two silent ones are the two states that are the phone working as asked: the setting off, root already
 * live, and a boot that has already had its attempt. Everything else is a boot that could not do what it was
 * asked, and on an unattended path the notification is the only account of why - including the cases the
 * user can do nothing about from there, because "nothing happened" and "nothing could happen" look the same
 * from the outside.
 */
internal fun DfrBootDecision.isWorthReporting(): Boolean = when (this) {
    DfrBootDecision.SkipDisabled,
    DfrBootDecision.SkipAlreadyRooted,
    DfrBootDecision.SkipAttempted,
    -> false
    else -> true
}
