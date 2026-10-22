package com.signalless.relay.ui

import com.signalless.relay.RelayConfig
import com.signalless.relay.TransportMode
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RelayControllerTest {
    private val identity = RelayIdentity.generate()
    private val config = RelayConfig(transport = TransportMode.FAKE, maxPeers = 7)
    private var controller: RelayController? = null

    @After
    fun tearDown() {
        controller?.shutdown()
    }

    @Test
    fun `start reaches advertising and publishes a ready event`() {
        val (relay, _) = newController()

        relay.start()
        val state = awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        assertEquals(identity.peerId, state.peerId)
        assertEquals("fake", state.transport)
        assertEquals(7, state.maxLinks)
        assertNotNull(state.advertisingSinceMs)
        assertNull(state.error)
        assertTrue(state.events.any { it.kind == RelayEventKind.READY })
    }

    @Test
    fun `stop clears links and returns to stopped`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        transports.single().linkUp("link-1")
        awaitState("link visible") { state -> state.links.any { it.id == "link-1" } }

        relay.stop()
        val stopped = awaitState("stopped") { it.phase == RelayPhase.STOPPED }

        assertTrue(stopped.links.isEmpty())
        assertEquals(0L, stopped.stats.received)
        assertNull(stopped.advertisingSinceMs)
        assertTrue(stopped.events.any { it.text == "stopped" })
    }

    @Test
    fun `restart keeps the peer id and drops old links`() {
        val (relay, transports) = newController()
        relay.start()
        val before = awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        transports.single().linkUp("link-1")
        awaitState("link visible") { state -> state.links.any { it.id == "link-1" } }

        relay.restart()
        val after = awaitState("advertising after restart") {
            it.phase == RelayPhase.ADVERTISING && transports.size == 2
        }

        assertEquals(before.peerId, after.peerId)
        assertEquals(2, transports.size)
        assertTrue(after.links.isEmpty())
    }

    @Test
    fun `start failure surfaces the message and retry succeeds`() {
        val (relay, transports) = newController { index, fake ->
            if (index == 0) {
                fake.startFailure = IllegalStateException("adapter unavailable")
            }
        }

        relay.start()
        val failed = awaitState("failed") { it.phase == RelayPhase.FAILED }

        assertTrue(failed.error.orEmpty().contains("adapter unavailable"))
        assertTrue(
            failed.events.any {
                it.kind == RelayEventKind.ERROR && it.text.contains("adapter unavailable")
            },
        )

        relay.start()
        val advertising = awaitState("advertising after retry") {
            it.phase == RelayPhase.ADVERTISING && transports.size == 2
        }

        assertNull(advertising.error)
    }

    @Test
    fun `double restart is ignored while transitioning`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        relay.restart()
        relay.restart()
        awaitState("advertising after restart") {
            it.phase == RelayPhase.ADVERTISING && transports.size == 2
        }

        assertEquals(2, transports.size)
        assertEquals(
            1,
            relay.state.value.events.count { it.text == "restart requested" },
        )
    }

    @Test
    fun `event ring buffer stays bounded`() {
        val (relay, _) = newController(eventLimit = 5)

        repeat(2) { cycle ->
            relay.start()
            awaitState("advertising $cycle") { it.phase == RelayPhase.ADVERTISING }
            relay.stop()
            awaitState("stopped $cycle") { it.phase == RelayPhase.STOPPED }
        }

        val events = relay.state.value.events
        assertEquals(5, events.size)
        assertEquals("stopped", events.last().text)
        assertEquals(200, RelayController.DEFAULT_EVENT_LIMIT)
    }

    @Test
    fun `default ring buffer keeps the newest 200 events`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        val fake = transports.single()
        repeat(RelayController.DEFAULT_EVENT_LIMIT + 1) { index ->
            fake.linkUp("link-$index")
            fake.linkDown("link-$index")
        }

        val state = awaitState("last link event") {
            it.events.lastOrNull()?.text == "link down link-${RelayController.DEFAULT_EVENT_LIMIT} reason=test"
        }
        assertEquals(RelayController.DEFAULT_EVENT_LIMIT, state.events.size)
    }

    @Test
    fun `event logger mirrors lifecycle and formatted link events`() {
        val logged = CopyOnWriteArrayList<String>()
        val (relay, transports) = newController(eventLogger = { logged += it })
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        transports.single().linkUp("link-1")
        awaitState("link event") { state ->
            state.events.any { it.text.startsWith("link up link-1") }
        }
        relay.stop()
        awaitState("stopped") { it.phase == RelayPhase.STOPPED }

        assertTrue(logged.contains("relay starting"))
        assertTrue(logged.contains("advertising ready"))
        assertTrue(logged.any { it.startsWith("link up link-1") })
        assertTrue(logged.contains("stopping"))
        assertTrue(logged.contains("stopped"))
    }

    @Test
    fun `shutdown is idempotent and rejects further commands`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        relay.shutdown()
        relay.shutdown()
        relay.start()

        assertEquals(RelayPhase.STOPPED, relay.state.value.phase)
        assertEquals(1, transports.size)
    }

    @Test
    fun `link first seen timestamp stays stable across refreshes`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        transports.single().linkUp("link-1")
        val first = awaitState("link visible") { state ->
            state.links.any { it.id == "link-1" }
        }
        val firstSeen = first.links.single { it.id == "link-1" }.firstSeenMs

        val later = awaitState("next tick") { state -> state.nowMs > first.nowMs + 40L }
        assertEquals(firstSeen, later.links.single { it.id == "link-1" }.firstSeenMs)

        transports.single().linkDown("link-1")
        val removed = awaitState("link removed") { it.links.isEmpty() }
        assertTrue(removed.events.any { it.kind == RelayEventKind.LINK_DOWN })
    }

    @Test
    fun `transport errors are surfaced without leaving advertising`() {
        val (relay, transports) = newController()
        relay.start()
        awaitState("advertising") { it.phase == RelayPhase.ADVERTISING }

        transports.single().error("ADVERTISING_FAILED", "adapter busy")
        val state = awaitState("error banner") { it.error != null }

        assertTrue(state.error.orEmpty().contains("adapter busy"))
        assertEquals(RelayPhase.ADVERTISING, state.phase)
        assertTrue(state.events.any { it.kind == RelayEventKind.ERROR })
    }

    private fun newController(
        eventLimit: Int = RelayController.DEFAULT_EVENT_LIMIT,
        eventLogger: (String) -> Unit = {},
        configure: (Int, FakeRelayTransport) -> Unit = { _, _ -> },
    ): Pair<RelayController, MutableList<FakeRelayTransport>> {
        val transports = mutableListOf<FakeRelayTransport>()
        val relay = RelayController(
            config = config,
            identity = identity,
            version = "test",
            transportFactory = { _: CoroutineScope ->
                FakeRelayTransport().also { fake ->
                    configure(transports.size, fake)
                    transports += fake
                }
            },
            tickIntervalMs = 20L,
            eventLimit = eventLimit,
            eventLogger = eventLogger,
        )
        controller = relay
        return relay to transports
    }

    private fun awaitState(
        description: String,
        predicate: (RelayUiState) -> Boolean,
    ): RelayUiState = runBlocking {
        val relay = requireNotNull(controller)
        try {
            withTimeout(5_000L) {
                relay.state.first(predicate)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $description; last=${relay.state.value}", timeout)
        }
    }
}
