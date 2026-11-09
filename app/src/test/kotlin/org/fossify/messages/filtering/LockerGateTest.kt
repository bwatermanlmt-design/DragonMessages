package org.fossify.messages.filtering

import org.fossify.messages.helpers.LockerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Locker gate's pure pieces: the salted code hash and the hex
 * helpers. The Config-backed set/verify path needs an Android context,
 * so it is covered by the emulator run instead.
 */
class LockerGateTest {

    @Test
    fun hashIsDeterministicForSameSaltAndCode() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        assertEquals(LockerGate.hashCode("s3cret-code", salt), LockerGate.hashCode("s3cret-code", salt))
    }

    @Test
    fun hashDiffersForDifferentCode() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        assertNotEquals(
            LockerGate.hashCode("s3cret-code", salt),
            LockerGate.hashCode("other-code", salt)
        )
    }

    @Test
    fun hashDiffersForDifferentSalt() {
        val first = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val second = byteArrayOf(16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
        assertNotEquals(LockerGate.hashCode("s3cret-code", first), LockerGate.hashCode("s3cret-code", second))
    }

    @Test
    fun hashIsHexSha256() {
        val salt = ByteArray(16)
        val hash = LockerGate.hashCode("code", salt)
        // SHA-256 is 32 bytes, 64 hex chars, lowercase.
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun hexRoundTrips() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1, 42)
        val hex = with(LockerGate) { bytes.toHex() }
        val back = with(LockerGate) { hex.fromHex() }
        assertNotNull(back)
        assertTrue(bytes.contentEquals(back!!))
    }

    @Test
    fun fromHexRejectsOddLength() {
        with(LockerGate) {
            assertNull("abc".fromHex())
        }
    }

    @Test
    fun fromHexRejectsNonHex() {
        with(LockerGate) {
            assertNull("zz".fromHex())
        }
    }

    // Auto-lock session (revision 13): the Locker stays open while
    // the user is in messages, and re-locks after the timeout away.

    @Test
    fun freshSessionNeedsAuth() {
        LockerGate.resetSession()
        assertTrue(LockerGate.needsAuth(timeoutSeconds = 30))
    }

    @Test
    fun justUnlockedNeedsNoAuth() {
        LockerGate.resetSession()
        LockerGate.markUnlocked()
        assertTrue(!LockerGate.needsAuth(timeoutSeconds = 30))
    }

    @Test
    fun awayBrieflyNeedsNoAuth() {
        LockerGate.resetSession()
        LockerGate.markUnlocked()
        LockerGate.markLeftMessages()
        val now = System.currentTimeMillis()
        assertTrue(!LockerGate.needsAuth(now + 10_000, timeoutSeconds = 30))
    }

    @Test
    fun awayPastTimeoutNeedsAuth() {
        LockerGate.resetSession()
        LockerGate.markUnlocked()
        LockerGate.markLeftMessages()
        val now = System.currentTimeMillis()
        assertTrue(LockerGate.needsAuth(now + 31_000, timeoutSeconds = 30))
    }

    @Test
    fun timeoutIsHonored() {
        LockerGate.resetSession()
        LockerGate.markUnlocked()
        LockerGate.markLeftMessages()
        val now = System.currentTimeMillis()
        assertTrue(LockerGate.needsAuth(now + 16_000, timeoutSeconds = 15))
        assertTrue(!LockerGate.needsAuth(now + 16_000, timeoutSeconds = 300))
    }

    @Test
    fun backInMessagesClearsTheAwayMarker() {
        LockerGate.resetSession()
        LockerGate.markUnlocked()
        LockerGate.markLeftMessages()
        LockerGate.markBackInMessages()
        val now = System.currentTimeMillis()
        assertTrue(!LockerGate.needsAuth(now + 60_000, timeoutSeconds = 30))
    }

    @Test
    fun secondDepartureDoesNotRestartTheAwayClock() {
        // The gate-cancel hole: leaving, then leaving again (the
        // Locker activity finishing after a cancelled gate) must
        // NOT grant a fresh timeout window — the clock measures
        // from the first departure.
        LockerGate.resetSession()
        LockerGate.markUnlocked(nowMillis = 1_000_000)
        LockerGate.markLeftMessages(nowMillis = 1_001_000)
        LockerGate.markLeftMessages(nowMillis = 1_021_000)
        // 10s after the first departure: still inside the timeout.
        assertTrue(!LockerGate.needsAuth(1_011_000, timeoutSeconds = 30))
        // 31s after the first departure, only 11s after the second:
        // the gate is due, because the clock never restarted.
        assertTrue(LockerGate.needsAuth(1_032_000, timeoutSeconds = 30))
    }

    @Test
    fun newDepartureAfterReturnStartsANewClock() {
        LockerGate.resetSession()
        LockerGate.markUnlocked(nowMillis = 1_000_000)
        LockerGate.markLeftMessages(nowMillis = 1_001_000)
        LockerGate.markBackInMessages()
        LockerGate.markLeftMessages(nowMillis = 1_021_000)
        // 10s after the second (real) departure: inside the timeout.
        assertTrue(!LockerGate.needsAuth(1_031_000, timeoutSeconds = 30))
        // 31s after the second departure: the gate is due.
        assertTrue(LockerGate.needsAuth(1_052_000, timeoutSeconds = 30))
    }
}
