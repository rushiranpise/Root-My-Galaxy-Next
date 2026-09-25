package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide, the ask it ends with, and the FAQ: that both are reachable and that neither can name a string
 * that does not exist.
 *
 * None of these fail loudly on the phone. A guide whose stored reading was never written shows on every
 * launch, one that is never shown is a first launch nobody was warned about, and a FAQ entry pointing at a
 * missing string is a question drawn over a blank answer - so the wiring is checked where it is written.
 */
class GuideAndFaqTest {

    @Test
    fun `the guide is stored, so it appears on a first launch and not again`() {
        assertTrue(source("AppPreferences.kt").contains("fun guideAccepted("))
        assertTrue(source("AppPreferences.kt").contains("fun setGuideAccepted("))

        val home = source("MainActivity.kt")
        // Shown from the reading rather than from a version or a counter: the phone that needs it is the one
        // that has never answered it.
        assertTrue(home.contains("!AppPreferences.guideAccepted(context)"))
        assertTrue(home.contains("AppPreferences.setGuideAccepted(context, true)"))
        // And it is not a one-time screen: Home keeps a way back to it.
        assertTrue(home.contains("R.string.guide_reread"))
    }

    @Test
    fun `the guide cannot be dismissed without being answered on a first launch`() {
        val home = source("MainActivity.kt")
        assertTrue(
            "the guide can be dismissed by tapping outside it before it has been read",
            home.contains("dismissOnClickOutside = alreadyAccepted"),
        )
        assertTrue(home.contains("dismissOnBackPress = alreadyAccepted"))
    }

    @Test
    fun `the notification step is the notification permission itself`() {
        val home = source("MainActivity.kt")
        // The ask has to be the runtime permission and not a row that opens settings: the reason the guide
        // gives for it is that a run turns the screen off, and a phone that declined still has Settings.
        assertTrue(home.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(home.contains("permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"))
        assertTrue(source("strings.xml").contains("name=\"guide_notifications_body\""))
    }

    @Test
    fun `every question the FAQ lists has both halves declared`() {
        val home = source("MainActivity.kt")
        val entries = Regex("FaqEntry\\(R\\.string\\.([a-z_]+), R\\.string\\.([a-z_]+)\\)")
            .findAll(home)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("the FAQ lists no questions at all", entries.isNotEmpty())

        val strings = source("strings.xml")
        val declared = Regex("<string name=\"([a-z_]+)\"").findAll(strings).map { it.groupValues[1] }.toSet()
        val mentioned = entries.flatMap { (question, answer) -> listOf(question, answer) }.toSet()
        val missing = mentioned - declared
        assertTrue("the FAQ names strings that do not exist: $missing", missing.isEmpty())

        // A question without its answer is the shape this catches: every pair is a _q and an _a of one name.
        entries.forEach { (question, answer) ->
            assertTrue("$question is not a question", question.endsWith("_q"))
            assertTrue("$answer is not the answer to it", answer == question.removeSuffix("_q") + "_a")
        }
    }

    @Test
    fun `the FAQ is reachable from Home beside the guide and about`() {
        val home = source("MainActivity.kt")
        assertTrue(home.contains("title = stringResource(R.string.faq_title)"))
        assertTrue(home.contains("FaqDialog(onDismiss = { showFaq = false })"))
        assertTrue(home.contains("title = stringResource(R.string.guide_reread)"))
        assertTrue(home.contains("GuideDialog("))
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
