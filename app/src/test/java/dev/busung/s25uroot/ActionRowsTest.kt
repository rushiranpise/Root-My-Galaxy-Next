package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout rule every dialog in this app now shares, held still.
 *
 * The rule is arithmetic, not taste - it decides how many answers go in a row and nothing else - so it is
 * the part of the design that can be tested without a screen, and the part that a later dialog would
 * quietly break by hand-laying its own rows. Two properties are what make it a rule rather than a packing
 * function: no row holds more than three answers, and no row is left with a single one stretched beside a
 * full row, because one button at the dialog's width under three at a third of it reads as a different
 * size of answer.
 *
 * The order of the answers is deliberately not this file's business: which one is filled is
 * [AppActionRole], which is the caller's decision about what it recommends.
 */
class ActionRowsTest {

    @Test
    fun `a set that fits in one row keeps one row`() {
        assertEquals(listOf(1), actionRowSizes(1))
        assertEquals(listOf(2), actionRowSizes(2))
        assertEquals(listOf(3), actionRowSizes(3))
    }

    @Test
    fun `four answers are two and two, not three and one`() {
        // The case the rule exists for: packing would put three in the first row and leave the fourth
        // stretched across the whole dialog underneath them.
        assertEquals(listOf(2, 2), actionRowSizes(4))
    }

    @Test
    fun `the rows of a set are as even as they can be`() {
        // Every row is the first row's size or one less, which is what "balanced and not packed" means on
        // a phone: five answers are three and two, seven are three, two and two.
        assertEquals(listOf(3, 2), actionRowSizes(5))
        assertEquals(listOf(3, 3), actionRowSizes(6))
        assertEquals(listOf(3, 2, 2), actionRowSizes(7))
        assertEquals(listOf(3, 3, 2, 2), actionRowSizes(10))
    }

    @Test
    fun `no row holds more than three answers`() {
        for (count in 1..12) {
            val sizes = actionRowSizes(count)
            assertTrue("$count answers in rows of $sizes", sizes.all { it in 1..3 })
        }
    }

    @Test
    fun `no row is left with one answer beside a full one`() {
        for (count in 1..12) {
            val sizes = actionRowSizes(count)
            assertTrue(
                "$count answers in rows of $sizes leaves a lone answer in a row",
                sizes.all { it == sizes.first() || it == sizes.first() - 1 },
            )
        }
    }

    @Test
    fun `the rows hold exactly the answers there are`() {
        for (count in 1..20) {
            assertEquals("$count", count, actionRowSizes(count).sum())
        }
    }

    @Test
    fun `a set with nothing in it has no rows`() {
        assertEquals(emptyList<Int>(), actionRowSizes(0))
        assertEquals(emptyList<Int>(), actionRowSizes(-1))
    }

    @Test
    fun `rows keep the answers in the order they were given`() {
        // The order is the caller's point - the recommended answer is first because the caller said so -
        // so the rows are a slicing of that order and nothing else.
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), actionRows(listOf(1, 2, 3, 4)))
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), actionRows(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `an answer is not working unless it says so`() {
        // The slot has to be off by default, or every dialog that already exists grows a spinner it never
        // asked for - and "is it working" is a claim only the caller can make.
        val quiet = AppAction(R.string.action_cancel) {}
        assertFalse(quiet.progress)
        assertTrue(quiet.enabled)
        assertEquals(AppActionRole.Standard, quiet.role)
    }

    @Test
    fun `an answer that is working is still one answer in the same row`() {
        // The spinner is per answer, not per row: the set a screen asks with keeps its shape while one of
        // its answers is in flight, so the remaining answers do not jump to a new place mid-press.
        val actions = listOf(
            AppAction(R.string.action_cancel, progress = false) {},
            AppAction(R.string.action_cancel, role = AppActionRole.Priority, progress = true) {},
            AppAction(R.string.action_cancel) {},
            AppAction(R.string.action_cancel) {},
        )
        assertEquals(listOf(2, 2), actionRows(actions).map { it.size })
        assertEquals(listOf(false, true, false, false), actionRows(actions).flatten().map { it.progress })
    }

    @Test
    fun `the shared button draws the spinner only for an answer that is working`() {
        val source = source("DialogActions.kt")
        assertTrue(
            "the spinner is drawn unconditionally, so every answer in the app is busy",
            source.contains("if (action.progress)"),
        )
        assertTrue("no spinner in the shared answer button", source.contains("LoadingIndicator("))
    }

    @Test
    fun `no screen builds its own busy state`() {
        // The shape this replaces, in every screen that had a slow press: a button that swapped its own
        // label - or the icon beside it - for a spinner while it worked. It is one statement written out
        // by hand everywhere it was needed, which is how the two answers in this app that could say "I am
        // working" came to be the two that were drawn by hand. [AppAction.progress] is that statement now,
        // so what must not exist is a spinner inside one of the platform's own buttons.
        //
        // Read by indentation rather than by parsing the file: a spinner's line is inside a call, and the
        // call it belongs to is the first line above it that is indented less - see [ownerCall]. The shared
        // button's own file is not read, because a spinner inside a button is exactly what it is.
        val handBuilt =
            """(?:^|\s)(?:Button|TextButton|FilledTonalButton|FilledButton|OutlinedButton)\(""".toRegex()
        val screens = screens()
        // Several spinners are read, and every one of them has to be somewhere this does not flag - a
        // status row, a list heading, a panel waiting on a read. A file that failed to read would find no
        // offenders and prove nothing, so the read is checked first.
        val seen = screens.sumOf { file ->
            file.readLines().count { it.contains("LoadingIndicator(") }
        }
        assertTrue("no spinner was read, so this proves nothing", seen >= 4)
        val offenders = screens.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { index, line ->
                if (!line.contains("LoadingIndicator(")) return@mapIndexedNotNull null
                val owner = ownerCall(lines, index)
                val call = owner?.let { lines[it].trim() }.orEmpty()
                if (handBuilt.containsMatchIn(call)) "${file.name}:${index + 1} $call" else null
            }
        }
        assertTrue("a screen draws its own spinner in a button - $offenders", offenders.isEmpty())
    }

    /**
     * The call a line's body belongs to: the nearest line above it that is indented less, and that is not
     * only the punctuation of an argument list.
     *
     * Indentation is the block structure here. The parenthesis-only lines are the reason this cannot simply
     * take the first shallower line: a multi-line call opens its trailing lambda with a `) {` of its own, so
     * stopping at that would name the button's own lambda as the call whose body the line is in. A block
     * that opens with a line of its own is passed through for the same reason - the `if` a busy button
     * draws its spinner under is not the call either.
     */
    private fun ownerCall(lines: List<String>, index: Int): Int? {
        var indent = lines[index].takeWhile { it == ' ' }.length
        for (above in index - 1 downTo 0) {
            val line = lines[above]
            val aboveIndent = line.takeWhile { it == ' ' }.length
            if (aboveIndent >= indent || isPunctuationOnly(line.trim())) continue
            // "Names a call" and "opens a block" are one check, because the call this body belongs to is
            // the first shallower line that is not itself a block opener.
            if (!line.trim().endsWith("{")) return above
            indent = aboveIndent
        }
        return null
    }

    /** Whether a line holds only the brackets of a call, and nothing that names one. */
    private fun isPunctuationOnly(trimmed: String): Boolean =
        trimmed.replace("else", "").none { !it.isWhitespace() && it !in "()}{," }

    @Test
    fun `the shizuku start prompt asks with the shared set instead of its own button`() {
        // The special case the slot exists for: this dialog built its own pressable answer, spinner and
        // all, which is how one screen came to have buttons that did not match the other twenty.
        val body = bodyOf(source("InstallActivity.kt"), "private fun ShizukuHoldDialog")
        assertTrue("the prompt does not ask with the shared set", body.contains("AppDialogActions("))
        assertTrue("the prompt does not mark the answer it is waiting on", body.contains("progress = prompt.starting"))
        assertFalse("the prompt still hand-builds an answer", body.contains("TextButton"))
    }

    @Test
    fun `every answer lands in exactly one row`() {
        // The bug this catches is an off-by-one in the slicing, which shows up as a dropped answer - and a
        // dropped answer here is a button that is never drawn, not a layout that looks wrong.
        val answers = (1..11).toList()
        assertEquals(answers, actionRows(answers).flatten())
    }

    @Test
    fun `no screen draws a labelled action button of its own`() {
        // The rule the vocabulary exists for, taken to the whole app rather than to the dialogs: an action
        // that carries words is [AppActionButton] - a filled answer - or [AppTextAction], the link shape for
        // one drawn inside a card or a form. A screen that draws its own is how the same action came to be a
        // platform button at the platform's padding and label size on one screen and the shared pill on the
        // next, which is what a run's Stop and a dialog's Cancel used to be.
        //
        // One screen is deliberately allowed its own: the retry dialog's answers carry a second line saying
        // what each one buys, and a taller left-aligned answer is not what [AppActionButton] draws. Its
        // three tiers are built in [RetryOption], wearing the shared fills - see the test below.
        val handBuilt =
            """(?:^|[^\w.])(Button|TextButton|FilledTonalButton|FilledButton|OutlinedButton|ElevatedButton)\("""
                .toRegex()
        val files = packageFiles()
        assertTrue("no source was read, so this proves nothing", files.size >= 20)
        val offenders = files
            .filter { file -> file.name != "DialogActions.kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.mapIndexedNotNull { index, line ->
                    if (line.trimStart().startsWith("import ")) return@mapIndexedNotNull null
                    if (!handBuilt.containsMatchIn(line)) return@mapIndexedNotNull null
                    if (index in retryOptionBody(lines)) return@mapIndexedNotNull null
                    "${file.name}:${index + 1} ${line.trim()}"
                }
            }
        assertTrue("a screen draws its own action button - $offenders", offenders.isEmpty())
    }

    @Test
    fun `an action's fills are spelled out once`() {
        // What the shared roles are for. A screen that writes out `surfaceContainerHighest` for its own
        // second-tier answer is a screen that keeps that shade after the rest of the app has moved on -
        // which is exactly what the retry dialog had done, in a comment claiming to match.
        val spellers = packageFiles()
            .filter { file -> file.name != "DialogActions.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.contains("ButtonDefaults.buttonColors(") }
                    .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
            }
        assertTrue("a screen spells an action's fill out instead of wearing the shared one - $spellers", spellers.isEmpty())
    }

    @Test
    fun `no screen in the app lays its own answers out`() {
        // The rule this pins is the reason the shared set exists: every question in this app is asked with
        // it, and the way that stops being true is one new dialog - a Row of buttons by hand, which is how
        // the same question came to look like six different questions in the first place. Checked by
        // reading the line after each confirm slot, so the slot's own comment can be anything.
        // The slot's whole body, not just its first statement: a slot is allowed to work out which
        // answers it has before it passes them - the DFR clean-up builds its list from the reading - and
        // what must never be there is a button of its own.
        val handBuilt = """\b(TextButton|FilledTonalButton|FilledButton|OutlinedButton)\(""".toRegex()
        val screens = screens()
        // Every assertion below passes on an empty file list, so the read is checked first.
        assertTrue("no screen was read, so this proves nothing", screens.size >= 5)
        val offenders = screens.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { index, line ->
                if (!line.contains("confirmButton = {")) return@mapIndexedNotNull null
                val body = slotBody(lines, index)
                val problem = when {
                    !body.contains("AppDialogActions(") -> "does not ask with the shared set"
                    handBuilt.containsMatchIn(body) -> "builds a button of its own"
                    else -> null
                }
                problem?.let { "${file.name}:${index + 1} $it" }
            }
        }
        assertTrue("a dialog lays its own answers out - $offenders", offenders.isEmpty())
    }

    /** A slot's body: from the line after it to its own closing brace, or the one line it was written on. */
    private fun slotBody(lines: List<String>, startIndex: Int): String {
        val line = lines[startIndex]
        if (line.endsWith("},") && line.indexOf('}') > line.indexOf('{')) {
            return line.substringAfter('{').substringBeforeLast('}')
        }
        val indent = line.takeWhile { it == ' ' }.length
        val body = StringBuilder()
        for (index in startIndex + 1 until lines.size) {
            val next = lines[index]
            if (next.takeWhile { it == ' ' }.length == indent && next.trim() == "},") break
            body.appendLine(next)
        }
        return body.toString()
    }

    @Test
    fun `no dialog answers from a second slot`() {
        // Every set carries its own dismissal answer, so a dismiss slot is a dialog whose answers are
        // split across two places again - which is the shape that orphaned Cancel on its own line under
        // the buttons it belongs beside.
        val offenders = screens()
            .filter { file -> file.readText().contains("dismissButton = {") }
            .map { it.name }
        assertTrue("a dialog answers from the dismiss slot: $offenders", offenders.isEmpty())
    }

    /**
     * The lines of [RetryOption]'s body, as a range, because that is the one screen allowed its own buttons.
     *
     * Read from wherever the file being scanned is: a file without the function has no such range, which is
     * how every other source is held to the rule.
     */
    private fun retryOptionBody(lines: List<String>): IntRange {
        val start = lines.indexOfFirst { it.startsWith("internal fun RetryOption(") }
        if (start < 0) return IntRange.EMPTY
        val end = (start + 1 until lines.size).firstOrNull { lines[it] == "}" } ?: lines.size
        return start..end
    }

    /** Every source of this package, whether or not it asks a question. */
    private fun packageFiles(): List<File> = listOf(
        File("src/main/java/dev/busung/s25uroot"),
        File("app/src/main/java/dev/busung/s25uroot"),
    ).firstOrNull(File::isDirectory)
        ?.walkTopDown()
        ?.filter { it.isFile && it.extension == "kt" }
        ?.toList()
        ?: error("the source directory was not found from ${File(".").absolutePath}")

    /** One of this package's sources, wherever the test JVM was started from. */
    private fun source(fileName: String): String =
        listOf(
            File("src/main/java/dev/busung/s25uroot/$fileName"),
            File("app/src/main/java/dev/busung/s25uroot/$fileName"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("$fileName was not found from ${File(".").absolutePath}")

    /** Every screen of this app: the composables that ask a question are all in this package. */
    private fun screens(): List<File> {
        val directory = listOf(
            File("src/main/java/dev/busung/s25uroot"),
            File("app/src/main/java/dev/busung/s25uroot"),
        ).firstOrNull(File::isDirectory)
            ?: error("the source directory was not found from ${File(".").absolutePath}")
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("confirmButton") }
            .toList()
    }

    /**
     * One declaration's body, up to the next declaration at the same indentation.
     *
     * Scoped rather than searched file-wide because the file holds a dozen of these: "does the prompt
     * still build its own button" has to be asked of this prompt, not of some other screen's.
     */
    private fun bodyOf(text: String, declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue("no `$declaration` in the source", start >= 0)
        val next = text.indexOf("\nprivate fun ", start + declaration.length)
        return text.substring(start, if (next < 0) text.length else next)
    }
}
