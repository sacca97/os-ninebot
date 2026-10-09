package com.sacca.openride.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GattStageTest {
    @Test fun timeoutIsReportedAsConnectionFailure() = runTest {
        try {
            awaitGattStage("Service discovery", 10_000, CompletableDeferred<Unit>())
            fail("Expected timeout")
        } catch (e: BleConnectException) {
            assertEquals("Service discovery timed out after 10s", e.message)
        }
    }

    @Test fun userCancellationIsPreserved() = runTest {
        var caught: Throwable? = null
        val job = launch {
            try { awaitGattStage("Bluetooth connection", 15_000, CompletableDeferred<Unit>()) }
            catch (e: Throwable) { caught = e; throw e }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(caught is CancellationException)
    }

    @Test fun completedStageReturnsItsResult() = runTest {
        assertEquals(0, awaitGattStage("Service discovery", 10_000, CompletableDeferred(0)))
    }
}
