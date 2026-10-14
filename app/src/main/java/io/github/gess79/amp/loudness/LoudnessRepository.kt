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
) {
    val needsAnalysis: Boolean get() = stale + missing > 0
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

    suspend fun getIfCurrent(track: TrackEntity): LoudnessEntity? {
        val entry = loudnessDao.get(track.id) ?: return null
        return if (isCurrent(entry, track)) entry else null
    }

    suspend fun upsert(
        track: TrackEntity,
        result: LoudnessResult,
        source: String,
    ) {
        val size = fingerprintSize(track)
        loudnessDao.upsert(
            LoudnessEntity(
                trackId = track.id,
                sizeBytes = size,
                dateModified = track.dateModified,
                lufs = result.lufs,
                peakDb = result.peakDb,
                source = source,
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

    private fun statusFor(
        tracks: List<TrackEntity>,
        entries: List<LoudnessEntity>,
    ): LoudnessPlaylistStatus {
        val map = entries.associateBy { it.trackId }
        var current = 0
        var stale = 0
        var missing = 0
        for (track in tracks) {
            val entry = map[track.id]
            when {
                entry == null -> missing++
                !isCurrent(entry, track) -> stale++
                else -> current++
            }
        }
        val total = tracks.size
        val needsAnalysis = stale + missing > 0
        val label = when {
            total == 0 -> context.getString(R.string.rg_no_tracks)
            !needsAnalysis -> context.getString(R.string.rg_all_current, total)
            stale > 0 && missing > 0 -> context.getString(
                R.string.rg_stale_and_missing,
                current,
                total,
                stale,
                missing,
            )
            stale > 0 -> context.getString(R.string.rg_stale_needed, current, total, stale)
            else -> context.getString(R.string.rg_missing_needed, current, total, missing)
        }
        return LoudnessPlaylistStatus(total, current, stale, missing, label)
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
