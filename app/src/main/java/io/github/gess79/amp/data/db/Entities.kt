package io.github.gess79.amp.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    val directory: String,
    val albumArtUri: String?,
    val dateAdded: Long,
    val sizeBytes: Long,
    val dateModified: Long = 0L,
)

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["sourcePath"], unique = true)],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorites: Boolean = false,
    /** Absolute path or uri:… for file-backed playlists; null for app-created ones. */
    val sourcePath: String? = null,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index("playlistId")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

@Entity(tableName = "shuffle_state")
data class ShuffleStateEntity(
    @PrimaryKey val id: Int = 1,
    val sourceId: String,
    val trackIdsCsv: String,
    val currentIndex: Int,
    val generation: Int,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = true,
    val isPlaying: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Last queue per source (playlist, folder, all), so switching sources keeps each round. */
@Entity(tableName = "source_queues")
data class SourceQueueEntity(
    @PrimaryKey val sourceId: String,
    val trackIdsCsv: String,
    val currentIndex: Int,
    val generation: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val updatedAt: Long,
)

/** Fingerprint of a scanned playlist file; unchanged files are not re-read. */
@Entity(tableName = "playlist_files")
data class PlaylistFileStateEntity(
    @PrimaryKey val sourceKey: String,
    val lastModified: Long,
    val size: Long,
    /** Hash of the track library at import time; a changed library re-matches all files. */
    val librarySignature: Long,
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val playedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "loudness",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class LoudnessEntity(
    @PrimaryKey val trackId: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val lufs: Float,
    val peakDb: Float,
    /** tag | scan | playback */
    val source: String,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Peak envelope, [io.github.gess79.amp.loudness.WaveformPeaks.BAR_COUNT] bytes, or null if not scanned. */
    val waveform: ByteArray? = null,
)
