package com.amp.player.di

import android.content.Context
import androidx.room.Room
import com.amp.player.data.db.AppDatabase
import com.amp.player.data.db.LoudnessDao
import com.amp.player.data.db.PlayHistoryDao
import com.amp.player.data.db.PlaylistDao
import com.amp.player.data.db.PlaylistFileStateDao
import com.amp.player.data.db.ShuffleStateDao
import com.amp.player.data.db.SourceQueueDao
import com.amp.player.data.db.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "amp.db")
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTrackDao(db: AppDatabase): TrackDao = db.trackDao()
    @Provides fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideShuffleStateDao(db: AppDatabase): ShuffleStateDao = db.shuffleStateDao()
    @Provides fun providePlayHistoryDao(db: AppDatabase): PlayHistoryDao = db.playHistoryDao()
    @Provides fun provideLoudnessDao(db: AppDatabase): LoudnessDao = db.loudnessDao()
    @Provides fun provideSourceQueueDao(db: AppDatabase): SourceQueueDao = db.sourceQueueDao()
    @Provides fun providePlaylistFileStateDao(db: AppDatabase): PlaylistFileStateDao =
        db.playlistFileStateDao()
}
