package dev.busung.s25uroot.dfr.stage2

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage two's screen: one word for this boot, one line about what is missing, four actions, and the log.
 *
 * ## The run is gated, because the run is what cannot be taken back
 *
 * The exploit spends the boot it runs in: it arms a marker in the kernel that only a reboot clears, and a
 * second attempt is a second late-load into a kernel that already has the module. So nothing on this screen
 * starts one until the two states that make it pointless are read and answered - and answered by a greyed
 * button rather than by a log line for a press that could never have worked, which is what it used to be.
 * See [BootState] and [blockReason].
 *
 * The same goes for the files: a run the helper cannot hand over is a run that cannot succeed, and it would
 * still spend the boot finding that out. [StageNeeds] lists them, the screen refuses without the ones that
 * are the run's own hand-off, and the phone's own files are shown as readings rather than gates - a phone
 * whose exploit is fine must not be refused a run over a file this process happens not to be able to see.
 *
 * ## What is on the screen, and what is in the log
 *
 * Four actions, and they are the four things that are worth doing from here: **Run**, which is the one that
 * costs the boot; **Open Manager**, which is the app that can act on the root this loaded; **Open RMG-NEXT**,
 * which owns the boot settings; and **Soft reboot**, which is the userspace restart a loaded KernelSU needs
 * before it does anything. Everything else this screen used to say - the process identity, the hook and
 * daemon readings, the manager table, the setting behind the launch - is read into the log instead and stays
 * there: it is diagnosis, nobody needs it before pressing, and the readings that decide a press are the two
 * this screen answers with one word.
 *
 * **Run is refused, or the screen says so before it is pressed.** [startRun] holds the same gate as the
 * button, so the auto-run the app asks for on a boot without root is answered the same way as a press.
 *
 * ## The log is the diagnosis
 *
 * A run that stops says which step refused, in the lines above it - and those lines are produced for minutes
 * before there is a verdict. So the log is a panel with a scroll of its own rather than the tail of the page:
 * a page that scrolled as the exploit talked would move the action out from under the thumb that pressed it.
 *
 * ## Written in code, not in resources
 *
 * This APK has one screen, and a layout resource for it would be a second place for the same view hierarchy
 * to be wrong - one that the compiler cannot check against the fields it fills. The strings are here for the
 * same reason: this module shares no code with the app, so its resources are its own, and a strings file
 * with a dozen entries in it would be a second file to keep in step for no reader.
 */
class Stage2Activity : Activity() {

    private lateinit var palette: Palette

    private lateinit var stateView: TextView
    private lateinit var summaryView: TextView
    private lateinit var needsHeaderView: TextView
    private lateinit var needsRows: LinearLayout
    private lateinit var runButton: Button
    private lateinit var recheckButton: Button
    private lateinit var openManagerButton: Button
    private lateinit var openAppButton: Button
    private lateinit var softRebootButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var controller: IBinder? = null
    private val controllerLock = Object()
    private val running = AtomicBoolean(false)
    private var controllerReceiver: BroadcastReceiver? = null

    /** The last reading of the files a run needs, so a press decides on it rather than re-reading the phone. */
    @Volatile
    private var needs: StageNeeds.Reading? = null

    /** Whether the app asked for the run on its way in, which is a reading the log still carries. */
    private var autorun = false

    /** What the app's own reroot-at-boot setting was, or null when the app did not say. Reported in the log. */
    private var rerootAtBoot: Boolean? = null

    /**
     * Which flavour this run loads, as the app named it, or null when the app did not say.
     *
     * The one fact about the payload this process cannot work out for itself: three managers can be installed
     * at once, they belong to three different projects, and which of them drives the daemon this boot will
     * exec is a fact about the payload the app resolved. So it is told - see `DfrInstall.STAGE_TWO_FLAVOR_EXTRA`
     * - and an id this APK does not know reads the same as no id at all.
     */
    private var payloadFlavor: ManagerFlavor? = null

    /** The window the app was drawing with, as `role=hex` pairs, or null when it did not say. */
    private var tint: Map<String, Int> = emptyMap()

    /**
     * Whether the file list is open.
     *
     * Closed is the default on a phone whose list is complete, because nine rows that all say `ok` are nine
     * rows of nothing to do - and open is forced whenever one of them is missing, because that is the row
     * somebody has to fix.
     */
    private var needsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autorun = intent?.getBooleanExtra(EXTRA_AUTORUN, false) == true
        rerootAtBoot = intent?.takeIf { it.hasExtra(EXTRA_REROOT_AT_BOOT) }
            ?.getBooleanExtra(EXTRA_REROOT_AT_BOOT, false)
        payloadFlavor = KsudStage.flavorOf(intent?.getStringExtra(EXTRA_FLAVOR))
        tint = parseTint(intent?.getStringExtra(EXTRA_TINT))
        palette = Palette(this, tint)
        setContentView(buildScreen())
        dressWindow()
        refreshReadouts()

        runButton.setOnClickListener { startRun() }
        recheckButton.setOnClickListener { recheck() }

        // The controller arrives as a binder on a broadcast, so the receiver has to be live before the
        // hop is asked for - and it is registered exported because the sender is network_stack, a
        // different process with a different uid.
        controllerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    controller = intent.extras?.getBinder(StageReceiver.CONTROLLER)
                    append("[+] Got the controller from network_stack")
                } catch (error: Throwable) {
                    append("[x] Could not get the controller: $error")
                } finally {
                    synchronized(controllerLock) { controllerLock.notifyAll() }
                }
            }
        }
        registerReceiver(controllerReceiver, IntentFilter(StageReceiver.EVIL_ACTION), RECEIVER_EXPORTED)

        // Staging first and the run second, on one thread: the run needs the daemon where the exploit
        // execs it, and this is what puts it there. Opened by hand the staging is all that happens,
        // because a run nobody asked for spends this boot's only attempt.
        runInBackground {
            append(KsudStage.stage(this))
            readThePhone(announce = true)
            if (autorun) runOnUiThread { startRun() }
        }
    }

    override fun onDestroy() {
        controllerReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /** Reads the phone again and stages again, which is the one action here that changes nothing. */
    private fun recheck() {
        if (running.get()) {
            append("[!] A run is already in progress, so the current readings were left unchanged.")
            return
        }
        runInBackground {
            append(KsudStage.stage(this))
            readThePhone(announce = true)
        }
    }

    /**
     * The files this boot's run stands on and the two states it cannot be run from, read once.
     *
     * The list itself is the card's, not the log's: which files are there and which are not is the one thing
     * a person is looking at before a press, and nine lines of it interleaved with the exploit's own output is
     * a screen where neither can be read. It reaches the log only when a run is *refused* for it - see
     * [startRun] - which is the one case where the list has to outlive the screen.
     */
    private fun readThePhone(announce: Boolean) {
        val reading = StageNeeds.check(this)
        needs = reading
        runOnUiThread { refreshReadouts() }
        if (announce) append(openingLine())
    }

    /**
     * One line saying what this process is, which is the one reading the card cannot carry.
     *
     * A stage two that installed without being system-uid looks exactly like a working one from every other
     * process on the device, and that is a fact about the log rather than about the phone's state: nothing on
     * the card changes when it is wrong. So it is one line, on the first read only, with the manager and who
     * started this run on the same line - the card names the manager in its action, and this is the record.
     */
    private fun openingLine(): String {
        val uid = Process.myUid()
        val installed = KsudStage.installedFlavors(this)
        val manager = payloadFlavor?.label
            ?: installed.firstOrNull()?.label
            ?: "no manager"
        val started = if (autorun) {
            "started by the app" + when (rerootAtBoot) {
                true -> " (reroot at boot on)"
                false -> " (reroot at boot off)"
                null -> ""
            }
        } else {
            "started by hand"
        }
        return "[*] pid=${Process.myPid()} uid=$uid${if (uid == Process.SYSTEM_UID) " (system)" else ""} " +
            "${selinuxContext()} · manager $manager · $started"
    }

    /**
     * The exploit, from the refusal check to the verdict.
     *
     * The parts that answer a press are here and the sequence itself is [exploit], because the app can
     * also ask for the run on its way in - and both entries have to refuse the same states in the same
     * words.
     */
    private fun startRun() {
        val refusal = blockReason()
        if (refusal.isNotEmpty()) {
            append("[x] $refusal")
            // The list behind that sentence, in the log as well as on the card: a refusal that names the count
            // of what is missing and nothing else is a phone nobody can fix from the log they were handed.
            needs?.takeIf { !it.ready }?.let { append(it.report()) }
            refreshReadouts()
            return
        }
        if (!running.compareAndSet(false, true)) {
            append("[!] A run is already in progress")
            return
        }
        runButton.isEnabled = false
        recheckButton.isEnabled = false
        progress.visibility = View.VISIBLE
        summaryView.text = "Running the exploit. The log below shows what is happening."
        summaryView.setTextColor(palette.onSurfaceVariant)
        runInBackground {
            var result = -1
            try {
                result = exploit()
            } finally {
                running.set(false)
                val success = result == 0
                runOnUiThread { progress.visibility = View.GONE }
                // The verdict goes to the log and the screen goes back to being a reading of the phone: what
                // the state word says after a run is the fact that decides the next press - rooted, or spent -
                // and a line about the run itself would overwrite that with news that is already in the log.
                append(
                    if (success) {
                        "\n[+] Root access obtained. The manager can act on it now."
                    } else {
                        "\n[x] The process stopped at $result. The lines above say where."
                    },
                )
                Log.i(TAG, "Run finished with $result")
                // Read again rather than only redrawn: a run consumes the file the daemon's own late-load
                // renames, so the list this screen printed on the way in is no longer what is on the phone.
                readThePhone(announce = false)
                // A run a person asked for, whose last step is the one the phone cannot finish by itself:
                // what was just loaded is inert until the Android userspace is built again, and the restart
                // that does it is KernelSU's own soft reboot. This process cannot ask for it - that needs a
                // root shell - so the app, which has one, is told instead. Not for a boot's run: that one was
                // started by the app's own gate, which watches this boot's kernel and does the restart there.
                if (success && !autorun) runOnUiThread { askTheAppToRestart() }
            }
        }
    }

    /**
     * Why a run must not start here, or an empty string when one may.
     *
     * One function for the button's own state and for the refusal, so a greyed button and a refused press
     * cannot be two different rules. The order is the order of what a person does about it: a rooted or
     * spent boot is a reboot, and a missing file is the app's staging.
     */
    private fun blockReason(): String {
        if (BootState.armed()) {
            return "The exploit is already active (${BootState.ARMED_MARKER} exists), so it cannot be run " +
                "again. Only a full device reboot will clear it. A soft reboot will not."
        }
        if (BootState.rootLive()) {
            return "KernelSU is already loaded in this boot's kernel, and a second late-load is not run into " +
                "a kernel that already has the module. Reboot, or soft reboot, and root again from there."
        }
        val reading = needs
            ?: return "The files this boot's run needs have not been read yet, so a run was not started."
        if (!reading.ready) {
            val blocking = reading.blocking
            return "The run is refused: ${blocking.size} of the ${reading.items.count { it.need.required }} " +
                "files it hands over are not there - " + blocking.joinToString(", ") { it.need.path } +
                ". The list is on the card."
        }
        return ""
    }

    /** The whole exploit, and its result: the code the controller answered with. */
    private fun exploit(): Int {
        var result = -1
        append(StageHop.hopToNetworkStack(this))
        val binder = awaitController(CONTROLLER_TIMEOUT_MS)
        if (binder == null) {
            append(
                "[x] No controller was found within ${CONTROLLER_TIMEOUT_MS / 1000}s. " +
    "The exploit may not have worked, or network_stack took too long. " +
    "Check the log above for details.",
            )
            return result
        }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        // The exploit reports while it runs, over a binder of ours, because it produces output
        // for minutes before it produces a verdict - so the callback is created first and the
        // answer is what comes back on the reply parcel.
        val reporter = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                runCatching { append(data.readString().orEmpty()) }
                    .onFailure { Log.e(TAG, "Reporter failed", it) }
                return true
            }
        }
        request.writeStrongBinder(reporter)
        try {
            if (binder.transact(CODE_RUN_ALL, request, reply, 0)) {
                result = reply.readInt()
                append("\nRun all -> $result")
            } else {
                append("[x] The controller refused the call")
            }
        } catch (error: Throwable) {
            append("[x] Controller call failed: ${error.message}")
        } finally {
            request.recycle()
            reply.recycle()
        }
        return result
    }

    /**
     * Tells the app that KernelSU has just arrived, which is the app's cue to do the restart.
     *
     * The restart is a soft reboot, which is KernelSU's own command run as root - and this process is the
     * system uid inside `system_server`, which the daemon does not hand a shell to. The app has one, along
     * with the setting that says whether to use it and the lock that keeps two reboots from starting at
     * once, so the whole of this side is the sentence "root is live": the app decides the rest.
     *
     * The app is started rather than broadcast to, because its receivers are deliberately not exported and
     * an extra on an explicit launch is what the two APKs already share. That is also why the app checks
     * which package started it before acting: this extra names a restart, and a screen any app on the phone
     * can open is not a place to accept one from.
     */
    private fun askTheAppToRestart() {
        val launch = packageManager.getLaunchIntentForPackage(MAIN_PACKAGE)
        if (launch == null) {
            append(
                "[*] Root is loaded. A restart is required to apply this state. " +
    "$MAIN_PACKAGE is not installed, so you need to restart the device yourself.",
            )
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch.putExtra(EXTRA_AFTER_ROOT, true)
        val started = runCatching { startActivity(launch) }.isSuccess
        append(
            if (started) {
                "[*] Root is loaded. A restart is required to apply this state. " +
    "$MAIN_PACKAGE was asked to restart the device."
            } else {
                "[!] Root is loaded, but $MAIN_PACKAGE could not be opened. " +
        "You need to restart the device yourself."
            },
        )
    }

    /**
     * Asks the app for the userspace restart, which is the only way this screen can ask for one.
     *
     * The request is the app's own direct soft-restart action - the one its launcher shortcut sends - so what
     * answers it is the app's existing path: the same probe for a root shell, the same per-boot lock, and the
     * same daemon command. Nothing here is a second implementation of a reboot, and a phone the app cannot
     * restart (no root, no Shizuku) is told to the person by the app's own screen rather than by this one.
     *
     * It is offered whether or not a run has happened, because that is the state it is for on this device: a
     * load is inert until the userspace is built again, and after one it is the only thing left to do.
     */
    private fun softReboot() {
        val launch = packageManager.getLaunchIntentForPackage(MAIN_PACKAGE)
        if (launch == null) {
            append("[!] $MAIN_PACKAGE is not installed, so the restart cannot be asked for. Restart the device yourself.")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch.action = SOFT_RESTART_ACTION
        val started = runCatching { startActivity(launch) }.isSuccess
        append(
            if (started) {
                "[*] asked $MAIN_PACKAGE for the userspace restart; it needs root to take it"
            } else {
                "[!] $MAIN_PACKAGE could not be opened, so you need to restart the device yourself"
            },
        )
    }

    /** Waits for the controller binder, reporting how long is left rather than going quiet. */
    private fun awaitController(timeoutMs: Long): IBinder? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(controllerLock) {
            var current = controller
            while (current == null) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                append("[*] Waiting for the controller (${left / 1000}s left)")
                runCatching { controllerLock.wait(minOf(left, 5_000)) }
                    .onFailure { Thread.currentThread().interrupt() }
                current = controller
            }
            return current
        }
    }

    /** This process's SELinux context, for the log's identity line. */
    private fun selinuxContext(): String = runCatching {
        "context=" + File("/proc/self/attr/current").readText().trim().trim('\u0000')
    }.getOrElse { "context=unreadable" }

    /** Everything the state word cannot carry: the pill, the one line under it, and what a press may do. */
    private fun refreshReadouts() {
        val reading = needs
        val busy = running.get()
        val armed = BootState.armed()
        val rootLive = BootState.rootLive()
        val blocking = reading?.blocking.orEmpty()
        val ready = reading?.ready == true

        val (word, tint) = when {
            busy -> "Running" to palette.onSurfaceVariant
            rootLive -> "Rooted" to palette.success
            armed -> "Already run" to palette.warning
            !ready -> "Not ready" to palette.warning
            else -> "Ready" to palette.success
        }
        stateView.text = word
        stateView.setTextColor(palette.onAccent(tint))
        stateView.background = palette.pillBackground(tint)

        summaryView.text = when {
            busy -> "Running the exploit. The log below shows what is happening."
            rootLive -> "KernelSU is loaded in this boot's kernel. Reboot, or soft reboot, before rooting again."
            armed -> "The exploit has already run this boot. Reboot to root this boot again."
            !ready -> if (reading == null) {
                "Reading the files this boot's run needs…"
            } else {
                "${blocking.size} of ${reading.items.count { it.need.required }} files missing: " +
                    blocking.joinToString(", ") { it.need.path }
            }
            else -> "All ${reading.items.count { it.need.required }} files this boot's run needs are present."
        }
        summaryView.setTextColor(if (busy || rootLive || armed || !ready) palette.onSurfaceVariant else palette.success)

        renderNeeds()
        refreshManagerAction()
        // The whole point: a run that cannot work is not offered. [startRun] refuses the same three states,
        // so this is the button's half of one rule rather than a second one.
        runButton.isEnabled = !busy && !armed && !rootLive && ready
        recheckButton.isEnabled = !busy
    }

    /**
     * The file list, as rows under its own header: a tick or a cross, the path, and for a missing one the
     * sentence that says what stops working without it.
     *
     * Rebuilt rather than patched, because the rows and the header have to agree about what is wrong and the
     * reading is what both come from. The reasons are only on the rows that are not `ok`: a list of nine
     * explanations is a wall, and most of them describe something that is not wrong.
     *
     * Three markers, because there are three states and two of them are not the same problem: a tick for a
     * path that is there, a cross for one the kernel says is not - which is the only one a run is refused
     * over - and a question mark for one this process is not allowed to look at, which the exploit uses from
     * its own context and a run does not depend on.
     */
    private fun renderNeeds() {
        val reading = needs
        needsRows.removeAllViews()
        if (reading == null) {
            needsHeaderView.text = "Files  ·  reading…"
            needsRows.visibility = View.GONE
            return
        }
        val blocking = reading.blocking.size
        val needed = reading.items.count { it.need.required }
        val notSeen = reading.notSeenReadings().size
        val tail = if (notSeen == 0) "" else "  ·  $notSeen of the phone's not visible here"
        needsHeaderView.text = when {
            needsExpanded -> "Files  ·  ${needed - blocking} of $needed needed present$tail"
            blocking > 0 -> "Files  ·  $blocking needed missing$tail"
            notSeen > 0 -> "Files  ·  all $needed needed present$tail"
            else -> "Files  ·  all $needed needed present"
        }
        needsRows.visibility = if (needsExpanded || blocking > 0) View.VISIBLE else View.GONE
        reading.items.forEach { checked ->
            val (mark, colour) = when (checked.presence) {
                StageNeeds.Presence.Present -> "\u2713  " to palette.onSurfaceVariant
                StageNeeds.Presence.Absent -> "\u2717  " to palette.error
                StageNeeds.Presence.Unreadable -> "?  " to palette.warning
            }
            needsRows.addView(
                text(mark + checked.need.path, 11f, colour, Typeface.MONOSPACE, 6),
            )
            if (checked.presence == StageNeeds.Presence.Absent) {
                needsRows.addView(
                    text(
                        "      ${checked.need.why}  (${checked.need.group.label})",
                        11f,
                        palette.onSurfaceVariant,
                        Typeface.DEFAULT,
                        2,
                    ),
                )
            }
            if (!checked.need.required && checked.presence != StageNeeds.Presence.Present) {
                needsRows.addView(
                    text(
                        "      not visible from this process${checked.because?.let { " ($it)" }.orEmpty()}: " +
                            "the exploit reads, patches or execs it from its own context, so this reading " +
                            "cannot stop a run",
                        11f,
                        palette.onSurfaceVariant,
                        Typeface.DEFAULT,
                        2,
                    ),
                )
            }
        }
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            runCatching { block() }.onFailure { append("[x] $it") }
        }.start()
    }

    /**
     * Adds a line, and follows the run only when the log was already at its end.
     *
     * Following unconditionally is the other half of what made this pane look broken: the exploit prints for
     * minutes, and a person who scrolled back to read an earlier line was dragged to the bottom by the next
     * one. At the end, follow; anywhere else, leave the reading where it was put.
     */
    private fun append(text: String) {
        val line = if (text.endsWith("\n")) text else "$text\n"
        runOnUiThread {
            val follow = !logScroll.canScrollVertically(1)
            logView.append(line)
            if (follow) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("RMG-NEXT helper log", logView.text))
    }

    // ------------------------------------------------------------------------------- the view itself

    private fun buildScreen(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(20), dip(20), dip(28))
            setBackgroundColor(palette.surface)
        }
        column.addView(text("RMG-NEXT Helper", 32f, palette.onSurface, Typeface.DEFAULT))

        stateView = pill("Ready")
        summaryView = text("", 13f, palette.onSurfaceVariant, Typeface.DEFAULT, 10)
        needsHeaderView = text("", 12f, palette.accent, Typeface.DEFAULT_BOLD, 16).apply {
            setPadding(dip(2), dip(6), dip(2), dip(6))
            isClickable = true
            setOnClickListener {
                needsExpanded = !needsExpanded
                renderNeeds()
            }
        }
        needsRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runButton = answer("Run", loud = true)
        recheckButton = answer("Recheck", loud = false)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dip(10) }
        }
        column.addView(
            card(
                sectionLabel("This boot"),
                stateView,
                summaryView,
                needsHeaderView,
                needsRows,
                runButton,
                progress,
                recheckButton,
            ),
        )

        openManagerButton = answer("Open Manager", loud = false).apply {
            setOnClickListener { openManager() }
        }
        openAppButton = answer("Open RMG-NEXT", loud = false).apply {
            setOnClickListener { openTheApp() }
        }
        softRebootButton = answer("Soft reboot", loud = false).apply {
            setOnClickListener { softReboot() }
        }
        column.addView(card(sectionLabel("Next"), openManagerButton, openAppButton, softRebootButton))

        column.addView(logCard())

        // Padded by the bars' own height rather than laid out under them, and asked for on the root so
        // that one listener answers for the whole screen. The system applies this without being asked on
        // the versions this APK targets, where a screen that ignored it would put its title under the
        // clock - and the window is put into that mode deliberately below, rather than left to the
        // version, so the two halves of this are one decision instead of two behaviours.
        return ScrollView(this).apply {
            addView(column)
            setBackgroundColor(palette.surface)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun logCard(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The label takes the row and the action stays the size of its word: a weighted view beside one
        // that asked for `MATCH_PARENT` gets nothing, which is how "Log" came to have no width of its own
        // and this row came to be one wide Copy across the whole card.
        header.addView(sectionLabel("Log"), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(
            text("Copy", 14f, palette.accent, Typeface.DEFAULT_BOLD).apply {
                setPadding(dip(12), dip(6), 0, dip(6))
                isClickable = true
                setOnClickListener { copyLog() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        // Not selectable, deliberately: a selectable `TextView` takes the drag for a text selection, so the
        // pane it sits in cannot be scrolled with a finger on it - which is exactly what this screen looked
        // like. The text is still recoverable whole, from the Copy action above it.
        logView = text("", 11f, palette.onSurfaceVariant, Typeface.MONOSPACE).apply {
            setLineSpacing(dip(3).toFloat(), 1f)
        }
        logScroll = LogScrollView(this).apply {
            addView(logView)
            background = cardBackground(palette.panel)
            setPadding(dip(12), dip(10), dip(12), dip(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, dip(LOG_HEIGHT_DP)).apply {
                topMargin = dip(10)
            }
        }
        return card(header, logScroll)
    }

    /**
     * The manager this screen's action opens, or null when there is nothing it could open.
     *
     * The flavour the app named wins, and it wins **even when it is not installed**: the reading under it
     * already says so, and a button that quietly opened somebody else's manager because the right one was
     * missing would be this screen making up the answer it was sent. When the app said nothing, a single
     * installed manager is unambiguous and the button opens that one; two of them are a question this
     * screen cannot answer, and it asks nothing rather than guessing.
     */
    private fun managerTarget(): ManagerFlavor? =
        payloadFlavor ?: KsudStage.installedFlavors(this).singleOrNull()

    /**
     * The button, from the reading above it: its name is the manager it would open, and it is live only
     * when that app is really there.
     */
    private fun refreshManagerAction() {
        val target = managerTarget()
        openManagerButton.visibility = if (target == null) View.GONE else View.VISIBLE
        if (target == null) return
        val installed = KsudStage.isInstalled(this, target)
        openManagerButton.text = "Open ${target.label} manager"
        openManagerButton.isEnabled = installed
    }

    /**
     * Opens the KernelSU manager, which is the app that can actually act on what this run achieves.
     *
     * This screen can say what the phone is and start the exploit; everything a person wants after that -
     * granting an app root, mounting a module, reading why a load failed - lives in the manager, and until
     * now the only way to it from here was to know which app to look for and find it in the launcher.
     *
     * A manager that is not installed is reported here rather than passed to the platform, because this is
     * also the state that says what the run in front of it will not be able to do.
     */
    private fun openManager() {
        val target = managerTarget()
        if (target == null) {
            summaryView.text = "No manager to open: the app did not say which version this run uses, " +
    "and no single manager is installed."
            summaryView.setTextColor(palette.onSurfaceVariant)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(target.packageName)
        if (launch == null) {
            summaryView.text = "no ${target.label} manager at ${target.packageName}"
            summaryView.setTextColor(palette.error)
            append("[!] ${target.packageName} is not installed")
            return
        }
        append("[*] opening the ${target.label} manager (${target.packageName})")
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The app that owns the boot settings, under the name the two APKs share. */
    private fun openTheApp() {
        val launch = packageManager.getLaunchIntentForPackage(MAIN_PACKAGE)
        if (launch == null) {
            summaryView.text = "RMG-NEXT is not installed under $MAIN_PACKAGE"
            summaryView.setTextColor(palette.error)
            return
        }
        append("[*] opening $MAIN_PACKAGE")
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A card: the platform's lifted panel, rounded, with the screen's own inset inside it. */
    private fun card(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(palette.card)
        setPadding(dip(16), dip(14), dip(16), dip(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dip(14) }
        children.forEach { addView(it) }
    }

    private fun sectionLabel(body: String): TextView =
        text(body, 14f, palette.onSurface, Typeface.DEFAULT_BOLD)

    /** The state, as one word on a tinted pill rather than as a line of prose. */
    private fun pill(initial: String): TextView =
        text(initial, 14f, palette.onAccent(palette.success), Typeface.DEFAULT_BOLD).apply {
            background = palette.pillBackground(palette.success)
            setPadding(dip(12), dip(5), dip(12), dip(5))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dip(12) }
        }

    private fun answer(label: String, loud: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (loud) palette.onAccent(palette.accent) else palette.onSurfaceVariant)
        background = palette.answerBackground(loud)
        stateListAnimator = null
        minHeight = 0
        minWidth = 0
        gravity = Gravity.CENTER
        setPadding(dip(12), dip(12), dip(12), dip(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, dip(ANSWER_HEIGHT_DP)).apply {
            topMargin = dip(10)
        }
    }

    private fun text(
        body: String,
        sizeSp: Float,
        color: Int,
        face: Typeface,
        topMargin: Int = 0,
    ): TextView = TextView(this).apply {
        text = body
        textSize = sizeSp
        setTextColor(color)
        typeface = face
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { this.topMargin = dip(topMargin) }
    }

    private fun cardBackground(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dip(CARD_RADIUS_DP).toFloat()
    }

    /**
     * The window the screen is drawn in: edge to edge, padded by the bars, with icons that read on it.
     *
     * The bars' *colours* are deliberately not set. They are deprecated for the versions this APK targets
     * and ignored on the newest of them, where the bars are transparent whatever an app asks - and a
     * screen whose answer to that is a colour it sets on some devices and not others is a screen with two
     * appearances. What it asks for here is the part that still exists: that the content owns the whole
     * window (see [buildScreen]'s inset listener) and that the bars' own icons are dark on a light screen.
     */
    private fun dressWindow() {
        window.setDecorFitsSystemWindows(false)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.isLight) light else 0, light)
    }

    /** Pixels, for the one screen this APK builds in code. */
    private fun dip(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "RMGStage2"

        /**
         * The app asking for the run on its way in.
         *
         * A boolean extra rather than an action or a different component, because the app starts this
         * activity exactly the way a person does - an explicit `am start` - and the run being wanted
         * before anyone is looking is the only thing that differs between the two.
         */
        const val EXTRA_AUTORUN = "rmg.autorun"

        /**
         * What the app's reroot-at-boot setting is, so the log can say who started this and on what terms.
         *
         * Optional, and absent means absent: this screen has no copy of that setting, and a missing extra
         * is the honest answer for a helper the app did not start.
         */
        const val EXTRA_REROOT_AT_BOOT = "rmg.rerootAtBoot"

        /**
         * Which KernelSU this run loads, as the app names it - held to
         * `DfrInstall.STAGE_TWO_FLAVOR_EXTRA` by the test that owns every shared name.
         *
         * Optional like the one above, and absent means absent: this screen has no way to work out which
         * flavour the payload loads, so without it the manager action falls back to a manager that is
         * installed and unambiguous.
         */
        const val EXTRA_FLAVOR = "rmg.flavor"

        /**
         * The colours the app's window is drawn with, as it draws them - held to `DfrInstall.STAGE_TWO_TINT_EXTRA`
         * by the test that owns every shared name.
         *
         * This screen used to resolve its whole palette from its own theme, which is the platform's
         * `DeviceDefault`: the OEM's colours, not the Material ones the app draws with, and an accent that
         * is a different colour outright. So the app sends the values it is drawing with and the screen is
         * painted in those, which is the only way two APKs that share no code can be one product's look.
         *
         * Optional, and absent means absent: a launch with no app behind it - the boot, or this screen
         * opened from the launcher - has no window to copy, and the palette falls back to the theme it is
         * drawn in, exactly as it did before.
         */
        const val EXTRA_TINT = "rmg.tint"

        /**
         * What this screen sets on the app when a run it started has loaded KernelSU.
         *
         * The app's half is `DfrInstall.STAGE_TWO_AFTER_ROOT_EXTRA`, and the two are held together by
         * `StageTwoIdentityTest` - which reads both sources - because they are one name in two APKs that
         * share no code. See [askTheAppToRestart] for why the app is the side that acts on it.
         */
        const val EXTRA_AFTER_ROOT = "rmg.afterRoot"

        /**
         * The app's own direct soft-restart request, which is the action its launcher shortcut sends.
         *
         * The other direction from the extra above: that one tells the app a load has just happened, this
         * one asks for the restart at a time of the person's choosing - after a run, or after a boot this
         * screen was opened on with KernelSU already loaded. It is the shortcut's action rather than a new
         * one so that what answers it is the app's existing path, with its probe, its per-boot lock and its
         * own screen when the phone cannot take a restart. Held to `ACTION_SOFT_RESTART` in the app's
         * `RebootTargets.kt` by `StageTwoIdentityTest`, since two APKs with no shared code cannot share a
         * literal any other way.
         */
        const val SOFT_RESTART_ACTION = "dev.busung.s25uroot.action.SOFT_RESTART"

        /**
         * The app, by its application id.
         *
         * A constant here because these two APKs share no code, and the actions above have to name the
         * app that answers them - so the name is held to the app's own build file by
         * `StageTwoIdentityTest`, which reads both.
         */
        const val MAIN_PACKAGE = "dev.rushiranpise.rmgnext"

        /** Enough for the hop and the first report; the run itself is not time-limited after that. */
        const val CONTROLLER_TIMEOUT_MS = 30_000L

        /** StageReceiver's run-all code, repeated here because the two are in different processes. */
        const val CODE_RUN_ALL = 5
    }
}

/**
 * The screen's colours: the window the app is drawing with, and the running theme behind it.
 *
 * The app's values win where it sent any, because the two are not the same palette and were never going
 * to be: the theme here is the platform's `DeviceDefault`, whose colours are the OEM's, while the app
 * draws a Material scheme built either from Material You's tonal palette or from a seed it chose. Those
 * include `colorAccent`, which is *not* the app's `primary` - an earlier version of this comment claimed
 * the two were one wallpaper-derived palette read twice, and the screens disagreeing is what that
 * assumption cost.
 *
 * So what is read from the theme is the fallback, and it is a real one rather than a formality: a launch
 * with no app behind it - the boot, or this screen opened from the launcher - has no window to copy, and
 * the theme is then the only honest answer. Both routes are then one expression per colour, which is
 * what keeps them from drifting apart.
 *
 * The values themselves are still not written down here: [tint] is what the app sent, so there is no
 * second palette in this file to keep in step with the app's - which is the one thing a helper that must
 * look like the app cannot afford.
 */
private fun parseTint(raw: String?): Map<String, Int> = raw.orEmpty()
    .split(',')
    .mapNotNull { pair ->
        val at = pair.indexOf('=')
        val value = if (at <= 0) null else pair.substring(at + 1).trim().toLongOrNull(16)
        // A pair that is not `role=hex` is dropped rather than defaulted: a role this screen does not know
        // is one the app added and this build never grew, and a value it cannot read is not a colour.
        if (value == null) null else pair.substring(0, at).trim() to value.toInt()
    }
    .toMap()
private class Palette(private val activity: Activity, private val tint: Map<String, Int>) {

    /**
     * Whether this window is a dark one.
     *
     * The app's answer when it sent one, because the app's light/dark choice is its own setting rather
     * than the phone's: a screen painted in a dark scheme on a phone set to light is the mismatch this
     * whole arrangement exists to remove.
     */
    private val dark: Boolean = tint["dark"]?.let { it != 0 }
        ?: ((activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

    /** One of the app's own colours, or null when it did not send this one. */
    private fun fromTheApp(role: String): Int? = tint[role]

    private fun color(attribute: Int, fallback: Long): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attribute, value, true)) return fallback.toInt()
        if (value.resourceId != 0) {
            return runCatching { activity.getColor(value.resourceId) }.getOrDefault(fallback.toInt())
        }
        return value.data
    }

    private fun stateList(attribute: Int, fallback: Long): ColorStateList {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attribute, value, true)) {
            return ColorStateList.valueOf(fallback.toInt())
        }
        if (value.resourceId == 0) return ColorStateList.valueOf(value.data)
        return runCatching { activity.getColorStateList(value.resourceId) }
            .getOrElse { ColorStateList.valueOf(fallback.toInt()) }
    }

    /**
     * The window behind everything.
     *
     * The app's own surface when it sent one, and otherwise the platform's - which is the case for a
     * launch with no screen behind it. Preferring the app's is the whole point: the two are different
     * palettes, not two spellings of one.
     */
    val surface: Int = fromTheApp("surface")
        ?: color(android.R.attr.colorBackground, if (dark) 0xFF101014 else 0xFFF7F7FA)

    /** A card on that window: the app's own card fill, or the platform's lifted panel. */
    val card: Int = fromTheApp("card")
        ?: color(android.R.attr.colorBackgroundFloating, if (dark) 0xFF1C1C22 else 0xFFFFFFFF)

    /** The log's pane: one step off the card it sits on, so the monospace block reads as a well. */
    val panel: Int = fromTheApp("panel") ?: if (dark) 0xFF26262E.toInt() else 0xFFEDEDF2.toInt()

    /** The text colours, which have to answer for the window they are drawn on - so the app's, when sent. */
    val onSurface: Int = fromTheApp("onSurface")
        ?: stateList(android.R.attr.textColorPrimary, if (dark) 0xFFE6E1E5 else 0xFF1B1B1F).defaultColor

    val onSurfaceVariant: Int = fromTheApp("onSurfaceVariant")
        ?: stateList(android.R.attr.textColorSecondary, if (dark) 0xFFC4C7C5 else 0xFF44474A).defaultColor

    /**
     * The accent, which the platform's `colorAccent` is not.
     *
     * The app's accent is either Material You's tonal palette or a scheme generated from a chosen seed;
     * the platform attribute is the OEM's accent, which is neither. So this is the colour that disagreed
     * most visibly, and the one whose fallback matters least: with the app behind the launch, it is always
     * the app's.
     */
    val accent: Int = fromTheApp("accent")
        ?: color(android.R.attr.colorAccent, if (dark) 0xFFA8C7FA else 0xFF415F91)

    /** Whether this window is a light one, which decides the bars' icons and the pills' strength. */
    val isLight: Boolean
        get() = luminance(surface) > LIGHT_SURFACE_LUMINANCE

    val success: Int = if (dark) 0xFF6BCB77.toInt() else 0xFF2E7D32.toInt()
    val warning: Int = if (dark) 0xFFE8B339.toInt() else 0xFF9A6400.toInt()
    val error: Int = if (dark) 0xFFF2837D.toInt() else 0xFFB3261E.toInt()

    /** Black or white, whichever the fill can carry, so one accent does not need a colour chosen by hand. */
    fun onAccent(fill: Int): Int = if (luminance(fill) > ON_FILL_LUMINANCE) Color.BLACK else Color.WHITE

    /** A pill: its tint at a fraction of its strength, so the word on it stays the readable part. */
    fun pillBackground(tint: Int) = GradientDrawable().apply {
        setColor(withAlpha(tint, if (isLight) 0.14f else 0.22f))
        cornerRadius = activity.resources.displayMetrics.density * PILL_RADIUS_DP
    }

    /**
     * An answer's fill: the accent for the loud one, the panel for the quiet ones.
     *
     * Built rather than themed, because these two are the app's own two fills - the primary one and a
     * flat panel - and the platform's button colours are a third and a fourth.
     */
    fun answerBackground(loud: Boolean): RippleDrawable {
        val fill = if (loud) accent else panel
        val content = if (loud) onAccent(accent) else onSurfaceVariant
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = activity.resources.displayMetrics.density * ANSWER_RADIUS_DP
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(content, 0.16f)), shape, null)
    }

    private fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
        (255 * fraction).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun luminance(color: Int): Double =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

    private companion object {
        /** Above this the window is a light one, and the bars' icons have to go dark. */
        const val LIGHT_SURFACE_LUMINANCE = 0.5

        /** Above this a fill needs dark content on it. */
        const val ON_FILL_LUMINANCE = 0.45

        const val PILL_RADIUS_DP = 999f
        const val ANSWER_RADIUS_DP = 14f
    }
}

/**
 * The log's own pane, which keeps the page around it from taking the drag it was given.
 *
 * This screen scrolls and so does the log inside it, and a plain `ScrollView` in that position loses every
 * gesture to the page: a parent intercepts a drag once it passes its own slop, whatever the child was about
 * to do with it, and the pane that came out of that drew a scrollbar and could not be moved. Disallowing the
 * parent for the length of the gesture is the whole of the fix; the framework resets the flag on the next
 * `ACTION_DOWN`, so nothing has to be handed back.
 *
 * A nested-scrolling `NestedScrollView` would settle the same argument by protocol, and the page here is a
 * plain `ScrollView` - so the protocol is not what is answering on the versions this APK targets.
 */
private class LogScrollView(context: Context) : ScrollView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onTouchEvent(event)
    }
}

/**
 * How tall the log's pane is.
 *
 * Fixed, exactly as the app's own log panel is, and for the same reason: the log is the one field here
 * whose length nothing bounds - an injector run prints its whole transform - and a pane that grew with
 * it would push the log's own header off the top of the screen.
 */
private const val LOG_HEIGHT_DP = 240

/** An answer, at the app's answer height. */
private const val ANSWER_HEIGHT_DP = 46

/** The app's cards are this round; a second corner here would be a second shape in one product. */
private const val CARD_RADIUS_DP = 18

// The two layout widths, named once: this screen is built in code and says them on nearly every line.
private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
