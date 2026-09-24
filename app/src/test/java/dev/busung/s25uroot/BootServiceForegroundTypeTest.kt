package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground type of every service the boot broadcast starts.
 *
 * An app that targets Android 15 may not launch a `dataSync` foreground service from a `BOOT_COMPLETED`
 * receiver, and the system refuses it by throwing at the caller - so the service never runs and the boot is
 * over. These services exist to be started exactly that way, which makes the type each one is declared with
 * the difference between working and not being started at all. Nothing at runtime would show the difference
 * either: the symptom is a phone that came back with an unrooted kernel and no notification.
 *
 * The set of services is read from the receiver rather than written down here, so a fourth boot service is
 * covered by this test on the day it is added.
 */
class BootServiceForegroundTypeTest {

    @Test
    fun `every service the boot receiver starts is declared special use`() {
        val manifest = manifestFile().readText()
        val services = bootStartedServices()
        assertTrue(
            "no boot services were found; the scan is looking at the wrong file",
            services.isNotEmpty(),
        )

        services.forEach { service ->
            val declaration = serviceDeclaration(manifest, service)
                ?: throw AssertionError("$service is started at boot and is not declared in the manifest")
            assertFalse(
                "$service is started from BOOT_COMPLETED, which Android 15 refuses for a dataSync service",
                declaration.contains("dataSync"),
            )
            assertTrue(
                "$service must be declared specialUse to be startable from the boot broadcast",
                declaration.contains("""android:foregroundServiceType="specialUse""""),
            )
            assertTrue(
                "a specialUse service declares what it is for with PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                declaration.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
            )
        }
    }

    /**
     * A boot start is a request Android can turn down, and a refusal has to be answered where someone can
     * read it - see [BootServiceStart].
     */
    @Test
    fun `every boot service answers a refused start in the shade`() {
        bootStartedServices().forEach { service ->
            val source = projectFile("src/main/java/dev/busung/s25uroot/$service.kt").readText()
            assertTrue(
                "$service can be refused at boot, so its start has to report that through BootServiceStart",
                source.contains("BootServiceStart.start("),
            )
        }
    }

    /** The services the boot broadcast starts, taken from the receiver rather than listed here. */
    private fun bootStartedServices(): List<String> = Regex("""(\w+Service)\.start\(""")
        .findAll(bootReceiverFile().readText())
        .map { match -> match.groupValues[1] }
        .distinct()
        .sorted()
        .toList()

    /**
     * The service element for [service], self-closing or not.
     *
     * Read as text rather than through the merged manifest, the same way the permission test reads it: what
     * this catches is two lines of one file disagreeing with each other.
     */
    private fun serviceDeclaration(manifest: String, service: String): String? {
        val opening = Regex("""<service\b[^>]*android:name="\.$service"[^>]*>""").find(manifest) ?: return null
        if (opening.value.trimEnd().endsWith("/>")) return opening.value
        val end = manifest.indexOf("</service>", opening.range.last + 1)
        return manifest.substring(opening.range.first, if (end < 0) manifest.length else end)
    }

    private fun manifestFile(): File = projectFile("src/main/AndroidManifest.xml")

    private fun bootReceiverFile(): File =
        projectFile("src/main/java/dev/busung/s25uroot/AutoRootBootReceiver.kt")

    /** A file of this module, from the module directory or from the repository root Gradle was run in. */
    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative"))
        .firstOrNull(File::isFile)
        ?: throw AssertionError("$relative was not found from ${File(".").absolutePath}")
}
