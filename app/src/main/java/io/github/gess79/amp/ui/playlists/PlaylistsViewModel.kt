package io.github.gess79.amp.ui.playlists

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gess79.amp.R
import io.github.gess79.amp.data.db.PlaylistEntity
import io.github.gess79.amp.data.db.TrackEntity
import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.data.library.LibrarySync
import io.github.gess79.amp.data.playlist.PlaylistFolders
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.loudness.LoudnessAnalyzer
import io.github.gess79.amp.loudness.LoudnessPlaylistStatus
import io.github.gess79.amp.loudness.LoudnessRepository
import io.github.gess79.amp.loudness.LoudnessScanState
import io.github.gess79.amp.playback.PlayerCommands
import io.github.gess79.amp.playback.PlayerController
import io.github.gess79.amp.playback.SourceProgress
import io.github.gess79.amp.ui.components.SourceStats
import io.github.gess79.amp.ui.components.sourceStatsOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val librarySync: LibrarySync,
    private val player: PlayerController,
    private val loudnessRepository: LoudnessRepository,
    private val loudnessAnalyzer: LoudnessAnalyzer,
    private val settings: UserSettings,
    private val playlistFolders: PlaylistFolders,
) : ViewModel() {
    val playlists = library.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasPlaylistFolders = playlistFolders.folders
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId

    private val _listStatus = MutableStateFlow<String?>(null)
    val listStatus: StateFlow<String?> = _listStatus

    /** True while a user-started refresh (button or pull-down) runs. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading

    val selectedPlaylist: StateFlow<PlaylistEntity?> = combine(playlists, _selectedPlaylistId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedTracks: StateFlow<List<TrackEntity>> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) {
                _detailLoading.value = false
                flowOf(emptyList())
            } else {
                flow {
                    _detailLoading.value = true
                    emit(library.getPlaylistTracks(id))
                    _detailLoading.value = false
                    library.observePlaylistTracks(id).collect { emit(it) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val detailStats: StateFlow<SourceStats?> = selectedTracks.map(::sourceStatsOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val replayGainEnabled = settings.replayGainEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val loudnessStatus: StateFlow<LoudnessPlaylistStatus?> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else loudnessRepository.observePlaylistStatus(id).map { it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loudnessScan: StateFlow<LoudnessScanState> = loudnessAnalyzer.state

    val playback = player.uiState

    val isCurrentPlaylistPlaying: StateFlow<Boolean> = combine(
        player.uiState,
        player.shuffleState,
        _selectedPlaylistId,
    ) { ui, persisted, selectedId ->
        val source = persisted?.queue?.sourceId
        ui.isPlaying && selectedId != null && source == "playlist:$selectedId"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Each playlist keeps its own shuffle flag, also while another source is playing. */
    val shuffleChipSelected: StateFlow<Boolean> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else player.observeSource("playlist:$id").map { it?.shuffleEnabled == true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sourceProgress: StateFlow<SourceProgress?> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else player.observeSourceProgress("playlist:$id")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        sync(force = false)
    }

    fun selectPlaylist(id: Long?) {
        _selectedPlaylistId.value = id
    }

    /** Manual refresh re-reads every playlist file. */
    fun refresh() = sync(force = true)

    private fun sync(force: Boolean) {
        viewModelScope.launch {
            if (force) _refreshing.value = true
            try {
                _listStatus.value = context.getString(R.string.library_scanning)
                val result = librarySync.refreshAll(force)
                _listStatus.value = context.getString(
                    R.string.library_scan_result,
                    result.trackCount,
                    result.playlistFilesImported,
                )
            } finally {
                if (force) _refreshing.value = false
            }
        }
    }

    fun addPlaylistFolder(uri: Uri) {
        viewModelScope.launch {
            playlistFolders.add(uri)
            refresh()
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { library.createPlaylist(name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { library.deletePlaylist(id) }
    }

    fun playPauseSelected() {
        val id = _selectedPlaylistId.value ?: return
        viewModelScope.launch {
            val source = "playlist:$id"
            if (isCurrentSource(source) && playback.value.isPlaying) {
                player.playPause()
            } else {
                val tracks = library.getPlaylistTracks(id)
                val fallbackShuffle = player.currentPersisted()?.shuffleEnabled ?: false
                player.playSource(source, tracks, PlayerCommands.MODE_RESUME, fallbackShuffle)
            }
        }
    }

    fun toggleShufflePlay() {
        val id = _selectedPlaylistId.value ?: return
        viewModelScope.launch {
            val tracks = library.getPlaylistTracks(id)
            player.playSource("playlist:$id", tracks, PlayerCommands.MODE_TOGGLE_SHUFFLE)
        }
    }

    fun shufflePlaylist(id: Long) {
        viewModelScope.launch {
            val tracks = library.getPlaylistTracks(id)
            player.playSource("playlist:$id", tracks, PlayerCommands.MODE_SHUFFLE_PLAY)
        }
    }

    fun playTrackInSelected(trackId: Long) {
        val id = _selectedPlaylistId.value ?: return
        viewModelScope.launch {
            player.playTrack("playlist:$id", library.getPlaylistTracks(id), trackId)
        }
    }

    fun removeFromSelected(trackId: Long) {
        val id = _selectedPlaylistId.value ?: return
        viewModelScope.launch { library.removeFromPlaylist(id, trackId) }
    }

    fun analyzeLoudness() {
        val id = _selectedPlaylistId.value ?: return
        loudnessAnalyzer.startScan("playlist:$id")
    }

    fun resetLoudness() {
        val id = _selectedPlaylistId.value ?: return
        viewModelScope.launch { loudnessRepository.resetPlaylist(id) }
    }

    private suspend fun isCurrentSource(sourceId: String): Boolean =
        player.currentPersisted()?.queue?.sourceId == sourceId
}
