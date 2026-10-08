package com.amp.player.ui.directories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amp.player.data.db.TrackEntity
import com.amp.player.data.library.LibraryRepository
import com.amp.player.data.settings.UserSettings
import com.amp.player.loudness.LoudnessAnalyzer
import com.amp.player.loudness.LoudnessPlaylistStatus
import com.amp.player.loudness.LoudnessRepository
import com.amp.player.loudness.LoudnessScanState
import com.amp.player.playback.PlayerCommands
import com.amp.player.playback.PlayerController
import com.amp.player.playback.SourceProgress
import com.amp.player.ui.components.SourceStats
import com.amp.player.ui.components.sourceStatsOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DirectoriesViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val player: PlayerController,
    private val loudnessRepository: LoudnessRepository,
    private val loudnessAnalyzer: LoudnessAnalyzer,
    private val settings: UserSettings,
) : ViewModel() {
    val directories = library.observeDirectories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedDirectory = MutableStateFlow<String?>(null)
    val selectedDirectory: StateFlow<String?> = _selectedDirectory

    val tracks: StateFlow<List<TrackEntity>> = _selectedDirectory
        .flatMapLatest { dir ->
            if (dir == null) flowOf(emptyList()) else library.observeByDirectory(dir)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<SourceStats?> = tracks.map(::sourceStatsOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loudnessStatus: StateFlow<LoudnessPlaylistStatus?> = _selectedDirectory
        .flatMapLatest { dir ->
            if (dir == null) flowOf(null)
            else loudnessRepository.observeStatus(library.observeByDirectory(dir))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loudnessScan: StateFlow<LoudnessScanState> = loudnessAnalyzer.state

    val replayGainEnabled = settings.replayGainEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val playback = player.uiState

    val isCurrentDirectoryPlaying: StateFlow<Boolean> = combine(
        player.uiState,
        player.shuffleState,
        _selectedDirectory,
    ) { ui, persisted, dir ->
        ui.isPlaying && dir != null && persisted?.queue?.sourceId == "dir:$dir"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Each folder keeps its own shuffle flag, like playlists. */
    val shuffleOn: StateFlow<Boolean> = _selectedDirectory
        .flatMapLatest { dir ->
            if (dir == null) flowOf(false)
            else player.observeSource("dir:$dir").map { it?.shuffleEnabled == true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sourceProgress: StateFlow<SourceProgress?> = _selectedDirectory
        .flatMapLatest { dir ->
            if (dir == null) flowOf(null) else player.observeSourceProgress("dir:$dir")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while a refresh runs (start, button, pull-down). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        refresh()
    }

    fun selectDirectory(dir: String?) {
        _selectedDirectory.value = dir
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                library.refreshLibrary()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun shuffleDirectory(dir: String) {
        viewModelScope.launch {
            val list = library.getDirectoryTracks(dir)
            player.playSource("dir:$dir", list, PlayerCommands.MODE_SHUFFLE_PLAY)
        }
    }

    fun playPauseSelected() {
        val dir = _selectedDirectory.value ?: return
        viewModelScope.launch {
            val source = "dir:$dir"
            if (player.currentPersisted()?.queue?.sourceId == source && playback.value.isPlaying) {
                player.playPause()
            } else {
                player.playSource(source, library.getDirectoryTracks(dir), PlayerCommands.MODE_RESUME)
            }
        }
    }

    fun toggleShuffleSelected() {
        val dir = _selectedDirectory.value ?: return
        viewModelScope.launch {
            player.playSource("dir:$dir", library.getDirectoryTracks(dir), PlayerCommands.MODE_TOGGLE_SHUFFLE)
        }
    }

    fun analyzeLoudness() {
        val dir = _selectedDirectory.value ?: return
        loudnessAnalyzer.startScan("dir:$dir")
    }

    fun resetLoudness() {
        val dir = _selectedDirectory.value ?: return
        viewModelScope.launch {
            loudnessRepository.resetTracks(library.getDirectoryTracks(dir).map { it.id })
        }
    }

    fun playFromDirectory(trackId: Long) {
        val dir = _selectedDirectory.value ?: return
        viewModelScope.launch {
            player.playTrack("dir:$dir", library.getDirectoryTracks(dir), trackId)
        }
    }
}
