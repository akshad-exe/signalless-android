package com.signalless.relay

import com.signalless.app.util.SLog
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RelayEngineObservabilityTest {
    private val myPeerId = "cccccccccccccccc"

    @Before
    fun silenceLogs() {
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        SLog.sink = null
    }

    @Test
    fun `ready flag turns on with ready and off on stop`() = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(myPeerId = myPeerId, transport = fake, scope = this)

        engine.start()
        awaitUntil("transport ready") { engine.isTransportReady() }
        engine.stop()

        assertFalse(engine.isTransportReady())
        assertNull(engine.lastTransportError())
    }

    @Test
    fun `last transport error is recorded and cleared on stop`() = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(myPeerId = myPeerId, transport = fake, scope = this)

        engine.start()
        awaitUntil("transport ready") { engine.isTransportReady() }

        fake.error("ADVERTISING_FAILED", "adapter busy")
        awaitUntil("transport error recorded") {
            engine.lastTransportError() == "ADVERTISING_FAILED: adapter busy"
        }

        engine.stop()
        assertNull(engine.lastTransportError())
    }

    @Test
    fun `ready flag is cleared when start fails`() = runBlocking {
        val fake = FakeRelayTransport()
        fake.startFailure = IllegalStateException("adapter unavailable")
        val engine = RelayEngine(myPeerId = myPeerId, transport = fake, scope = this)

        try {
            engine.start()
            throw AssertionError("expected start to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("adapter unavailable", expected.message)
        }

        assertFalse(engine.isTransportReady())
        assertNull(engine.lastTransportError())
    }

    private suspend fun awaitUntil(description: String, condition: () -> Boolean) {
        try {
            withTimeout(2_000L) {
                while (!condition()) {
                    delay(10L)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $description", timeout)
        }
    }
}
