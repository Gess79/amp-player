package io.github.gess79.amp.loudness

import java.nio.charset.Charset
import kotlin.math.log10

/**
 * Read-only scan of ReplayGain / R128 tags in the file header.
 * Does not rewrite tags.
 */
object ReplayGainTags {
    private val gainRegex = Regex(
        """replaygain_track_gain.{0,8}?([+-]?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val r128Regex = Regex(
        """r128_track_gain.{0,8}?([+-]?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val peakRegex = Regex(
        """replaygain_track_peak.{0,8}?(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(header: ByteArray): LoudnessResult? {
        val text = header.toString(Charset.forName("ISO-8859-1"))
            .replace('\u0000', ' ')
        val gainMatch = gainRegex.find(text)
        val r128Match = r128Regex.find(text)
        val peakMatch = peakRegex.find(text)
        val gainDb = when {
            gainMatch != null -> gainMatch.groupValues[1].toFloatOrNull()
            r128Match != null -> r128Match.groupValues[1].toFloatOrNull()
            else -> null
        } ?: return null
        // Classic ReplayGain 89 dB ≈ -18 LUFS. Applying gainDb reaches that reference.
        val lufs = -18f - gainDb
        val peakLinear = peakMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        val peakDb = (20.0 * log10(peakLinear.toDouble().coerceAtLeast(1e-12))).toFloat()
        return LoudnessResult(lufs = lufs, peakDb = peakDb)
    }

    fun parseAscii(text: String): LoudnessResult? =
        parse(text.toByteArray(Charset.forName("ISO-8859-1")))
}
