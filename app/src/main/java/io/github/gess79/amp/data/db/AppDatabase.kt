package io.github.gess79.amp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        ShuffleStateEntity::class,
        PlayHistoryEntity::class,
        LoudnessEntity::class,
        SourceQueueEntity::class,
        PlaylistFileStateEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun shuffleStateDao(): ShuffleStateDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun loudnessDao(): LoudnessDao
    abstract fun sourceQueueDao(): SourceQueueDao
    abstract fun playlistFileStateDao(): PlaylistFileStateDao

    companion object {
        /** Must match Room's generated schema exactly, or the app fails to open the DB. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_queues` (`sourceId` TEXT NOT NULL, " +
                        "`trackIdsCsv` TEXT NOT NULL, `currentIndex` INTEGER NOT NULL, " +
                        "`generation` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`shuffleEnabled` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_files` (`sourceKey` TEXT NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                        "`librarySignature` INTEGER NOT NULL, PRIMARY KEY(`sourceKey`))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO source_queues (sourceId, trackIdsCsv, currentIndex, " +
                        "generation, positionMs, shuffleEnabled, updatedAt) " +
                        "SELECT sourceId, trackIdsCsv, currentIndex, generation, positionMs, " +
                        "shuffleEnabled, updatedAt FROM shuffle_state WHERE id = 1",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN dateModified INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS loudness (
                        trackId INTEGER NOT NULL PRIMARY KEY,
                        sizeBytes INTEGER NOT NULL,
                        dateModified INTEGER NOT NULL,
                        lufs REAL NOT NULL,
                        peakDb REAL NOT NULL,
                        source TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_loudness_trackId ON loudness(trackId)")
            }
        }
    }
}
