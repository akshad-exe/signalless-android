package com.signalless.relay.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class RelayFormatTest {
    @Test
    fun `peer id is grouped in fours`() {
        assertEquals("9f97 f332 1a2b 3c4d", RelayFormat.peerId("9f97f3321a2b3c4d"))
        assertEquals("abcd ef", RelayFormat.peerId("abcdef"))
        assertEquals("", RelayFormat.peerId(""))
    }

    @Test
    fun `duration switches to hours after sixty minutes`() {
        assertEquals("00:00", RelayFormat.duration(0L))
        assertEquals("03:12", RelayFormat.duration(192_000L))
        assertEquals("42:11", RelayFormat.duration(2_531_000L))
        assertEquals("01:02:03", RelayFormat.duration(3_723_000L))
        assertEquals("00:42:11", RelayFormat.duration(2_531_000L, alwaysHours = true))
    }

    @Test
    fun `duration clamps negative elapsed time`() {
        assertEquals("00:00", RelayFormat.duration(-5_000L))
    }

    @Test
    fun `counts are grouped with spaces`() {
        assertEquals("0", RelayFormat.count(0L))
        assertEquals("999", RelayFormat.count(999L))
        assertEquals("1 000", RelayFormat.count(1_000L))
        assertEquals("999 999", RelayFormat.count(999_999L))
        assertEquals("1 234 567", RelayFormat.count(1_234_567L))
    }

    @Test
    fun `clock uses the requested zone`() {
        assertEquals("11:22:11", RelayFormat.clock(1_703_762_531_000L, ZoneOffset.UTC))
    }

    @Test
    fun `auto transport is labelled helper in the header`() {
        assertEquals("helper", RelayFormat.transportLabel("auto"))
        assertEquals("helper", RelayFormat.transportLabel("helper"))
        assertEquals("fake", RelayFormat.transportLabel("fake"))
    }
}
