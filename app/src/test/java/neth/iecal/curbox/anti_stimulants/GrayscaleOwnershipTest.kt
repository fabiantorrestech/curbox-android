package neth.iecal.curbox.anti_stimulants

import neth.iecal.curbox.anti_stimulants.GrayscaleOwnership.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscaleOwnershipTest {

    // Core guarantee: with no group asking for grayscale, Curbox never writes the setting.

    @Test
    fun notWantedNeverTouchesSomeoneElsesFilter() {
        val ownership = GrayscaleOwnership()
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = true))
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = false))
        assertFalse(ownership.owned)
    }

    @Test
    fun wantedWhileSomeoneElsesFilterIsOnIsNotClaimed() {
        val ownership = GrayscaleOwnership()
        assertEquals(Command.NONE, ownership.request(wants = true, systemOn = true))
        assertFalse(ownership.owned)
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = true))
    }

    // Normal cycle

    @Test
    fun enableThenDisableOwnFilter() {
        val ownership = GrayscaleOwnership()
        assertEquals(Command.ENABLE, ownership.request(wants = true, systemOn = false))
        assertFalse("not owned until seen on", ownership.owned)

        assertEquals(Command.NONE, ownership.observed(systemOn = true))
        assertTrue(ownership.owned)
        assertNull(ownership.pending)

        assertEquals(Command.DISABLE, ownership.request(wants = false, systemOn = true))
        assertEquals(Command.NONE, ownership.observed(systemOn = false))
        assertFalse(ownership.owned)
    }

    // Issue 1: an enable that never landed must not leave Curbox owning a later filter.

    @Test
    fun failedEnableDoesNotClaimOwnership() {
        val ownership = GrayscaleOwnership()
        ownership.request(wants = true, systemOn = false)
        assertEquals(Command.NONE, ownership.timedOut(systemOn = false))
        assertFalse(ownership.owned)

        // GrayScaler+ switches the filter on later; leaving the app must not switch it off.
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = true))
    }

    // Issue 2: someone else switched Curbox's filter off and later on again.

    @Test
    fun takeoverByAnotherAppDropsOwnership() {
        val ownership = GrayscaleOwnership(owned = true)
        assertEquals(Command.NONE, ownership.observed(systemOn = false))
        assertFalse(ownership.owned)

        assertEquals(Command.NONE, ownership.observed(systemOn = true))
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = true))
    }

    @Test
    fun ownershipDroppedOnRequestIfFilterWentOffUnseen() {
        val ownership = GrayscaleOwnership(owned = true)
        assertEquals(Command.NONE, ownership.request(wants = false, systemOn = false))
        assertFalse(ownership.owned)
    }

    // Issue 3: Shizuku commands run on separate threads, so they are serialized here.

    @Test
    fun disableWaitsForPendingEnable() {
        val ownership = GrayscaleOwnership()
        assertEquals(Command.ENABLE, ownership.request(wants = true, systemOn = false))
        assertEquals("queued, not sent", Command.NONE, ownership.request(wants = false, systemOn = false))

        assertEquals(Command.DISABLE, ownership.observed(systemOn = true))
        assertEquals(Command.NONE, ownership.observed(systemOn = false))
        assertFalse(ownership.owned)
        assertNull(ownership.pending)
    }

    @Test
    fun latestQueuedRequestWins() {
        val ownership = GrayscaleOwnership()
        ownership.request(wants = true, systemOn = false)
        ownership.request(wants = false, systemOn = false)
        ownership.request(wants = true, systemOn = false)

        assertEquals(Command.NONE, ownership.observed(systemOn = true))
        assertTrue(ownership.owned)
    }

    @Test
    fun queuedRequestReplaysAfterTimeout() {
        val ownership = GrayscaleOwnership()
        ownership.request(wants = true, systemOn = false)
        ownership.request(wants = true, systemOn = false)
        assertEquals("retries the enable", Command.ENABLE, ownership.timedOut(systemOn = false))
    }

    @Test
    fun failedDisableKeepsOwnership() {
        val ownership = GrayscaleOwnership(owned = true)
        ownership.request(wants = false, systemOn = true)
        ownership.timedOut(systemOn = true)
        assertTrue(ownership.owned)
    }

    // Restart: persisted ownership is honored only while the filter is still on.

    @Test
    fun restoredOwnershipStillDisables() {
        val ownership = GrayscaleOwnership(owned = true)
        assertEquals(Command.NONE, ownership.observed(systemOn = true))
        assertEquals(Command.DISABLE, ownership.request(wants = false, systemOn = true))
    }
}
