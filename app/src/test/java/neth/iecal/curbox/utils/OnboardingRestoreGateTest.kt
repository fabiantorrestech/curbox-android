package neth.iecal.curbox.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnboardingRestoreGateTest {
    @Test
    fun completedSetupCannotWriteSettings() = runBlocking {
        var written = false
        try {
            OnboardingRestoreGate().restore({ false }) { written = true }
            fail("Restore must be rejected after setup")
        } catch (_: IllegalStateException) {
            assertFalse(written)
        }
    }

    @Test
    fun completionWaitsForAnInFlightRestore() = runBlocking {
        val gate = OnboardingRestoreGate()
        val allowWrite = CompletableDeferred<Unit>()
        var complete = false
        var restored = false
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.restore({ !complete }) {
                allowWrite.await()
                restored = true
            }
        }
        val finish = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.complete {
                assertTrue(restored)
                complete = true
            }
        }
        assertFalse(complete)
        allowWrite.complete(Unit)
        restore.join()
        finish.join()
        assertTrue(complete)
        try {
            gate.restore({ !complete }) { fail("Late picker result must not write settings") }
            fail("Restore window must stay closed")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun failedRestoreReleasesTheGateForRetry() = runBlocking {
        val gate = OnboardingRestoreGate()
        try {
            gate.restore({ true }) { error("Read failed") }
        } catch (_: IllegalStateException) {
        }
        var restored = false
        gate.restore({ true }) { restored = true }
        assertTrue(restored)
    }
}
