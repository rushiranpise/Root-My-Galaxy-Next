package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app asks for Shizuku as it opens, and what it says when it asks for nothing.
 *
 * The dialog belongs to Shizuku and is per-app per-install, so raising it is a small thing done once - but
 * raising it for a phone that is set never to use Shizuku, or while the service is down (where nothing is
 * raised at all), is a launch that looks like it forgot. Both facts are in the rule, and the sentence for each
 * names the thing that has to change.
 */
class ShizukuLaunchTest {

    @Test
    fun `the dialog is raised only when there is a service to ask and a reason to ask`() {
        assertTrue(
            "the app has Shizuku set on, the service running, and no grant - and asked for nothing",
            ShizukuLaunch.shouldAsk(shizukuMode = true, running = true, granted = false),
        )
        assertFalse(
            "the dialog is being raised on a phone that already granted it",
            ShizukuLaunch.shouldAsk(shizukuMode = true, running = true, granted = true),
        )
        assertFalse(
            "the dialog is being raised while the Shizuku service is down, where Shizuku answers false and " +
                "shows nothing",
            ShizukuLaunch.shouldAsk(shizukuMode = true, running = false, granted = false),
        )
        assertFalse(
            "the dialog is being raised on a phone set not to use Shizuku, so the answer would not be used",
            ShizukuLaunch.shouldAsk(shizukuMode = false, running = true, granted = false),
        )
    }

    @Test
    fun `a launch that asks for nothing says which fact stopped it`() {
        assertTrue(
            "a setting that is off is not being named as the reason",
            ShizukuLaunch.nothingToAskLine(shizukuMode = false, running = true).contains("set not to use Shizuku"),
        )
        assertTrue(
            "a service that is down is not being named as the reason",
            ShizukuLaunch.nothingToAskLine(shizukuMode = true, running = false).contains("service is not running"),
        )
        assertTrue(
            "a grant that already exists is not being named as the reason",
            ShizukuLaunch.nothingToAskLine(shizukuMode = true, running = true).contains("already has it"),
        )
    }

    @Test
    fun `the app asks as it opens, once per launch`() {
        val activity = source("app/src/main/java/dev/busung/s25uroot/MainActivity.kt")
        assertTrue(
            "nothing asks for the Shizuku grant at launch any more, so every run pays for the dialog instead",
            activity.contains("maybeRequestShizukuPermission()"),
        )
        assertTrue(
            "the request is no longer guarded to once per process, so a rotation or a second window raises " +
                "the dialog again",
            activity.contains("ShizukuLaunch.takeFirstAsk()"),
        )
    }

    @Test
    fun `only the first ask in a process gets through`() {
        // The second one is the rotation, the theme change and the second window. Nothing asserts the first
        // call's value here - another test in this class may have spent it - only that it is spent once.
        ShizukuLaunch.takeFirstAsk()
        assertFalse(
            "a second ask in the same process is being allowed, so the dialog can be raised more than once",
            ShizukuLaunch.takeFirstAsk(),
        )
    }

    private fun source(path: String): String {
        val direct = File(path)
        val fromRoot = File("../$path")
        return when {
            direct.isFile -> direct.readText()
            fromRoot.isFile -> fromRoot.readText()
            else -> error("no such file from the test's working directory: $path")
        }
    }
}
