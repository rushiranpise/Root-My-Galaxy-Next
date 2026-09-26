package dev.busung.s25uroot.dfr

import dev.busung.s25uroot.HelperTint
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
    fun `the helper's flavour table is the app's flavour table`() {
        // All three fields and not only the package, because the helper now does three things with them:
        // it names the manager a person should look for, it opens it by package, and it is told which one
        // this run loads by the app's own id - so an id that drifted would be a manager row about no flavour
        // at all, and a label that drifted would be a button naming an app nobody has.
        //
        // Sorted by id, so a reordering on either side is not a failure: the app's order is what its rows
        // are drawn in, and the helper's is not a decision at all.
        assertEquals(
            "the helper's own table of the three flavours is not the app's, so one of the two would name " +
                "or open the wrong manager for a payload",
            appFlavors().sortedBy { it.first },
            helperFlavors().sortedBy { it.first },
        )
        assertEquals(
            "a flavour appears twice in one of the two tables, so a lookup by id or package would answer " +
                "with whichever came first",
            helperFlavors().map { it.first }.toSet().size,
            helperFlavors().size,
        )
    }

    @Test
    fun `the flavour the app sends is the flavour the helper reads`() {
        // The one fact about the payload the helper cannot work out for itself: three managers can be
        // installed at once and only the app knows which one this boot's kernel belongs to. An extra spelled
        // differently on the two sides is a helper that always falls back - which looks like a phone with no
        // manager rather than like a name that drifted.
        assertEquals(
            "the app sends the payload's flavour under an extra the helper does not read, so its manager " +
                "row would always fall back to guessing between the managers that are installed",
            DfrInstall.STAGE_TWO_FLAVOR_EXTRA,
            constantIn(stageTwoActivity(), "EXTRA_FLAVOR"),
        )
        assertTrue(
            "the helper declares the flavour extra but never asks the intent for it",
            stageTwoActivity().contains("getStringExtra(EXTRA_FLAVOR)"),
        )
        // The helper resolves the id through the same table the test above holds to the app's, so the
        // fallback for an id from a newer app is a reading with no flavour in it - not a guess.
        assertTrue(
            "the helper no longer resolves the flavour id it is given through its own table, so an unknown " +
                "id would be able to claim a manager",
            stageTwoActivity().contains("KsudStage.flavorOf("),
        )
    }

    @Test
    fun `the helper's manager action opens the manager of the flavour it was told`() {
        // The action the screen gained: a button under that reading, disabled when the manager is not there,
        // and pointed at the package of the flavour this run loads rather than at whichever one answers.
        val activity = stageTwoActivity()
        assertTrue(
            "the helper's manager reading has no action under it, so the one app a person needs next on " +
                "that screen is still only reachable from the launcher",
            activity.contains("openManagerButton"),
        )
        assertTrue(
            "opening a manager no longer goes through the platform's own launch intent, so the button " +
                "would open something of this APK's instead of another app",
            activity.contains("getLaunchIntentForPackage(target.packageName)"),
        )
        assertTrue(
            "the button's own name is no longer the manager it opens, so a screen told one flavour could " +
                "offer another",
            activity.contains("\"Open \${target.label} manager\""),
        )
        assertTrue(
            "the action is live whether or not that manager is installed, so it would be a button that " +
                "opens nothing",
            activity.contains("openManagerButton.isEnabled = installed"),
        )
    }

    @Test
    fun `the app's own id is the id the helper names back`() {
        // The helper's boot row offers the way to the setting that decides all of this, and the setting
        // is the app's - so the one name the two APKs share in that direction is this one.
        assertEquals(
            "the helper would open an application id the app is not built under, which is a button " +
                "that opens nothing",
            applicationIdIn(appBuildFile()),
            mainPackageIn(stageTwoActivity()),
        )
    }

    @Test
    fun `the extra the helper sets when root lands is the one the app reads`() {
        // The restart a finished run needs is KernelSU's own soft reboot, and the helper cannot ask for it -
        // that is the installed daemon run as root, and this helper is the system uid inside system_server.
        // So the load is inert until the app restarts, and the only thing carrying that request across is
        // this extra: spelled differently on the two sides, a run finished by hand would sit there loaded
        // and doing nothing, which is exactly the symptom it is meant to fix.
        assertEquals(
            "the helper tells the app that root has arrived under an extra the app does not read, so a " +
                "run finished by hand would leave the load inert until somebody restarted by hand",
            DfrInstall.STAGE_TWO_AFTER_ROOT_EXTRA,
            constantIn(stageTwoActivity(), "EXTRA_AFTER_ROOT"),
        )
        // Set and read, not merely named on both sides: an extra only one of the two spells is one this
        // test would pass on while the intent carried nothing.
        assertTrue(
            "the helper declares the extra but never sets it on the app's launch, so the request never " +
                "leaves the helper",
            stageTwoActivity().contains("putExtra(EXTRA_AFTER_ROOT, true)"),
        )
        assertTrue(
            "the app never reads the extra, so the helper's request would stop at the intent",
            mainActivity().contains("DfrInstall.STAGE_TWO_AFTER_ROOT_EXTRA"),
        )
        // And only a run a person asked for: the boot's own run is this app's gate, which is already
        // watching the kernel and would otherwise be raced by a second restart request.
        assertTrue(
            "the helper asks for the restart on an auto-run too, which is the boot's own path and the " +
                "app gate's to finish",
            stageTwoActivity().contains("if (success && !autorun)"),
        )
    }

    @Test
    fun `the restart the helper offers is the one the app already takes`() {
        // The helper cannot restart anything itself: a soft reboot is the installed daemon run as root, and
        // this APK is the system uid inside system_server, which the daemon hands no shell to. So the button
        // asks the app - and it asks with the app's *own* direct soft-restart action, the one its launcher
        // shortcut sends, rather than a name of the helper's own. What answers it is then the path that
        // already probes for a shell and holds the per-boot lock, and there is no second implementation of a
        // reboot to keep in step.
        assertEquals(
            "the helper asks for a restart under an action the app does not answer, so the button would " +
                "open the app and nothing would happen",
            actionSoftRestartInApp(),
            constantIn(stageTwoActivity(), "SOFT_RESTART_ACTION"),
        )
        assertTrue(
            "the helper declares the action but never sets it on the launch, so the request never leaves " +
                "this screen",
            stageTwoActivity().contains("launch.action = SOFT_RESTART_ACTION"),
        )
        assertTrue(
            "the app no longer answers the shortcut's soft-restart action, so nothing would take the request",
            mainActivity().contains("restartShortcutOf"),
        )
    }

    @Test
    fun `the app and the helper agree about the extras that start a run`() {
        assertEquals(
            "the app sets an extra the helper does not read, so a boot-time reroot would open the " +
                "screen and wait for a press nobody is there to make",
            DfrInstall.STAGE_TWO_AUTORUN_EXTRA,
            constantIn(stageTwoActivity(), "EXTRA_AUTORUN"),
        )
        assertEquals(
            "the app reports its reroot-at-boot setting under an extra the helper does not read, so " +
                "the helper's boot row would always answer that the app did not say",
            DfrInstall.STAGE_TWO_REROOT_EXTRA,
            constantIn(stageTwoActivity(), "EXTRA_REROOT_AT_BOOT"),
        )
        // Declared *and* read: an extra that is only named is one this test would pass on while the
        // helper ignored it, which is the failure the two assertions above exist to catch.
        assertTrue(
            "the helper declares the auto-run extra but never asks the intent for it",
            stageTwoActivity().contains("getBooleanExtra(EXTRA_AUTORUN"),
        )
    }

    @Test
    fun `the app asks for the run on the same command it already used to open the screen`() {
        // The two calls are one command with an extra, which is what makes the boot path the flow that
        // already works rather than a second way into the helper: no new component, no new permission.
        val plain = DfrInstall.launchCommand()
        val autorun = DfrInstall.launchCommand(autorun = true, rerootAtBoot = true)
        assertTrue("the plain launch no longer starts the helper", plain.startsWith("/system/bin/am start -n '"))
        assertEquals("opening the helper by hand now asks for a run", plain, DfrInstall.launchCommand(rerootAtBoot = null))
        assertTrue("the auto-run launch does not carry the extra", autorun.contains("--ez rmg.autorun true"))
        assertTrue("the boot setting is not carried", autorun.contains("--ez rmg.rerootAtBoot true"))
    }

    @Test
    fun `the helper's screen follows the phone's own theme`() {
        // The palette on that screen is resolved from the theme it is drawn in, so a theme that stops
        // following the phone's dark setting is a screen that says one thing to the phone and another to
        // everybody looking at it - and nothing else in this project would notice.
        assertEquals(
            "the helper's manifest names a theme that is not the helper's own",
            "@style/Theme.RmgHelper",
            themeIn(helperManifest()),
        )
        assertTrue(
            "the helper's theme no longer switches with the phone's light/dark setting, so its whole " +
                "palette is resolved against the wrong window",
            helperTheme().contains("@android:style/Theme.DeviceDefault.DayNight"),
        )
    }

    @Test
    fun `the window the app sends is the window the helper paints`() {
        // The helper's own theme is the platform's DeviceDefault - the OEM's palette, and `colorAccent` is
        // not this app's `primary` - so a helper that resolved everything from its theme was a different
        // app's screen next to this one. What fixes that is the app handing over the values it is drawing
        // with, which makes an extra spelled differently on the two sides silent: the helper would fall
        // back to its theme and simply look wrong again, with nothing failing.
        assertEquals(
            "the app sends the window's colours under an extra the helper does not read, so its screen " +
                "would go back to the OEM's palette without anything else noticing",
            HelperTint.EXTRA,
            constantIn(stageTwoActivity(), "EXTRA_TINT"),
        )
        assertEquals(
            "the launch and the theme disagree about the name, so the colours the theme computed never " +
                "reach a launch",
            DfrInstall.STAGE_TWO_TINT_EXTRA,
            HelperTint.EXTRA,
        )
        assertTrue(
            "the screen that opens the helper no longer hands over the colours it is drawn in, so the " +
                "helper opens in its own theme",
            source("app/src/main/java/dev/busung/s25uroot/DfrUi.kt").contains("tint = HelperTint.value"),
        )
        assertTrue(
            "the helper no longer prefers what it was handed, so a launch that did send the window would " +
                "be painted from the theme anyway",
            stageTwoActivity().contains("fromTheApp(\"accent\")"),
        )
    }

    @Test
    fun `the helper's version code is taken from the helper rather than written down`() {
        // What the app compares to decide whether the copy on the phone is the one it ships is this
        // number, so a literal here is a stale helper the app cannot see: the phone keeps the old copy,
        // Package Manager reports it as installed and healthy - it really is - and the run it starts
        // fails inside the exploit. Derived from the module's own sources, it changes when the helper does.
        assertTrue(
            "the helper's versionCode is not taken from the module's own sources, so nothing makes it " +
                "change when the helper does and the app's stale-helper check can never fire",
            helperBuildFile().contains("versionCode = helperVersionCode"),
        )
        assertTrue(
            "no digest of the helper's sources in its build file, so the number above cannot be " +
                "following them",
            helperBuildFile().contains("MessageDigest.getInstance(\"SHA-256\")"),
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
        assertTrue(appBuildFile().contains("applicationId"))
        assertTrue(stageTwoActivity().contains("class Stage2Activity"))
        assertTrue(mainActivity().contains("class MainActivity"))
        assertTrue(helperTheme().contains("Theme.RmgHelper"))
    }

    private fun helperBuildFile(): String = source("dfr/build.gradle.kts")

    private fun appManifest(): String = source("app/src/main/AndroidManifest.xml")

    private fun appBuildFile(): String = source("app/build.gradle.kts")

    private fun helperManifest(): String = source("dfr/src/main/AndroidManifest.xml")

    private fun flavourSource(): String = source("app/src/main/java/dev/busung/s25uroot/KernelSuFlavor.kt")

    private fun stageTwoSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt")

    private fun stageTwoActivity(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt")

    private fun mainActivity(): String = source("app/src/main/java/dev/busung/s25uroot/MainActivity.kt")

    private fun helperTheme(): String = source("dfr/src/main/res/values/themes.xml")

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

    /** The theme a manifest names for the whole application. */
    private fun themeIn(text: String): String =
        Regex("""android:theme="([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no android:theme in the manifest")

    /** The application id the helper names back, read from its screen's own constants. */
    private fun mainPackageIn(text: String): String = constantIn(text, "MAIN_PACKAGE")

    /**
     * One `const val NAME = "value"` out of a source file.
     *
     * Read as a constant rather than as the first quoted string in the file, because these values are
     * also spelled in the prose around them: a search that took the first match would pass on a constant
     * that had been emptied while its name stayed in a comment.
     */
    private fun constantIn(text: String, name: String): String =
        Regex("""const val $name = "([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no `const val $name` in the source")

    /** The resource directory a build file adds from the repository root, or an error naming the file. */
    private fun sharedIconDirectoryIn(text: String): String =
        Regex("""res\.srcDir\(rootProject\.file\("([^"]+)"\)\)""").find(text)?.groupValues?.get(1)
            ?: error("no rootProject res.srcDir in the module's build file")

    /**
     * The helper's flavour table, as (id, label, package) triples.
     *
     * Read from the `ManagerFlavor(...)` calls rather than from the block around them: the block is full of
     * prose that names both managers and projects, and a harvest of every quoted string in it would pass on
     * a table whose fields had been shuffled between entries.
     */
    private fun helperFlavors(): List<Triple<String, String, String>> {
        val triples = Regex(
            """ManagerFlavor\(id = "([^"]+)", label = "([^"]+)", packageName = "([^"]+)"\)""",
        ).findAll(stageTwoSource())
            .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        assertTrue("no flavour table in the stage two's source", triples.isNotEmpty())
        return triples
    }

    /**
     * The app's own flavour table, from the three fields each entry declares.
     *
     * Zipped rather than matched per entry, because the entries are enum constants with several fields and
     * only these three are quoted: each appears exactly once per entry, in the same order, which is what
     * makes the zip the same pairing the compiler sees. The two size checks are the guard - a field added
     * between them, or an entry that stopped declaring one, would otherwise shift every pairing by one and
     * make this test compare the wrong things quietly.
     */
    private fun appFlavors(): List<Triple<String, String, String>> {
        val text = flavourSource()
        val ids = quoted("""\bid = "([^"]+)""", text)
        val labels = quoted("""\blabel = "([^"]+)""", text)
        val packages = quoted("""\bmanagerPackage = "([^"]+)""", text)
        assertTrue(
            "the app's flavour table does not declare one id, label and package per entry " +
                "(${ids.size}/${labels.size}/${packages.size}), so this test is comparing the wrong fields",
            ids.size == labels.size && labels.size == packages.size && ids.isNotEmpty(),
        )
        return ids.zip(labels).zip(packages) { (id, label), packageName -> Triple(id, label, packageName) }
    }

    private fun quoted(pattern: String, text: String): List<String> =
        Regex(pattern).findAll(text).map { it.groupValues[1] }.toList()

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /**
     * The app's own soft-restart action, out of the file that defines it.
     *
     * Read rather than restated, because the literal is already written twice inside the app - once in this
     * file and once in the launcher's `shortcuts.xml` - and a third copy here would be a third thing to keep
     * in step with two APKs that share no code.
     */
    private fun actionSoftRestartInApp(): String =
        constantIn(source("app/src/main/java/dev/busung/s25uroot/RebootTargets.kt"), "ACTION_SOFT_RESTART")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))
}
