// PrecisionSink.kt

package com.mochyy.mochymusic.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.mochyy.mochymusic.playback.StreamInspector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decides, song by song, whether audio reaches the track as 32-bit float or 16-bit. The sink
 * under it is built with float on, but media3's float path skips every audio processor (the
 * equalizer, audio fx, crossmix, haptics, skip silence, speed), so float only goes through while
 * [AudioOutputs.floatAllowed] says none of them would be lost. Otherwise hi-res audio is brought
 * down to 16-bit right here and takes the usual road, exactly like it did before.
 */
@UnstableApi
class PrecisionSink(
    delegate: AudioSink,
    private val outputs: AudioOutputs,
    private val inspector: StreamInspector?,
) : ForwardingAudioSink(delegate) {

    private var inputFormat: Format? = null
    private var bufferSize = 0
    private var outputChannels: IntArray? = null
    /** hi-res pcm is coming in and being turned into 16-bit here */
    private var converting = false
    private var configuredAllowed = false

    // a converted buffer the sink hasn't taken all of yet, and the decoder buffer it came from
    private var pending: ByteBuffer? = null
    private var pendingSource: ByteBuffer? = null
    private var scratch: ByteBuffer? = null

    // the decoder is asked for float only when float would make it all the way to the track
    override fun getFormatSupport(format: Format): Int {
        if (format.pcmEncoding == C.ENCODING_PCM_FLOAT && MimeTypes.AUDIO_RAW == format.sampleMimeType && !outputs.floatAllowed) {
            return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        return super.getFormatSupport(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        this.inputFormat = inputFormat
        this.bufferSize = specifiedBufferSize
        this.outputChannels = outputChannels
        inspector?.onSinkConfigured(inputFormat)
        applyConfiguration()
    }

    private fun applyConfiguration() {
        val format = inputFormat ?: return
        val isPcm = MimeTypes.AUDIO_RAW == format.sampleMimeType
        val hiRes = isPcm && Util.isEncodingHighResolutionPcm(format.pcmEncoding)
        configuredAllowed = outputs.floatAllowed
        converting = hiRes && !configuredAllowed
        pending = null
        pendingSource = null
        if (isPcm) {
            outputs.prepareMixer(
                sampleRate = format.sampleRate,
                channels = outputChannels?.size ?: format.channelCount,
                encoding = if (hiRes && !converting) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
            )
        }
        val handed = if (converting) format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build() else format
        super.configure(handed, bufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val format = inputFormat
        // an effect was switched on (or every one off) mid-song: the sink drains and reopens, a moment's gap
        if (format != null && pending == null && configuredAllowed != outputs.floatAllowed &&
            MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingHighResolutionPcm(format.pcmEncoding)
        ) {
            applyConfiguration()
        }
        if (!converting) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        if (pending == null || pendingSource !== buffer) {
            pending = toInt16(buffer, format!!.pcmEncoding)
            pendingSource = buffer
            buffer.position(buffer.limit())
        }
        val done = super.handleBuffer(pending!!, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            pending = null
            pendingSource = null
        }
        return done
    }

    override fun flush() {
        pending = null
        pendingSource = null
        super.flush()
    }

    override fun reset() {
        pending = null
        pendingSource = null
        super.reset()
    }

    // keeps the top 16 bits of each sample, the same cut media3's own 16-bit path makes
    private fun toInt16(source: ByteBuffer, encoding: Int): ByteBuffer {
        val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = when (encoding) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> input.remaining() / 3
            else -> input.remaining() / 4
        }
        val out = scratch?.takeIf { it.capacity() >= samples * 2 }
            ?: ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.LITTLE_ENDIAN).also { scratch = it }
        out.clear()
        val start = input.position()
        when (encoding) {
            C.ENCODING_PCM_24BIT -> for (i in 0 until samples) {
                out.put(input.get(start + i * 3 + 1))
                out.put(input.get(start + i * 3 + 2))
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> for (i in 0 until samples) {
                out.put(input.get(start + i * 3 + 1))
                out.put(input.get(start + i * 3))
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until samples) {
                out.put(input.get(start + i * 4 + 2))
                out.put(input.get(start + i * 4 + 3))
            }
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> for (i in 0 until samples) {
                out.put(input.get(start + i * 4 + 1))
                out.put(input.get(start + i * 4))
            }
            else -> for (i in 0 until samples) {
                val value = input.getFloat(start + i * 4).coerceIn(-1f, 1f)
                out.putShort((value * Short.MAX_VALUE).roundToInt().toShort())
            }
        }
        out.flip()
        return out
    }
}
