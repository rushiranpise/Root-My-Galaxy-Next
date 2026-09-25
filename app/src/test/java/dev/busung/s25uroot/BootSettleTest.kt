package dev.busung.s25uroot

import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootSettleTest {

    @Test
    fun `the default is a wait, not zero`() {
        // The gate exists because a cold device makes the racy stage worse, so shipping it off would
        // ship it not at all.
        assertTrue(BootSettle.DEFAULT_SECONDS > 0)
        assertTrue(BootSettle.allowedSeconds.contains(BootSettle.DEFAULT_SECONDS))
    }

    @Test
    fun `one floor serves both unattended gates, and it is not the manual one`() {
        // Root on boot and Reroot at boot are the same decision about the same kind of boot - one that
        // is acting with nobody at the screen - so they read one value. Two settings here would be two
        // claims about how settled a device has to be, and a phone that stayed unrooted because they
        // disagreed is not a thing anyone could tell apart from the exploit failing.
        //
        // It is still not the manual floor: both gates are woken by `BOOT_COMPLETED`, which has already
        // waited out part of the boot, and a person tuning automation must not be changing how long a
        // manual run pauses.
        assertTrue(BootSettle.allowedSeconds.contains(BootSettle.GATE_DEFAULT_SECONDS))
        assertTrue(BootSettle.GATE_DEFAULT_SECONDS > 0)
        assertTrue(BootSettle.GATE_DEFAULT_SECONDS < BootSettle.DEFAULT_SECONDS)
    }

    @Test
    fun `a gate's ceiling covers the longest wait the setting offers`() {
        // What a gate budgets its own wake lock and timeout with. A budget that followed the value in
        // force at one boot would expire part-way through a wait the user had asked for and report it
        // as the gate giving up, so the ceiling is the setting's own maximum, derived from it.
        assertEquals(BootSettle.allowedSeconds.max().toLong() * 1_000L, BootSettle.GATE_CEILING_MILLIS)
        assertTrue(BootSettle.GATE_CEILING_MILLIS >= BootSettle.GATE_DEFAULT_SECONDS * 1_000L)
    }

    @Test
    fun `a stored value is rounded to one of the offered ones`() {
        assertEquals(180, BootSettle.normalize(180))
        assertEquals(120, BootSettle.normalize(119))
        assertEquals(300, BootSettle.normalize(400))
        assertEquals(0, BootSettle.normalize(-5))
    }

    @Test
    fun `a device already past the gate waits not at all`() {
        // Measured from the boot: two minutes of uptime satisfy a two minute gate.
        assertEquals(0L, BootSettle.remainingMillis(120, 120_000L))
        assertEquals(0L, BootSettle.remainingMillis(120, 900_000L))
    }

    @Test
    fun `a device just booted waits the rest of the gate`() {
        assertEquals(90_000L, BootSettle.remainingMillis(120, 30_000L))
        assertEquals(120_000L, BootSettle.remainingMillis(120, 0L))
    }

    @Test
    fun `the gate is off when it is set to zero`() {
        assertEquals(0L, BootSettle.remainingMillis(0, 0L))
    }

    @Test
    fun `the wait ends by itself once the device's own uptime reaches the floor`() = runBlocking {
        // The clock is the device's uptime, read again on every pass rather than counted down: a phone
        // that slept through the floor is settled the moment it wakes, where a counter of ticks would
        // have it wait out a sleep it never spent.
        var uptime = 90_000L
        val reported = mutableListOf<Long>()
        val outcome = BootSettle.awaitFloor(
            requiredSeconds = 120,
            onWaiting = { reported += it },
            tickMillis = 1,
            uptimeMillis = { uptime.also { uptime += 20_000L } },
        )
        assertEquals(BootSettleWait.Settled, outcome)
        assertEquals(listOf(30_000L, 10_000L), reported)
    }

    @Test
    fun `a device already past the floor is settled without a countdown`() = runBlocking {
        // The common case at a `BOOT_COMPLETED` that arrives late, and the one where a message would be
        // a notification flashing a wait nothing was going to be spent on.
        var reported = 0
        val outcome = BootSettle.awaitFloor(
            requiredSeconds = 0,
            onWaiting = { reported++ },
            tickMillis = 1,
            uptimeMillis = { 0L },
        )
        assertEquals(BootSettleWait.Settled, outcome)
        assertEquals(0, reported)
    }

    @Test
    fun `a caller that changes its mind ends the wait, and early`() = runBlocking {
        // Both gates' settings can be turned off from the app during this wait, and the caller cannot
        // end it from outside - the wait does not return until it is over.
        var passes = 0
        var lastReported = -1L
        val outcome = BootSettle.awaitFloor(
            requiredSeconds = 600,
            onWaiting = { lastReported = it },
            stillWanted = { passes++ < 2 },
            tickMillis = 1,
            uptimeMillis = { 0L },
        )
        assertEquals(BootSettleWait.Abandoned, outcome)
        assertEquals("the countdown is the time left, not the floor", 600_000L, lastReported)
        assertEquals("two passes were waited, and the third is what gave up", 3, passes)
    }

    @Test
    fun `a device already past the floor is settled even by a caller that has given up`() = runBlocking {
        // The two endings are checked in one order, and that order is the answer: the uptime is a fact
        // about the device, the wait is a decision about this caller, and there is nothing left for the
        // decision to do. The same order [NetworkReach.awaitConnected] reports a network in.
        val outcome = BootSettle.awaitFloor(
            requiredSeconds = 60,
            stillWanted = { false },
            tickMillis = 1,
            uptimeMillis = { 120_000L },
        )
        assertEquals(BootSettleWait.Settled, outcome)
    }

    @Test
    fun `a countdown reads as minutes and seconds, rounded up`() {
        assertEquals("1:42", BootSettle.formatRemaining(101_500L))
        assertEquals("2:00", BootSettle.formatRemaining(120_000L))
        assertEquals("0:09", BootSettle.formatRemaining(8_400L))
        // Never 0:00 while there is still something to wait for.
        assertEquals("0:01", BootSettle.formatRemaining(1L))
        assertEquals("0:00", BootSettle.formatRemaining(0L))
        assertEquals("0:00", BootSettle.formatRemaining(-5L))
    }

    @Test
    fun `a countdown reads the same in a locale with its own digits`() {
        // `%d` renders in the default locale's digits, so a device set to Arabic, Persian, Bengali or
        // Devanagari would otherwise count down in its own numerals - and a reading that changes shape
        // with a locale setting is the one thing a countdown must not do.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1:42", BootSettle.formatRemaining(101_500L))
            assertEquals("2:00", BootSettle.formatRemaining(120_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a label says what was chosen`() {
        assertEquals("Off", BootSettle.label(0))
        assertEquals("30 s", BootSettle.label(30))
        assertEquals("1 min", BootSettle.label(60))
        assertEquals("1 min 30 s", BootSettle.label(90))
        assertEquals("2 min", BootSettle.label(120))
        assertEquals("5 min", BootSettle.label(300))
        assertEquals("10 min", BootSettle.label(600))
    }
}
