package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three directories the residue screen reads, and the rule that decides what may be deleted.
 *
 * The screen now shows everything this app leaves anywhere, so the names in it are the whole of what a
 * person is told. Two of those names live in another module's Kotlin - the daemon the system-uid helper
 * stages is compiled into `:dfr`, and the two files an inject leaves are named in the injector's own
 * constants - so the lists here are cross-checked against their sources rather than trusted.
 *
 * The third directory is the one with a rule instead of a list: `/data/adb` holds the installed daemon
 * and the user's modules, which is the root this app just obtained. It is listed and never deleted from,
 * and that is asserted here because the failure is a delete button somebody adds later.
 */
class ResidueScopesTest {

    @Test
    fun `the three scopes name the three directories, and only three`() {
        assertEquals(
            listOf(
                ResidueScope.SystemDirectory.path,
                ResidueScope.AdbDirectory.path,
            ),
            ResidueScopes.sections.map { it.path },
        )
        assertEquals(StagedResidue.DIRECTORY, ResidueScope.TempDirectory.path)
        assertEquals(SYSTEM_DIRECTORY, ResidueScope.SystemDirectory.path)
        assertEquals(ADB_DIRECTORY, ResidueScope.AdbDirectory.path)
    }

    @Test
    fun `KernelSU's own directory is listed and never deleted from`() {
        val adb = ResidueScope.AdbDirectory

        assertFalse(
            "a delete from /data/adb would remove the daemon the whole device's root runs through, or a " +
                "module the user installed",
            adb.deletable,
        )
        // And the rule is carried through rather than decided at the button: a section in a read-only
        // scope offers no path to delete, whatever it found.
        val section = ResidueSection(
            scope = adb,
            entries = listOf(
                TempEntry("ksud", present(4_892_712L), directory = ADB_DIRECTORY),
                TempEntry("modules", present(0L), isDirectory = true, directory = ADB_DIRECTORY),
            ),
        )
        assertTrue("nothing was found to make the point with", section.anything)
        assertEquals(emptyList<String>(), section.deletablePaths)
    }

    @Test
    fun `the daemon the helper stages is the path this list names`() {
        // The stage two is a second APK that shares no code with the app, so neither side can read the
        // other's constant. Its shellcode bind-mounts and execs the path the Kotlin stages, and this list
        // has to name that same path or the row would describe a file nothing writes.
        val stagedDaemon = constantIn(stageTwoSource(), "DEST")

        assertEquals(
            "the residue list names a daemon path the system-uid helper does not stage",
            stagedDaemon,
            SYSTEM_RESIDUE_PATHS.first { it.path == SYSTEM_STAGED_DAEMON }.path,
        )
    }

    @Test
    fun `the other install's files are listed rather than left out`() {
        // On any phone that ran DFReroot they are sitting in /data/system: a six-megabyte daemon and a
        // full copy of packages.xml from before it touched the file. A list that omitted them would read
        // as a clean directory beside a device that has them.
        val names = SYSTEM_RESIDUE_PATHS.map { it.path }

        assertTrue(names.contains(DFREROOT_STAGED_DAEMON))
        assertTrue(names.contains(DFREROOT_PACKAGES_BACKUP))
        // And they are marked as not this app's, which is what makes the row's own label honest.
        assertEquals(
            ResidueRole.OtherInstall,
            SYSTEM_RESIDUE_PATHS.first { it.path == DFREROOT_STAGED_DAEMON }.role,
        )
        // Nothing else in the list carries that role: everything else here is a file this project writes.
        assertEquals(
            listOf(DFREROOT_STAGED_DAEMON, DFREROOT_PACKAGES_BACKUP),
            SYSTEM_RESIDUE_PATHS.filter { it.role == ResidueRole.OtherInstall }.map { it.path },
        )
    }

    @Test
    fun `a section reports what it can delete and nothing else`() {
        val section = ResidueSection(
            scope = ResidueScope.SystemDirectory,
            named = listOf(
                ResidueFinding(StagedPath(SYSTEM_STAGED_DAEMON, ResidueRole.Daemon), present(6_014_920L)),
                ResidueFinding(StagedPath("$SYSTEM_DIRECTORY/packages.xml.bak-rmgnext", ResidueRole.Backup), absent()),
                ResidueFinding(StagedPath(DFREROOT_PACKAGES_BACKUP, ResidueRole.OtherInstall), unreadable()),
            ),
        )

        // Everything that is there, and everything this app could not look at - but not the path that is
        // absent: `rm -f` is silent about a path that is not there, and naming one would put a path in the
        // command whose "not found" comes back as a complaint about a file that nobody ever had.
        //
        // The unreadable path is in the list, and that is the rule worth stating: a file this app may not
        // stat is one it may still delete, because `rm` needs write permission on the directory rather
        // than read permission on the file - which is the exact situation `/data/system` puts it in.
        assertEquals(
            listOf(SYSTEM_STAGED_DAEMON, DFREROOT_PACKAGES_BACKUP),
            section.deletablePaths,
        )
        assertEquals(6_014_920L, section.totalBytes)
        // A path that could not be read is not a path that is not there: it is shown and described as
        // unreadable rather than left out, which is what keeps this section from reading as empty.
        assertTrue(section.anything)
        assertEquals(listOf(DFREROOT_PACKAGES_BACKUP), section.visibleNamed.map { it.staged.path } - SYSTEM_STAGED_DAEMON)
    }

    @Test
    fun `a closed folder's heading says what it holds in that folder's own words`() {
        // The line that makes closing a folder the right default: the heading has to answer "what is in
        // here" for the three together to be readable at a glance. The three empty cases stay distinct,
        // because "empty", "none of the paths this app writes is there" and "could not be listed" are
        // three different claims about one directory.
        val holds = ResidueSection(
            scope = ResidueScope.SystemDirectory,
            named = listOf(
                ResidueFinding(StagedPath(SYSTEM_STAGED_DAEMON, ResidueRole.Daemon), present(6_014_920L)),
                ResidueFinding(StagedPath("$SYSTEM_DIRECTORY/packages.xml.bak-rmgnext", ResidueRole.Backup), absent()),
            ),
        )
        assertEquals(R.string.residue_folder_tally, holds.folderSummary().res)
        assertEquals(1, holds.folderSummary().args[0])
        assertEquals(StagedResidue.sizeLabel(6_014_920L), holds.folderSummary().args[1])

        // Everything found counts, an unreadable entry included: it is a fact about the device, and a
        // folder that counted only what it could describe would call `/data/adb` empty.
        val unreadable = ResidueSection(
            scope = ResidueScope.AdbDirectory,
            entries = listOf(TempEntry("ksud", unreadable(), directory = ADB_DIRECTORY)),
        )
        assertEquals(R.string.residue_folder_tally, unreadable.folderSummary().res)
        assertEquals(1, unreadable.folderSummary().args[0])

        // Listed and empty is the strong claim, read by name and clean is the weaker one, and a
        // directory that could not be listed says neither.
        assertEquals(
            R.string.residue_scope_clean_listed,
            ResidueSection(scope = ResidueScope.AdbDirectory).folderSummary().res,
        )
        // A catalogue read without a shell, where nothing in it is there: the same section that found
        // nothing, said by name rather than as an empty directory.
        val byName = ResidueSection(
            scope = ResidueScope.SystemDirectory,
            named = listOf(
                ResidueFinding(StagedPath(SYSTEM_STAGED_DAEMON, ResidueRole.Daemon), absent()),
                ResidueFinding(StagedPath("$SYSTEM_DIRECTORY/packages.xml.bak-rmgnext", ResidueRole.Backup), absent()),
            ),
        )
        assertEquals(R.string.residue_scope_clean, byName.folderSummary().res)
        assertEquals(byName.named.size, byName.folderSummary().args[0])
        assertEquals(
            R.string.residue_scope_unlisted,
            ResidueSection(scope = ResidueScope.AdbDirectory, listed = false).folderSummary().res,
        )
    }

    @Test
    fun `the temp folder's heading counts both halves of its reading`() {
        // This folder is a catalogue and a listing at once, so its heading is the one place the two
        // numbers have to be added up - and the count is what somebody sees before deciding whether to
        // open it at all.
        val report = ResidueReport(
            findings = listOf(
                ResidueFinding(
                    StagedPath("${StagedResidue.DIRECTORY}/ksu-payload", ResidueRole.Payload),
                    present(1_048_576L),
                ),
            ),
            directoryVisible = true,
            extras = listOf(TempEntry("someone-elses.bin", present(2_097_152L))),
            directoryListed = true,
        )
        assertEquals(R.string.residue_folder_tally, report.folderSummary().res)
        assertEquals(2, report.folderSummary().args[0])
        assertEquals(StagedResidue.sizeLabel(report.totalBytes), report.folderSummary().args[1])

        // A temp directory read without a shell can only say its own paths are absent, and one that was
        // listed can say it is empty; the two sentences differ because the claims do.
        val cleanCatalogue = listOf(
            ResidueFinding(
                StagedPath("${StagedResidue.DIRECTORY}/ksu-payload", ResidueRole.Payload),
                absent(),
            ),
        )
        val byName = ResidueReport(findings = cleanCatalogue, directoryVisible = true)
        assertEquals(R.string.residue_scope_clean, byName.folderSummary().res)
        assertEquals(1, byName.folderSummary().args[0])
        val listed = ResidueReport(findings = cleanCatalogue, directoryVisible = true, directoryListed = true)
        assertEquals(R.string.residue_scope_clean_listed, listed.folderSummary().res)
        assertTrue(listed.folderSummary().args.isEmpty())
    }

    @Test
    fun `the temp directory is not read as a section, because it has its own report`() {
        // Five verdicts - clean, clean by name, blind, and the two two-part cases - live in [ResidueReport]
        // and would be thrown away by flattening it into a section beside these two.
        val failure = runCatching { ResidueScopes.read(ResidueScope.TempDirectory) }.exceptionOrNull()

        assertTrue("the temp directory is read twice, and the two readings can disagree", failure != null)
    }

    @Test
    fun `both sources were really read`() {
        assertTrue(stageTwoSource().contains("object KsudStage"))
        assertTrue(stageTwoSource().contains("const val DEST"))
    }

    private fun stageTwoSource(): String = source(
        "dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt",
    )

    private fun source(relative: String): String =
        candidates(relative).firstOrNull { it.isFile }?.readText()
            ?: error("none of ${candidates(relative)} exists, so this test read nothing")

    /** The module directory and the repository root: the test JVM's working directory is one of them. */
    private fun candidates(relative: String): List<File> = listOf(File(relative), File("../$relative"))

    /** The value of a `const val NAME = "..."` in Kotlin source. */
    private fun constantIn(text: String, name: String): String =
        Regex("""const val $name = "([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no `const val $name = \"...\"` in the source")

    private fun present(bytes: Long) = ResidueReading.Present(bytes, modifiedAtMillis = 1_700_000_000_000L)

    private fun absent() = ResidueReading.Gone

    private fun unreadable() = ResidueReading.Unreadable
}
