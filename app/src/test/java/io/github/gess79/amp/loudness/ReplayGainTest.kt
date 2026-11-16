package io.github.gess79.amp.loudness

import io.github.gess79.amp.data.db.LoudnessEntity
import io.github.gess79.amp.data.db.TrackEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sin

class ReplayGainTest {

    @Test
    fun gainOnly_doesNotExceedPeakHeadroom() {
        val gain = ReplayGain.playbackGainDb(lufs = -20f, peakDb = -0.5f, limiter = false)
        assertThat(gain).isWithin(0.05f).of(0f)
    }

    @Test
    fun gainOnly_reachesTargetWhenPeakAllows() {
        val gain = ReplayGain.playbackGainDb(lufs = -20f, peakDb = -12f, limiter = false)
        assertThat(gain).isWithin(0.05f).of(6f)
    }

    @Test
    fun limiterMode_usesDesiredGainCapped() {
        val gain = ReplayGain.playbackGainDb(lufs = -40f, peakDb = -0.1f, limiter = true)
        assertThat(gain).isWithin(0.05f).of(ReplayGain.MAX_GAIN_DB)
    }

    @Test
    fun tags_parseClassicReplayGain() {
        val header = "xxxx REPLAYGAIN_TRACK_GAIN=-6.54 dB replaygain_track_peak=0.5 yyyy"
        val result = ReplayGainTags.parseAscii(header)
        assertThat(result).isNotNull()
        assertThat(result!!.lufs).isWithin(0.1f).of(-11.46f)
        assertThat(result.peakDb).isWithin(0.2f).of(-6.02f)
    }

    @Test
    fun meter_fullScaleSineHasPeakNearZero() {
        val meter = LoudnessMeter()
        meter.reset(48_000, 1)
        val n = 48_000
        val samples = FloatArray(n) { i ->
            sin(2.0 * Math.PI * 1000.0 * i / 48_000.0).toFloat()
        }
        meter.addInterleaved(samples)
        val result = meter.result()
        assertThat(result).isNotNull()
        assertThat(result!!.peakDb).isGreaterThan(-0.2f)
        assertThat(result.peakDb).isAtMost(0.1f)
        assertThat(result.lufs).isGreaterThan(-8f)
        assertThat(result.lufs).isLessThan(-2f)
    }

    @Test
    fun fingerprint_sizeMatchWithZeroDateModifiedIsCurrent() {
        val cached = loudness(size = 1_000, modified = 0)
        val track = track(size = 1_000, modified = 1_700_000_000)
        assertThat(isLoudnessCurrent(cached, track)).isTrue()
    }

    @Test
    fun fingerprint_sizeMismatchIsStale() {
        val cached = loudness(size = 1_000, modified = 10)
        val track = track(size = 2_000, modified = 10)
        assertThat(isLoudnessCurrent(cached, track)).isFalse()
    }

    @Test
    fun fingerprint_dateMismatchStillCurrentIfSizeMatches() {
        val cached = loudness(size = 1_000, modified = 10)
        val changed = track(size = 1_000, modified = 20)
        val same = track(size = 1_000, modified = 10)
        assertThat(isLoudnessCurrent(cached, changed)).isTrue()
        assertThat(isLoudnessCurrent(cached, same)).isTrue()
    }

    @Test
    fun waveform_missingEnvelopeIsNotCurrent() {
        val entry = loudness(size = 1_000, modified = 10)
        val track = track(size = 1_000, modified = 10)
        assertThat(isWaveformCurrentEntry(entry, track)).isFalse()
    }

    @Test
    fun waveform_downsampleKeepsLoudAndQuietBars() {
        val blocks = List(WaveformPeaks.BAR_COUNT) { index -> if (index < 48) 1f else 0f }
        val out = WaveformPeaks.downsample(blocks)
        assertThat(out.size).isEqualTo(WaveformPeaks.BAR_COUNT)
        assertThat(out[0].toInt() and 0xFF).isEqualTo(255)
        assertThat(out.last().toInt() and 0xFF).isEqualTo(0)
    }

    @Test
    fun waveform_storedEnvelopeMatchesFileSize() {
        val peaks = ByteArray(WaveformPeaks.BAR_COUNT) { 40 }
        val entry = loudness(size = 1_000, modified = 10).copy(waveform = peaks)
        val same = track(size = 1_000, modified = 99)
        val changed = track(size = 2_000, modified = 10)
        assertThat(isWaveformCurrentEntry(entry, same)).isTrue()
        assertThat(isWaveformCurrentEntry(entry, changed)).isFalse()
    }

    @Test
    fun limiter_doesNotBoostTrackAlreadyAtTarget() {
        val gain = ReplayGain.playbackGainDb(lufs = -13.8f, peakDb = 0f, limiter = true)
        assertThat(gain).isWithin(0.05f).of(-0.2f)
    }

    private fun track(size: Long, modified: Long) = TrackEntity(
        id = 1,
        title = "t",
        artist = "a",
        album = "al",
        durationMs = 1,
        path = "/x",
        directory = "/x",
        albumArtUri = null,
        dateAdded = 0,
        sizeBytes = size,
        dateModified = modified,
    )

    private fun loudness(size: Long, modified: Long) = LoudnessEntity(
        trackId = 1,
        sizeBytes = size,
        dateModified = modified,
        lufs = -14f,
        peakDb = -1f,
        source = "scan",
    )
}
