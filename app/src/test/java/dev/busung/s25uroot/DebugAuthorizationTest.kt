package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why this app writes the device's ADB authorization timeout at all.
 *
 * The rule being pinned: Android gives an accepted host's key a week and then revokes it, so a device
 * that was paired once asks to be paired again - which is the recurring manual step this exists to
 * remove. Writing zero is the framework's "never", and the two things that can go wrong are a device
 * that refuses the write and a device whose setting cannot be read at all. Neither may be reported as
 * arranged, and neither may be reported as a plain failure either.
 */
class DebugAuthorizationTest {

    /** The number this app is fighting: a week, which is what an unwritten device uses. */
    @Test
    fun `the device default is a week`() {
        assertEquals(7L * 24 * 60 * 60 * 1_000, ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS)
    }

    @Test
    fun `only zero counts as never expiring`() {
        assertTrue(authorizationNeverExpires(0L))
        assertTrue(!authorizationNeverExpires(ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS))
        assertTrue(!authorizationNeverExpires(null))
    }

    @Test
    fun `a timeout that already reads as zero is not written to`() {
        assertEquals(
            DebugAuthorizationResult.AlreadyPermanent,
            debugAuthorizationResult(
                before = 0L,
                route = WirelessAdbEnableRoute.Setting,
                after = 0L,
            ),
        )
    }

    @Test
    fun `a write that reads back as zero is a success`() {
        assertEquals(
            DebugAuthorizationResult.MadePermanent,
            debugAuthorizationResult(
                before = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
                route = WirelessAdbEnableRoute.Setting,
                after = 0L,
            ),
        )
        assertEquals(
            DebugAuthorizationResult.MadePermanent,
            debugAuthorizationResult(
                before = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
                route = WirelessAdbEnableRoute.Root,
                after = 0L,
            ),
        )
    }

    /** The shape that used to pass for success: the write went through and the week stayed. */
    @Test
    fun `a write that leaves the week in place is a refusal`() {
        assertEquals(
            DebugAuthorizationResult.Refused,
            debugAuthorizationResult(
                before = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
                route = WirelessAdbEnableRoute.Setting,
                after = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
            ),
        )
    }

    /** A device with no route to the setting is answered about its permissions, not about the write. */
    @Test
    fun `no route is unavailable, whatever the setting reads`() {
        assertEquals(
            DebugAuthorizationResult.Unavailable,
            debugAuthorizationResult(
                before = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
                route = WirelessAdbEnableRoute.Unavailable,
                after = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
            ),
        )
        assertEquals(
            DebugAuthorizationResult.Unavailable,
            debugAuthorizationResult(
                before = null,
                route = WirelessAdbEnableRoute.Unavailable,
                after = null,
            ),
        )
    }

    /** An unreadable setting after a write is never reported as an expiring one. */
    @Test
    fun `a setting that cannot be read afterwards is unknown rather than expiring`() {
        assertEquals(
            DebugAuthorizationResult.Unknown,
            debugAuthorizationResult(
                before = ADB_DEFAULT_AUTHORIZATION_TIMEOUT_MILLIS,
                route = WirelessAdbEnableRoute.Setting,
                after = null,
            ),
        )
    }

    /**
     * The settings the fork this was taken from writes, and that Android reads: without both, the
     * switch that lets a host be authorized at all is missing, or the week is never written away.
     */
    @Test
    fun `both settings that make an authorization last are written`() {
        val source = sourceOf("AdbPairing.kt")
        assertTrue(source.contains("adb_enabled"))
        assertTrue(source.contains("adb_allowed_connection_time"))
    }

    /**
     * The step has to hang off the funnel every run opens its transport through, and off the moment a
     * pairing exists. Dropping either call would leave the app working exactly as before - which is the
     * silent regression this test is here to catch.
     */
    @Test
    fun `the authorization is made permanent when a pairing is created and on every run`() {
        assertTrue(sourceOf("TemporaryWirelessAdb.kt").contains("AdbPairing.tryAuthorizeDebugging("))
        assertTrue(sourceOf("AdbPairingService.kt").contains("AdbPairing.authorizeDebugging("))
    }

    private fun sourceOf(fileName: String): String =
        listOf(
            File("src/main/java/dev/busung/s25uroot/$fileName"),
            File("app/src/main/java/dev/busung/s25uroot/$fileName"),
        ).firstOrNull(File::exists)?.readText()
            ?: throw AssertionError("$fileName was not found from ${File(".").absolutePath}")
}
