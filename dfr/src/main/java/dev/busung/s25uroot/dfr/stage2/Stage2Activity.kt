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
 * Stage two's screen: what this process is, what the phone looks like from here, one action, and the log.
 *
 * The sequence is the one it always was - the run is one press because the exploit is one sequence, and
 * every step after the first is only reachable from the one before - and what changed is how much of the
 * phone the screen answers for. Everything the app knows about this flow it reads through a root shell,
 * and there is no root shell in the state this screen exists for: a phone that has rebooted with the
 * exploit's hooks gone. From inside system, with no shell at all, these are the facts worth having, so
 * these are the ones on the screen:
 *
 * - **Who this process is**, because a stage two that installed without being system-uid looks exactly
 *   like a working one from every other process on the device.
 * - **Whether the hooks are already in the kernel**, because that is the state a second run cannot fix
 *   and a reboot can: a run started anyway is a run that collides with a half-armed kernel.
 * - **Which daemon this boot will exec**, and whether it is there at all - the app stages it while it has
 *   root, and a boot with no root is exactly the boot whose staging may be missing.
 * - **Which manager is installed**, which is what tells a wrong-flavour daemon from a right one when the
 *   run's own last step is the one that fails.
 *
 * ## Started by the app rather than by a person
 *
 * [EXTRA_AUTORUN] makes the run start by itself once the daemon is staged, which is what lets the app
 * bring this screen up from a boot with no root. It is an *extra* rather than this screen's own
 * behaviour because the decision belongs to the app: that is the half holding the setting, the boot
 * receipt and the one-attempt-per-boot rule, and the half allowed to act on them. Opened by hand, this
 * screen still waits for the press.
 *
 * ## The log is the diagnosis
 *
 * A run that stops says which step refused, in the lines above it - and those lines are produced for
 * minutes before there is a verdict. So the log is a panel with a scroll of its own rather than the tail
 * of the page: a page that scrolled as the exploit talked would move the action out from under the thumb
 * that pressed it.
 *
 * ## Written in code, not in resources
 *
 * This APK has one screen, and a layout resource for it would be a second place for the same view
 * hierarchy to be wrong - one that the compiler cannot check against the fields it fills. The strings
 * are here for the same reason: this module shares no code with the app, so its resources are its own,
 * and a strings file with twelve entries in it would be a second file to keep in step for no reader.
 */
class Stage2Activity : Activity() {

    private lateinit var palette: Palette

    private lateinit var stateView: TextView
    private lateinit var identityView: TextView
    private lateinit var hookView: TextView
    private lateinit var daemonView: TextView
    private lateinit var managerView: TextView
    private lateinit var openManagerButton: Button
    private lateinit var startedView: TextView
    private lateinit var bootView: TextView
    private lateinit var outcomeView: TextView
    private lateinit var runButton: Button
    private lateinit var rereadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var controller: IBinder? = null
    private val controllerLock = Object()
    private val running = AtomicBoolean(false)
    private var controllerReceiver: BroadcastReceiver? = null

    /** Whether the app asked for the run on its way in, so the boot section can say who started this. */
    private var autorun = false

    /** What the app's own reroot-at-boot setting was, or null when the app did not say. */
    private var rerootAtBoot: Boolean? = null

    /**
     * Which flavour this run loads, as the app named it, or null when the app did not say.
     *
     * The one fact about the payload this process cannot work out for itself: three managers can be
     * installed at once, they belong to three different projects, and which of them drives the daemon this
     * boot will exec is a fact about the payload the app resolved. So it is told - see
     * `DfrInstall.STAGE_TWO_FLAVOR_EXTRA` - and an id this APK does not know reads the same as no id at all.
     */
    private var payloadFlavor: ManagerFlavor? = null

    /** The window the app was drawing with, as `role=hex` pairs, or null when it did not say. */
    private var tint: Map<String, Int> = emptyMap()

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
        rereadButton.setOnClickListener { reread() }

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
            runOnUiThread { refreshReadouts() }
            if (autorun) runOnUiThread { startRun() }
        }
    }

    override fun onDestroy() {
        controllerReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /** Reads the phone again and stages again, which is the one action here that changes nothing. */
    private fun reread() {
        if (running.get()) {
            append("[!] A run is already in progress, so the current readings were left unchanged.")
            return
        }
        runInBackground {
            append(KsudStage.stage(this))
            runOnUiThread { refreshReadouts() }
        }
    }

    /**
     * The exploit, from the refusal check to the verdict.
     *
     * The parts that answer a press are here and the sequence itself is [exploit], because the app can
     * also ask for the run on its way in - and both entries have to refuse the same states in the same
     * words.
     */
    private fun startRun() {
        if (armed()) {
            append(
                "[x] The exploit is already active ($ARMED_MARKER exists), so it cannot be run again.\n" +
                    "    Only a full device reboot will clear it. A soft reboot will not.",
            )
            refreshReadouts()
            return
        }
        if (!running.compareAndSet(false, true)) {
            append("[!] A run is already in progress")
            return
        }
        runButton.isEnabled = false
        rereadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        outcomeView.text = "Running the exploit. The log below shows what is happening."
        outcomeView.setTextColor(palette.onSurfaceVariant)
        refreshReadouts()
        runInBackground {
            var result = -1
            try {
                result = exploit()
            } finally {
                running.set(false)
                val success = result == 0
                runOnUiThread {
                    runButton.isEnabled = true
                    rereadButton.isEnabled = true
                    progress.visibility = View.GONE
                    outcomeView.text = if (success) {
                        "Root access obtained. Check the KernelSU Manager."
                    } else {
                        "The process stopped at $result. Check the log above for details."
                    }
                    outcomeView.setTextColor(if (success) palette.success else palette.error)
                    refreshReadouts()
                }
                Log.i(TAG, "Run finished with $result")
                // A run a person asked for, whose last step is the one the phone cannot finish by itself:
                // what was just loaded is inert until the Android userspace is built again, and the restart
                // that does it is KernelSU's own soft reboot. This process cannot ask for it - that needs a
                // root shell - so the app, which has one, is told instead. Not for a boot's run: that one was
                // started by the app's own gate, which watches this boot's kernel and does the restart there.
                if (success && !autorun) runOnUiThread { askTheAppToRestart() }
            }
        }
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

    /**
     * Whether the hooks are in this boot's kernel, as far as this process can tell.
     *
     * A failed reading answers no, which is the direction that costs a run rather than a collision: the
     * exploit's own mutex is the thing that actually decides, and this only exists to say so before a
     * press instead of after it.
     */
    private fun armed(): Boolean = runCatching { File(ARMED_MARKER).exists() }.getOrDefault(false)

    /**
     * Who this process is: the pid, the uid, and the SELinux context.
     *
     * Three facts and not one, because the failure this screen has to make obvious is a stage two that
     * *installed* but is not system-uid - which is what a certificate that went into the wrong shared
     * user looks like, and which no single field shows.
     */
    private fun identity(): String {
        val uid = Process.myUid()
        val context = runCatching {
            File("/proc/self/attr/current").readText().trim().trim('\u0000')
        }.getOrElse { "unreadable" }
        return "pid=${Process.myPid()}  uid=$uid (system uid: ${uid == Process.SYSTEM_UID})\n" +
            "process=${applicationInfo.processName}\ncontext=$context"
    }

    /** The daemon the exploit will exec, as it looks from here. */
    private fun daemon(): String {
        val staged = File(KsudStage.DEST)
        if (!staged.isFile) {
            return "Nothing at ${KsudStage.DEST}\n" +
                "The app stages it while it has root, so a boot with no root reaches a run from here"
        }
        val size = "%,d bytes".format(staged.length())
        // Running it is the only way to know which build it is, and this process may be refused that -
        // a refused exec is a fact about this reading and not about the daemon, so it is said as one.
        val version = runCatching {
            val process = ProcessBuilder(KsudStage.DEST, "-V").redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLine().orEmpty()
            process.waitFor()
            line.trim()
        }.getOrNull().orEmpty()
        return "${KsudStage.DEST}  $size\n" +
            if (version.isNotEmpty()) {
                version
            } else {
                "Could not read the version from this process. The daemon is running with root access from the exploit."
            }
    }

    /**
     * Which manager this run's kernel will be driven by, and what else is on the phone.
     *
     * Two readings in one field, because the second is what makes the first diagnosable. The flavour the
     * app named is the answer - it comes from the payload this run loads, which is the only side that knows
     * - and whether its manager is installed is the thing a person needs before they start: a run that
     * succeeds with no manager leaves root nothing on the phone can use. The list under it is what a
     * wrong-daemon failure is measured against, and it is also the fallback when the app did not say.
     */
    private fun managers(): String {
        val installed = KsudStage.installedFlavors(this)
        val told = payloadFlavor
        if (told == null) {
            val others = if (installed.isEmpty()) {
                "none of the three flavours is installed"
            } else {
                installed.joinToString("\n") { "${it.label}  ${it.packageName}" }
            }
            return "The app did not report which version this run uses.\n$others"
        }
        val here = KsudStage.isInstalled(this, told)
        return buildString {
            append(told.label).append("  ").append(told.packageName).append('\n')
            append(if (here) "installed - this is the manager for the daemon above" else "not installed")
            val others = installed.filter { it.id != told.id }
            if (others.isNotEmpty()) {
                append("\n")
                append(others.joinToString("\n") { "${it.label}  ${it.packageName} is also installed" })
            }
            if (!here) {
                append("\nThe KernelSU version used by this run has no manager to control it")
            }
        }
    }

    private fun refreshReadouts() {
        val isArmed = armed()
        val busy = running.get()
        val tint = when {
            isArmed -> palette.warning
            busy -> palette.onSurfaceVariant
            else -> palette.success
        }
        stateView.text = when {
            busy -> "Running"
            isArmed -> "Active"
            else -> "Ready"
        }
        stateView.setTextColor(palette.onAccent(tint))
        stateView.background = palette.pillBackground(tint)
        identityView.text = identity()
        hookView.text = if (isArmed) {
            "$ARMED_MARKER exists - the hooks are active in this boot's kernel.\n" +
        "A second run is not allowed. Only a full device reboot will clear them."
        } else {
            "$ARMED_MARKER is not present - the hooks have not been enabled for this boot."
        }
        hookView.setTextColor(if (isArmed) palette.warning else palette.onSurface)
        daemonView.text = daemon()
        managerView.text = managers()
        refreshManagerAction()
        startedView.text = if (autorun) {
            "Started automatically when the device booted"
        } else {
            "Started by you from the launcher or app"
        }

        bootView.text = when (rerootAtBoot) {
            true -> "On - the app will get root again after a boot without root"
            false -> "Off - if the device boots without root, you must start it yourself"
            // The app decides this setting. This screen only displays it.
            null -> "Not available here - the app controls this setting"
        }
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            runCatching { block() }.onFailure { append("[x] $it") }
        }.start()
    }

    private fun append(text: String) {
        val line = if (text.endsWith("\n")) text else "$text\n"
        runOnUiThread {
            logView.append(line)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
        column.addView(
            text("uid 1000  ·  system", 12f, palette.onSurfaceVariant, Typeface.DEFAULT, 4),
        )

        stateView = pill("Ready")
        identityView = value()
        hookView = value()
        daemonView = value()
        managerView = value()
        openManagerButton = answer("Open Manager", loud = false).apply {
            setOnClickListener { openManager() }
        }
        column.addView(
            card(
                sectionLabel("Status"),
                stateView,
                field("Process", identityView),
                field("Hooked", hookView),
                field("Daemon", daemonView),
                field("KernelSU Manager", managerView),
                // The action for that reading, under it, which is the shape the app's own readings cards
                // use for the same reason: this is the one thing on this screen that another app owns, and
                // being told which manager this run loads is only half of what to do with it.
                openManagerButton,
            ),
        )

        outcomeView = text(
            "Ready to Root?",
            12f,
            palette.onSurfaceVariant,
            Typeface.DEFAULT,
            8,
        )
        runButton = answer("Run", loud = true)
        rereadButton = answer("Refresh", loud = false)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dip(10) }
        }
        column.addView(card(sectionLabel("Run"), outcomeView, runButton, progress, rereadButton))

        column.addView(logCard())

        startedView = value()
        bootView = value()
        column.addView(
            card(
                sectionLabel("Reason"),
                field("Started", startedView),
                field("Auto Root", bootView),
                openAppButton(),
            ),
        )
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
        logView = text("", 11f, palette.onSurfaceVariant, Typeface.MONOSPACE).apply {
            setTextIsSelectable(true)
            setLineSpacing(dip(3).toFloat(), 1f)
        }
        logScroll = ScrollView(this).apply {
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
     * The manager this screen's one action opens, or null when there is nothing it could open.
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
            outcomeView.text = "No manager to open: the app did not say which version this run uses, " +
    "and no single manager is installed."
            outcomeView.setTextColor(palette.onSurfaceVariant)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(target.packageName)
        if (launch == null) {
            outcomeView.text = "no ${target.label} manager at ${target.packageName}"
            outcomeView.setTextColor(palette.error)
            append("[!] ${target.packageName} is not installed")
            return
        }
        append("[*] opening the ${target.label} manager (${target.packageName})")
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openAppButton(): Button {
        val view = answer("Open RMG-NEXT", loud = false)
        view.setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(MAIN_PACKAGE)
            if (launch == null) {
                outcomeView.text = "RMG-NEXT is not installed under $MAIN_PACKAGE"
                outcomeView.setTextColor(palette.error)
                return@setOnClickListener
            }
            append("[*] Opening $MAIN_PACKAGE to change the boot settings")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return view
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

    /** One label above one value, which is the shape this app's own readings cards use. */
    private fun field(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dip(12) }
        addView(text(label, 12f, palette.onSurfaceVariant, Typeface.DEFAULT_BOLD))
        addView(value, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dip(2) })
    }

    private fun value(): TextView = text("", 14f, palette.onSurface, Typeface.DEFAULT)

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
         * What the app's reroot-at-boot setting is, so the boot row can show it.
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
         * flavour the payload loads, so without it the manager row says so and the action falls back to a
         * manager that is installed and unambiguous.
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
         * The app, by its application id.
         *
         * A constant here because these two APKs share no code, and the boot row's action has to name the
         * app that owns the setting - so the name is held to the app's own build file by
         * `StageTwoIdentityTest`, which reads both.
         */
        const val MAIN_PACKAGE = "dev.rushiranpise.rmgnext"

        /** The hooks the module installs; present means a run has already been armed this boot. */
        const val ARMED_MARKER = "/dev/df"

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
