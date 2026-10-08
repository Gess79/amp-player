package com.amp.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.amp.player.data.db.TrackEntity
import com.amp.player.shuffle.PersistedShuffle
import com.amp.player.shuffle.ShuffleQueueStore
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val mediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class SourceProgress(val position: Int, val total: Int, val shuffle: Boolean)

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shuffleStore: ShuffleQueueStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    val shuffleState = shuffleStore.observe()

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        publish(player)
                    }
                })
                publish(c)
                startPositionUpdates()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun startPositionUpdates() {
        scope.launch {
            while (true) {
                controller?.let { publish(it) }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun publish(player: Player) {
        val meta = player.mediaMetadata
        _uiState.value = PlaybackUiState(
            connected = true,
            isPlaying = player.isPlaying,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            artworkUri = meta.artworkUri?.toString(),
            mediaId = player.currentMediaItem?.mediaId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            PlaybackService.expectLocalUserPause = true
            c.pause()
        } else {
            c.play()
        }
    }

    fun skipToNext() {
        sendCommand(PlayerCommands.SKIP_NEXT, Bundle.EMPTY)
    }

    fun skipToPrevious() {
        sendCommand(PlayerCommands.SKIP_PREVIOUS, Bundle.EMPTY)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun startShuffle(sourceId: String, tracks: List<TrackEntity>, startTrackId: Long? = null) {
        startPlayback(sourceId, tracks, startTrackId, shuffle = true)
    }

    fun startOrdered(sourceId: String, tracks: List<TrackEntity>, startTrackId: Long? = null) {
        startPlayback(sourceId, tracks, startTrackId, shuffle = false)
    }

    fun startPlayback(
        sourceId: String,
        tracks: List<TrackEntity>,
        startTrackId: Long? = null,
        shuffle: Boolean,
    ) {
        if (tracks.isEmpty()) return
        val args = bundleOf(
            PlayerCommands.ARG_SOURCE_ID to sourceId,
            PlayerCommands.ARG_TRACK_IDS to tracks.map { it.id }.toLongArray(),
            PlayerCommands.ARG_SHUFFLE_ENABLED to shuffle,
        )
        if (startTrackId != null) {
            args.putLong(PlayerCommands.ARG_START_TRACK_ID, startTrackId)
        }
        sendCommand(PlayerCommands.START_SHUFFLE, args)
    }

    /**
     * Plays [sourceId] according to [mode] (see `PlayerCommands.MODE_*`), continuing the
     * source's saved queue where appropriate. [fallbackShuffle] applies when nothing is saved.
     */
    fun playSource(
        sourceId: String,
        tracks: List<TrackEntity>,
        mode: String,
        fallbackShuffle: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        sendCommand(
            PlayerCommands.PLAY_SOURCE,
            bundleOf(
                PlayerCommands.ARG_SOURCE_ID to sourceId,
                PlayerCommands.ARG_TRACK_IDS to tracks.map { it.id }.toLongArray(),
                PlayerCommands.ARG_PLAY_MODE to mode,
                PlayerCommands.ARG_SHUFFLE_ENABLED to fallbackShuffle,
            ),
        )
    }

    /** Plays [trackId] inside [sourceId]'s current round, keeping the source's shuffle flag. */
    fun playTrack(sourceId: String, tracks: List<TrackEntity>, trackId: Long) {
        if (tracks.isEmpty()) return
        sendCommand(
            PlayerCommands.PLAY_SOURCE,
            bundleOf(
                PlayerCommands.ARG_SOURCE_ID to sourceId,
                PlayerCommands.ARG_TRACK_IDS to tracks.map { it.id }.toLongArray(),
                PlayerCommands.ARG_PLAY_MODE to PlayerCommands.MODE_PLAY_TRACK,
                PlayerCommands.ARG_START_TRACK_ID to trackId,
            ),
        )
    }

    fun observeSource(sourceId: String) = shuffleStore.observeSource(sourceId)

    /** Position inside the source's saved/active round, e.g. 7 / 90. */
    fun observeSourceProgress(sourceId: String): Flow<SourceProgress?> =
        shuffleStore.observeSource(sourceId)
            .map { saved ->
                saved?.let {
                    SourceProgress(it.queue.currentIndex + 1, it.queue.trackIds.size, it.shuffleEnabled)
                }
            }
            .distinctUntilChanged()

    suspend fun savedForSource(sourceId: String): PersistedShuffle? =
        shuffleStore.loadSource(sourceId)

    fun resumeShuffle() = sendCommand(PlayerCommands.RESUME_SHUFFLE, Bundle.EMPTY)

    fun toggleFavorite() = sendCommand(PlayerCommands.TOGGLE_FAVORITE, Bundle.EMPTY)

    fun toggleShuffle() = sendCommand(PlayerCommands.TOGGLE_SHUFFLE, Bundle.EMPTY)

    fun setShuffle(enabled: Boolean) {
        sendCommand(
            PlayerCommands.SET_SHUFFLE,
            bundleOf(PlayerCommands.ARG_SHUFFLE_ENABLED to enabled),
        )
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sendCommand(
            PlayerCommands.SET_SLEEP_TIMER,
            bundleOf(PlayerCommands.ARG_SLEEP_MINUTES to minutes),
        )
    }

    private fun sendCommand(action: String, args: Bundle) {
        val c = controller ?: return
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    suspend fun currentPersisted(): PersistedShuffle? = shuffleStore.load()
}
