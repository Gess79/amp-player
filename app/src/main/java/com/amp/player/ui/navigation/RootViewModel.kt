package com.amp.player.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amp.player.data.library.LibraryRepository
import com.amp.player.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val library: LibraryRepository,
) : ViewModel() {
    val playback = playerController.uiState

    init {
        viewModelScope.launch {
            library.ensureFavoritesPlaylist()
        }
    }

    fun playPause() {
        playerController.playPause()
    }

    fun skipToPrevious() {
        playerController.skipToPrevious()
    }

    fun skipToNext() {
        playerController.skipToNext()
    }

    fun seekTo(positionMs: Long) {
        playerController.seekTo(positionMs)
    }
}
