package com.signalless.app.features.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex audio of one call, held only in memory.
 *
 * record -> encode -> packetize -> [send], and [onPacket] -> jitter buffer ->
 * decode -> play. Single use: create one engine per call. The caller owns audio
 * mode, focus, and routing, and must hold the microphone permission.
 */
class CallAudioEngine(
    callId: ByteArray,
    private val codec: VoiceCodec,
    private val send: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    private val callIdPrefix = callId.copyOf(CallAudioPacket.CALL_ID_PREFIX_SIZE)
    private val packetizer = CallAudioPacketizer(callId, codec.type, clockMs)
    private val outbound = CallSendQueue()
    private val jitter = JitterBuffer(codec.type.frameMs)
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private val effects = mutableListOf<AudioEffect>()
    private var threads = emptyList<Thread>()

    @Volatile
    var muted = false

    /** Returns false when the microphone cannot be opened. */
    fun start(): Boolean {
        check(threads.isEmpty()) { "engine already started" }
        val microphone = try {
            openMicrophone()
        } catch (error: Exception) {
            Log.w(TAG, "Microphone unavailable: ${error.message}")
            release()
            return false
        }
        running.set(true)
        threads = listOf(
            worker("capture") { captureLoop(microphone) },
            worker("send") { while (running.get()) outbound.poll(100L)?.let(send) },
            worker("play") { playbackLoop() }
        )
        threads.forEach(Thread::start)
        return true
    }

    /** Queues received audio. Returns false for a packet that is not for this call. */
    fun onPacket(payload: ByteArray): Boolean {
        val packet = CallAudioPacket.decode(payload) ?: return false
        if (packet.codec != codec.type || !packet.callId.contentEquals(callIdPrefix)) return false
        jitter.offer(packet.seq, packet.frames, clockMs())
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { record?.stop() }
        threads.forEach { it.join(JOIN_TIMEOUT_MS) }
        // A thread stuck in a blocking call still owns the codec; leaking it beats crashing.
        if (threads.none(Thread::isAlive)) release()
    }

    private fun worker(name: String, body: () -> Unit) = Thread({
        try {
            body()
        } catch (error: Exception) {
            if (running.getAndSet(false)) {
                Log.w(TAG, "Call audio $name failed: ${error.message}")
                onFailure(error)
            }
        }
    }, "SignalLess-call-$name")

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord {
        val rate = VoiceCodecs.CAPTURE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "no record buffer: $minBuffer" }
        val microphone = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CAPTURE_SAMPLES * 8)
        )
        record = microphone
        check(microphone.state == AudioRecord.STATE_INITIALIZED) { "not initialized" }
        val session = microphone.audioSessionId
        listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null
        ).forEach { effect ->
            effect.enabled = true
            effects.add(effect)
        }
        microphone.startRecording()
        check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "not recording" }
        return microphone
    }

    private fun captureLoop(microphone: AudioRecord) {
        val pcm = ShortArray(CAPTURE_SAMPLES)
        while (running.get()) {
            val read = microphone.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (!running.get()) return
            check(read >= 0) { "read failed: $read" }
            if (muted) {
                packetizer.reset()
                continue
            }
            if (read == 0) continue
            codec.encode(pcm.copyOf(read)).forEach { frame ->
                packetizer.add(frame).forEach(outbound::offer)
            }
        }
    }

    /** Blocking writes pace this loop, so the jitter buffer is polled in real time. */
    private fun playbackLoop() {
        var track: AudioTrack? = null
        var last = ShortArray(0)
        try {
            while (running.get()) {
                val pcm = when (val next = jitter.poll()) {
                    is JitterBuffer.Playout.Frame ->
                        codec.decode(next.data).also { if (it.isNotEmpty()) last = it }
                    JitterBuffer.Playout.Conceal ->
                        // -6 dB repeat of the previous audio.
                        if (last.isNotEmpty()) ShortArray(last.size) { (last[it] / 2).toShort() }
                        else silence()
                    JitterBuffer.Playout.Silence -> silence()
                }
                if (pcm.isEmpty()) continue
                val output = track?.takeIf { it.sampleRate == codec.playbackRate }
                    ?: run {
                        track?.release()
                        openSpeaker(codec.playbackRate).also { track = it }
                    }
                val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                check(written >= 0) { "write failed: $written" }
            }
        } finally {
            track?.release()
        }
    }

    private fun silence() = ShortArray(codec.playbackRate * codec.type.frameMs / 1_000)

    private fun openSpeaker(rate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "no playback buffer: $minBuffer" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuffer)
            .build()
            .also { it.play() }
    }

    private fun release() {
        effects.forEach(AudioEffect::release)
        effects.clear()
        record?.release()
        record = null
        codec.close()
    }

    private companion object {
        const val TAG = "CallAudioEngine"
        const val CAPTURE_SAMPLES = VoiceCodecs.CAPTURE_RATE / 50 // 20 ms
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
