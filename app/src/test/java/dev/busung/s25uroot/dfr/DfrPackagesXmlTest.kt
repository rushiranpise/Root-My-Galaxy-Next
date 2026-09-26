package dev.busung.s25uroot.dfr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The ported packages.xml injector, exercised on the JVM.
 *
 * The ported core takes bytes and returns bytes, and its two paths that matter - reading a text
 * `packages.xml` with JAXP and writing one back - need no Android at all. So the whole decision the
 * injector makes can be checked here, against a fixture shaped like the device's real file
 * (`<cert index="N" flags="2"/>` inside `<pastSigs>`, one foreign entry already present, a key table
 * indexed by encounter order), rather than discovered on a phone whose next boot depends on it.
 *
 * This is the reason porting their installer is worth anything to us: DFReroot's own flow only ever
 * demonstrates itself by rebooting into a modified packages.xml. Here a wrong edit fails a test.
 */
class DfrPackagesXmlTest {

    private val ours = "ab".repeat(60)          // 120 hex chars, like a DER cert
    private val foreign = "cd".repeat(60)

    /** A fixture with the structure the device actually has, scaled down. */
    private fun fixture(packages: Int = 24, sharedUser: Boolean = true): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<packages>\n")
        append("  <version sdkVersion=\"36\" databaseVersion=\"3\" />\n")
        repeat(packages) { i ->
            append("  <package name=\"com.example.p$i\" codePath=\"/data/app/p$i\" ")
            append("userId=\"10${100 + i}\" versionCode=\"$i\">\n")
            append("    <sigs count=\"1\" schemeVersion=\"3\">\n")
            append("      <cert index=\"$i\" key=\"${"0$i".takeLast(2).repeat(60)}\" />\n")
            append("    </sigs>\n  </package>\n")
        }
        // The real file's own index layout: a current cert at 7, a foreign past cert at 112.
        if (sharedUser) {
            append("  <shared-user name=\"android.uid.system\" userId=\"1000\">\n")
            append("    <sigs count=\"1\" schemeVersion=\"3\">\n")
            append("      <cert index=\"7\" key=\"${"7f".repeat(60)}\" />\n")
            append("      <pastSigs count=\"1\">\n")
            append("        <cert index=\"112\" flags=\"2\" key=\"$foreign\" />\n")
            append("      </pastSigs>\n")
            append("    </sigs>\n  </shared-user>\n")
        }
        append("  <shared-user name=\"com.other\" userId=\"10009\">\n")
        append("    <sigs count=\"1\"><cert index=\"1\" key=\"${"1e".repeat(60)}\" /></sigs>\n")
        append("  </shared-user>\n</packages>\n")
    }

    private fun parse(xml: String): Document = PackagesXml.parseToDom(xml.toByteArray())

    private fun pastCerts(doc: Document, target: String): List<Element> {
        val users = doc.getElementsByTagName("shared-user")
        for (i in 0 until users.length) {
            val el = users.item(i) as Element
            if (el.getAttribute("name") != target) continue
            val sigs = el.getElementsByTagName("sigs").item(0) as Element
            val past = sigs.getElementsByTagName("pastSigs").item(0) as Element
            return (0 until past.getElementsByTagName("cert").length).map {
                past.getElementsByTagName("cert").item(it) as Element
            }
        }
        return emptyList()
    }

    @Test
    fun `an inject adds two of our certs and keeps the foreign one`() {
        val doc = parse(fixture())
        val log = StringBuilder()
        val before = pastCerts(doc, DfrInstall.TARGET).size
        val patched = PackagesXml.buildPatchedXml(doc, ours, listOf(DfrInstall.TARGET), log)

        // Re-parse what was written: the test reads the same bytes PMS will.
        val after = parse(String(patched))
        val certs = pastCerts(after, DfrInstall.TARGET)

        assertEquals("one foreign cert before", 1, before)
        assertEquals("three after: the foreign one plus ours twice", 3, certs.size)
        assertEquals("our key is written twice", 2, certs.count { it.getAttribute("key") == ours })
        // The foreign cert carries flags="2" as well - that is what a past cert is - so the flag is
        // checked on ours rather than counted across the block.
        assertEquals(
            "each of ours carries the shared-user flag",
            2,
            certs.count { it.getAttribute("flags") == "2" && it.getAttribute("key") == ours },
        )
        assertEquals("the foreign cert is kept", 1, certs.count { it.getAttribute("key") == foreign })

        val past = (after.getElementsByTagName("shared-user")
            .let { users ->
                (0 until users.length).map { users.item(it) as Element }
                    .first { it.getAttribute("name") == DfrInstall.TARGET }
            }.getElementsByTagName("pastSigs").item(0) as Element)
        assertEquals("count follows the children", "3", past.getAttribute("count"))

        assertTrue("the injector's own verification passes", PackagesXml.verifyPatched(patched, listOf(DfrInstall.TARGET), ours).isNotEmpty())
        assertTrue("and a re-read agrees we are in", PackagesXml.isInjected(after, DfrInstall.TARGET, ours))
    }

    @Test
    fun `the fresh index is past every index and the key table`() {
        val doc = parse(fixture())
        val log = StringBuilder()
        val patched = PackagesXml.buildPatchedXml(doc, ours, listOf(DfrInstall.TARGET), log)
        val certs = pastCerts(parse(String(patched)), DfrInstall.TARGET)
        val index = certs.first { it.getAttribute("key") == ours }.getAttribute("index").toInt()

        // The fixture's highest index is the foreign cert's 112, and the encounter table is built from
        // the inline keys; a fresh index must clear both or it would collide with a padded slot.
        assertTrue("index $index clears every existing index", index > 112)
        assertTrue(log.contains("fresh cert index="))
    }

    @Test
    fun `injecting twice is refused before anything is written`() {
        // The second attempt is made on what the first one wrote - that is the state a second inject
        // actually meets on a device, and it has to be refused rather than stacking a second pair of
        // certs on top.
        val patched = PackagesXml.buildPatchedXml(parse(fixture()), ours, listOf(DfrInstall.TARGET), StringBuilder())

        val error = runCatching {
            PackagesXml.buildPatchedXml(parse(String(patched)), ours, listOf(DfrInstall.TARGET), StringBuilder())
        }.exceptionOrNull()
        assertNotNull("a second inject has to fail", error)
        assertTrue(error!!.message.orEmpty().contains("already injected"))
    }

    @Test
    fun `an uninstall removes only our key`() {
        val target = DfrInstall.TARGET
        val patched = PackagesXml.buildPatchedXml(parse(fixture()), ours, listOf(target), StringBuilder())

        val doc = parse(String(patched))
        val log = StringBuilder()
        assertTrue("something was removed", PackagesXml.removeOurKeys(doc, listOf(target), ours, log))

        val certs = pastCerts(doc, target)
        assertEquals("only the foreign cert is left", 1, certs.size)
        assertEquals(foreign, certs.first().getAttribute("key"))
        assertFalse(PackagesXml.isInjected(doc, target, ours))
        assertTrue(log.contains("removed"))
    }

    @Test
    fun `an uninstall on a clean file writes nothing`() {
        val doc = parse(fixture())
        assertFalse(PackagesXml.removeOurKeys(doc, listOf(DfrInstall.TARGET), ours, StringBuilder()))
    }

    @Test
    fun `the structural checks refuse a file that is not this file`() {
        // Too few packages: exactly the condition a truncated or half-written packages.xml has, and
        // the reason the ported code refuses rather than "repairing" it.
        val few = runCatching {
            PackagesXml.structuralChecks(
                parse(fixture(packages = 5)),
                listOf(DfrInstall.TARGET),
                StringBuilder(),
            )
        }.exceptionOrNull()
        assertTrue(few!!.message.orEmpty().contains("refusing"))

        // The shared user is absent: PMS assigns user ids, so a replacement entry invented here would
        // be wrong in a way nothing else can catch.
        val missing = runCatching {
            PackagesXml.structuralChecks(
                parse(fixture(sharedUser = false)),
                listOf(DfrInstall.TARGET),
                StringBuilder(),
            )
        }.exceptionOrNull()
        assertTrue(missing!!.message.orEmpty().contains("absent"))

        // And the fixture itself passes both, so the two refusals above are about what they say.
        PackagesXml.structuralChecks(
            parse(fixture()),
            listOf(DfrInstall.TARGET),
            StringBuilder(),
        )
    }

    @Test
    fun `a hex value in a decimal attribute aborts the rewrite`() {
        // INT_HEX wire types surface as hex strings from the string layer and PMS's decimal parseInt
        // would reject them on the next read, so the guard has to stop the write rather than write it.
        val hexed = fixture().replace("userId=\"1000\"", "userId=\"0x3e8\"")
        val error = runCatching { PackagesXml.buildPatchedXml(parse(hexed), ours, listOf(DfrInstall.TARGET), StringBuilder()) }
            .exceptionOrNull()
        assertNotNull("a hex userId must abort", error)
        assertTrue(error!!.message.orEmpty().contains("not decimal"))
    }

    @Test
    fun `a key that is not plausible hex is refused`() {
        listOf("not-hex", "", "abc", "zz".repeat(60)).forEach { bad ->
            val error = runCatching {
                PackagesXml.buildPatchedXml(parse(fixture()), bad, listOf(DfrInstall.TARGET), StringBuilder())
            }.exceptionOrNull()
            assertNotNull("key '$bad' must be refused", error)
        }
    }

    @Test
    fun `the files an inject leaves are named as this project's, not the other install's`() {
        // A shared name is not cosmetic. The backup is written once and never over an existing one, so
        // this install finding DFReroot's `.bak-df-installer` would adopt their pre-inject file as its
        // own rescue copy and never make one of its own - the same rule as the daemon path.
        assertNotEquals("the backup is named where the other install names its own", ".bak-df-installer", PackagesXml.BACKUP_SUFFIX)
        assertNotEquals("the staged copy is named where the other install names its own", ".new-df-installer", PackagesXml.TEMP_SUFFIX)
        assertTrue(PackagesXml.BACKUP_SUFFIX, PackagesXml.BACKUP_SUFFIX.contains("rmgnext"))
        assertTrue(PackagesXml.TEMP_SUFFIX, PackagesXml.TEMP_SUFFIX.contains("rmgnext"))
    }

    @Test
    fun `the residue screen is told about both files an inject can leave`() {
        // Listed rather than deleted: what an inject leaves in /data/system is the residue screen's to
        // show, so the names there have to be the ones the injector writes. Typed out on the screen side
        // instead of read from here, a rename would leave a file nothing lists.
        assertEquals(
            listOf(
                PackagesXml.PACKAGES_XML + PackagesXml.BACKUP_SUFFIX,
                PackagesXml.PACKAGES_XML + PackagesXml.TEMP_SUFFIX,
            ),
            DfrInstall.leftoverPaths,
        )
    }

    @Test
    fun `the command the app runs names our class and the shared user`() {
        val command = DfrInstall.command("/data/app/base.apk", DfrMode.DryRun)
        assertTrue(command, command.contains("CLASSPATH='/data/app/base.apk'"))
        assertTrue(command, command.contains(DfrInstall.MAIN_CLASS))
        assertTrue(command, command.contains("--targets android.uid.system"))
        assertTrue(command, command.contains("--dry-run"))
        assertFalse("a dry run must not carry the inject's empty flag", command.endsWith(" "))
        assertTrue(DfrInstall.command("/x", DfrMode.Inject).let { !it.contains("--dry-run") })
        assertTrue(DfrInstall.command("/x", DfrMode.Uninstall).contains("--uninstall"))
    }
}
