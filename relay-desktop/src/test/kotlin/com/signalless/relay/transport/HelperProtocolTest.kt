package com.signalless.relay.transport

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class HelperProtocolTest {
    @Test
    fun `parses ready and link events`() {
        val ready = HelperProtocol.parse(
            """{"v":1,"type":"ready","platform":"windows","maxLinks":7,""" +
                """"serviceDataSupported":true,"serviceDataEnabled":false,"api":"10.0.22631"}""",
        ) as HelperEvent.Ready
        assertEquals("windows", ready.platform)
        assertEquals(7, ready.maxLinks)
        assertTrue(ready.serviceDataSupported)

        val link = HelperProtocol.parse(
            """{"v":1,"type":"link_up","link":"l1","mtu":517,"maxNotification":512}""",
        ) as HelperEvent.LinkUp
        assertEquals("l1", link.linkId)
        assertEquals(517, link.mtu)
        assertEquals(512, link.maxNotificationSize)
    }

    @Test
    fun `decodes base64 receive payloads`() {
        val event = HelperProtocol.parse(
            """{"v":1,"type":"rx","link":"l2","data":"AQIDBA=="}""",
        ) as HelperEvent.Rx
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), event.data)
    }

    @Test
    fun `encodes transmit commands without losing binary data`() {
        val line = HelperProtocol.txCommand("l9", 42L, byteArrayOf(0, 1, 2, 255.toByte()))
        val json = JsonParser.parseString(line).asJsonObject
        assertEquals(1, json.get("v").asInt)
        assertEquals("tx", json.get("type").asString)
        assertEquals("l9", json.get("link").asString)
        assertEquals(42L, json.get("seq").asLong)
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 255.toByte()),
            java.util.Base64.getDecoder().decode(json.get("data").asString),
        )
    }

    @Test
    fun `tolerates a utf8 bom before the first object`() {
        val event = HelperProtocol.parse(
            "\uFEFF" + """{"v":1,"type":"ready","platform":"windows","maxLinks":7,""" +
                """"serviceDataSupported":false,"serviceDataEnabled":false}""",
        ) as HelperEvent.Ready
        assertEquals("windows", event.platform)
        assertEquals(7, event.maxLinks)
    }

    @Test
    fun `rejects an unsupported protocol version`() {
        val error = runCatching {
            HelperProtocol.parse("""{"v":2,"type":"ready","maxLinks":7}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects an oversized line`() {
        val line = " " + "x".repeat(HelperProtocol.MAX_LINE_BYTES)
        val error = runCatching { HelperProtocol.parse(line) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects invalid base64`() {
        val error = runCatching {
            HelperProtocol.parse("""{"v":1,"type":"rx","link":"l1","data":"not base64!"}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `unknown events are surfaced instead of crashing the reader`() {
        val event = HelperProtocol.parse("""{"v":1,"type":"new_event"}""")
        assertTrue(event is HelperEvent.Unknown)
    }

    @Test
    fun `protocol line limit counts utf8 bytes`() {
        val text = "é".repeat(HelperProtocol.MAX_LINE_BYTES / 2 + 1)
        assertTrue(text.toByteArray(StandardCharsets.UTF_8).size > HelperProtocol.MAX_LINE_BYTES)
        val error = runCatching { HelperProtocol.parse(text) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
