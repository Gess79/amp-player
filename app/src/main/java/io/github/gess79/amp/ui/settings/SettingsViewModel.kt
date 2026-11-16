package io.github.gess79.amp.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gess79.amp.R
import io.github.gess79.amp.data.library.LibrarySync
import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.data.playlist.PlaylistFolders
import io.github.gess79.amp.data.playlist.PlaylistImporter
import io.github.gess79.amp.data.settings.AppLanguage
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.playback.BluetoothWakeDiagnostics
import io.github.gess79.amp.playback.PlaybackService
import io.github.gess79.amp.playback.PlayerCommands
import io.github.gess79.amp.playback.PlayerController
import io.github.gess79.amp.util.AmpLog
import io.github.gess79.amp.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: UserSettings,
    private val library: LibraryRepository,
    private val librarySync: LibrarySync,
    private val importer: PlaylistImporter,
    private val playlistFolders: PlaylistFolders,
    private val player: PlayerController,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {
    val resumeShuffle = settings.resumeShuffle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val darkTheme = settings.darkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val spreadSimilarTracks = settings.spreadSimilarTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val spreadSameArtist = settings.spreadSameArtist
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val replayGainEnabled = settings.replayGainEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val replayGainLimiter = settings.replayGainLimiter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val bluetoothAutoplay = settings.bluetoothAutoplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val autoplayDevices = settings.autoplayDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val appLanguage = settings.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.DEFAULT)

    val playlistFolderList = playlistFolders.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

    fun setResumeShuffle(value: Boolean) {
        viewModelScope.launch { settings.setResumeShuffle(value) }
    }

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(value) }
    }

    fun setSpreadSimilarTracks(value: Boolean) {
        viewModelScope.launch { settings.setSpreadSimilarTracks(value) }
    }

    fun setSpreadSameArtist(value: Boolean) {
        viewModelScope.launch { settings.setSpreadSameArtist(value) }
    }

    fun setReplayGainEnabled(value: Boolean) {
        viewModelScope.launch { settings.setReplayGainEnabled(value) }
    }

    fun setReplayGainLimiter(value: Boolean) {
        viewModelScope.launch { settings.setReplayGainLimiter(value) }
    }

    fun setBluetoothAutoplay(value: Boolean) {
        viewModelScope.launch { settings.setBluetoothAutoplay(value) }
    }

    fun setAutoplayDeviceAllowed(address: String, allowed: Boolean) {
        viewModelScope.launch { settings.setAutoplayDeviceAllowed(address, allowed) }
    }

    fun setAppLanguage(value: AppLanguage) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            settings.setAppLanguage(value)
            library.ensureFavoritesPlaylist()
            widgetUpdater.refreshLocale()
            runCatching {
                context.startService(
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_REFRESH_WIDGETS),
                )
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val result = librarySync.refreshAll(force = true)
            _importMessage.value = context.getString(
                R.string.settings_refresh_result,
                result.trackCount,
                result.playlistFilesImported,
            )
        }
    }

    fun addPlaylistFolder(uri: Uri) {
        viewModelScope.launch {
            playlistFolders.add(uri)
            refreshLibrary()
        }
    }

    fun removePlaylistFolder(uri: Uri) {
        viewModelScope.launch {
            playlistFolders.remove(uri)
            refreshLibrary()
        }
    }

    fun shuffleAll() {
        viewModelScope.launch {
            val tracks = library.getAllTracks()
            player.playSource("all", tracks, PlayerCommands.MODE_SHUFFLE_PLAY)
        }
    }

    fun importM3u(uri: Uri) {
        viewModelScope.launch {
            val result = importer.importUri(uri)
            _importMessage.value = result.error
                ?: context.getString(
                    R.string.settings_import_result,
                    result.matched,
                    result.listed,
                )
        }
    }

    fun shareLogs() {
        viewModelScope.launch {
            AmpLog.i("Settings", "shareLogs requested")
            val file = withContext(Dispatchers.IO) { AmpLog.exportSnapshot(context) }
            if (file == null || !file.exists() || file.length() == 0L) {
                _importMessage.value = context.getString(R.string.settings_logs_empty)
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_logs_share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        context.getString(R.string.settings_logs_share_chooser),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                _importMessage.value = context.getString(
                    R.string.settings_logs_ready,
                    file.length().toInt(),
                )
            }.onFailure {
                AmpLog.e("Settings", "shareLogs failed", it)
                _importMessage.value = context.getString(
                    R.string.settings_logs_share_failed,
                    it.message ?: "",
                )
            }
        }
    }

    fun clearLogs() {
        AmpLog.clear()
        _importMessage.value = context.getString(R.string.settings_logs_cleared)
    }

    fun checkBluetoothWakePermission() {
        BluetoothWakeDiagnostics.logStatus(context, "settings-check")
        _importMessage.value = BluetoothWakeDiagnostics.statusText(context)
    }
}
