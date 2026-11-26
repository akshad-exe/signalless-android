package com.signalless.app.features.call

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech codec for one call. [encode] and [decode] may run on different threads,
 * each from one thread only. Close after both threads stop.
 */
interface VoiceCodec : AutoCloseable {
    val type: CallCodec

    /** Sample rate of [decode]'s output. It can change during the first calls. */
    val playbackRate: Int

    /** Takes 16 kHz mono PCM; returns the frames that are ready. */
    fun encode(pcm: ShortArray): List<ByteArray>

    /** Returns the PCM that is ready, which can be empty while the decoder starts. */
    fun decode(frame: ByteArray): ShortArray
}

object VoiceCodecs {
    const val CAPTURE_RATE = 16_000
    const val OPUS_BLE_BIT_RATE = 12_000
    const val OPUS_WIFI_BIT_RATE = 24_000
    private const val AAC_BIT_RATE = 16_000

    /** `CallSignal.codecMask` for this device. AAC-LC is always available. */
    fun localMask(): Int {
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.let { codecs ->
                listOf(true, false).all { encoder ->
                    codecs.any { info ->
                        info.isEncoder == encoder &&
                            info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, true) }
                    }
                }
            }
        return CallCodec.AAC_LC.mask or (if (opus) CallCodec.OPUS.mask else 0)
    }

    /** Opus when both sides have it, else AAC-LC, else null. */
    fun negotiate(localMask: Int, remoteMask: Int): CallCodec? {
        val shared = localMask and remoteMask
        return listOf(CallCodec.OPUS, CallCodec.AAC_LC).firstOrNull { shared and it.mask != 0 }
    }

    fun create(type: CallCodec, opusBitRate: Int = OPUS_BLE_BIT_RATE): VoiceCodec = when (type) {
        CallCodec.OPUS -> OpusCodec(opusBitRate)
        CallCodec.AAC_LC -> AacCodec(AAC_BIT_RATE)
    }
}

class OpusCodec(bitRate: Int) : MediaCodecVoiceCodec(
    CallCodec.OPUS,
    encoderFormat = audioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, VoiceCodecs.CAPTURE_RATE, bitRate).apply {
        // Constant bit rate keeps three frames inside one unfragmented packet.
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    },
    decoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, 1).apply {
        setByteBuffer("csd-0", opusHead())
        setByteBuffer("csd-1", nanos(0L))
        setByteBuffer("csd-2", nanos(80_000_000L))
    }
) {
    private companion object {
        const val OPUS_RATE = 48_000

        /** RFC 7845 identification header: version 1, mono, no pre-skip or gain. */
        fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1).put(1).putShort(0).putInt(OPUS_RATE).putShort(0).put(0)
            flip()
        }

        fun nanos(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}

class AacCodec(bitRate: Int) : MediaCodecVoiceCodec(
    CallCodec.AAC_LC,
    encoderFormat = audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, VoiceCodecs.CAPTURE_RATE, bitRate).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    },
    decoderFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC, VoiceCodecs.CAPTURE_RATE, 1
    ).apply {
        // AudioSpecificConfig: AAC-LC, 16 kHz, mono.
        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
    }
)

private fun audioFormat(mime: String, sampleRate: Int, bitRate: Int): MediaFormat =
    MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096)
    }

abstract class MediaCodecVoiceCodec(
    final override val type: CallCodec,
    private val encoderFormat: MediaFormat,
    private val decoderFormat: MediaFormat
) : VoiceCodec {
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var encodedSamples = 0L
    private var decodedFrames = 0L

    @Volatile
    final override var playbackRate: Int = decoderFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private set

    final override fun encode(pcm: ShortArray): List<ByteArray> {
        val codec = encoder ?: start(encoderFormat, encode = true).also { encoder = it }
        val output = mutableListOf<ByteArray>()
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        // A stalled encoder drops this block: late call audio has no value.
        if (index >= 0) {
            val input = checkNotNull(codec.getInputBuffer(index))
            input.clear()
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            val timeUs = encodedSamples * 1_000_000L / VoiceCodecs.CAPTURE_RATE
            encodedSamples += pcm.size
            codec.queueInputBuffer(index, 0, pcm.size * 2, timeUs, 0)
        }
        drain(codec) { output.add(ByteArray(it.remaining()).also { bytes -> it.get(bytes) }) }
        return output
    }

    final override fun decode(frame: ByteArray): ShortArray {
        val codec = decoder ?: start(decoderFormat, encode = false).also { decoder = it }
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) {
            val input = checkNotNull(codec.getInputBuffer(index))
            input.clear()
            input.put(frame)
            codec.queueInputBuffer(index, 0, frame.size, decodedFrames++ * type.frameMs * 1_000L, 0)
        }
        var pcm = ShortArray(0)
        drain(codec) {
            val samples = it.order(ByteOrder.nativeOrder()).asShortBuffer()
            pcm += ShortArray(samples.remaining()).also { out -> samples.get(out) }
        }
        return pcm
    }

    private fun start(format: MediaFormat, encode: Boolean): MediaCodec {
        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
        val codec = if (encode) MediaCodec.createEncoderByType(mime) else MediaCodec.createDecoderByType(mime)
        return try {
            codec.configure(format, null, null, if (encode) MediaCodec.CONFIGURE_FLAG_ENCODE else 0)
            codec.start()
            codec
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    private fun drain(codec: MediaCodec, consume: (ByteBuffer) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var timeoutUs = TIMEOUT_US
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (codec === decoder) {
                    playbackRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                continue
            }
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                consume(buffer)
            }
            codec.releaseOutputBuffer(index, false)
            timeoutUs = 0L
        }
    }

    final override fun close() {
        listOfNotNull(encoder, decoder).forEach { codec ->
            runCatching { codec.stop() }
            codec.release()
        }
        encoder = null
        decoder = null
    }

    private companion object {
        const val TIMEOUT_US = 5_000L
    }
}
