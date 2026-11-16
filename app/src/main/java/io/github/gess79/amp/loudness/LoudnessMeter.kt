package io.github.gess79.amp.loudness

import kotlin.math.log10
import kotlin.math.pow

/**
 * Incremental ITU-R BS.1770-style integrated loudness + sample peak.
 * Good enough for ReplayGain-style playback; not a certified meter.
 */
class LoudnessMeter {
    private var sampleRate = 0
    private var channels = 2
    private val shelf = Array(2) { Biquad() }
    private val hp = Array(2) { Biquad() }
    private val block = Array(2) { FloatArray(0) }
    private var blockSize = 0
    private var hopSize = 0
    private var write = 0
    private val meanSquares = ArrayList<Double>(512)
    private var peak = 1e-9f
    private var totalSamples = 0L

    fun reset(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8000)
        channels = channelCount.coerceIn(1, 2)
        configureFilters()
        blockSize = (this.sampleRate * 0.4).toInt().coerceAtLeast(64)
        hopSize = (this.sampleRate * 0.1).toInt().coerceAtLeast(16)
        for (c in 0 until 2) {
            block[c] = FloatArray(blockSize)
            shelf[c].reset()
            hp[c].reset()
        }
        write = 0
        meanSquares.clear()
        peak = 1e-9f
        totalSamples = 0L
    }

    fun addInterleaved(samples: FloatArray, length: Int = samples.size) {
        if (sampleRate <= 0 || length <= 0) return
        var i = 0
        while (i + channels - 1 < length) {
            addFrame(samples[i], if (channels == 2) samples[i + 1] else samples[i])
            i += channels
        }
    }

    fun addPcm16(samples: ShortArray, length: Int) {
        if (sampleRate <= 0 || length <= 0) return
        val scale = 1f / 32768f
        if (channels == 2) {
            var i = 0
            while (i + 1 < length) {
                addFrame(samples[i] * scale, samples[i + 1] * scale)
                i += 2
            }
        } else {
            for (i in 0 until length) {
                val x = samples[i] * scale
                addFrame(x, x)
            }
        }
    }

    private fun addFrame(left: Float, right: Float) {
        val peakL = if (left >= 0f) left else -left
        val peakR = if (right >= 0f) right else -right
        val framePeak = if (channels == 2) {
            if (peakL > peakR) peakL else peakR
        } else {
            peakL
        }
        if (framePeak > peak) peak = framePeak
        block[0][write] = hp[0].process(shelf[0].process(left))
        if (channels == 2) {
            block[1][write] = hp[1].process(shelf[1].process(right))
        }
        write++
        totalSamples++
        if (write >= blockSize) {
            commitBlock()
            val remain = blockSize - hopSize
            if (remain > 0) {
                System.arraycopy(block[0], hopSize, block[0], 0, remain)
                if (channels == 2) {
                    System.arraycopy(block[1], hopSize, block[1], 0, remain)
                }
            }
            write = remain.coerceAtLeast(0)
        }
    }

    fun result(): LoudnessResult? {
        if (write >= hopSize) commitBlock()
        if (meanSquares.isEmpty() || totalSamples < sampleRate / 4) return null
        val ungated = meanSquares.average()
        val absThreshold = 10.0.pow((-70.0 + 0.691) / 10.0)
        val aboveAbs = meanSquares.filter { it > absThreshold }
        val gated = if (aboveAbs.isEmpty()) ungated else {
            val rel = aboveAbs.average()
            val relThreshold = rel * 10.0.pow(-1.0)
            val aboveRel = aboveAbs.filter { it > relThreshold }
            if (aboveRel.isEmpty()) rel else aboveRel.average()
        }
        val lufs = (-0.691 + 10.0 * log10(gated.coerceAtLeast(1e-12))).toFloat()
        val peakDb = (20.0 * log10(peak.toDouble().coerceAtLeast(1e-12))).toFloat()
        return LoudnessResult(lufs = lufs, peakDb = peakDb)
    }

    private fun commitBlock() {
        if (write <= 0) return
        var ms = 0.0
        val n = write
        for (c in 0 until channels) {
            var sum = 0.0
            val buf = block[c]
            for (i in 0 until n) {
                val v = buf[i].toDouble()
                sum += v * v
            }
            ms += sum / n
        }
        meanSquares += ms
    }

    private fun configureFilters() {
        val (sB, sA, hB, hA) = coefficients(sampleRate)
        for (c in 0 until 2) {
            shelf[c].set(sB, sA)
            hp[c].set(hB, hA)
        }
    }

    private fun coefficients(sr: Int): FilterSet {
        // Published BS.1770 / libebur128 coefficients.
        return if (sr >= 46_000) {
            FilterSet(
                bShelf = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285),
                aShelf = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585),
                bHp = doubleArrayOf(1.0, -2.0, 1.0),
                aHp = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621),
            )
        } else {
            FilterSet(
                bShelf = doubleArrayOf(1.5308412300503478, -2.6509799951547293, 1.1690790799215869),
                aShelf = doubleArrayOf(1.0, -1.6636550791611476, 0.7125954282417920),
                bHp = doubleArrayOf(1.0, -2.0, 1.0),
                aHp = doubleArrayOf(1.0, -1.9891696735107023, 0.9891995484900693),
            )
        }
    }

    private data class FilterSet(
        val bShelf: DoubleArray,
        val aShelf: DoubleArray,
        val bHp: DoubleArray,
        val aHp: DoubleArray,
    )

    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var z1 = 0.0
        private var z2 = 0.0

        fun set(b: DoubleArray, a: DoubleArray) {
            b0 = b[0]; b1 = b[1]; b2 = b[2]
            a1 = a[1]; a2 = a[2]
            reset()
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y.toFloat()
        }
    }
}

data class LoudnessResult(
    val lufs: Float,
    val peakDb: Float,
)

data class PcmMeasurement(
    val loudness: LoudnessResult,
    val waveform: ByteArray,
)
