package io.github.gess79.amp.widget

import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.playback.PlayerController
import io.github.gess79.amp.shuffle.ShuffleQueueStore
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
