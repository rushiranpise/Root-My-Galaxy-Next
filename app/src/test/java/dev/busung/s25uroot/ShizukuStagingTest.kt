package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three commands a staging is made of, and the one property they all need: an ending.
 *
 * The upload, the exit and the publish are each a wait on a process rather than on the phone, and all three
 * sit in front of a payload. A `cat` nobody is draining blocks on the pipe rather than on anything the
 * transport could read, so the failure this guards against is not an error but a silence: a run that has
 * stopped with no line in the log and no state to read. The staging itself needs a device, so the shape
 * that makes it endable is held here, against the source.
 */
class ShizukuStagingTest {

    private val quotedTemp = shellQuote("/data/local/tmp/payload.so.shizuku-abc.tmp")
    private val quotedPath = shellQuote("/data/local/tmp/payload.so")

    @Test
    fun theUploadNeverNamesTheDestination() {
        // The bug this replaced: `cat > <destination>` truncated the file the app was about to
        // execute, so an upload that died halfway left a partial payload where a good one had been
        // and the cache ran it. Nothing before publishing may mention the destination.
        val upload = uploadCommand(quotedTemp)

        assertFalse(upload.contains(quotedPath))
        assertTrue(upload.contains("cat > $quotedTemp"))
        assertTrue(upload.contains("rm -f $quotedTemp"))
    }

    @Test
    fun publishingRefusesAShortFileBeforeMovingIt() {
        val script = publishCommand(quotedPath, quotedTemp, "755", expectedBytes = 4096)

        // The size is checked against what the local side actually sent, so a truncated transfer is
        // caught even when the manifest declared size was wrong.
        assertTrue(script.contains("actual=$(/system/bin/wc -c < $quotedTemp)"))
        assertTrue(script.contains("-ne 4096"))
        assertTrue(script.contains("staged size mismatch"))
        // And it fails before the move, so the destination keeps whatever it had.
        assertTrue(script.indexOf("exit 1") < script.indexOf("mv -f"))
        assertTrue(script.contains("chmod 755 $quotedTemp"))
        assertTrue(script.contains("mv -f $quotedTemp $quotedPath"))
    }

    @Test
    fun publishingCleansUpAfterItselfOnEveryExit() {
        val script = publishCommand(quotedPath, quotedTemp, "644", expectedBytes = 1)

        // A temp file left behind in /data/local/tmp is one more file the next run has to reason
        // about, and the trap is what covers the failure paths that exit before the move.
        assertTrue(script.contains("trap 'rm -f $quotedTemp' EXIT HUP INT TERM"))
        assertTrue(script.startsWith("set -e"))
    }

    @Test
    fun aPathIsQuotedSoTheRemoteShellCannotInterpretIt() {
        assertEquals("'/data/local/tmp/payload.so'", shellQuote("/data/local/tmp/payload.so"))
        assertEquals("'/data/local/tmp/a b.so'", shellQuote("/data/local/tmp/a b.so"))
        // A quote inside the value closes the quoted string, escapes, and reopens it.
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        // Interpolation characters reach the shell as data, not as syntax.
        assertEquals("'\$(reboot)'", shellQuote("\$(reboot)"))
    }

    @Test
    fun aStagingStepBoundedIsAStepTheRunGetsPast() {
        val source = transportSource()
        val body = declaration(source, "fun writeFile(")

        // All three steps take the window: the copy, the exit that follows it, and the publish at the end.
        assertTrue(
            "the copy is back to running inline, where a pipe the far side has stopped draining blocks the " +
                "caller with nothing to time out: $body",
            body.contains("copyInto("),
        )
        assertTrue(
            "the upload's exit is waited on with no window again, so a `cat` that never finishes is a run " +
                "that never continues: $body",
            body.contains("awaitExit("),
        )
        assertTrue(
            "the publish is sent without the window the other two steps were given",
            body.contains("runShell(publishCommand(") && body.contains("timeoutMillis)"),
        )
        assertFalse(
            "the upload is waited on unbounded again, which is the wait this argument exists to end",
            body.contains("upload.waitFor()"),
        )
    }

    @Test
    fun theCopyWaitsOnItsOwnThreadSoTheWindowCanEndIt() {
        // A blocking `copyTo` on the caller's thread cannot be timed out from the outside: the process can be
        // destroyed, but nothing is left to notice. The copy goes on a thread of its own for the same reason
        // the command reader does, and is abandoned rather than joined when it runs out of its window.
        val body = declaration(transportSource(), "private fun copyInto(")

        assertTrue(
            "the copy is joined without a window, so a stalled transfer is waited on instead of ended: $body",
            body.contains("writer.join(timeoutMillis)"),
        )
        assertTrue(
            "a copy that ran out of its window is not abandoned, so the run is left holding the thread that " +
                "is holding it: $body",
            body.contains("if (writer.isAlive)") && body.contains("destroyForcibly()"),
        )
        assertTrue(
            "the writer is not a daemon, so an unclosable pipe could hold the app open after the run gave up " +
                "on it",
            body.contains("isDaemon = true"),
        )
    }

    @Test
    fun aCommandThatRunsOutOfItsWindowIsARouteThatDidNotRunIt() {
        // The same answer SuShell gives about a `su` that never answered, which is what lets a caller read
        // either route without knowing which one spoke: null is "this route did not run it", where "it ran
        // and failed" would send a run down a repair path for a command that never ran.
        val body = declaration(transportSource(), "fun shell(")

        assertTrue(
            "the command's window is no longer applied to the wait, so the argument is documentation: $body",
            body.contains("waitFor(timeoutMillis, TimeUnit.MILLISECONDS)"),
        )
        assertTrue(
            "a command that ran out of its window no longer answers as a route that did not run it: $body",
            body.substringAfter("if (!answered)").contains("return null"),
        )
    }

    @Test
    fun anIncompleteReadIsARouteThatDidNotRunIt() {
        // The process being over is not the answer being complete. A reader still draining when its grace
        // runs out has not read the whole of what the command said, and the part that arrived is worse than
        // nothing: a caller greps it for a fact and reads its absence as the fact being absent, when the
        // route may have said yes. So it is refused rather than reported - the same null the window above
        // gives, said in the same place a caller already reads.
        val body = declaration(transportSource(), "fun shell(")

        assertTrue(
            "the reader is joined without a grace, so a stalled read is waited on instead of ended: $body",
            body.contains("reader.join(READER_GRACE_MILLIS)"),
        )
        val afterTheJoin = body.substringAfter("reader.join(READER_GRACE_MILLIS)")
            .substringBefore("exitValue()")
        assertTrue(
            "a reader still running after its grace is not asked about, so a partial read is reported as " +
                "the command's whole answer: $body",
            afterTheJoin.contains("reader.isAlive"),
        )
        assertTrue(
            "a reader still running after its grace no longer answers as a route that did not run it: $body",
            afterTheJoin.contains("return null"),
        )
        assertTrue(
            "the incomplete read is not logged the way the window's own ending is: $body",
            afterTheJoin.contains("AppLog.warn"),
        )
    }

    @Test
    fun anIncompleteSuReadIsARouteThatDidNotRunIt() {
        // The same refusal the Shizuku route makes, and for the same reason: `su` coming back is not its
        // output being read. A reader still draining when its grace runs out has not read the whole of what
        // the command said, and the part that arrived is worse than nothing - a caller greps it for a fact
        // and reads its absence as the fact being absent, when the route may have said yes. The two routes
        // have to answer the same way, because a caller picks between them and reads whichever spoke.
        val body = declaration(suShellSource(), "fun run(")

        assertTrue(
            "the reader is joined without a grace, so a stalled read is waited on instead of ended: $body",
            body.contains("reader.join(READER_GRACE_MILLIS)"),
        )
        // Anchored on the last join, the one on the answered path: the join inside the no-answer branch is
        // followed by a return of its own, so a reading taken after that would pass with no refusal here.
        val afterTheJoin = body.substringAfterLast("reader.join(READER_GRACE_MILLIS)")
            .substringBefore("exitValue()")
        assertTrue(
            "a reader still running after its grace is not asked about, so `su`'s partial read is " +
                "reported as the command's whole answer: $body",
            afterTheJoin.contains("reader.isAlive"),
        )
        assertTrue(
            "a reader still running after its grace no longer answers as a route that did not run it: $body",
            afterTheJoin.contains("return null"),
        )
        assertTrue(
            "the incomplete read is not logged, so a refused route reads as a silent one: $body",
            afterTheJoin.contains("Log.w("),
        )
    }

    @Test
    fun theWindowIsAskedOfTheRemoteRatherThanInheritedFromProcess() {
        // The trap this exists for: `Process.waitFor(long, TimeUnit)` is a loop around `exitValue()`, and it
        // catches only `IllegalThreadStateException` - what a *local* process throws while its child is still
        // running. A live process on the far side of the binder answers `IllegalStateException` with the same
        // "process hasn't exited" message instead, which that loop does not catch, so the window fails on its
        // very first probe - before any waiting has happened - and the caller reads the probe's refusal as
        // the staging's own failure. Measured on the device: the payload daemon's staging failed 1.7 s in
        // with "Couldn't stage /data/local/tmp/ksud-s25u-kdp through Shizuku: process hasn't exited". The
        // call sites were pinned below; this contract was not, and every window in the file rests on it.
        val body = declaration(transportSource(), "override fun waitFor(timeout: Long, unit: TimeUnit)")

        assertTrue(
            "the bounded wait is inherited from Process again, so every window in this file fails on its " +
                "first probe against a process that is merely still alive: $body",
            body.contains("waitForTimeout("),
        )
        assertTrue(
            "the unit is not sent in the form the remote parses, so the window never becomes one",
            body.contains("unit.toString()") || body.contains("unit.name()"),
        )
    }

    @Test
    fun onlyOctalPermissionsAreAcceptedAsAMode() {
        assertTrue(isFileMode("755"))
        assertTrue(isFileMode("644"))
        assertTrue(isFileMode("0755"))
        assertFalse(isFileMode("75"))
        assertFalse(isFileMode("999"))
        assertFalse(isFileMode(""))
        assertFalse(isFileMode("755; rm -rf /"))
        assertFalse(isFileMode("-rwxr-xr-x"))
    }

    private fun transportSource() =
        source("src/main/java/dev/busung/s25uroot/ShizukuController.kt")

    private fun suShellSource() =
        source("src/main/java/dev/busung/s25uroot/SuShell.kt")

    private fun source(relativeToApp: String): String = listOf(
        File(relativeToApp),
        File("app/$relativeToApp"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$relativeToApp was not found from ${File(".").absolutePath}")

    /** One declaration's own text: the function at [signature], up to the next member at its own indent. */
    private fun declaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature was not found, so this test read nothing of it", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("\n    }")
        return if (end > 0) rest.substring(0, end) else rest
    }
}
