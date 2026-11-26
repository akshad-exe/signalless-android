package com.signalless.app.features.call

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reorders call frames and decides what to play every [frameMs].
 *
 * The target delay starts at 60 ms and follows measured packet jitter between
 * 40 and 120 ms. A frame that arrives after its slot is dropped.
 */
class JitterBuffer(private val frameMs: Int) {
    sealed interface Playout {
        class Frame(val data: ByteArray) : Playout
        /** One frame is missing: repeat the previous audio, quieter. */
        data object Conceal : Playout
        data object Silence : Playout
    }

    private val frames = TreeMap<Long, ByteArray>()
    private var newest = NONE
    private var next = NONE
    private var buffering = true
    private var misses = 0
    private var jitterMs = (START_DELAY_MS - MIN_DELAY_MS) / 2.0
    private var lastTransitMs: Long? = null

    val targetDelayMs: Int
        @Synchronized get() = (MIN_DELAY_MS + 2 * jitterMs).roundToInt().coerceAtMost(MAX_DELAY_MS)

    /** One packet: `seq` (u16) numbers its first frame. */
    @Synchronized
    fun offer(seq: Int, packetFrames: List<ByteArray>, arrivalMs: Long) {
        var first = if (newest == NONE) seq.toLong() else newest + (seq - newest.toInt()).toShort()
        if (next != NONE && abs(first - next) > MAX_JUMP_FRAMES) {
            reset()
            first = seq.toLong()
        }
        val transit = arrivalMs - first * frameMs
        lastTransitMs?.let { previous ->
            val change = abs(transit - previous)
            // Larger steps are pauses such as mute, not network jitter.
            if (change < PAUSE_MS) jitterMs += (change - jitterMs) / 16.0
        }
        lastTransitMs = transit
        packetFrames.forEachIndexed { index, frame ->
            val position = first + index
            if (next == NONE || position >= next) {
                frames[position] = frame
                newest = maxOf(newest, position)
            }
        }
    }

    @Synchronized
    fun poll(): Playout {
        val target = targetDelayMs
        if (buffering) {
            if (frames.isEmpty() || (frames.lastKey() - frames.firstKey() + 1) * frameMs < target) {
                return Playout.Silence
            }
            next = frames.firstKey()
            buffering = false
            misses = 0
        }
        // A burst left too much queued: skip ahead so latency does not stay high.
        if (frames.isNotEmpty() && (frames.lastKey() - next + 1) * frameMs > target + maxOf(100, 2 * frameMs)) {
            next = frames.lastKey() - target / frameMs
            frames.headMap(next).clear()
        }
        val frame = frames.remove(next)
        if (frame != null) {
            next++
            misses = 0
            return Playout.Frame(frame)
        }
        misses++
        // With later frames queued this one is lost. With nothing queued, wait for it.
        if (frames.isNotEmpty()) next++ else if (misses >= UNDERRUN_FRAMES) buffering = true
        return if (misses == 1) Playout.Conceal else Playout.Silence
    }

    private fun reset() {
        frames.clear()
        newest = NONE
        next = NONE
        buffering = true
        lastTransitMs = null
    }

    companion object {
        const val MIN_DELAY_MS = 40
        const val START_DELAY_MS = 60
        const val MAX_DELAY_MS = 120
        private const val NONE = -1L
        private const val MAX_JUMP_FRAMES = 500L
        private const val PAUSE_MS = 500L
        private const val UNDERRUN_FRAMES = 10
    }
}
