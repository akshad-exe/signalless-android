package com.signalless.relay.ui.compose

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.prefs.Preferences
import kotlin.math.max
import kotlin.math.min

/** Window geometry in physical screen pixels, so it survives DPI changes. */
internal data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun toRectangle(): Rectangle = Rectangle(x, y, width, height)
}

/** Persists the last window position and size. */
internal object WindowBoundsStore {
    private const val KEY_X = "window.x"
    private const val KEY_Y = "window.y"
    private const val KEY_WIDTH = "window.width"
    private const val KEY_HEIGHT = "window.height"

    private val preferences: Preferences? by lazy {
        runCatching { Preferences.userNodeForPackage(WindowBoundsStore::class.java) }.getOrNull()
    }

    fun load(): WindowBounds? {
        val prefs = preferences ?: return null
        return runCatching {
            val width = prefs.getInt(KEY_WIDTH, 0)
            val height = prefs.getInt(KEY_HEIGHT, 0)
            if (width <= 0 || height <= 0) {
                return@runCatching null
            }
            sanitize(
                WindowBounds(
                    x = prefs.getInt(KEY_X, 0),
                    y = prefs.getInt(KEY_Y, 0),
                    width = width,
                    height = height,
                ),
                screenBounds(),
            )
        }.getOrNull()
    }

    fun save(bounds: Rectangle) {
        if (bounds.width < 320 || bounds.height < 240) {
            return
        }
        val prefs = preferences ?: return
        runCatching {
            prefs.putInt(KEY_X, bounds.x)
            prefs.putInt(KEY_Y, bounds.y)
            prefs.putInt(KEY_WIDTH, bounds.width)
            prefs.putInt(KEY_HEIGHT, bounds.height)
            prefs.flush()
        }
    }

    /**
     * Keeps a saved rectangle only when at least part of its title bar is on a
     * monitor, and clamps oversized or slightly-offscreen geometry back into
     * the monitor it overlaps most.
     */
    internal fun sanitize(bounds: WindowBounds, screens: List<Rectangle>): WindowBounds? {
        if (bounds.width < 320 || bounds.height < 240 || screens.isEmpty()) {
            return null
        }
        val rectangle = bounds.toRectangle()
        val screen = screens.maxByOrNull { intersectionArea(rectangle, it) } ?: return null
        val visibleWidth = min(rectangle.x + rectangle.width, screen.x + screen.width) -
            max(rectangle.x, screen.x)
        val visibleHeight = min(rectangle.y + rectangle.height, screen.y + screen.height) -
            max(rectangle.y, screen.y)
        if (visibleWidth < 80 || visibleHeight < 80) {
            return null
        }

        val width = bounds.width.coerceAtMost(screen.width)
        val height = bounds.height.coerceAtMost(screen.height)
        val maxX = screen.x + screen.width - width
        val maxY = screen.y + screen.height - height
        return WindowBounds(
            x = bounds.x.coerceIn(screen.x, maxX),
            y = bounds.y.coerceIn(screen.y, maxY),
            width = width,
            height = height,
        )
    }

    private fun intersectionArea(a: Rectangle, b: Rectangle): Int {
        val width = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val height = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        return if (width > 0 && height > 0) width * height else 0
    }

    private fun screenBounds(): List<Rectangle> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
    }.getOrDefault(emptyList())
}
