package io.github.gess79.amp.loudness

import kotlin.math.abs

/** Compact peak envelope: one unsigned byte (0–255) per bar, linear amplitude. */
object WaveformPeaks {
    const val BAR_COUNT = 96

    fun downsample(blockPeaks: List<Float>): ByteArray {
        val out = ByteArray(BAR_COUNT)
        if (blockPeaks.isEmpty()) return out
        for (bar in 0 until BAR_COUNT) {
            val start = bar * blockPeaks.size / BAR_COUNT
            var end = (bar + 1) * blockPeaks.size / BAR_COUNT
            if (end <= start) end = (start + 1).coerceAtMost(blockPeaks.size)
            var peak = 0f
            for (i in start until end) {
                if (blockPeaks[i] > peak) peak = blockPeaks[i]
            }
            val level = (peak.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            out[bar] = level.toByte()
        }
        return out
    }
}

/** Collects block peaks while PCM is decoded, then reduces them to [WaveformPeaks.BAR_COUNT]. */
class WaveformAccumulator {
    private var channels = 1
    private val blocks = ArrayList<Float>(2048)
    private var blockMax = 0f
    private var frames = 0

    fun reset(channelCount: Int) {
        channels = channelCount.coerceAtLeast(1)
        blocks.clear()
        blockMax = 0f
        frames = 0
    }

    fun addInterleaved(samples: FloatArray, length: Int) {
        val ch = channels
        var i = 0
        while (i + ch <= length) {
            var peak = 0f
            for (c in 0 until ch) {
                val a = abs(samples[i + c])
                if (a > peak) peak = a
            }
            if (peak > blockMax) blockMax = peak
            frames++
            if (frames >= FRAMES_PER_BLOCK) {
                blocks.add(blockMax.coerceIn(0f, 1f))
                blockMax = 0f
                frames = 0
            }
            i += ch
        }
    }

    fun finish(): ByteArray {
        if (frames > 0) blocks.add(blockMax.coerceIn(0f, 1f))
        return WaveformPeaks.downsample(blocks)
    }

    private companion object {
        const val FRAMES_PER_BLOCK = 4096
    }
}
