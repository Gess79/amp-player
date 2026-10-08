package com.amp.player.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amp.player.data.db.TrackEntity
import com.amp.player.data.library.LibraryRepository
import com.amp.player.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class QueueInfo(
    val generation: Int,
    val progressLabel: String,
    val currentIndex: Int,
    val sourceId: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val player: PlayerController,
    private val library: LibraryRepository,
) : ViewModel() {
    val playback = player.uiState

    val queueInfo: StateFlow<QueueInfo?> = player.shuffleState
        .map { persisted ->
            persisted?.queue?.let {
                QueueInfo(it.generation, it.progressLabel, it.currentIndex, it.sourceId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val queueTracks: StateFlow<List<TrackEntity>> = player.shuffleState
        .flatMapLatest { persisted ->
            flow {
                val ids = persisted?.queue?.trackIds.orEmpty()
                emit(if (ids.isEmpty()) emptyList() else library.getTracksByIds(ids))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFavorite: StateFlow<Boolean> = playback
        .flatMapLatest { ui ->
            flow {
                val id = ui.mediaId?.toLongOrNull()
                emit(if (id == null) false else library.isFavorite(id))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun playPause() {
        player.playPause()
    }

    fun skipToNext() {
        player.skipToNext()
    }

    fun skipToPrevious() {
        player.skipToPrevious()
    }
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun resumeShuffle() = player.resumeShuffle()
    fun toggleFavorite() = player.toggleFavorite()
    fun toggleShuffle() = player.toggleShuffle()
    fun setSleepTimer(minutes: Int) = player.setSleepTimerMinutes(minutes)

    val shuffleEnabled: StateFlow<Boolean> = player.shuffleState
        .map { it?.shuffleEnabled == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
