package com.amp.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amp.player.data.library.LibraryRepository
import com.amp.player.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val player: PlayerController,
) : ViewModel() {
    private val _title = MutableStateFlow("")
    private val _artist = MutableStateFlow("")
    private val _album = MutableStateFlow("")
    private val _directory = MutableStateFlow("")

    val title: StateFlow<String> = _title
    val artist: StateFlow<String> = _artist
    val album: StateFlow<String> = _album
    val directory: StateFlow<String> = _directory

    val playlists = library.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results = combine(_title, _artist, _album, _directory) { t, ar, al, d ->
        Filter(t, ar, al, d)
    }.flatMapLatest { f ->
        if (f.artist.isBlank() && f.album.isBlank() && f.directory.isBlank()) {
            library.search(f.title)
        } else {
            library.searchFiltered(f.title, f.artist, f.album, f.directory)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTitle(v: String) { _title.value = v }
    fun setArtist(v: String) { _artist.value = v }
    fun setAlbum(v: String) { _album.value = v }
    fun setDirectory(v: String) { _directory.value = v }

    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            val tracks = results.value
            if (tracks.isEmpty()) return@launch
            player.startShuffle("search", tracks, startTrackId = trackId)
        }
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { library.toggleFavorite(trackId) }
    }

    fun addToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch { library.addToPlaylist(playlistId, listOf(trackId)) }
    }

    private data class Filter(
        val title: String,
        val artist: String,
        val album: String,
        val directory: String,
    )
}
