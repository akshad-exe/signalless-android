package com.signalless.relay.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Rectangle

class WindowBoundsTest {
    private val screen = Rectangle(0, 0, 1920, 1080)

    @Test
    fun `keeps a fully visible window unchanged`() {
        val bounds = WindowBounds(100, 120, 800, 600)
        assertEquals(bounds, WindowBoundsStore.sanitize(bounds, listOf(screen)))
    }

    @Test
    fun `rejects tiny and completely off-screen windows`() {
        assertNull(WindowBoundsStore.sanitize(WindowBounds(10, 10, 200, 100), listOf(screen)))
        assertNull(WindowBoundsStore.sanitize(WindowBounds(2600, 100, 600, 400), listOf(screen)))
        assertNull(WindowBoundsStore.sanitize(WindowBounds(100, 100, 800, 600), emptyList()))
    }

    @Test
    fun `pulls a mostly off-screen window back onto the monitor`() {
        val restored = WindowBoundsStore.sanitize(
            WindowBounds(1800, 900, 600, 400),
            listOf(screen),
        )
        assertEquals(WindowBounds(1320, 680, 600, 400), restored)
    }

    @Test
    fun `shrinks geometry larger than the monitor`() {
        val restored = WindowBoundsStore.sanitize(
            WindowBounds(0, 0, 3000, 2000),
            listOf(screen),
        )
        assertEquals(WindowBounds(0, 0, 1920, 1080), restored)
    }

    @Test
    fun `handles a monitor with negative coordinates`() {
        val left = Rectangle(-1920, 0, 1920, 1080)
        val bounds = WindowBounds(-1000, 100, 800, 600)
        assertEquals(bounds, WindowBoundsStore.sanitize(bounds, listOf(left)))
    }
}
