package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flavour is a fact about the KernelSU a payload stages, and one function writes it.
 *
 * It used to be a setting standing beside the payload, and the two could disagree: set to KernelSU
 * while a KernelSU-Next payload resolved for this phone, the app would offer and look for official
 * KernelSU's manager - `me.weishu.kernelsu`, against a kernel whose only manager is `com.rifsxd.ksunext`
 * - and list tiann's releases for a daemon none of them are built against. Nothing on the screen said so.
 *
 * The fix is that the disagreement no longer exists: `rememberResolvedPayload` sets the flavour from the
 * profile it just recorded, so the manager, the release list and the module root on boot all follow the
 * payload that would run. These tests hold that shape in the source, because it is a rule about where a
 * write may exist rather than a value any one call returns - the same reason the package-identity guard
 * is written as a scan.
 */
class PayloadDecidesFlavorTest {

    private val sources: List<File> = moduleRoots()
        .flatMap { root -> root.resolve("src/main/java").walkTopDown().filter(File::isFile).toList() }
        .filter { it.extension == "kt" }

    @Test
    fun `the payload that resolves is what sets the flavour`() {
        val body = functionBody("ManagerOffer.kt", "internal fun rememberResolvedPayload")

        // Both halves: the version the manager offer reads, and the flavour that decides which manager
        // that offer is even about. Writing only the first is the bug this test exists for.
        assertTrue(
            "rememberResolvedPayload no longer records the payload's KernelSU version:\n$body",
            body.contains("AppPreferences.setPayloadKernelSuVersion(context, profile.flavor"),
        )
        assertTrue(
            "rememberResolvedPayload no longer sets the flavour from the payload it recorded, so the " +
                "manager this app offers can be the one for a kernel this phone is not running:\n$body",
            body.contains("AppPreferences.setKernelsuFlavor(context, profile.flavor)"),
        )
    }

    @Test
    fun `nothing else in the app writes the flavour`() {
        val writers = sources
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.contains("AppPreferences.setKernelsuFlavor(") }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }

        assertEquals(
            "the flavour must be written from exactly one call site - the resolved payload - because a " +
                "second writer is a second source of truth, which is the state this rule removed",
            listOf("ManagerOffer.kt"),
            writers.map { it.substringBefore(':') },
        )
    }

    @Test
    fun `no screen offers a flavour of its own`() {
        // The callback a screen used to call to write the setting. Its absence is the point: with it gone
        // there is no control anywhere whose value the payload could disagree with.
        val offenders = sources
            .flatMap { file -> file.readLines().map { line -> "${file.name}: ${line.trim()}" } }
            .filter { line -> line.contains("onKernelsuFlavorChanged") }

        assertEquals(
            "a screen can set the flavour again; the flavour is the payload's to decide, and the override " +
                "is picking another payload in the sheet",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the settings row says where its value comes from`() {
        val settings = sources.single { it.name == "MainActivity.kt" }.readText()

        assertTrue(
            "the KernelSU flavour row no longer says that the payload decides it, so it reads as a " +
                "setting someone was supposed to change",
            settings.contains("R.string.settings_ksu_flavor_from_payload"),
        )
    }

    @Test
    fun `the overview reports the manager of that flavour and of no other`() {
        // One row per project was a comparison nobody can act on: the flavour is what the resolved payload
        // loads, so the other projects' managers are apps the next run will never open, and listing them
        // made the card ask a question with no answer on it.
        val body = readinessCard()
        assertTrue(
            "the slice is not the readiness card, so this test is holding something else to the rule:\n$body",
            body.contains("R.string.readiness_shizuku"),
        )
        assertFalse(
            "the readiness card lists every flavour again, so a phone carrying another project's manager " +
                "reports on an app this phone's payload will never load:\n$body",
            body.contains("KernelSuFlavor.entries"),
        )
        assertEquals(
            "the card no longer draws exactly one manager row, which is the one for the flavour it was " +
                "handed:\n$body",
            1,
            Regex("""(?<![\w.])ManagerRow\(""").findAll(body).count(),
        )
        assertTrue(
            "the row no longer asks about the flavour handed to it, so it can report on a manager this " +
                "device is not meant to have:\n$body",
            body.contains("readiness.managers.installed(kernelsuFlavor)"),
        )
    }

    @Test
    fun `the flavour row takes no tap`() {
        // The row's own arguments: from its title up to the read that begins the manager card below it,
        // which is the point the source stops talking about this row.
        val row = sources.single { it.name == "MainActivity.kt" }.readText()
            .substringAfter("title = stringResource(R.string.settings_ksu_flavor),")
            .substringBefore("val managerOffer = offeredManager(")

        assertTrue(
            "the slice is not the flavour row, so this test is holding something else to the rule:\n$row",
            row.contains("R.string.settings_ksu_flavor_from_payload"),
        )
        assertFalse(
            "tapping the flavour row does something again. What it shows is derived, so a tap can only " +
                "offer the sheet where a payload is picked - a choice about the next run, which belongs " +
                "where that run is started",
            row.contains("onClick"),
        )
    }

    /**
     * The readiness card's own text: its declaration up to the row declaration that follows it.
     *
     * Not [functionBody], which stops at the first line that is only a brace - enough for the three-line
     * top-level function it was written for, and not for one with a `Card` and a `Column` in it.
     */
    private fun readinessCard(): String = sources.single { it.name == "MainActivity.kt" }.readText()
        .substringAfter("private fun ReadinessCard(")
        .substringBefore("private fun ManagerRow(")

    /**
     * One function's body, found by its declaration and closed at the first line that is only a brace.
     *
     * Enough for this file rather than general: the function these tests hold is three lines long and top
     * level, and a scanner matching braces would be more code than the thing it checks.
     */
    private fun functionBody(fileName: String, declaration: String): String {
        val lines = sources.single { it.name == fileName }.readLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(declaration) }
        assertTrue(
            "no `$declaration` in $fileName; this test is looking at a function that no longer exists",
            start >= 0,
        )
        return lines.drop(start).takeWhile { it.trim() != "}" }.joinToString("\n")
    }

    /** Where the shipped module is, whichever directory the test JVM was started in. */
    private fun moduleRoots(): List<File> = listOf(File("."), File("app"))
        .filter { File(it, "src/main").isDirectory }
}
