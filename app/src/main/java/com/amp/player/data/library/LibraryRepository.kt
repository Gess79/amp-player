package com.amp.player.data.library

import android.content.Context
import com.amp.player.R
import com.amp.player.data.db.PlaylistDao
import com.amp.player.data.db.PlaylistEntity
import com.amp.player.data.db.PlayHistoryDao
import com.amp.player.data.db.PlayHistoryEntity
import com.amp.player.data.db.SourceQueueDao
import com.amp.player.data.db.TrackDao
import com.amp.player.data.db.TrackEntity
import com.amp.player.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val playHistoryDao: PlayHistoryDao,
    private val sourceQueueDao: SourceQueueDao,
    private val scanner: MediaStoreScanner,
) {
    private val favoritesMutex = Mutex()

    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun observeDirectories(): Flow<List<String>> = trackDao.observeDirectories()
    fun observeByDirectory(directory: String): Flow<List<TrackEntity>> =
        trackDao.observeByDirectory(directory)

    fun search(query: String): Flow<List<TrackEntity>> = trackDao.search(query)
    fun searchFiltered(
        title: String,
        artist: String,
        album: String,
        directory: String,
    ): Flow<List<TrackEntity>> = trackDao.searchFiltered(title, artist, album, directory)

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.observeTracks(playlistId)

    fun observeRecent(limit: Int = 50): Flow<List<TrackEntity>> =
        playHistoryDao.observeRecent(limit)

    suspend fun getTrack(id: Long): TrackEntity? = trackDao.getById(id)
    suspend fun getTracksByIds(ids: List<Long>): List<TrackEntity> {
        if (ids.isEmpty()) return emptyList()
        val map = trackDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    suspend fun getPlaylist(playlistId: Long) = playlistDao.getById(playlistId)

    suspend fun getPlaylistTracks(playlistId: Long): List<TrackEntity> =
        playlistDao.getTracks(playlistId)

    suspend fun getDirectoryTracks(directory: String): List<TrackEntity> =
        trackDao.getByDirectory(directory)

    suspend fun getAllTracks(): List<TrackEntity> = trackDao.getAll()

    suspend fun refreshLibrary(): Int = withContext(Dispatchers.IO) {
        val scanned = scanner.scan()
        if (scanned.isEmpty()) {
            AmpLog.w("Library", "MediaStore returned 0 tracks — keeping existing library")
        } else {
            trackDao.upsertAll(scanned)
            trackDao.deleteMissing(scanned.map { it.id })
        }
        ensureFavoritesPlaylist()
        scanned.size
    }

    suspend fun ensureFavoritesPlaylist() = favoritesMutex.withLock {
        val favoritesName = context.getString(R.string.favorites)
        val favorites = playlistDao.getAllFavorites()
        when {
            favorites.isEmpty() -> {
                playlistDao.upsert(PlaylistEntity(name = favoritesName, isFavorites = true))
            }
            favorites.size > 1 -> {
                favorites.drop(1).forEach { playlistDao.deleteAny(it.id) }
                val keep = favorites.first()
                if (keep.name != favoritesName) {
                    playlistDao.upsert(keep.copy(name = favoritesName))
                }
            }
            else -> {
                val keep = favorites.first()
                if (keep.name != favoritesName) {
                    playlistDao.upsert(keep.copy(name = favoritesName))
                }
            }
        }
    }

    suspend fun createPlaylist(name: String): Long =
        playlistDao.upsert(
            PlaylistEntity(
                name = name.trim().ifBlank { context.getString(R.string.playlist_fallback_title) },
            ),
        )

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun deletePlaylist(id: Long) {
        playlistDao.delete(id)
        sourceQueueDao.delete("playlist:$id")
    }

    /** File-backed playlists (m3u/xspf) that are neither favorites nor app-created. */
    suspend fun getFilePlaylists(): List<PlaylistEntity> =
        playlistDao.getAll().filter { !it.isFavorites && it.sourcePath != null }

    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>) =
        playlistDao.addTracks(playlistId, trackIds)

    suspend fun removeFromPlaylist(playlistId: Long, trackId: Long) =
        playlistDao.removeTrack(playlistId, trackId)

    suspend fun setPlaylistTracks(playlistId: Long, trackIds: List<Long>) =
        playlistDao.replaceTracks(playlistId, trackIds)

    suspend fun toggleFavorite(trackId: Long): Boolean {
        ensureFavoritesPlaylist()
        val fav = playlistDao.getFavorites() ?: return false
        val ids = playlistDao.getTrackIds(fav.id)
        return if (trackId in ids) {
            playlistDao.removeTrack(fav.id, trackId)
            false
        } else {
            playlistDao.addTracks(fav.id, listOf(trackId))
            true
        }
    }

    suspend fun isFavorite(trackId: Long): Boolean {
        val fav = playlistDao.getFavorites() ?: return false
        return trackId in playlistDao.getTrackIds(fav.id)
    }

    suspend fun recordPlay(trackId: Long) {
        playHistoryDao.insert(PlayHistoryEntity(trackId = trackId))
    }

    fun contentUriFor(trackId: Long) = scanner.contentUriFor(trackId)
}
