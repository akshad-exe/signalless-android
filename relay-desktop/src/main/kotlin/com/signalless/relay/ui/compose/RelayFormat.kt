package com.signalless.relay.ui.compose

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure formatting helpers shared by the window and its unit tests. */
internal object RelayFormat {
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    /** Groups 16 hex characters as four blocks: `9f97 f332 1a2b 3c4d`. */
    fun peerId(peerId: String): String = peerId.chunked(4).joinToString(" ")

    /**
     * Formats elapsed time as `MM:SS`, or `HH:MM:SS` after an hour (or when
     * [alwaysHours] is set, as the header uptime does).
     */
    fun duration(elapsedMs: Long, alwaysHours: Boolean = false): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (alwaysHours || hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** Groups thousands with spaces, matching the plan's counter style. */
    fun count(value: Long): String {
        val digits = value.toString()
        if (value < 0L) {
            return "-" + count(-value)
        }
        return digits.reversed().chunked(3).joinToString(" ").reversed()
    }

    fun clock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormatter.format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** The UI names the resolved BLE transport instead of showing CLI `auto`. */
    fun transportLabel(transport: String): String = when (transport) {
        "auto" -> "helper"
        else -> transport
    }
}
