package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The run's notification: that its two buttons reach something, and that it does not outlive the run.
 *
 * Every one of these fails quietly in the same way - a tap on a button that does nothing, or a notification
 * for an install that is not happening - which is why they are assertions about the wiring rather than about
 * the screen: nothing renders an unreachable action as broken.
 */
class RunNotificationTest {

    @Test
    fun `the receiver behind both actions is declared`() {
        val manifest = source("AndroidManifest.xml")
        val declared = manifest.substringAfter("<receiver\n            android:name=\".RunActionReceiver\"", "")

        assertTrue("the run notification's actions have no receiver", declared.isNotEmpty())
        assertTrue(
            "the receiver is reachable from another app",
            declared.substringBefore(">").contains("android:exported=\"false\""),
        )
    }

    @Test
    fun `the actions the notification sends are the ones the receiver answers`() {
        val receiver = source("RunActionReceiver.kt")
        val notification = source("RunNotification.kt")
        val manifest = source("AndroidManifest.xml")

        assertTrue("Stop is not handled", receiver.contains("ACTION_STOP -> {"))
        assertTrue("Copy log is not handled", receiver.contains("ACTION_COPY_LOG -> {"))
        assertTrue(
            "the Stop button sends an action nothing answers",
            notification.contains("RunActionReceiver.ACTION_STOP"),
        )
        assertTrue(
            "the Copy log button sends an action nothing answers",
            notification.contains("RunActionReceiver.ACTION_COPY_LOG"),
        )
        // The other half of the wiring: a receiver class the manifest does not name is never constructed.
        assertTrue(manifest.contains(".RunActionReceiver"))
    }

    @Test
    fun `a stop is polled where a run actually waits`() {
        val viewModel = source("InstallViewModel.kt")

        // Once where it is written and once per long wait, of which the run has two: the boot settle and the
        // exploit's own poll loop. A check that only existed in one of them would leave the other unstoppable
        // from the notification, which is exactly the phase it is for.
        assertEquals(
            "the stop is not polled in the run's two long waits",
            3,
            Regex("stopIfAskedFromOutside\\(\\)").findAll(viewModel).count(),
        )
    }

    @Test
    fun `the notification is posted with the phase and taken down with the run`() {
        val viewModel = source("InstallViewModel.kt")

        // Named arguments rather than positional since the run's own id joined them, which is the thing
        // that decides which run a later tap on it opens.
        assertTrue(viewModel.contains("RunNotification.post("))
        assertTrue(viewModel.contains("progress = installProgress(phase, failureStage = null)"))
        assertTrue(viewModel.contains("runId = activeRunId"))
        assertTrue(viewModel.contains("RunNotification.clear(app)"))
        // The gate has a notification of its own: doubling it is how the shade stops being read.
        assertTrue(viewModel.contains("if (!runIsUnattended)"))
    }

    @Test
    fun `the running notification is the shape Android 16 promotes`() {
        val notification = source("RunNotification.kt")

        // Promotion is a request the platform answers, and these are what it is answered from: the style it
        // draws a bar into, the chip's word, the request itself, and the permission the manifest has to ask
        // for. Any one of them missing is a run that is never promoted, which looks exactly like a phone that
        // does not promote - the failure this test exists for.
        assertTrue(notification.contains("NotificationCompat.ProgressStyle()"))
        assertTrue(notification.contains("setShortCriticalText("))
        assertTrue(notification.contains("setRequestPromotedOngoing(true)"))
        assertTrue(
            "the manifest does not ask for the permission a promoted notification needs",
            source("AndroidManifest.xml").contains("android.permission.POST_PROMOTED_NOTIFICATIONS"),
        )
        // All three are Android 16 calls, so a build that made them unconditionally would crash the first
        // time a phone below it posted a run notification.
        assertTrue(
            "the live update's own calls are not behind the API that has them",
            notification.contains("Build.VERSION.SDK_INT >= LIVE_UPDATE_API"),
        )
    }

    @Test
    fun `the boot gate's notification is the same live update`() {
        val gate = source("AutoRootService.kt")

        // The gate's notification is the one a phone in a pocket actually shows after a reboot, and it is
        // built by another object entirely - so this is what says the two are one shape rather than two live
        // updates for the same run disagreeing about it.
        assertTrue(
            "the boot gate posts a notification that nothing promotes",
            gate.contains("RunNotification.live("),
        )
        assertTrue(
            "an unattended run's notification does not name its stage the way the run's own does",
            gate.contains("chip = RunNotification.chipLabel(state.phase)"),
        )
        assertTrue(
            "the gate's bar is not the bar the run screen draws",
            gate.contains("fraction = installProgress(state.phase, state.failure?.stage)"),
        )
        // The two waits before there is a run to name are the gate's own words, and both are declared: a chip
        // whose string is missing posts a live update with an empty chip rather than failing where anyone
        // would see it.
        val strings = baseStrings()
        assertTrue(gate.contains("R.string.autoroot_chip_booting"))
        assertTrue(gate.contains("R.string.autoroot_chip_shizuku"))
        assertTrue(strings.contains("name=\"autoroot_chip_booting\""))
        assertTrue(strings.contains("name=\"autoroot_chip_shizuku\""))
    }

    @Test
    fun `every phase has the word its chip gets`() {
        val notification = source("RunNotification.kt")

        val missing = InstallPhase.entries.filterNot { notification.contains("InstallPhase.${it.name}") }
        assertTrue("a phase has no chip word, so its live update has an empty chip: $missing", missing.isEmpty())

        // Both directions. A word referenced and not declared is an empty chip; one declared and never
        // referenced is a phase's word nobody ever sees. On the phone the two look the same.
        val referenced = Regex("R\\.string\\.(run_chip_[a-z_]+)")
            .findAll(notification).map { it.groupValues[1] }.toSet()
        val declared = Regex("<string name=\"(run_chip_[a-z_]+)\"")
            .findAll(baseStrings()).map { it.groupValues[1] }.toSet()
        // Without this the comparison below holds between two empty sets, which is how a renamed string
        // would leave the chip empty and this test still green.
        assertTrue("no chip words were found in the notification at all", referenced.isNotEmpty())
        assertEquals("the chip words are not the ones the default locale declares", declared, referenced)
    }

    @Test
    fun `a notification with no run behind it is swept at launch`() {
        // A run killed with its process cannot take its own down, and one left behind would keep someone
        // waiting on an install that is not happening.
        assertTrue(
            source("RootMyGalaxyApplication.kt").contains("RunNotification.clearStale(this)"),
        )
    }

    @Test
    fun `a stop left over from another boot is dropped rather than kept`() {
        // The failure this guards against is the opposite of a missed stop: a request that waits until the
        // next run in this process and stops that one instead.
        assertTrue(source("InstallViewModel.kt").contains("RunStopSignal.clear(app)"))
    }

    private fun source(name: String): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name }.toList() }
            .firstOrNull()
        requireNotNull(file) { "$name was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    /**
     * The default locale's strings rather than whichever `strings.xml` the walk meets first.
     *
     * There are twelve of them, and the translated ones carry a fraction of the keys - so a test that read
     * the first file it found would pass or fail on the order of a directory listing.
     */
    private fun baseStrings(): String {
        val file = candidateRoots()
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.name == "strings.xml" && it.parentFile?.name == "values" }
                    .toList()
            }
            .firstOrNull()
        requireNotNull(file) { "the default locale's strings were not found" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main"),
        File("app/src/main"),
    ).filter(File::isDirectory)
}
