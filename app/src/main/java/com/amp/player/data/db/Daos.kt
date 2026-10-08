package com.amp.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :q || '%'
           OR artist LIKE '%' || :q || '%'
           OR album LIKE '%' || :q || '%'
           OR directory LIKE '%' || :q || '%'
           OR path LIKE '%' || :q || '%'
        ORDER BY title COLLATE NOCASE
        """,
    )
    fun search(q: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE (:title = '' OR title LIKE '%' || :title || '%')
          AND (:artist = '' OR artist LIKE '%' || :artist || '%')
          AND (:album = '' OR album LIKE '%' || :album || '%')
          AND (:directory = '' OR directory LIKE '%' || :directory || '%')
        ORDER BY title COLLATE NOCASE
        """,
    )
    fun searchFiltered(
        title: String,
        artist: String,
        album: String,
        directory: String,
    ): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT directory FROM tracks ORDER BY directory COLLATE NOCASE")
    fun observeDirectories(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE directory = :directory ORDER BY title COLLATE NOCASE")
    fun observeByDirectory(directory: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE directory = :directory ORDER BY title COLLATE NOCASE")
    suspend fun getByDirectory(directory: String): List<TrackEntity>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id NOT IN (:ids)")
    suspend fun deleteMissing(ids: List<Long>)

    @Query("DELETE FROM tracks")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isFavorites DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE isFavorites = 1 LIMIT 1")
    suspend fun getFavorites(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE isFavorites = 1 ORDER BY id ASC")
    suspend fun getAllFavorites(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE sourcePath = :sourcePath LIMIT 1")
    suspend fun getBySourcePath(sourcePath: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id AND isFavorites = 0")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteAny(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id AND isFavorites = 0")
    suspend fun rename(id: Long, name: String)

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    suspend fun getTracks(playlistId: Long): List<TrackEntity>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getTrackIds(playlistId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(items: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Transaction
    suspend fun replaceTracks(playlistId: Long, trackIds: List<Long>) {
        clearTracks(playlistId)
        insertPlaylistTracks(
            trackIds.mapIndexed { index, trackId ->
                PlaylistTrackEntity(playlistId, trackId, index)
            },
        )
    }

    @Transaction
    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) {
        var pos = maxPosition(playlistId) + 1
        val existing = getTrackIds(playlistId).toSet()
        val toAdd = trackIds.filterNot { it in existing }
        insertPlaylistTracks(
            toAdd.map { id ->
                PlaylistTrackEntity(playlistId, id, pos++)
            },
        )
    }
}

@Dao
interface ShuffleStateDao {
    @Query("SELECT * FROM shuffle_state WHERE id = 1")
    suspend fun get(): ShuffleStateEntity?

    @Query("SELECT * FROM shuffle_state WHERE id = 1")
    fun observe(): Flow<ShuffleStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ShuffleStateEntity)

    @Query("DELETE FROM shuffle_state")
    suspend fun clear()
}

@Dao
interface SourceQueueDao {
    @Query("SELECT * FROM source_queues WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): SourceQueueEntity?

    @Query("SELECT * FROM source_queues WHERE sourceId = :sourceId")
    fun observe(sourceId: String): Flow<SourceQueueEntity?>

    @Upsert
    suspend fun upsert(state: SourceQueueEntity)

    @Query("UPDATE source_queues SET positionMs = :positionMs, updatedAt = :updatedAt WHERE sourceId = :sourceId")
    suspend fun updatePosition(sourceId: String, positionMs: Long, updatedAt: Long)

    @Query("DELETE FROM source_queues WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}

@Dao
interface PlaylistFileStateDao {
    @Query("SELECT * FROM playlist_files")
    suspend fun getAll(): List<PlaylistFileStateEntity>

    @Upsert
    suspend fun upsert(state: PlaylistFileStateEntity)

    @Query("DELETE FROM playlist_files WHERE sourceKey = :sourceKey")
    suspend fun delete(sourceKey: String)
}

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN (
            SELECT trackId, MAX(playedAt) AS lastPlayed
            FROM play_history
            GROUP BY trackId
            ORDER BY lastPlayed DESC
            LIMIT :limit
        ) h ON h.trackId = t.id
        ORDER BY h.lastPlayed DESC
        """,
    )
    fun observeRecent(limit: Int = 50): Flow<List<TrackEntity>>
}

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM loudness")
    fun observeAll(): Flow<List<LoudnessEntity>>

    @Query("SELECT * FROM loudness WHERE trackId = :trackId")
    suspend fun get(trackId: Long): LoudnessEntity?

    @Query("SELECT * FROM loudness WHERE trackId IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<LoudnessEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LoudnessEntity)

    @Query("DELETE FROM loudness WHERE trackId IN (:ids)")
    suspend fun deleteByTrackIds(ids: List<Long>)
}
