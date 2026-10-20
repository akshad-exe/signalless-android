package com.signalless.relay.transport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * NDJSON protocol shared by the Kotlin core and a BLE helper process.
 *
 * A line is one JSON object. Standard output belongs to this protocol only;
 * diagnostics go to stderr. Binary payloads are base64-encoded.
 */
object HelperProtocol {
    const val VERSION: Int = 1
    const val MAX_LINE_BYTES: Int = 1_048_576

    fun parse(line: String): HelperEvent {
        require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_LINE_BYTES) {
            "helper line exceeds $MAX_LINE_BYTES bytes"
        }
        val json = JsonParser.parseString(line)
        require(json.isJsonObject) { "helper line must be a JSON object" }
        val objectValue = json.asJsonObject
        require(objectValue.get("v")?.asInt == VERSION) {
            "unsupported helper protocol version"
        }
        val type = objectValue.requireString("type")
        return when (type) {
            "ready" -> HelperEvent.Ready(
                platform = objectValue.optionalString("platform") ?: "unknown",
                maxLinks = objectValue.requireInt("maxLinks"),
                serviceDataSupported = objectValue.optionalBoolean("serviceDataSupported") ?: false,
                serviceDataEnabled = objectValue.optionalBoolean("serviceDataEnabled") ?: false,
                api = objectValue.optionalString("api"),
            )
            "advertising" -> HelperEvent.Advertising(
                state = objectValue.requireString("state"),
                serviceData = objectValue.optionalBoolean("serviceData") ?: false,
                detail = objectValue.optionalString("detail"),
            )
            "link_up" -> HelperEvent.LinkUp(
                linkId = objectValue.requireString("link"),
                mtu = objectValue.requireInt("mtu"),
                maxNotificationSize = objectValue.requireInt("maxNotification"),
            )
            "link_down" -> HelperEvent.LinkDown(
                linkId = objectValue.requireString("link"),
                reason = objectValue.optionalString("reason") ?: "unknown",
            )
            "rx" -> HelperEvent.Rx(
                linkId = objectValue.requireString("link"),
                data = decodeBase64(objectValue.requireString("data")),
            )
            "tx_result" -> HelperEvent.TxResult(
                linkId = objectValue.requireString("link"),
                sequence = objectValue.optionalLong("seq"),
                ok = objectValue.optionalBoolean("ok") ?: false,
                error = objectValue.optionalString("error"),
            )
            "error" -> HelperEvent.Error(
                code = objectValue.optionalString("code") ?: "HELPER_ERROR",
                message = objectValue.optionalString("message") ?: "helper error",
            )
            else -> HelperEvent.Unknown(type)
        }
    }

    fun startCommand(): String = command("start").toString()

    fun txCommand(linkId: String, sequence: Long, data: ByteArray): String {
        val json = command("tx")
        json.addProperty("link", linkId)
        json.addProperty("seq", sequence)
        json.addProperty("data", Base64.getEncoder().encodeToString(data))
        return json.toString()
    }

    fun stopCommand(): String = command("stop").toString()

    fun shutdownCommand(): String = command("shutdown").toString()

    private fun command(type: String): JsonObject = JsonObject().apply {
        addProperty("v", VERSION)
        addProperty("type", type)
    }

    private fun JsonObject.requireString(name: String): String {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        require(!value.isNullOrBlank()) { "helper field '$name' is missing" }
        return value
    }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.requireInt(name: String): Int {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw IllegalArgumentException("helper field '$name' is missing")
        return value
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("helper field 'data' is not valid base64", error)
    }
}

sealed interface HelperEvent {
    data class Ready(
        val platform: String,
        val maxLinks: Int,
        val serviceDataSupported: Boolean,
        val serviceDataEnabled: Boolean,
        val api: String?,
    ) : HelperEvent

    data class Advertising(
        val state: String,
        val serviceData: Boolean,
        val detail: String?,
    ) : HelperEvent

    data class LinkUp(
        val linkId: String,
        val mtu: Int,
        val maxNotificationSize: Int,
    ) : HelperEvent

    data class LinkDown(
        val linkId: String,
        val reason: String,
    ) : HelperEvent

    class Rx(
        val linkId: String,
        val data: ByteArray,
    ) : HelperEvent

    data class TxResult(
        val linkId: String,
        val sequence: Long?,
        val ok: Boolean,
        val error: String?,
    ) : HelperEvent

    data class Error(
        val code: String,
        val message: String,
    ) : HelperEvent

    data class Unknown(val type: String) : HelperEvent
}
