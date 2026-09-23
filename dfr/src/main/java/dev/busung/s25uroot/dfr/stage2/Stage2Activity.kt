package dev.busung.s25uroot.dfr.stage2

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage two's screen, and where the two things it can do are separated on purpose.
 *
 * The button at the top runs the exploit. It is one button because the exploit is one sequence - patch
 * the vendor file, make `modprobe` usable, have init run it, load the module - and every step after the
 * first is only reachable from the one before. What it cannot do is run twice: loading the module arms
 * hooks that only a hard reboot clears, so a second press is refused here, in the words that say why,
 * rather than by a kernel that would otherwise be left half-patched.
 *
 * Everything under it is a readout, and it is the reason this screen exists at all: the identity block
 * is the proof that this process is what it claims to be (`uid=1000`, running in `system`), and the log
 * is the trace of where in that sequence a run stopped.
 *
 * The screen is written in code rather than in resources because this APK has one screen, and a layout
 * resource for it would be a second place for the same view hierarchy to be wrong.
 */
class Stage2Activity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var runButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var controller: IBinder? = null
    private val controllerLock = Object()
    private val running = AtomicBoolean(false)
    private var controllerReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        statusView.text = identity()
        refreshStatus()
        runButton.setOnClickListener { run()
        }
        // The controller arrives as a binder on a broadcast, so the receiver has to be live before the
        // hop is asked for - and it is registered exported because the sender is network_stack, a
        // different process with a different uid.
        controllerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    controller = intent.extras?.getBinder(StageReceiver.CONTROLLER)
                    append("[+] controller received from network_stack")
                } catch (error: Throwable) {
                    append("[x] reading the controller binder failed: $error")
                } finally {
                    synchronized(controllerLock) { controllerLock.notifyAll() }
                }
            }
        }
        registerReceiver(controllerReceiver, IntentFilter(StageReceiver.EVIL_ACTION), RECEIVER_EXPORTED)
        runInBackground { append(KsudStage.stage(this)) }
    }

    override fun onDestroy() {
        controllerReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /** The exploit, from the refusal check to the verdict. */
    private fun run() {
        if (File(ARMED_MARKER).exists()) {
            append(
                "[x] the exploit is already armed ($ARMED_MARKER exists), so a second run is refused.\n" +
                    "    Only a hard reboot clears it - a soft reboot does not.",
            )
            return
        }
        if (!running.compareAndSet(false, true)) {
            append("[!] a run is already in progress")
            return
        }
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusView.text = "running the exploit..."
        runInBackground {
            var result = -1
            try {
                append(StageHop.hopToNetworkStack(this))
                val binder = awaitController(CONTROLLER_TIMEOUT_MS)
                if (binder == null) {
                    append(
                        "[x] no controller within ${CONTROLLER_TIMEOUT_MS / 1000}s: the hop did not land, " +
                            "or network_stack was too slow. The log above says which step refused.",
                    )
                    return@runInBackground
                }
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                // The exploit reports while it runs, over a binder of ours, because it produces output
                // for minutes before it produces a verdict - so the callback is created first and the
                // answer is what comes back on the reply parcel.
                val reporter = object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        runCatching { append(data.readString().orEmpty()) }
                            .onFailure { Log.e(TAG, "reporter failed", it) }
                        return true
                    }
                }
                request.writeStrongBinder(reporter)
                try {
                    if (binder.transact(CODE_RUN_ALL, request, reply, 0)) {
                        result = reply.readInt()
                        append("\nrun all -> $result")
                    } else {
                        append("[x] the controller refused the call")
                    }
                } catch (error: Throwable) {
                    append("[x] controller call failed: ${error.message}")
                } finally {
                    request.recycle()
                    reply.recycle()
                }
            } finally {
                running.set(false)
                val success = result == 0
                runOnUiThread {
                    runButton.isEnabled = true
                    progress.visibility = View.GONE
                    statusView.text = if (success) "root obtained" else "run failed"
                    statusView.setTextColor(Color.parseColor(if (success) "#1B8A2E" else "#C62828"))
                    refreshStatus()
                }
                Log.i(TAG, "run finished with $result")
            }
        }
    }

    /** Waits for the controller binder, reporting how long is left rather than going quiet. */
    private fun awaitController(timeoutMs: Long): IBinder? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(controllerLock) {
            var current = controller
            while (current == null) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                append("[*] waiting for the controller (${left / 1000}s left)")
                runCatching { controllerLock.wait(minOf(left, 5_000)) }
                    .onFailure { Thread.currentThread().interrupt() }
                current = controller
            }
            return current
        }
    }

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
        return "pid=${Process.myPid()} uid=$uid (system uid: ${uid == Process.SYSTEM_UID})\n" +
            "process=${applicationInfo.processName}\ncontext=$context"
    }

    private fun refreshStatus() {
        val armed = File(ARMED_MARKER).exists()
        statusView.setTextColor(Color.parseColor(if (armed) "#B26A00" else "#1B8A2E"))
        if (!running.get()) {
            statusView.text = identity() + "\n" + if (armed) {
                "hook armed - a rerun needs a hard reboot"
            } else {
                "ready"
            }
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

    /** Dips to pixels, for the one screen this APK builds in code. */
    private fun dip(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    /** One screen, built in code. */
    private fun buildScreen(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(16), dip(16), dip(16))
        }
        column.addView(
            TextView(this).apply {
                text = "Root My Galaxy - stage 2"
                textSize = 20f
            },
        )
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1B8A2E"))
            setPadding(0, dip(12), 0, dip(12))
        }
        column.addView(statusView)

        runButton = Button(this).apply { text = "Obtain root" }
        column.addView(runButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        column.addView(progress)

        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        column.addView(logScroll)
        return column
    }

    private companion object {
        const val TAG = "RMGStage2"

        /** The hooks the module installs; present means a run has already been armed this boot. */
        const val ARMED_MARKER = "/dev/df"

        /** Enough for the hop and the first report; the run itself is not time-limited after that. */
        const val CONTROLLER_TIMEOUT_MS = 30_000L

        /** StageReceiver's run-all code, repeated here because the two are in different processes. */
        const val CODE_RUN_ALL = 5
    }
}
