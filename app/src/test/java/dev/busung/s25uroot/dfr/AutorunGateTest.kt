package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing the stage-two screen may not take on faith: who asked for a run.
 *
 * This activity is exported, because the app starts it through `am start` - and an exported component that
 * acts on an extra acts on *anyone's* extra. Any app on the phone could put `rmg.autorun` in an intent and
 * spend the boot's one attempt, and a boot whose attempt was spent that way cannot be rooted again without a
 * restart, with nothing on the screen saying why. The fix is not a secret - there is nowhere to keep one
 * without root - but the platform's own answer to *who launched this activity*: the app's launches always
 * come from a shell, so root or the `shell` user is the whole of the legitimate answer, and neither is an
 * identity an ordinary app can produce.
 *
 * What is pinned here is that the extra is checked against that answer and nothing else, that both of the
 * refusing verdicts are separate so the log can say which happened, and that a build which cannot ask the
 * platform fails closed rather than trusting the extra. All of it is read from the sources, because this
 * module shares no code with the helper's own APK.
 */
class AutorunGateTest {

    @Test
    fun `the extra no longer decides the run on its own`() {
        val activity = stageTwoActivity()
        assertTrue(
            "the screen still turns the extra straight into a run, so any app on the phone could spend " +
                "this boot's one attempt with an intent it wrote itself",
            !activity.contains("autorun = intent?.getBooleanExtra(EXTRA_AUTORUN, false) == true"),
        )
        assertTrue(
            "the extra is not weighed against who launched the activity, so the gate this test exists " +
                "for is not there",
            activity.contains("Autorun.verdict("),
        )
        assertTrue(
            "the run is not driven by the verdict, so the check and the thing it decides could disagree",
            activity.contains("autorun = autorunVerdict.runs"),
        )
        // The launched-from reading is asked of the activity this screen is, not of some other object: it
        // is the same process's answer, and a reading about anything else would be no answer at all.
        assertTrue(
            "the verdict is not made from this activity's own launcher, so the reading could be about " +
                "something other than the process that started this screen",
            activity.contains("Autorun.launchedFromUid(this)"),
        )
    }

    @Test
    fun `a launch is authorized by root or the shell and by nothing else`() {
        val autorun = autorunSource()
        assertTrue(
            "the trusted identities are not exactly root and the shell, so either a launch nobody " +
                "sent is authorized or a legitimate boot could not ask for its run",
            autorun.contains("TRUSTED_UIDS: Set<Int> = setOf(0, Process.SHELL_UID)"),
        )
        // The four cases, each present so a name cannot be removed while the enum entry it needs stays,
        // and the two refusals separate: an app that sent a run it may not send is a phone with something
        // else on it, while a build that cannot ask is a phone that needs an update or a restart.
        for (case in listOf("Granted", "NotRequested", "Refused", "Unanswerable")) {
            assertTrue(
                "$case is not an answer the verdict can give, so a launch that would reach it is " +
                    "unaccounted for",
                autorun.contains("AutorunVerdict.$case"),
            )
        }
        assertTrue(
            "a requested run with no readable launcher is not refused, so a build that cannot ask would " +
                "fall back to trusting the extra",
            autorun.contains("launchedFromUid == null -> AutorunVerdict.Unanswerable"),
        )
        assertTrue(
            "a requested run from a launcher that is not trusted is not refused, which is the hole this " +
                "whole change closes",
            autorun.contains("else -> AutorunVerdict.Refused"),
        )
        assertTrue(
            "no request is not distinguished from a refused one, so the log could not tell a person who " +
                "opened the screen from an app that tried to spend the boot",
            autorun.contains("!requested -> AutorunVerdict.NotRequested"),
        )
        assertTrue(
            "only the granted verdict runs the exploit, which is the whole of the gate",
            autorun.contains("val runs: Boolean get() = this == Granted"),
        )
    }

    @Test
    fun `a build that cannot read the launcher refuses rather than trusts`() {
        val autorun = autorunSource()
        assertTrue(
            "the API level the launched-from reading arrived at is not written down, so a build below it " +
                "could ask for a value that is not there",
            autorun.contains("const val READING_API = 34"),
        )
        assertTrue(
            "the reading is not gated on its API level, so on a phone below it the screen would throw " +
                "or read nonsense where the extra would have been trusted",
            autorun.contains("if (Build.VERSION.SDK_INT >= READING_API)"),
        )
        assertTrue(
            "the platform's refusal to answer is not turned into a null, so a null-nobody-sent and a " +
                "build-that-cannot-ask would be two different things to the verdict",
            autorun.contains("runCatching { activity.launchedFromUid }.getOrNull()"),
        )
    }

    @Test
    fun `a refused run says so in the log`() {
        val activity = stageTwoActivity()
        assertTrue(
            "a launch that asked for a run and did not get it says nothing about it, so a boot that " +
                "quietly stopped rerooting would look like one where nobody asked",
            activity.contains("when (autorunVerdict)"),
        )
        assertTrue(
            "the refusal is not distinguished from a launch that asked for nothing, so the log would " +
                "not say which of the two happened",
            activity.contains("AutorunVerdict.Refused ->") &&
                activity.contains("AutorunVerdict.Unanswerable ->"),
        )
    }

    @Test
    fun `every source was really read`() {
        // Each assertion above passes on an empty string, so a moved file would turn this class green.
        assertTrue(stageTwoActivity().contains("class Stage2Activity"))
        assertTrue(autorunSource().contains("internal object Autorun"))
    }

    private fun stageTwoActivity(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt")

    private fun autorunSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Autorun.kt")

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
