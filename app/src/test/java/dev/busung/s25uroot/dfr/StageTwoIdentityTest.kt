package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage two's identity, which is one value in four places and none of them can see the others.
 *
 * The helper is its own APK with its own application id, and the app names it by string to three
 * different tools - `pm list packages`, `pm install`, `am start` - plus the workflow that publishes it.
 * Nothing at build time compares those, so a rename on one side is an install that lands under a name
 * nothing looks for, or an `am start` that names a package that is not there. Both failures read as
 * "the helper is broken" rather than as "the two names drifted", which is why they are held together
 * here, by reading the sources.
 *
 * The daemon list is the same shape one level down: this module shares no code with the app, so the
 * manager packages it reads a `libksud.so` from are a second copy of the app's own flavour table. A
 * project renaming its manager package would leave that copy naming an app that no longer exists, and a
 * manager that is installed but invisible is exactly the state this list exists to end.
 */
class StageTwoIdentityTest {

    /** Every file the shared directory has to carry, held to here so moving one cannot pass quietly. */
    private val ICON_FILES = listOf(
        "launcher-icon/mipmap-anydpi/ic_launcher.xml",
        "launcher-icon/mipmap-anydpi-v26/ic_launcher.xml",
        "launcher-icon/mipmap-anydpi-v33/ic_launcher.xml",
        "launcher-icon/drawable/ic_launcher_foreground.xml",
        "launcher-icon/values/colors.xml",
    )

    @Test
    fun `the id the app installs is the id the helper is built under`() {
        assertEquals(
            "the app runs `pm install` and `am start` against one id while the helper APK is built " +
                "with another, so the flow would install nothing and start nothing",
            DfrInstall.STAGE_TWO_PACKAGE,
            applicationIdIn(helperBuildFile()),
        )
    }

    @Test
    fun `the helper is named as this project's helper wherever a user sees it`() {
        assertEquals(
            "the label a launcher and the installer show is not this project's name for the helper",
            "RMG-NEXT helper",
            labelIn(helperManifest()),
        )
    }

    @Test
    fun `the daemon the helper reads comes from the manager packages the app knows`() {
        // As sets and not as lists: the helper's order is its own business - the app's table is what
        // decides which manager a flavour installs - but a package missing from either side is a
        // flavour whose manager would never be found.
        val flavourPackages = Regex("""managerPackage = "([^"]+)"""")
            .findAll(flavourSource())
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "the helper looks for a manager under a different package than the app installs, so that " +
                "flavour's daemon would be unfindable on a phone that has it",
            flavourPackages,
            daemonPackagesIn(stageTwoSource()),
        )
    }

    @Test
    fun `the helper draws the same icon as the app`() {
        // The two APKs are one product and a launcher shows them together, so the icon is one set of
        // files. A manifest naming an icon the module cannot resolve is a build failure rather than a
        // drift, so the second assertion is the one that matters: a module reading its own private copy
        // is an icon that changes on one side only.
        assertEquals(
            "the app and the helper name different icons, so one of the two draws something else",
            iconIn(appManifest()),
            iconIn(helperManifest()),
        )
        assertEquals(
            "the app and the helper name the same icon but do not read it from the same directory, " +
                "so one of them has a copy of its own",
            sharedIconDirectoryIn(appBuildFile()),
            sharedIconDirectoryIn(helperBuildFile()),
        )
        assertTrue(
            "the shared directory holds no launcher icon, so both manifests resolve one from " +
                "somewhere else",
            ICON_FILES.all { File(it).isFile || File("../$it").isFile },
        )
    }

    @Test
    fun `every source was really read`() {
        // Each assertion above passes on an empty collection, so a moved file would turn this class
        // green. The markers are the definitions the values are read out of.
        assertTrue(helperBuildFile().contains("applicationId"))
        assertTrue(helperManifest().contains("android:label"))
        assertTrue(flavourSource().contains("enum class KernelSuFlavor"))
        assertTrue(stageTwoSource().contains("object KsudStage"))
        assertTrue(appManifest().contains("android:icon"))
        assertTrue(appBuildFile().contains("res.srcDir"))
    }

    private fun helperBuildFile(): String = source("dfr/build.gradle.kts")

    private fun appManifest(): String = source("app/src/main/AndroidManifest.xml")

    private fun appBuildFile(): String = source("app/build.gradle.kts")

    private fun helperManifest(): String = source("dfr/src/main/AndroidManifest.xml")

    private fun flavourSource(): String = source("app/src/main/java/dev/busung/s25uroot/KernelSuFlavor.kt")

    private fun stageTwoSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt")

    private fun applicationIdIn(text: String): String =
        Regex("""applicationId = "([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no applicationId in the helper's build file")

    private fun labelIn(text: String): String =
        Regex("""android:label="([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no android:label in the helper's manifest")

    /** The drawable a manifest names as the app's icon, which is the whole of what a launcher draws. */
    private fun iconIn(text: String): String =
        Regex("""android:icon="([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no android:icon in the manifest")

    /** The resource directory a build file adds from the repository root, or an error naming the file. */
    private fun sharedIconDirectoryIn(text: String): String =
        Regex("""res\.srcDir\(rootProject\.file\("([^"]+)"\)\)""").find(text)?.groupValues?.get(1)
            ?: error("no rootProject res.srcDir in the module's build file")

    /**
     * The quoted names inside `MANAGER_PACKAGES`, read from the block rather than from the whole file:
     * the surrounding prose names packages too, and a search over the whole file would pick those up and
     * make this test pass on a list that had been emptied.
     */
    private fun daemonPackagesIn(text: String): Set<String> {
        val start = text.indexOf("MANAGER_PACKAGES = listOf(")
        assertTrue("no MANAGER_PACKAGES list in the stage two's source", start >= 0)
        val block = text.substring(start, text.indexOf(")", start + 1).takeIf { it > 0 } ?: text.length)
        return Regex(""""([^"]+)"""").findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
