package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen during a run: what each press of the power key would do, and that both run paths ask for one.
 *
 * The failures these exist for are quiet in both directions - a run that wakes the screen it meant to put
 * out, and a run that finishes with the screen dark and nothing to say why - so the decisions are checked
 * directly, and the wiring is checked where a missing call would leave the setting doing nothing at all.
 */
class RunScreenOffTest {

    @Test
    fun `a run with no shell leaves the screen alone`() {
        assertEquals(
            RunScreenOff.Decision.NoShell,
            RunScreenOff.decision(interactive = true, canPress = false),
        )
        assertEquals(
            RunScreenOff.Decision.NoShell,
            RunScreenOff.decision(interactive = false, canPress = false),
        )
    }

    @Test
    fun `the key is pressed only while the screen is on`() {
        // The power key toggles. Pressing it at a screen that is already out would wake the phone for the
        // exploit and then put it out again at the end, which is the opposite of what both presses are for.
        assertEquals(RunScreenOff.Decision.Press, RunScreenOff.decision(interactive = true, canPress = true))
        assertEquals(
            RunScreenOff.Decision.AlreadyOut,
            RunScreenOff.decision(interactive = false, canPress = true),
        )
    }

    @Test
    fun `the wake presses only while the screen is still out`() {
        assertTrue(RunScreenOff.shouldWake(interactive = false))
        assertFalse(RunScreenOff.shouldWake(interactive = true))
    }

    @Test
    fun `the command is the power key, by absolute path, with a short leash`() {
        // Absolute because Shizuku starts the command with no shell in between to resolve a name.
        assertTrue(RunScreenOff.POWER_KEY_COMMAND.startsWith("/system/bin/"))
        assertTrue(RunScreenOff.POWER_KEY_COMMAND.endsWith("keyevent 26"))
        // The payload waits behind this press, so a transport that has not answered quickly is a transport
        // the run carries on without.
        assertTrue(RunScreenOff.POWER_KEY_TIMEOUT_MILLIS in 1..15_000L)
    }

    @Test
    fun `both run paths ask, and the run's end brings the screen back`() {
        val viewModel = source("InstallViewModel.kt")

        // One definition and one call per transport: Shizuku's shell, and the ADB session the local run is
        // already holding. A transport added without a call would leave this count at three and the screen
        // on for every run that goes that way.
        assertEquals(
            "a run path does not put the screen out",
            3,
            Regex("putTheScreenOut\\(").findAll(viewModel).count(),
        )
        assertTrue(
            "nothing brings the screen back after a run that put it out",
            viewModel.contains("wakeTheScreenAgain()"),
        )
        assertTrue(viewModel.contains("AppPreferences.screenOffDuringRun(app)"))
        // Not unattended: nobody asked for that phone to be dark, and its boot may have left it that way.
        assertTrue(viewModel.contains("if (runIsUnattended || !AppPreferences.screenOffDuringRun(app)) return"))
    }

    @Test
    fun `the setting is offered and stored`() {
        assertTrue(source("AppPreferences.kt").contains("fun setScreenOffDuringRun("))
        val settings = source("MainActivity.kt")
        assertTrue(settings.contains("R.string.settings_screen_off_during_run"))
        assertTrue(settings.contains("R.string.settings_screen_off_during_run_summary"))
        assertTrue(settings.contains("AppPreferences.setScreenOffDuringRun(this, enabled)"))
        assertTrue(
            "the switch has no string to draw",
            source("strings.xml").contains("name=\"settings_screen_off_during_run\""),
        )
    }

    @Test
    fun `the setting is off until somebody turns it on`() {
        // Off by default: the presses change the screen of a phone whose owner asked for a run and not for
        // that, and a run interrupted before the wake can press again leaves the phone dark. The switch in
        // Settings is how a person asks for it instead.
        assertTrue(
            "the stored default puts the screen out before anyone asked",
            source("AppPreferences.kt").contains("prefs(context).getBoolean(SCREEN_OFF_DURING_RUN, false)"),
        )
        assertTrue(
            "the switch's first frame disagrees with the stored default",
            source("MainActivity.kt").contains("screenOffDuringRun by mutableStateOf(false)"),
        )
    }

    private fun source(name: String): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name }.toList() }
            .firstOrNull { it.parentFile?.name == "values" || it.extension == "kt" }
        requireNotNull(file) { "$name was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main"),
        File("app/src/main"),
    ).filter(File::isDirectory)
}
