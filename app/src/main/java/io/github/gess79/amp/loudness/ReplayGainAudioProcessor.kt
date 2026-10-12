package io.github.gess79.amp.loudness

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * Capture original PCM for learning, then apply ReplayGain and optional limiter.
 * Never writes back to files.
 */
@Singleton
class ReplayGainAudioProcessor @Inject constructor() : BaseAudioProcessor() {
    @Volatile var captureEnabled = false
    @Volatile var limiterEnabled = false
    @Volatile private var gainLinear = 1f
    @Volatile private var limiterGain = 1f

    private val meter = LoudnessMeter()
    private var configuredRate = 0
    private var configuredChannels = 2

    @Synchronized
    fun resetCapture(sampleRate: Int = configuredRate, channels: Int = configuredChannels) {
        val rate = if (sampleRate > 0) sampleRate else 44_100
        val ch = channels.coerceIn(1, 2)
        meter.reset(rate, ch)
        limiterGain = 1f
    }

    @Synchronized
    fun finishCapture(): LoudnessResult? {
        if (!captureEnabled) return null
        return meter.result()
    }

    fun setGainDb(db: Float) {
        gainLinear = ReplayGain.dbToLinear(db)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredRate = inputAudioFormat.sampleRate
        configuredChannels = inputAudioFormat.channelCount.coerceIn(1, 2)
        resetCapture(configuredRate, configuredChannels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val channels = configuredChannels
        val ceiling = ReplayGain.dbToLinear(ReplayGain.LIMITER_CEILING_DB)
        val capture = captureEnabled
        val limiter = limiterEnabled
        val gain = gainLinear
        val shorts = remaining / 2
        val captureBuf = if (capture) FloatArray(shorts) else null
        var captureIndex = 0
        val inBuf = inputBuffer
        while (inBuf.hasRemaining()) {
            // Linked stereo: scale both samples of a frame by the same limiter gain.
            if (channels == 2 && inBuf.remaining() >= 4) {
                val l = inBuf.short / 32768f
                val r = inBuf.short / 32768f
                if (captureBuf != null) {
                    captureBuf[captureIndex++] = l
                    captureBuf[captureIndex++] = r
                }
                var gl = l * gain
                var gr = r * gain
                if (limiter) {
                    val peak = maxOf(abs(gl), abs(gr))
                    var lg = limiterGain
                    val needed = if (peak > ceiling) ceiling / peak else 1f
                    lg = if (needed < lg) needed else lg + (1f - lg) * 0.0008f
                    limiterGain = lg
                    gl *= lg
                    gr *= lg
                    gl = gl.coerceIn(-ceiling, ceiling)
                    gr = gr.coerceIn(-ceiling, ceiling)
                }
                output.putShort(toShort(gl))
                output.putShort(toShort(gr))
            } else {
                val s = inBuf.short / 32768f
                if (captureBuf != null && captureIndex < captureBuf.size) {
                    captureBuf[captureIndex++] = s
                }
                var g = s * gain
                if (limiter) {
                    val peak = abs(g)
                    var lg = limiterGain
                    val needed = if (peak > ceiling) ceiling / peak else 1f
                    lg = if (needed < lg) needed else lg + (1f - lg) * 0.0008f
                    limiterGain = lg
                    g = (g * lg).coerceIn(-ceiling, ceiling)
                }
                output.putShort(toShort(g))
            }
        }
        if (captureBuf != null && captureIndex > 0) {
            synchronized(this) {
                meter.addInterleaved(captureBuf, min(captureIndex, captureBuf.size))
            }
        }
        output.flip()
    }

    private fun toShort(sample: Float): Short {
        val clipped = sample.coerceIn(-1f, 1f)
        return (clipped * 32767f).toInt().toShort()
    }
}
