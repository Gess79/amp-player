package io.github.gess79.amp.loudness

import android.content.Context
import io.github.gess79.amp.R
import io.github.gess79.amp.data.db.LoudnessDao
import io.github.gess79.amp.data.db.LoudnessEntity
import io.github.gess79.amp.data.db.PlaylistDao
import io.github.gess79.amp.data.db.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class LoudnessPlaylistStatus(
    val total: Int,
    val current: Int,
    val stale: Int,
    val missing: Int,
    val label: String,
    val waveformMissing: Int = 0,
) {
    val needsAnalysis: Boolean get() = stale + missing + waveformMissing > 0
}

@Singleton
class LoudnessRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loudnessDao: LoudnessDao,
    private val playlistDao: PlaylistDao,
) {
    fun observePlaylistStatus(playlistId: Long): Flow<LoudnessPlaylistStatus> =
        observeStatus(playlistDao.observeTracks(playlistId))

    fun observeStatus(tracks: Flow<List<TrackEntity>>): Flow<LoudnessPlaylistStatus> =
        combine(tracks, loudnessDao.observeAll()) { list, entries -> statusFor(list, entries) }

    suspend fun get(trackId: Long): LoudnessEntity? = loudnessDao.get(trackId)

    fun observe(trackId: Long): Flow<LoudnessEntity?> = loudnessDao.observe(trackId)

    suspend fun getIfCurrent(track: TrackEntity): LoudnessEntity? {
        val entry = loudnessDao.get(track.id) ?: return null
        return if (isCurrent(entry, track)) entry else null
    }

    /**
     * Writes loudness. Keeps an existing waveform when [waveform] is null and the file size
     * still matches. A size change drops a stale envelope unless a new one is passed in.
     */
    suspend fun upsert(
        track: TrackEntity,
        result: LoudnessResult,
        source: String,
        waveform: ByteArray? = null,
    ) {
        val size = fingerprintSize(track)
        val existing = loudnessDao.get(track.id)
        val wave = waveform ?: existing?.waveform?.takeIf { existing.sizeBytes == size }
        loudnessDao.upsert(
            LoudnessEntity(
                trackId = track.id,
                sizeBytes = size,
                dateModified = track.dateModified,
                lufs = result.lufs,
                peakDb = result.peakDb,
                source = source,
                waveform = wave,
            ),
        )
    }

    /** Writes only the peak envelope. Loudness values already stored for this file stay. */
    suspend fun saveWaveform(track: TrackEntity, waveform: ByteArray) {
        val existing = loudnessDao.get(track.id) ?: return
        loudnessDao.upsert(
            existing.copy(
                sizeBytes = fingerprintSize(track),
                dateModified = track.dateModified,
                updatedAt = System.currentTimeMillis(),
                waveform = waveform,
            ),
        )
    }

    suspend fun resetPlaylist(playlistId: Long) {
        resetTracks(playlistDao.getTrackIds(playlistId))
    }

    suspend fun resetTracks(trackIds: List<Long>) {
        if (trackIds.isNotEmpty()) loudnessDao.deleteByTrackIds(trackIds)
    }

    fun isCurrent(entry: LoudnessEntity, track: TrackEntity): Boolean =
        isLoudnessCurrent(entry, track)

    fun isWaveformCurrent(entry: LoudnessEntity, track: TrackEntity): Boolean =
        isWaveformCurrentEntry(entry, track)

    private fun statusFor(
        tracks: List<TrackEntity>,
        entries: List<LoudnessEntity>,
    ): LoudnessPlaylistStatus {
        val map = entries.associateBy { it.trackId }
        var current = 0
        var stale = 0
        var missing = 0
        var waveformMissing = 0
        for (track in tracks) {
            val entry = map[track.id]
            when {
                entry == null -> missing++
                !isCurrent(entry, track) -> stale++
                !isWaveformCurrent(entry, track) -> {
                    current++
                    waveformMissing++
                }
                else -> current++
            }
        }
        val total = tracks.size
        val loudnessGaps = stale + missing > 0
        val label = when {
            total == 0 -> context.getString(R.string.rg_no_tracks)
            loudnessGaps && stale > 0 && missing > 0 -> context.getString(
                R.string.rg_stale_and_missing,
                current,
                total,
                stale,
                missing,
            )
            loudnessGaps && stale > 0 -> context.getString(R.string.rg_stale_needed, current, total, stale)
            loudnessGaps -> context.getString(R.string.rg_missing_needed, current, total, missing)
            waveformMissing > 0 -> context.getString(
                R.string.rg_waveform_missing,
                total,
                waveformMissing,
            )
            else -> context.getString(R.string.rg_all_current, total)
        }
        return LoudnessPlaylistStatus(total, current, stale, missing, label, waveformMissing)
    }
}

internal fun fingerprintSize(track: TrackEntity): Long {
    val fileLen = java.io.File(track.path).takeIf { it.isFile }?.length() ?: 0L
    return if (fileLen > 0L) fileLen else track.sizeBytes
}

internal fun isLoudnessCurrent(entry: LoudnessEntity, track: TrackEntity): Boolean {
    val size = fingerprintSize(track)
    if (entry.sizeBytes == 0L || size == 0L) return true
    return entry.sizeBytes == size
}

internal fun isWaveformCurrentEntry(entry: LoudnessEntity, track: TrackEntity): Boolean {
    val peaks = entry.waveform ?: return false
    if (peaks.size != WaveformPeaks.BAR_COUNT) return false
    return isLoudnessCurrent(entry, track)
}
