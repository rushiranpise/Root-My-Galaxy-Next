package dev.busung.s25uroot

import dev.busung.s25uroot.dfr.DfrHelperStanding
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helper on this phone against the one this build ships.
 *
 * The interesting half is not the comparison but everything around it: which standings offer nothing, that
 * both version codes are required, that the decision is Package Manager's own reading rather than a shell
 * command, and - the one that is a rule rather than a fact - that an install over an existing helper must
 * not stamp the attempt the way the flow's first install does, because that stamp would send the flow's
 * step list to a restart that an in-place update does not need.
 */
class HelperUpdateTest {

    @Test
    fun `a different build is the only thing offered`() {
        assertEquals(
            HelperUpdate(onPhone = 41L, inThisBuild = 44L),
            HelperUpdate.of(DfrHelperStanding.Stale, 41L, 44L),
        )
        // Nothing installed is not an update: there is nothing to replace, and installing a helper for the
        // first time is the flow's own steps, with the certificate and the reboot they owe.
        assertNull(HelperUpdate.of(DfrHelperStanding.Missing, null, 44L))
        // An ordinary app under the helper's id needs the removal the flow offers, not a newer build of the
        // same mistake.
        assertNull(HelperUpdate.of(DfrHelperStanding.Ordinary, 41L, 44L))
        // The state the card exists to reach.
        assertNull(HelperUpdate.of(DfrHelperStanding.System, 44L, 44L))
    }

    @Test
    fun `both version codes are required before anything is offered`() {
        // The card's claim is a comparison, so a missing half of it is a reading that cannot be told apart
        // from an APK that could not be read - and installing on that would be replacing a helper to fix a
        // number.
        assertNull(HelperUpdate.of(DfrHelperStanding.Stale, null, 44L))
        assertNull(HelperUpdate.of(DfrHelperStanding.Stale, 41L, null))
        assertNull(HelperUpdate.of(DfrHelperStanding.Stale, null, null))
    }

    @Test
    fun `a helper built by a newer app is offered too`() {
        // Equality, not order: the question is whether the phone has the code in this APK, not whether its
        // number went up - so a helper from a later build is the same answer and the same fix. Held at the
        // flow's own comparison, which is the only place the two can be compared without two APKs.
        assertEquals(HelperUpdate(50L, 44L), HelperUpdate.of(DfrHelperStanding.Stale, 50L, 44L))
    }

    @Test
    fun `the reading is Package Manager's, not a shell's`() {
        val model = source(MODEL)
        // No `probe()`, no `runOnEitherShell`: this is asked on the screen the app opens on, before
        // anything on the device is running, and every part of it is a question Package Manager answers.
        assertTrue(
            "the standing does not come from Package Manager's own reading",
            model.contains("DfrInstall.helperStanding(context, apk)"),
        )
        assertTrue(
            "the version codes are not read from the bundled APK as well as the installed package",
            model.contains("DfrInstall.readStageTwoBuild(context, apk)"),
        )
        assertFalse("the reading runs a shell command", model.contains("DfrInstall.probe"))
    }

    @Test
    fun `the update does not stamp the install the way a first install does`() {
        val model = source(MODEL)
        // The stamp answers "has the phone restarted since this app last installed the helper", and it
        // exists because a helper installed for the first time is accepted as a system app only after a
        // reboot. An install *over* a system-uid helper owes none: Package Manager never revisits the uid,
        // and the code injected into system_server drops its cached copy of the package on the next run.
        assertTrue(
            "the update reads the bundled helper and installs over the one on the phone",
            model.contains("DfrInstall.installStageTwo(apk)"),
        )
        assertFalse(
            "the update stamps the install, so the flow's own step list would ask for a restart it does " +
                "not need",
            model.contains("setDfrInstalledAt"),
        )
        // And the press it mirrors *does* stamp, so the difference above is a decision rather than a
        // function nobody happens to call.
        assertTrue(
            "the flow's install press no longer stamps its attempt",
            source(UI).contains("AppPreferences.setDfrInstalledAt(context, System.currentTimeMillis())"),
        )
    }

    @Test
    fun `the card is on the screen the app opens on`() {
        val home = source(HOME)
        assertTrue(
            "nothing surfaces the mismatch, which is the whole point of the card",
            home.contains("HelperUpdateCard("),
        )
        assertTrue(
            "the card is drawn from a reading taken once and never refreshed",
            home.contains("HelperUpdate.read(context)"),
        )
        // Off the screen while a run is in flight, on the update banner's own rule: a package install
        // beside a run is CPU and a Package Manager turn that the run's timing did not ask for.
        assertTrue(
            "the card is offered during a run",
            home.contains("pendingHelper != null && !installState.busy"),
        )
    }

    @Test
    fun `the card is a warning and its answer is filled like one`() {
        val home = source(HOME)
        val card = home.substringAfter("private fun HelperUpdateCard(")
            .substringBefore("\n@Composable")
        // The attention palette, because this is not an offer to make a phone newer: it is a phone whose
        // root at boot has stopped working, and the update banner's container is a colour that reads as an
        // announcement rather than a fault.
        assertTrue(
            "the card wears the update banner's container instead of the attention one",
            card.contains("MaterialTheme.colorScheme.errorContainer"),
        )
        assertTrue("the card has no warning mark", card.contains("Icons.Rounded.Warning"))
        // And its answer is filled for the surface it sits on. The role decides the fill, which is what
        // the shared vocabulary is for; a screen spelling `ButtonDefaults.buttonColors` out here is the
        // thing `ActionRowsTest` fails the build over.
        assertTrue(
            "the card's answer is not filled for the card it is drawn on",
            card.contains("role = AppActionRole.Attention"),
        )
    }

    @Test
    fun `the words the card needs exist`() {
        val strings = source(STRINGS)
        for (name in listOf(
            "helper_update_title",
            "helper_update_body",
            "helper_update_codes",
            "helper_update_action",
            "helper_update_installing",
        )) {
            assertTrue("$name is missing from this app's resources", strings.contains("name=\"$name\""))
        }
    }

    /** Both sources were really read: every assertion above passes on an empty string. */
    @Test
    fun `the files this reads were really read`() {
        assertTrue(source(MODEL).contains("internal data class HelperUpdate"))
        assertTrue(source(HOME).contains("private fun HelperUpdateCard("))
    }

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))

    private companion object {
        private const val MODEL = "app/src/main/java/dev/busung/s25uroot/HelperUpdate.kt"
        private const val HOME = "app/src/main/java/dev/busung/s25uroot/MainActivity.kt"
        private const val UI = "app/src/main/java/dev/busung/s25uroot/DfrUi.kt"
        private const val STRINGS = "app/src/main/res/values/strings.xml"
    }
}
