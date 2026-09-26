package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot-settle floor as both boot gates use it: one setting, one loop, two readers.
 *
 * Root on boot and Reroot at boot act on a boot that is still settling, with nobody at the screen, which is
 * one decision about one kind of boot - so it is one value. Two values here would be two claims about how
 * settled a device has to be before either may act, and the failure they would produce is not tellable apart
 * from the exploit failing: a phone that came back unrooted because the gate that ran was left at a floor the
 * other one was not.
 *
 * These are source readings rather than runtime ones, because what they hold is the arrangement of the two
 * services - which setting each reads, which loop waits it out, and that the reroot gate waits before it
 * launches anything. Nothing at runtime would fail if a second setting appeared beside the first: the phone
 * would simply be settled by one rule at Root on boot and another at Reroot at boot.
 */
class BootGateSettleTest {

    @Test
    fun `both boot gates wait the shared floor on the shared loop`() {
        // The whole point of the shared helper: a gate that grew its own loop over its own value is how the
        // two of them come to disagree, and the loop is where the countdown, the tick and the answer to a
        // withdrawn setting live.
        BOOT_GATES.forEach { service ->
            val source = codeOf(service)
            assertTrue(
                "$service has to wait the shared floor, not a loop of its own",
                source.contains("BootSettle.awaitFloor("),
            )
            assertTrue(
                "$service has to read the one floor both gates are settled by",
                source.contains("AppPreferences.bootGateSettleSeconds("),
            )
            assertTrue(
                "$service's wait has to report itself, or an unattended wait is a hang",
                source.contains("R.string.status_boot_settle"),
            )
        }
    }

    @Test
    fun `neither boot gate reads the manual run's floor`() {
        // The one mistake this arrangement can make: an unattended gate reading the setting a person tunes
        // for a manual run, so turning automation on and finding it too slow quietly rewrites what a manual
        // run does next. The manual value is a different decision about a different watcher.
        BOOT_GATES.forEach { service ->
            assertFalse(
                "$service must not read the manual settle, which is a delay a person is watching",
                codeOf(service).contains("AppPreferences.bootSettleSeconds("),
            )
        }
    }

    @Test
    fun `the reroot gate waits before it starts the helper, and asks again after`() {
        // Where the wait sits is the whole of its value: it is the state of the phone at the moment the
        // exploit starts that matters, so a settle spent before the decision to reroot - or after the
        // launch - is a wait that protected nothing. And the decision is asked again once it is over,
        // because a minute is long enough for a run started by hand to root the phone.
        val source = codeOf("DfrBootService")
        val guarded = source.indexOf("if (decision.startsTheHelper()) {")
        val settle = source.indexOf("awaitSettledFloor()")
        val launch = source.indexOf("decision.startsTheHelper() -> startTheHelper(")
        // The end of the settle's own branch: the re-read has to be inside it, and before anything is
        // launched. Bounded by the next thing the gate does rather than by the launch line, because a
        // re-read that only happened after the shell wait would be a decision taken on a stale phone.
        val branchEnd = source.indexOf("DfrBootDecision.NoShell", settle)
        assertTrue("the settle has to be guarded by the decision to act", guarded >= 0)
        assertTrue("the settle has to happen at all", settle >= 0)
        assertTrue("no gate can launch without a settle in front of it", launch >= 0)
        assertTrue("the settle belongs before anything is launched", settle < launch)
        assertTrue("and inside the branch that decided there is something to launch", guarded < settle)
        assertTrue("the settle has to be followed by the rest of the gate's own decisions", branchEnd > settle)
        assertTrue(
            "the decision has to be re-read once the phone has had time to change",
            source.indexOf("decision = decide(bootToken, helper)", settle) in (settle + 1)..branchEnd,
        )
    }

    @Test
    fun `the gate's budget covers the longest wait the setting can ask for`() {
        // A budget that followed the value in force at one boot would cut the gate off part-way through a
        // wait its owner had asked for, and report that as the gate giving up. So it is sized on the
        // ceiling, and the ceiling is derived from the values the chooser offers.
        assertTrue(
            "DfrBootService's own deadline has to include the longest settle",
            codeOf("DfrBootService").contains("BootSettle.GATE_CEILING_MILLIS"),
        )
    }

    @Test
    fun `the floor is stored once, under the name the field already has`() {
        val prefs = sourceOf("AppPreferences")
        val defaults = Regex("""getInt\(\s*[A-Z_]+\s*,\s*BootSettle\.GATE_DEFAULT_SECONDS""")
            .findAll(prefs)
            .count()
        assertEquals("two stored values defaulting to the gate floor is a second setting", 1, defaults)
        val readers = Regex("""getInt\(\s*AUTO_ROOT_SETTLE_SECONDS""").findAll(prefs).count()
        assertEquals("one value, one reader", 1, readers)
        assertFalse("the accessor is named for both gates now", prefs.contains("fun autoRootSettleSeconds"))
        assertTrue(
            // A preference key is a fact about phones in the field rather than a name in this code: renaming
            // it would silently reset everyone who has ever chosen a value back to the default.
            "the stored key keeps the name it was first written under",
            prefs.contains("\"auto_root_settle_seconds\""),
        )
    }

    /** Both of them: the list is here so a third gate added later is covered on the day it is added. */
    private val BOOT_GATES = listOf("AutoRootService", "DfrBootService")

    private fun sourceOf(name: String): String =
        projectFile("src/main/java/dev/busung/s25uroot/$name.kt").readText()

    /**
     * The same source with its comments taken out.
     *
     * Not a tidy-up: every assertion in this file is about a line of code, and a KDoc paragraph that names
     * the constant a gate is supposed to read would otherwise satisfy the test that looks for it - which is
     * how the budget check here passed while the budget did not cover the ceiling.
     */
    private fun codeOf(name: String): String = sourceOf(name)
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    /** A file of this module, from the module directory or from the repository root Gradle was run in. */
    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative"))
        .firstOrNull(File::isFile)
        ?: throw AssertionError("$relative was not found from ${File(".").absolutePath}")
}
