package com.signalless.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigTest {
    @Test
    fun `defaults target the windows connection budget`() {
        val config = RelayConfig.parse(emptyList())

        assertEquals(7, config.maxPeers)
        assertEquals(7, config.ttl)
        assertEquals(TransportMode.AUTO, config.transport)
        assertNull(config.logFile)
        assertTrue(config.discoverable)
    }

    @Test
    fun `parses discoverable advertising flags`() {
        assertTrue(RelayConfig.parse(listOf("--discoverable")).discoverable)
        assertFalse(RelayConfig.parse(listOf("--no-discoverable")).discoverable)
    }

    @Test
    fun `parses every supported option`() {
        val config = RelayConfig.parse(
            listOf(
                "--peers", "12",
                "--ttl", "9",
                "--transport", "fake",
                "--log-file", "relay.log",
            ),
        )

        assertEquals(12, config.maxPeers)
        assertEquals(9, config.ttl)
        assertEquals(TransportMode.FAKE, config.transport)
        assertEquals("relay.log", config.logFile?.toString())
    }

    @Test
    fun `rejects peers outside the supported range`() {
        assertCliError("--peers must be between 1 and 64") {
            RelayConfig.parse(listOf("--peers", "0"))
        }
        assertCliError("--peers must be between 1 and 64") {
            RelayConfig.parse(listOf("--peers", "65"))
        }
    }

    @Test
    fun `rejects ttl outside the supported range`() {
        assertCliError("--ttl must be between 1 and 255") {
            RelayConfig.parse(listOf("--ttl", "0"))
        }
        assertCliError("--ttl must be between 1 and 255") {
            RelayConfig.parse(listOf("--ttl", "256"))
        }
    }

    @Test
    fun `rejects non numeric values`() {
        assertCliError("--peers must be an integer") {
            RelayConfig.parse(listOf("--peers", "seven"))
        }
    }

    @Test
    fun `rejects an unknown transport`() {
        assertCliError("--transport must be auto, helper, or fake") {
            RelayConfig.parse(listOf("--transport", "ble"))
        }
    }

    @Test
    fun `rejects a missing option value`() {
        assertCliError("missing value for --peers") {
            RelayConfig.parse(listOf("--peers"))
        }
    }

    @Test
    fun `rejects a blank log path`() {
        assertCliError("--log-file must not be blank") {
            RelayConfig.parse(listOf("--log-file", " "))
        }
    }

    @Test
    fun `rejects the removed interface option`() {
        assertCliError("unknown option: --iface") {
            RelayConfig.parse(listOf("--iface", "hci0"))
        }
    }

    private fun assertCliError(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("expected CliException")
        } catch (error: CliException) {
            assertEquals(true, error.message.orEmpty().contains(expectedMessage))
        }
    }
}
