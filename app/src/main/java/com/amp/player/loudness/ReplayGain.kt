package com.amp.player.loudness

object ReplayGain {
    const val TARGET_LUFS = -14f
    const val MAX_GAIN_DB = 12f
    const val PEAK_HEADROOM_DB = 0.5f
    const val LIMITER_CEILING_DB = -1f

    fun playbackGainDb(lufs: Float, peakDb: Float, limiter: Boolean): Float {
        val desired = (TARGET_LUFS - lufs).coerceIn(-24f, MAX_GAIN_DB)
        if (limiter) return desired
        val maxBeforeClip = -peakDb - PEAK_HEADROOM_DB
        return minOf(desired, maxBeforeClip)
    }

    fun dbToLinear(db: Float): Float =
        Math.pow(10.0, db.toDouble() / 20.0).toFloat()
}
