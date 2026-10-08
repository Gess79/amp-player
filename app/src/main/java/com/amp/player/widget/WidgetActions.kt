package com.amp.player.widget

import com.amp.player.data.library.LibraryRepository
import com.amp.player.playback.PlayerController
import com.amp.player.shuffle.ShuffleQueueStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt entry point still used by WidgetSnapshot / WidgetUpdater helpers. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playerController(): PlayerController
    fun libraryRepository(): LibraryRepository
    fun shuffleQueueStore(): ShuffleQueueStore
}
