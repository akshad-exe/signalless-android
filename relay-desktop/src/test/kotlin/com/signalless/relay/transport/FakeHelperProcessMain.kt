package com.signalless.relay.transport

import com.google.gson.JsonParser

/**
 * Test-only helper process. It speaks just enough NDJSON to exercise the
 * Kotlin transport without WinRT or Bluetooth.
 */
fun main() {
    val output = System.out
    fun write(line: String) {
        output.println(line)
        output.flush()
    }

    write(
        """{"v":1,"type":"ready","platform":"fake","maxLinks":7,""" +
            """"serviceDataSupported":false,"serviceDataEnabled":false,"api":"test"}""",
    )
    write("""{"v":1,"type":"link_up","link":"l1","mtu":185,"maxNotification":182}""")
    write("""{"v":1,"type":"rx","link":"l1","data":"AQID"}""")

    while (true) {
        val line = readLine() ?: return
        if (line.isBlank()) continue
        val json = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            continue
        }
        when (json.get("type")?.asString) {
            "shutdown" -> return
            "tx" -> {
                val link = json.get("link")?.asString ?: continue
                val seq = json.get("seq")?.asLong ?: continue
                write(
                    """{"v":1,"type":"tx_result","link":"$link","seq":$seq,"ok":true}""",
                )
            }
        }
    }
}
