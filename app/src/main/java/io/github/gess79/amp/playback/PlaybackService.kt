package io.github.gess79.amp.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import io.github.gess79.amp.MainActivity
import io.github.gess79.amp.R
import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.data.settings.AppLocale
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.loudness.LoudnessRepository
import io.github.gess79.amp.loudness.ReplayGain
import io.github.gess79.amp.loudness.ReplayGainAudioProcessor
import io.github.gess79.amp.loudness.ReplayGainRenderersFactory
import io.github.gess79.amp.shuffle.PersistedShuffle
import io.github.gess79.amp.shuffle.ShufflePlanOptions
import io.github.gess79.amp.shuffle.ShufflePlanner
import io.github.gess79.amp.shuffle.ShuffleQueue
import io.github.gess79.amp.shuffle.ShuffleQueueStore
import io.github.gess79.amp.util.AmpLog
import io.github.gess79.amp.widget.WidgetUpdater
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        const val ACTION_PLAY_PAUSE = "io.github.gess79.amp.action.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.gess79.amp.action.NEXT"
        const val ACTION_PREVIOUS = "io.github.gess79.amp.action.PREVIOUS"
        const val ACTION_TOGGLE_SHUFFLE = "io.github.gess79.amp.action.TOGGLE_SHUFFLE"
        const val ACTION_RESUME_OR_SHUFFLE = "io.github.gess79.amp.action.RESUME_OR_SHUFFLE"
        const val ACTION_REFRESH_WIDGETS = "io.github.gess79.amp.action.REFRESH_WIDGETS"
        const val ACTION_BT_RESUME = "io.github.gess79.amp.action.BT_RESUME"

        /** Set before an in-app/widget pause so head-unit STOP is not treated as user pause. */
        @Volatile var expectLocalUserPause = false

        /** Same ID Media3's DefaultMediaNotificationProvider uses by default. */
        private const val MEDIA_NOTIFICATION_ID = 1001
        private const val BOOTSTRAP_CHANNEL_ID = "amp_playback"
        /** Car head units often finish A2DP/AVRCP after the first device-added event. */
        private const val BT_SETTLE_MS = 1_800L
        private const val BT_PLAY_RETRY_MS = 3_000L
        private const val BT_PLAY_RETRY_COUNT = 24
        private const val BT_SPEAKER_GUARD_MS = 700L
        /** Head units often send STOP ~100ms after we load the next shuffle item. */
        private const val IGNORE_REMOTE_STOP_MS = 2_500L
    }

    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var shuffleStore: ShuffleQueueStore
    @Inject lateinit var userSettings: UserSettings
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var replayGainProcessor: ReplayGainAudioProcessor
    @Inject lateinit var loudnessRepository: LoudnessRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackMutex = Mutex()
    private var exoPlayer: ExoPlayer? = null
    private var player: Player? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val activeSourceId = MutableStateFlow<String?>(null)
    private var queue: ShuffleQueue? = null
        set(value) {
            field = value
            activeSourceId.value = value?.sourceId
        }
    private var shuffleEnabled: Boolean = true
    private var persistJob: Job? = null
    private var sleepJob: Job? = null
    private var advancing = false
    /** Resume when a BT audio sink appears, unless the user explicitly paused. */
    private var resumeOnBluetooth = false
    private var userPausedPlayback = false
    /**
     * A pause already happened. Do not call play() again while this Bluetooth
     * connection stays up (focus loss, settle, or a file-error retry). Cleared
     * when playback is requested again, when Bluetooth audio drops, or when a
     * music output connects, so the next connection may auto-start.
     */
    private var pauseBlocksPlayRetries = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var btResumeJob: Job? = null
    private var lastA2dpAddedElapsed = 0L
    private var lastBtReattachElapsed = 0L
    private var reattachingAudio = false
    private var lastPlayCurrentElapsed = 0L
    private var lastPlayCurrentWantedPlay = false
    private var recoveringFromIdle = false
    /**
     * Last position we intentionally restored/seeked to. Protects the DB from being
     * overwritten with 0 when the service dies mid-restore or before Exo applies the seek
     * (seen with Coma: ~136s wiped to 0 on a brief onCreate/onDestroy cycle).
     */
    private var anchoredPositionMs: Long = 0L
    private var btPlayRetryJob: Job? = null
    /** Set only for a Bluetooth-triggered start. Manual play leaves this false. */
    private var autoplayArmed = false
    private var speakerGuardJob: Job? = null
    /**
     * One route-kick per A2DP connection, and only after playback is actually running.
     * isPlaying alone is not enough: the playhead moves while the car sink is still silent.
     */
    private var a2dpKickDone = false

    private val customCommands = listOf(
        SessionCommand(PlayerCommands.START_SHUFFLE, Bundle.EMPTY),
        SessionCommand(PlayerCommands.RESUME_SHUFFLE, Bundle.EMPTY),
        SessionCommand(PlayerCommands.TOGGLE_FAVORITE, Bundle.EMPTY),
        SessionCommand(PlayerCommands.SET_SLEEP_TIMER, Bundle.EMPTY),
        SessionCommand(PlayerCommands.TOGGLE_SHUFFLE, Bundle.EMPTY),
        SessionCommand(PlayerCommands.SET_SHUFFLE, Bundle.EMPTY),
        SessionCommand(PlayerCommands.SKIP_NEXT, Bundle.EMPTY),
        SessionCommand(PlayerCommands.SKIP_PREVIOUS, Bundle.EMPTY),
        SessionCommand(PlayerCommands.PLAY_SOURCE, Bundle.EMPTY),
    )

    override fun onCreate() {
        super.onCreate()
        AmpLog.i("Playback", "onCreate")
        // Widgets call startForegroundService(); Android requires startForeground() quickly.
        ensureForegroundBootstrap("onCreate")
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(MEDIA_NOTIFICATION_ID)
                .build(),
        )

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(ReplayGainRenderersFactory(this, replayGainProcessor))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exoPlayer = exo
        exo.repeatMode = Player.REPEAT_MODE_OFF

        val forwarding = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    // Car/head-unit must not enable ExoPlayer repeat/shuffle — we own both.
                    .remove(COMMAND_SET_REPEAT_MODE)
                    .remove(COMMAND_SET_SHUFFLE_MODE)
                    .remove(COMMAND_STOP)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    -> true
                    COMMAND_SET_REPEAT_MODE,
                    COMMAND_SET_SHUFFLE_MODE,
                    COMMAND_STOP,
                    -> false
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

            override fun setRepeatMode(repeatMode: Int) {
                AmpLog.w("Playback", "ignored setRepeatMode=$repeatMode (forcing OFF)")
                super.setRepeatMode(Player.REPEAT_MODE_OFF)
            }

            override fun getShuffleModeEnabled(): Boolean = shuffleEnabled

            override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                AmpLog.w("Playback", "ignored setShuffleModeEnabled=$shuffleModeEnabled")
            }

            override fun seekToNext() {
                serviceScope.launch { playNextInShuffle() }
            }

            override fun seekToNextMediaItem() = seekToNext()

            override fun seekToPrevious() {
                serviceScope.launch { playPreviousInShuffle() }
            }

            override fun seekToPreviousMediaItem() = seekToPrevious()

            override fun stop() {
                if (shouldIgnoreRemoteStop()) {
                    AmpLog.w("Playback", "stop() ignored (auto-advance window)")
                    return
                }
                AmpLog.i("Playback", "stop() → pause")
                pause()
            }

            override fun play() {
                // Session, notification, and headset play are manual. Autoplay calls exo.play().
                disarmAutoplayGuard()
                super.play()
            }
        }
        player = forwarding

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                AmpLog.i("Playback", "state=${playbackStateName(playbackState)} track=${queue?.currentTrackId}")
                if (playbackState == Player.STATE_ENDED && !advancing) {
                    serviceScope.launch {
                        savePlaybackLoudnessIfComplete()
                        playNextInShuffle()
                    }
                }
                if (playbackState == Player.STATE_IDLE) {
                    recoverIfIdleAfterPlayCurrent()
                }
                persistSoon()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                AmpLog.i(
                    "Playback",
                    "playWhenReady=$playWhenReady reason=${playWhenReadyReason(reason)} " +
                        "suppression=${exo.playbackSuppressionReason}",
                )
                when {
                    playWhenReady -> {
                        pauseBlocksPlayRetries = false
                        userPausedPlayback = false
                        setResumeOnBluetooth(true)
                        // Kick only once playback is really requested. Doing it earlier,
                        // while focus is still denied, never reaches the car.
                        armA2dpKick("play-when-ready")
                        btPlayRetryJob?.cancel()
                    }
                    reattachingAudio -> { /* pause/play bounce to bind A2DP; not a user pause */ }
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> {
                        pauseBlocksPlayRetries = true
                        btPlayRetryJob?.cancel()
                        btResumeJob?.cancel()
                        if (expectLocalUserPause) {
                            expectLocalUserPause = false
                            userPausedPlayback = true
                            setResumeOnBluetooth(false)
                        }
                    }
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                        userPausedPlayback = false
                        pauseBlocksPlayRetries = false
                        setResumeOnBluetooth(true)
                    }
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                        // Head unit often holds focus for a while after A2DP connects.
                        // play() stays false; keep retrying instead of rebinding a dead route.
                        AmpLog.i("Playback", "audio focus holders ${audioActivitySnapshot()}")
                        if (pauseBlocksPlayRetries || userPausedPlayback) {
                            AmpLog.i("Playback", "BT play retry skipped, playback was paused")
                        } else if (resumeOnBluetooth) {
                            scheduleA2dpPlayRetries()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AmpLog.i(
                    "Playback",
                    "isPlaying=$isPlaying pos=${exo.currentPosition} " +
                        "audioSession=${exo.audioSessionId} track=${queue?.currentTrackId}",
                )
                if (isPlaying) {
                    userPausedPlayback = false
                    setResumeOnBluetooth(true)
                    btPlayRetryJob?.cancel()
                    armA2dpKick("is-playing")
                    if (autoplayArmed) schedulePhoneSpeakerGuard()
                }
                serviceScope.launch {
                    val q = queue
                    val exo = exoPlayer
                    if (q != null && exo != null) {
                        val pos = positionToPersist(exo)
                        shuffleStore.save(q, pos, shuffleEnabled, isPlaying)
                        publishWidgets(q, positionMs = pos, isPlaying = isPlaying, force = true)
                    }
                    widgetUpdater.setPlaying(isPlaying)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    anchoredPositionMs = newPosition.positionMs.coerceAtLeast(0L)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (repeatMode != Player.REPEAT_MODE_OFF) {
                    AmpLog.w("Playback", "onRepeatModeChanged=$repeatMode → forcing OFF")
                    exo.repeatMode = Player.REPEAT_MODE_OFF
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                AmpLog.w(
                    "Playback",
                    "suppression=${suppressionName(playbackSuppressionReason)} " +
                        "isPlaying=${exo.isPlaying} ${audioActivitySnapshot()}",
                )
                if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (exo.playWhenReady || resumeOnBluetooth)
                ) {
                    if (pauseBlocksPlayRetries && !exo.playWhenReady) {
                        AmpLog.i("Playback", "BT settle skipped, playback was paused (suppression-cleared)")
                    } else {
                        scheduleBluetoothAudioSettle("suppression-cleared")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AmpLog.e(
                    "Playback",
                    "playerError code=${error.errorCodeName} msg=${error.message}",
                    error,
                )
                if (error.hasAccessDenial()) {
                    AmpLog.w(
                        "Playback",
                        "media access denied audioPermission=${hasAudioPermission()} " +
                            "track=${queue?.currentTrackId}",
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                AmpLog.i(
                    "Playback",
                    "mediaTransition id=${mediaItem?.mediaId} reason=${mediaTransitionReason(reason)}",
                )
                // Safety net if a controller still forces single-item repeat.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && !advancing) {
                    AmpLog.w("Playback", "REPEAT transition → advance shuffle queue")
                    serviceScope.launch { playNextInShuffle() }
                    return
                }
                serviceScope.launch {
                    persistSoon()
                }
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, forwarding, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .setId("amp_session")
            .build()

        serviceScope.launch {
            resumeOnBluetooth = userSettings.resumeOnBluetooth.first()
            val a2dp = hasA2dpOutput()
            val permitted = autoplayMayStart()
            AmpLog.i(
                "Playback",
                "resumeOnBluetooth restored=$resumeOnBluetooth a2dp=$a2dp autoplay=$permitted",
            )
            val play = resumeOnBluetooth && a2dp && permitted
            if (play) {
                autoplayArmed = true
                ensureForegroundBootstrap("startup-a2dp-play")
            }
            if (userSettings.resumeShuffle.first()) {
                playbackMutex.withLock { restoreShuffleIfPossibleLocked(play = play) }
            }
            if (resumeOnBluetooth) maybeResumeExistingBluetooth()
        }
        startPersistLoop()
        observeSleepTimer()
        observeActiveSource()
        registerBluetoothResume()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Widget intents use startForegroundService(); keep FGS while media is active.
        val force = startInForegroundRequired ||
            session.player.playWhenReady ||
            session.player.isPlaying
        super.onUpdateNotification(session, force)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AmpLog.i("Playback", "onStartCommand action=${intent?.action} flags=$flags")
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                // Satisfy FGS contract if MediaButtonReceiver cold-started us. Do NOT force
                // play/resume here — Media3 handles the KeyEvent in super.onStartCommand.
                // Forcing play fought user pause and reopened the UI on Samsung (task removed).
                ensureForegroundBootstrap("media-button")
                serviceScope.launch {
                    playbackMutex.withLock {
                        val exo = exoPlayer
                        if (exo == null || exo.mediaItemCount == 0) {
                            restoreShuffleIfPossibleLocked(play = false)
                        }
                    }
                }
            }
            ACTION_BT_RESUME -> {
                ensureForegroundBootstrap("bt-wake")
                serviceScope.launch {
                    playbackMutex.withLock {
                        val exo = exoPlayer
                        if (exo == null || exo.mediaItemCount == 0) {
                            restoreShuffleIfPossibleLocked(play = false)
                        }
                    }
                    if (resumeOnBluetooth && !userPausedPlayback) {
                        scheduleBluetoothAudioSettle("bt-wake")
                    }
                }
            }
        }
        if (intent?.action?.startsWith("io.github.gess79.amp.action.") == true &&
            intent.action != ACTION_REFRESH_WIDGETS
        ) {
            ensureForegroundBootstrap("widget:${intent.action}")
        }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> serviceScope.launch {
                playbackMutex.withLock {
                    val exo = exoPlayer
                    if (exo != null) {
                        if (exo.isPlaying) {
                            expectLocalUserPause = true
                            exo.pause()
                        } else {
                            resumeFromIdleOrPlayLocked(exo)
                        }
                        widgetUpdater.setPlaying(exo.isPlaying)
                    } else {
                        disarmAutoplayGuard()
                        restoreShuffleIfPossibleLocked(play = true)
                    }
                }
            }
            ACTION_NEXT -> serviceScope.launch { playNextInShuffle() }
            ACTION_PREVIOUS -> serviceScope.launch { playPreviousInShuffle() }
            ACTION_TOGGLE_SHUFFLE -> serviceScope.launch {
                setShuffleEnabled(!shuffleEnabled)
                widgetUpdater.updateAll()
            }
            ACTION_RESUME_OR_SHUFFLE -> serviceScope.launch {
                playbackMutex.withLock {
                    val persisted = shuffleStore.load()
                    if (persisted != null) {
                        disarmAutoplayGuard()
                        restoreShuffleIfPossibleLocked(play = true)
                    } else {
                        val tracks = library.getAllTracks()
                        startPlaybackLocked("all", tracks.map { it.id }, null, shuffle = true)
                    }
                }
            }
            ACTION_REFRESH_WIDGETS -> serviceScope.launch {
                persistSoon(forceWidget = true)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AmpLog.w("Playback", "onTaskRemoved")
        persistNowBlocking()
        val keepPlaying = exoPlayer?.isPlaying == true || exoPlayer?.playWhenReady == true
        if (!keepPlaying) {
            AmpLog.i("Playback", "onTaskRemoved while paused → stopSelf")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AmpLog.i("Playback", "onDestroy playing=${exoPlayer?.isPlaying}")
        unregisterBluetoothResume()
        persistNowBlocking()
        sleepJob?.cancel()
        persistJob?.cancel()
        btResumeJob?.cancel()
        btPlayRetryJob?.cancel()
        widgetUpdater.setPlaying(false)
        serviceScope.cancel()
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        player = null
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    /**
     * Satisfies Android's startForegroundService() contract immediately.
     * Media3 later replaces the same notification id with the real media controls.
     */
    private fun ensureForegroundBootstrap(reason: String) {
        runCatching {
            val str = AppLocale.wrap(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    BOOTSTRAP_CHANNEL_ID,
                    str.getString(R.string.notification_channel_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = str.getString(R.string.notification_channel_playback_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, BOOTSTRAP_CHANNEL_ID)
                .setContentTitle(str.getString(R.string.app_name))
                .setContentText(str.getString(R.string.notification_playback_starting))
                .setSmallIcon(R.drawable.ic_widget_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, MEDIA_NOTIFICATION_ID, notification, type)
            AmpLog.i("Playback", "startForeground ok ($reason)")
        }.onFailure {
            AmpLog.e("Playback", "startForeground failed ($reason)", it)
        }
    }

    private fun registerBluetoothResume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val a2dp = addedDevices.filter { it.isA2dpOutput() }
                if (a2dp.isEmpty()) {
                    val other = addedDevices.filter { it.isBluetoothAudioOutput() }
                    if (other.isNotEmpty()) {
                        AmpLog.i(
                            "Playback",
                            "BT audio added (non-A2DP) types=${other.joinToString { it.type.toString() }}",
                        )
                    }
                    return
                }
                lastA2dpAddedElapsed = SystemClock.elapsedRealtime()
                a2dpKickDone = false
                serviceScope.launch {
                    val connected = BluetoothMediaRoute(this@PlaybackService).connectedBluetoothMedia()
                    if (connected.isNotEmpty()) userSettings.rememberAutoplayDevices(connected)
                }
                if (pauseBlocksPlayRetries) {
                    pauseBlocksPlayRetries = false
                    AmpLog.i("Playback", "pause block cleared, Bluetooth audio connected")
                }
                AmpLog.i(
                    "Playback",
                    "BT A2DP added types=${a2dp.joinToString { it.type.toString() }} " +
                        "resumeOnBluetooth=$resumeOnBluetooth userPaused=$userPausedPlayback " +
                        "playing=${exoPlayer?.isPlaying} playWhenReady=${exoPlayer?.playWhenReady}",
                )
                scheduleBluetoothAudioSettle("a2dp-added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val bt = removedDevices.filter { it.isBluetoothAudioOutput() }
                if (bt.isEmpty()) return
                if (removedDevices.any { it.isA2dpOutput() }) {
                    a2dpKickDone = false
                    pauseBlocksPlayRetries = false
                    btPlayRetryJob?.cancel()
                    btResumeJob?.cancel()
                }
                AmpLog.i(
                    "Playback",
                    "BT audio removed types=${bt.joinToString { it.type.toString() }} " +
                        "playing=${exoPlayer?.isPlaying}",
                )
            }
        }
        audioDeviceCallback = callback
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun unregisterBluetoothResume() {
        val callback = audioDeviceCallback ?: return
        runCatching {
            getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(callback)
        }
        audioDeviceCallback = null
    }

    private fun maybeResumeExistingBluetooth() {
        if (userPausedPlayback || !resumeOnBluetooth) return
        val a2dp = hasA2dpOutput()
        AmpLog.i(
            "Playback",
            "startup BT check a2dp=$a2dp resume=$resumeOnBluetooth " +
                "playing=${exoPlayer?.isPlaying} playWhenReady=${exoPlayer?.playWhenReady}",
        )
        if (!a2dp) return
        scheduleBluetoothAudioSettle("startup-a2dp")
    }

    private fun setResumeOnBluetooth(value: Boolean) {
        if (resumeOnBluetooth == value) return
        resumeOnBluetooth = value
        serviceScope.launch { userSettings.setResumeOnBluetooth(value) }
    }

    private fun scheduleBluetoothAudioSettle(
        reason: String,
        delayMs: Long = BT_SETTLE_MS,
        replaceRunning: Boolean = true,
    ) {
        if (replaceRunning) btResumeJob?.cancel()
        btResumeJob = serviceScope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            if (!userPausedPlayback && !resumeOnBluetooth) {
                resumeOnBluetooth = userSettings.resumeOnBluetooth.first()
            }
            playbackMutex.withLock {
                applyBluetoothAudioSettleLocked(reason)
            }
        }
    }

    private suspend fun applyBluetoothAudioSettleLocked(reason: String) {
        val exo = exoPlayer ?: return
        val a2dp = hasA2dpOutput()
        AmpLog.i(
            "Playback",
            "BT settle ($reason) a2dp=$a2dp playing=${exo.isPlaying} " +
                "playWhenReady=${exo.playWhenReady} resume=$resumeOnBluetooth " +
                "userPaused=$userPausedPlayback suppression=${exo.playbackSuppressionReason}",
        )
        if (!a2dp) return
        if (pauseBlocksPlayRetries && !exo.playWhenReady && !exo.isPlaying) {
            AmpLog.i("Playback", "BT settle skipped, playback was paused ($reason)")
            return
        }
        when {
            exo.playWhenReady || exo.isPlaying -> {
                if (a2dpKickDone) {
                    AmpLog.i("Playback", "BT settle skip, route already kicked ($reason)")
                } else if (reattachBluetoothAudioLocked(exo, reason)) {
                    a2dpKickDone = true
                }
            }
            resumeOnBluetooth && !userPausedPlayback -> {
                if (!autoplayMayStart()) {
                    AmpLog.i("Playback", "BT settle skipped, autoplay not permitted ($reason)")
                    return
                }
                if (pauseBlocksPlayRetries || userPausedPlayback) {
                    AmpLog.i("Playback", "BT resume skipped, playback was paused ($reason)")
                    return
                }
                AmpLog.i("Playback", "BT resume → play")
                autoplayArmed = true
                ensureForegroundBootstrap("bt-resume")
                if (exo.mediaItemCount == 0) {
                    restoreShuffleIfPossibleLocked(play = true)
                } else {
                    exo.play()
                }
                logAudioFocusResult("bt-resume", exo)
                if (!exo.playWhenReady && !exo.isPlaying) {
                    AmpLog.w("Playback", "BT resume playWhenReady still false")
                    scheduleA2dpPlayRetries()
                }
            }
            else -> AmpLog.i("Playback", "BT settle skipped (paused by user)")
        }
    }

    /**
     * Focus stays denied for a long time on some head units (this morning: still
     * false 5s after connect, widget play only succeeded ~65s later). Keep asking.
     */
    private fun scheduleA2dpPlayRetries() {
        if (btPlayRetryJob?.isActive == true) return
        btPlayRetryJob = serviceScope.launch {
            repeat(BT_PLAY_RETRY_COUNT) { attempt ->
                delay(BT_PLAY_RETRY_MS)
                if (!isActive || pauseBlocksPlayRetries || userPausedPlayback || !resumeOnBluetooth) {
                    return@launch
                }
                if (!autoplayMayStart()) {
                    AmpLog.i("Playback", "BT play retry stopped, autoplay not permitted")
                    return@launch
                }
                if (!hasA2dpOutput()) return@launch
                val exo = exoPlayer ?: return@launch
                if (exo.isPlaying || exo.playWhenReady) {
                    armA2dpKick("retry-already-playing")
                    return@launch
                }
                AmpLog.i("Playback", "BT play retry ${attempt + 1}/$BT_PLAY_RETRY_COUNT")
                playbackMutex.withLock {
                    val player = exoPlayer ?: return@withLock
                    if (player.isPlaying || player.playWhenReady ||
                        userPausedPlayback || pauseBlocksPlayRetries
                    ) {
                        return@withLock
                    }
                    autoplayArmed = true
                    if (player.mediaItemCount == 0) {
                        restoreShuffleIfPossibleLocked(play = true)
                    } else {
                        player.play()
                    }
                    logAudioFocusResult("retry-${attempt + 1}", player)
                }
            }
            AmpLog.w("Playback", "BT play retries exhausted")
        }
    }

    /**
     * Recreate the audio sink on the new A2DP route and re-send AVRCP play.
     * Playing into a half-ready car sink shows progress with no sound until the
     * head unit is kicked (source switch) or we bounce the route here.
     */
    private fun reattachBluetoothAudioLocked(exo: ExoPlayer, reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBtReattachElapsed < 1_200L) {
            AmpLog.i("Playback", "BT reattach skipped (debounced) reason=$reason")
            return false
        }
        lastBtReattachElapsed = now
        val pos = exo.currentPosition.coerceAtLeast(0L)
        AmpLog.i("Playback", "BT reattach ($reason) pos=$pos")
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        reattachingAudio = true
        try {
            exo.setAudioAttributes(attrs, true)
            if (!exo.playWhenReady) exo.play()
        } finally {
            reattachingAudio = false
        }
        return true
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun Throwable.hasAccessDenial(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is SecurityException) return true
            if (current.message.orEmpty().contains("has no access")) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun hasA2dpOutput(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isA2dpOutput() }
    }

    private fun AudioDeviceInfo.isA2dpOutput(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

    private fun AudioDeviceInfo.isBluetoothAudioOutput(): Boolean =
        isA2dpOutput() || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun shouldIgnoreRemoteStop(): Boolean =
        lastPlayCurrentWantedPlay &&
            !userPausedPlayback &&
            !pauseBlocksPlayRetries &&
            SystemClock.elapsedRealtime() - lastPlayCurrentElapsed < IGNORE_REMOTE_STOP_MS

    private fun recoverIfIdleAfterPlayCurrent() {
        if (recoveringFromIdle || !shouldIgnoreRemoteStop()) return
        recoveringFromIdle = true
        AmpLog.w("Playback", "IDLE right after playCurrent → re-prepare")
        serviceScope.launch {
            try {
                playbackMutex.withLock {
                    val exo = exoPlayer ?: return@withLock
                    if (exo.playbackState != Player.STATE_IDLE) return@withLock
                    if (userPausedPlayback || pauseBlocksPlayRetries) return@withLock
                    if (exo.mediaItemCount == 0) {
                        restoreShuffleIfPossibleLocked(play = true)
                    } else {
                        exo.prepare()
                        exo.play()
                    }
                }
            } finally {
                recoveringFromIdle = false
            }
        }
    }

    private fun disarmAutoplayGuard() {
        autoplayArmed = false
        speakerGuardJob?.cancel()
    }

    /**
     * After an autoplay start, pause if music is actually coming out of the phone.
     * Does not count as a user pause, so a later real car connect can try again.
     */
    private fun schedulePhoneSpeakerGuard() {
        if (!autoplayArmed || speakerGuardJob?.isActive == true) return
        speakerGuardJob = serviceScope.launch {
            delay(BT_SPEAKER_GUARD_MS)
            val playing = exoPlayer?.isPlaying == true
            val speaker = BluetoothMediaRoute(this@PlaybackService).phoneSpeakerIsMediaRoute()
            AmpLog.i("Playback", "BT autoplay route check playing=$playing phoneSpeaker=$speaker")
            if (!autoplayArmed || !playing) return@launch
            if (!speaker) {
                autoplayArmed = false
                return@launch
            }
            AmpLog.w("Playback", "BT autoplay stayed on phone speaker → pause")
            playbackMutex.withLock {
                autoplayArmed = false
                btPlayRetryJob?.cancel()
                exoPlayer?.pause()
            }
        }
    }

    /** User switch on, and at least one connected music output is allowed. */
    private suspend fun autoplayMayStart(): Boolean {
        if (!userSettings.bluetoothAutoplay.first()) return false
        val connected = BluetoothMediaRoute(this).connectedBluetoothMedia()
        if (connected.isNotEmpty()) userSettings.rememberAutoplayDevices(connected)
        if (connected.isEmpty()) return false
        return connected.any { userSettings.isAutoplayDeviceAllowed(it.address) }
    }

    private suspend fun resumeFromIdleOrPlayLocked(exo: ExoPlayer) {
        disarmAutoplayGuard()
        when {
            exo.mediaItemCount == 0 -> restoreShuffleIfPossibleLocked(play = true)
            exo.playbackState == Player.STATE_IDLE -> {
                AmpLog.i("Playback", "play from IDLE → prepare")
                exo.prepare()
                exo.play()
                armA2dpKick("play-from-idle")
            }
            else -> {
                exo.play()
                armA2dpKick("widget-play")
            }
        }
    }

    /**
     * Rebind the car route once this A2DP link is actually playing.
     * A connection that has already been up for a few seconds does not need the
     * long head-unit settle; the playhead-without-sound case is exactly that.
     */
    private fun armA2dpKick(reason: String) {
        if (a2dpKickDone || !hasA2dpOutput()) return
        val connectedFor = SystemClock.elapsedRealtime() - lastA2dpAddedElapsed
        val delayMs = if (connectedFor > 5_000L) 400L else BT_SETTLE_MS
        scheduleBluetoothAudioSettle("kick-$reason", delayMs = delayMs)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            customCommands.forEach { sessionCommands.add(it) }
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SET_REPEAT_MODE)
                .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                .remove(Player.COMMAND_STOP)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlayerCommands.START_SHUFFLE -> {
                    val sourceId = args.getString(PlayerCommands.ARG_SOURCE_ID).orEmpty()
                    val trackIds = args.getLongArray(PlayerCommands.ARG_TRACK_IDS)?.toList().orEmpty()
                    val startId = args.getLong(PlayerCommands.ARG_START_TRACK_ID, -1L)
                        .takeIf { it > 0 }
                    val shuffle = args.getBoolean(PlayerCommands.ARG_SHUFFLE_ENABLED, true)
                    serviceScope.launch {
                        playbackMutex.withLock {
                            startPlaybackLocked(sourceId, trackIds, startId, shuffle)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.PLAY_SOURCE -> {
                    val sourceId = args.getString(PlayerCommands.ARG_SOURCE_ID).orEmpty()
                    val trackIds = args.getLongArray(PlayerCommands.ARG_TRACK_IDS)?.toList().orEmpty()
                    val mode = args.getString(PlayerCommands.ARG_PLAY_MODE)
                        ?: PlayerCommands.MODE_RESUME
                    val fallbackShuffle = args.getBoolean(PlayerCommands.ARG_SHUFFLE_ENABLED, false)
                    val trackId = args.getLong(PlayerCommands.ARG_START_TRACK_ID, -1L)
                    serviceScope.launch {
                        playbackMutex.withLock {
                            if (mode == PlayerCommands.MODE_PLAY_TRACK && trackId > 0) {
                                playTrackLocked(sourceId, trackIds, trackId)
                            } else {
                                playSourceLocked(sourceId, trackIds, mode, fallbackShuffle)
                            }
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.RESUME_SHUFFLE -> {
                    serviceScope.launch {
                        playbackMutex.withLock { restoreShuffleIfPossibleLocked(play = true) }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.TOGGLE_FAVORITE -> {
                    serviceScope.launch {
                        queue?.currentTrackId?.let { library.toggleFavorite(it) }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.TOGGLE_SHUFFLE -> {
                    serviceScope.launch { setShuffleEnabled(!shuffleEnabled) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.SKIP_NEXT -> {
                    serviceScope.launch { playNextInShuffle() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.SKIP_PREVIOUS -> {
                    serviceScope.launch { playPreviousInShuffle() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.SET_SHUFFLE -> {
                    val enabled = args.getBoolean(PlayerCommands.ARG_SHUFFLE_ENABLED, true)
                    serviceScope.launch { setShuffleEnabled(enabled) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlayerCommands.SET_SLEEP_TIMER -> {
                    val minutes = args.getInt(PlayerCommands.ARG_SLEEP_MINUTES, 0)
                    serviceScope.launch {
                        if (minutes <= 0) userSettings.setSleepTimerEnd(null)
                        else userSettings.setSleepTimerEnd(System.currentTimeMillis() + minutes * 60_000L)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            AmpLog.i("Playback", "onPlaybackResumption from ${controller.packageName}")
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    playbackMutex.withLock {
                        val persisted = shuffleStore.load()
                            ?: throw IllegalStateException("No queue")
                        queue = persisted.queue
                        shuffleEnabled = persisted.shuffleEnabled
                        userPausedPlayback = false
                        setResumeOnBluetooth(true)
                        val item = mediaItemFor(persisted.queue.currentTrackId)
                            ?: throw IllegalStateException("Track missing")
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(item),
                                0,
                                persisted.positionMs,
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    AmpLog.e("Playback", "onPlaybackResumption failed", t)
                    future.setException(t)
                }
            }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("AMP")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val items = when (parentId) {
                    "root" -> listOf(
                        browsable("playlists", getString(R.string.playlists_title)),
                        browsable("favorites", getString(R.string.favorites)),
                        browsable("recent", getString(R.string.recently_played)),
                        browsable("all", getString(R.string.source_all_tracks)),
                    )
                    "playlists" -> library.observePlaylists().first().map {
                        val name = if (it.isFavorites) getString(R.string.favorites) else it.name
                        browsable("playlist:${it.id}", name)
                    }
                    "favorites" -> {
                        val fav = library.observePlaylists().first().firstOrNull { it.isFavorites }
                        if (fav == null) emptyList()
                        else library.getPlaylistTracks(fav.id).map { playable(it.id, it.title, it.artist) }
                    }
                    "recent" -> library.observeRecent().first().map {
                        playable(it.id, it.title, it.artist)
                    }
                    "all" -> library.getAllTracks().map { playable(it.id, it.title, it.artist) }
                    else -> if (parentId.startsWith("playlist:")) {
                        val id = parentId.removePrefix("playlist:").toLongOrNull()
                        if (id == null) emptyList()
                        else library.getPlaylistTracks(id).map { playable(it.id, it.title, it.artist) }
                    } else emptyList()
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val resolved = mediaItems.mapNotNull { item ->
                    val id = item.mediaId.toLongOrNull() ?: return@mapNotNull null
                    mediaItemFor(id)
                }.toMutableList()
                future.set(resolved)
            }
            return future
        }
    }

    private suspend fun startPlaybackLocked(
        sourceId: String,
        trackIds: List<Long>,
        startTrackId: Long?,
        shuffle: Boolean,
    ) {
        if (trackIds.isEmpty()) return
        disarmAutoplayGuard()
        ensureForegroundBootstrap("startPlayback")
        snapshotActiveLocked()
        shuffleEnabled = shuffle
        val newQueue = buildQueue(sourceId, trackIds, startTrackId, shuffle, generation = 1)
        queue = newQueue
        AmpLog.i(
            "Shuffle",
            "new round gen=${newQueue.generation} size=${newQueue.trackIds.size} " +
                "start=${newQueue.currentTrackId} shuffle=$shuffle",
        )
        shuffleStore.save(newQueue, 0L, shuffleEnabled, isPlaying = true)
        playCurrentLocked(seekMs = 0L, play = true)
    }

    private suspend fun playSourceLocked(
        sourceId: String,
        trackIds: List<Long>,
        mode: String,
        fallbackShuffle: Boolean,
    ) {
        if (sourceId.isEmpty() || trackIds.isEmpty()) return
        val exo = exoPlayer ?: return
        val active = queue?.takeIf { it.sourceId == sourceId && exo.mediaItemCount > 0 }
        val saved = if (active != null) null else shuffleStore.loadSource(sourceId)
        val sourceShuffle = if (active != null) shuffleEnabled else saved?.shuffleEnabled == true
        AmpLog.i(
            "Shuffle",
            "playSource $sourceId mode=$mode active=${active != null} saved=${saved != null} " +
                "shuffle=$sourceShuffle",
        )
        when (mode) {
            PlayerCommands.MODE_SHUFFLE_PLAY -> when {
                active != null && sourceShuffle -> resumeFromIdleOrPlayLocked(exo)
                saved != null && sourceShuffle -> switchToSavedLocked(saved, trackIds, shuffle = true)
                else -> startPlaybackLocked(sourceId, trackIds, null, shuffle = true)
            }
            PlayerCommands.MODE_TOGGLE_SHUFFLE -> when {
                active != null -> {
                    setShuffleEnabled(!shuffleEnabled)
                    if (shuffleEnabled && !exo.isPlaying) resumeFromIdleOrPlayLocked(exo)
                }
                saved != null && sourceShuffle -> switchToSavedLocked(saved, trackIds, shuffle = false)
                else -> startPlaybackLocked(sourceId, trackIds, null, shuffle = true)
            }
            else -> when {
                active != null -> if (!exo.isPlaying) resumeFromIdleOrPlayLocked(exo)
                saved != null -> switchToSavedLocked(saved, trackIds, saved.shuffleEnabled)
                else -> startPlaybackLocked(sourceId, trackIds, null, fallbackShuffle)
            }
        }
    }

    /** Plays a tapped track inside the source's current round, keeping its shuffle flag. */
    private suspend fun playTrackLocked(sourceId: String, trackIds: List<Long>, trackId: Long) {
        if (sourceId.isEmpty() || trackIds.isEmpty()) return
        val exo = exoPlayer ?: return
        val active = queue?.takeIf { it.sourceId == sourceId && exo.mediaItemCount > 0 }
        val saved = if (active != null) null else shuffleStore.loadSource(sourceId)
        val base = active ?: saved?.queue
        val shuffle = if (active != null) shuffleEnabled else saved?.shuffleEnabled == true
        val sourceIds = resolveSourceTrackIds(sourceId)?.takeIf { it.isNotEmpty() } ?: trackIds
        val synced = base?.let { it.reconcile(sourceIds, shuffle) ?: it }
        if (synced == null || trackId !in synced.trackIds) {
            startPlaybackLocked(sourceId, sourceIds, trackId, shuffle)
            return
        }
        var next = synced.playNow(trackId, shuffle)
        if (synced.currentTrackId !in sourceIds) next = next.without(synced.currentTrackId)
        ensureForegroundBootstrap("play-track")
        if (active == null) snapshotActiveLocked()
        shuffleEnabled = shuffle
        queue = next
        AmpLog.i("Shuffle", "play track $trackId in $sourceId ${next.progressLabel} shuffle=$shuffle")
        playCurrentLocked(seekMs = 0L, play = true)
    }

    /** Continues a source's saved queue at its track and position, updated to current contents. */
    private suspend fun switchToSavedLocked(
        saved: PersistedShuffle,
        fallbackTrackIds: List<Long>,
        shuffle: Boolean,
    ) {
        val sourceId = saved.queue.sourceId
        val sourceIds = resolveSourceTrackIds(sourceId)?.takeIf { it.isNotEmpty() } ?: fallbackTrackIds
        val q = saved.queue.reconcile(sourceIds, shuffle) ?: saved.queue
        if (library.getTrack(q.currentTrackId) == null) {
            startPlaybackLocked(sourceId, sourceIds, null, shuffle)
            return
        }
        ensureForegroundBootstrap("switch-source")
        snapshotActiveLocked()
        shuffleEnabled = shuffle
        queue = q
        AmpLog.i(
            "Shuffle",
            "continue $sourceId ${q.progressLabel} gen=${q.generation} pos=${saved.positionMs} " +
                "shuffle=$shuffle",
        )
        playCurrentLocked(seekMs = saved.positionMs, play = true)
    }

    /** Saves the active queue with its exact position before another source takes over. */
    private suspend fun snapshotActiveLocked() {
        val q = queue ?: return
        val exo = exoPlayer ?: return
        if (exo.mediaItemCount == 0) return
        shuffleStore.save(q, exo.currentPosition.coerceAtLeast(0L), shuffleEnabled, exo.isPlaying)
    }

    /** Keeps the running queue in step with its source (playlist sync, edits, new songs). */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeActiveSource() {
        serviceScope.launch {
            activeSourceId
                .flatMapLatest { id ->
                    val sourceId = id ?: return@flatMapLatest flowOf(null)
                    val ids = observeSourceTrackIds(sourceId) ?: return@flatMapLatest flowOf(null)
                    ids.map { sourceId to it }
                }
                .debounce(500)
                .distinctUntilChanged()
                .collect { update ->
                    val (sourceId, ids) = update ?: return@collect
                    playbackMutex.withLock { reconcileActiveLocked(sourceId, ids) }
                }
        }
    }

    private fun observeSourceTrackIds(sourceId: String): Flow<List<Long>>? {
        val tracks = when {
            sourceId.startsWith("playlist:") -> {
                val id = sourceId.removePrefix("playlist:").toLongOrNull() ?: return null
                library.observePlaylistTracks(id)
            }
            sourceId.startsWith("dir:") -> library.observeByDirectory(sourceId.removePrefix("dir:"))
            sourceId == "all" -> library.observeTracks()
            else -> return null
        }
        return tracks.map { list -> list.map { it.id } }
    }

    private suspend fun reconcileActiveLocked(sourceId: String, sourceIds: List<Long>) {
        val current = queue ?: return
        if (current.sourceId != sourceId) return
        val updated = current.reconcile(sourceIds, shuffleEnabled) ?: return
        queue = updated
        AmpLog.i(
            "Shuffle",
            "source changed $sourceId size ${current.trackIds.size}→${updated.trackIds.size} " +
                "now ${updated.progressLabel} shuffle=$shuffleEnabled",
        )
        val exo = exoPlayer
        val pos = exo?.let { positionToPersist(it) } ?: anchoredPositionMs
        val playing = exo?.isPlaying == true
        shuffleStore.save(updated, pos, shuffleEnabled, playing)
        publishWidgets(updated, positionMs = pos, isPlaying = playing, force = true)
    }

    private suspend fun restoreShuffleIfPossibleLocked(play: Boolean) {
        val persisted = shuffleStore.load() ?: return
        queue = persisted.queue
        shuffleEnabled = persisted.shuffleEnabled
        // Anchor before any await so a mid-restore onDestroy cannot persist position 0.
        anchoredPositionMs = persisted.positionMs.coerceAtLeast(0L)
        val exo = exoPlayer
        // Don't let idle restore (play=false) overwrite a concurrent widget/app play request.
        val shouldPlay = play || (exo?.playWhenReady == true)
        AmpLog.i(
            "Playback",
            "restore playRequested=$play shouldPlay=$shouldPlay pos=${persisted.positionMs} " +
                "track=${persisted.queue.currentTrackId}",
        )
        if (shouldPlay) ensureForegroundBootstrap("restore-play")
        playCurrentLocked(seekMs = persisted.positionMs, play = shouldPlay)
    }

    private suspend fun setShuffleEnabled(enabled: Boolean) {
        val current = queue ?: run {
            shuffleEnabled = enabled
            return
        }
        if (enabled == shuffleEnabled) return
        shuffleEnabled = enabled
        val sourceIds = resolveSourceTrackIds(current.sourceId) ?: current.trackIds
        val newQueue = buildQueue(
            current.sourceId,
            sourceIds,
            current.currentTrackId,
            shuffle = enabled,
            generation = current.generation,
        )
        val pos = exoPlayer?.let { positionToPersist(it) } ?: anchoredPositionMs
        val playing = exoPlayer?.isPlaying == true
        queue = newQueue
        shuffleStore.save(newQueue, pos, shuffleEnabled, playing)
        publishWidgets(newQueue, positionMs = pos, isPlaying = playing, force = true)
    }

    private suspend fun resolveSourceTrackIds(sourceId: String): List<Long>? = when {
        sourceId.startsWith("playlist:") -> {
            val id = sourceId.removePrefix("playlist:").toLongOrNull() ?: return null
            library.getPlaylistTracks(id).map { it.id }
        }
        sourceId.startsWith("dir:") -> {
            library.getDirectoryTracks(sourceId.removePrefix("dir:")).map { it.id }
        }
        sourceId == "all" -> library.getAllTracks().map { it.id }
        else -> null
    }

    private suspend fun playNextInShuffle() = playbackMutex.withLock {
        val queued = queue ?: return@withLock
        advancing = true
        try {
            // "all" is large and only shrinks when files vanish; the live observer covers it.
            val sourceIds = if (queued.sourceId == "all") null
            else resolveSourceTrackIds(queued.sourceId)?.takeIf { it.isNotEmpty() }
            val current = sourceIds?.let { queued.reconcile(it, shuffleEnabled) } ?: queued
            val next = current.tryAdvance()
            var updated = when {
                next != null -> next
                shuffleEnabled -> reshuffleRound(current)
                else -> current.wrapToStart()
            }
            if (sourceIds != null && current.currentTrackId !in sourceIds.toHashSet()) {
                updated = updated.without(current.currentTrackId)
            }
            queue = updated
            AmpLog.i(
                "Shuffle",
                "advance ${updated.progressLabel} gen=${updated.generation} " +
                    "now=${updated.currentTrackId} remaining=${updated.remainingInRound}" +
                    if (next == null) " (new round, overlap skip ${current.currentTrackId})" else "",
            )
            shuffleStore.save(updated, 0L, shuffleEnabled, isPlaying = true)
            playCurrentLocked(seekMs = 0L, play = true)
        } finally {
            advancing = false
        }
    }

    private suspend fun buildQueue(
        sourceId: String,
        trackIds: List<Long>,
        startTrackId: Long?,
        shuffle: Boolean,
        generation: Int,
    ): ShuffleQueue {
        if (!shuffle) {
            return ShuffleQueue.createOrdered(sourceId, trackIds, startTrackId)
                .copy(generation = generation)
        }
        val tracks = library.getTracksByIds(trackIds)
        val options = ShufflePlanOptions(
            spreadSimilar = userSettings.spreadSimilarTracks.first(),
            spreadSameArtist = userSettings.spreadSameArtist.first(),
        )
        val plan = ShufflePlanner.planRound(
            tracks = tracks,
            startTrackId = startTrackId,
            options = options,
        )
        AmpLog.i(
            "Shuffle",
            "plan size=${plan.trackIds.size} similarPairs=${plan.similarPairs} " +
                "spreadSimilar=${options.spreadSimilar} spreadArtist=${options.spreadSameArtist}",
        )
        return ShuffleQueue.restore(sourceId, plan.trackIds, 0, generation)
    }

    private suspend fun reshuffleRound(current: ShuffleQueue): ShuffleQueue {
        val planned = buildQueue(
            sourceId = current.sourceId,
            trackIds = current.trackIds,
            startTrackId = current.currentTrackId,
            shuffle = true,
            generation = current.generation + 1,
        )
        return if (planned.trackIds.size <= 1) planned
        else planned.copy(currentIndex = 1)
    }

    private suspend fun playPreviousInShuffle() = playbackMutex.withLock {
        val current = queue ?: return@withLock
        val exo = exoPlayer ?: return@withLock
        if (exo.currentPosition > 3_000) {
            anchoredPositionMs = 0L
            exo.seekTo(0)
            persistSoon()
            return@withLock
        }
        val prev = current.retreat()
        queue = prev
        shuffleStore.save(prev, 0L, shuffleEnabled, exo.isPlaying)
        playCurrentLocked(seekMs = 0L, play = true)
    }

    private suspend fun playCurrentLocked(seekMs: Long, play: Boolean) {
        val q = queue ?: return
        val exo = exoPlayer ?: return
        val track = library.getTrack(q.currentTrackId) ?: return
        val item = mediaItemFor(track)
        AmpLog.i(
            "Playback",
            "playCurrent id=${track.id} title=${track.title} seekMs=$seekMs play=$play " +
                "shuffle=$shuffleEnabled source=${q.sourceId}",
        )
        if (play) ensureForegroundBootstrap("playCurrent")
        lastPlayCurrentElapsed = SystemClock.elapsedRealtime()
        lastPlayCurrentWantedPlay = play
        anchoredPositionMs = seekMs.coerceAtLeast(0L)
        applyReplayGainLocked(track)
        exo.setMediaItem(item, seekMs)
        exo.prepare()
        if (play) exo.play() else exo.pause()
        library.recordPlay(q.currentTrackId)
        shuffleStore.save(q, seekMs, shuffleEnabled, play)
        publishWidgets(q, track, seekMs, play || exo.isPlaying, force = true)
        widgetUpdater.setPlaying(play || exo.isPlaying)
        if (play) armA2dpKick("play-after-a2dp")
    }

    /**
     * Prefer Exo's live position once it has moved; otherwise keep [anchoredPositionMs]
     * so a paused restore at e.g. 2:16 is not written back as 0.
     */
    private fun positionToPersist(exo: ExoPlayer): Long {
        val live = exo.currentPosition.coerceAtLeast(0L)
        if (live >= 1_500L || exo.isPlaying) {
            anchoredPositionMs = live
            return live
        }
        return anchoredPositionMs.coerceAtLeast(live)
    }

    private suspend fun applyReplayGainLocked(track: io.github.gess79.amp.data.db.TrackEntity) {
        val enabled = userSettings.replayGainEnabled.first()
        val limiter = userSettings.replayGainLimiter.first()
        replayGainProcessor.captureEnabled = enabled
        replayGainProcessor.limiterEnabled = enabled && limiter
        replayGainProcessor.resetCapture()
        if (!enabled) {
            replayGainProcessor.setGainDb(0f)
            return
        }
        val entry = loudnessRepository.get(track.id)
        if (entry == null) {
            replayGainProcessor.setGainDb(0f)
            AmpLog.i("ReplayGain", "no cache yet id=${track.id} title=${track.title}")
            return
        }
        val stale = !loudnessRepository.isCurrent(entry, track)
        val gainDb = ReplayGain.playbackGainDb(entry.lufs, entry.peakDb, limiter)
        replayGainProcessor.setGainDb(gainDb)
        AmpLog.i(
            "ReplayGain",
            "apply id=${track.id} title=${track.title} lufs=${"%.1f".format(entry.lufs)} " +
                "peak=${"%.1f".format(entry.peakDb)} gain=${"%.1f".format(gainDb)}dB " +
                "limiter=$limiter source=${entry.source}" +
                if (stale) " stale=true" else "",
        )
    }

    private suspend fun savePlaybackLoudnessIfComplete() {
        if (!userSettings.replayGainEnabled.first()) return
        val trackId = queue?.currentTrackId ?: return
        val track = library.getTrack(trackId) ?: return
        if (loudnessRepository.get(track.id) != null) return
        val result = replayGainProcessor.finishCapture() ?: return
        loudnessRepository.upsert(track, result, source = "playback")
        AmpLog.i(
            "ReplayGain",
            "learned playback id=${track.id} lufs=${"%.1f".format(result.lufs)} peak=${"%.1f".format(result.peakDb)}",
        )
    }

    private fun mediaTransitionReason(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "OTHER($reason)"
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "OTHER($reason)"
    }

    /**
     * ExoPlayer owns the focus request. A second requestAudioFocus would take
     * focus ourselves, so the result is read from the player state afterwards:
     * granted (playWhenReady), delayed (transient suppression), failed (still paused).
     */
    private fun logAudioFocusResult(reason: String, exo: ExoPlayer) {
        val result = when {
            exo.playbackSuppressionReason ==
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "delayed"
            exo.playWhenReady || exo.isPlaying -> "granted"
            else -> "failed"
        }
        AmpLog.i(
            "Playback",
            "audio focus result=$result reason=$reason " +
                "playWhenReady=${exo.playWhenReady} playing=${exo.isPlaying} " +
                "suppression=${suppressionName(exo.playbackSuppressionReason)} " +
                audioActivitySnapshot(),
        )
    }

    private fun audioActivitySnapshot(): String {
        val am = getSystemService(AudioManager::class.java) ?: return "mode=unknown"
        val mode = when (am.mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in-call"
            AudioManager.MODE_IN_COMMUNICATION -> "in-communication"
            else -> "other(${am.mode})"
        }
        val active = am.activePlaybackConfigurations.joinToString(prefix = "[", postfix = "]") { cfg ->
            audioUsageName(cfg.audioAttributes.usage)
        }
        return "mode=$mode musicActive=${am.isMusicActive} active=$active"
    }

    private fun audioUsageName(usage: Int): String = when (usage) {
        android.media.AudioAttributes.USAGE_MEDIA -> "media"
        android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "voice-call"
        android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "call-signalling"
        android.media.AudioAttributes.USAGE_NOTIFICATION -> "notification"
        android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
        android.media.AudioAttributes.USAGE_ASSISTANT -> "assistant"
        android.media.AudioAttributes.USAGE_GAME -> "game"
        android.media.AudioAttributes.USAGE_ALARM -> "alarm"
        android.media.AudioAttributes.USAGE_UNKNOWN -> "unknown"
        else -> "usage($usage)"
    }

    private fun suppressionName(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "NONE"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE -> "UNSUITABLE_AUDIO_ROUTE"
        else -> "OTHER($reason)"
    }

    private suspend fun publishWidgets(
        q: ShuffleQueue? = queue,
        track: io.github.gess79.amp.data.db.TrackEntity? = null,
        positionMs: Long = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L,
        isPlaying: Boolean = exoPlayer?.isPlaying == true,
        force: Boolean = false,
    ) {
        val active = q ?: return
        val current = track ?: library.getTrack(active.currentTrackId)
        widgetUpdater.publish(
            queue = active,
            current = current,
            positionMs = positionMs,
            isPlaying = isPlaying,
            shuffleEnabled = shuffleEnabled,
            force = force,
        )
    }

    private suspend fun mediaItemFor(trackId: Long): MediaItem? {
        val track = library.getTrack(trackId) ?: return null
        return mediaItemFor(track)
    }

    private fun mediaItemFor(track: io.github.gess79.amp.data.db.TrackEntity): MediaItem {
        val uri = library.contentUriFor(track.id)
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.albumArtUri?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    private fun browsable(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun playable(id: Long, title: String, artist: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun startPersistLoop() {
        persistJob = serviceScope.launch {
            while (isActive) {
                delay(1_000)
                val q = queue ?: continue
                val exo = exoPlayer ?: continue
                if (!exo.isPlaying) continue
                val pos = positionToPersist(exo)
                shuffleStore.save(q, pos, shuffleEnabled, true)
                publishWidgets(q, positionMs = pos, isPlaying = true, force = false)
            }
        }
    }

    private fun persistSoon(forceWidget: Boolean = false) {
        val q = queue ?: return
        val exo = exoPlayer ?: return
        serviceScope.launch {
            val pos = positionToPersist(exo)
            shuffleStore.save(q, pos, shuffleEnabled, exo.isPlaying)
            publishWidgets(q, positionMs = pos, isPlaying = exo.isPlaying, force = forceWidget)
        }
    }

    private fun persistNowBlocking() {
        val q = queue ?: return
        val exo = exoPlayer ?: return
        runBlocking {
            shuffleStore.save(
                q,
                positionToPersist(exo),
                shuffleEnabled,
                exo.isPlaying,
            )
        }
    }

    private fun observeSleepTimer() {
        sleepJob = serviceScope.launch {
            userSettings.sleepTimerEndEpochMs.collect { end ->
                if (end == null) return@collect
                val delayMs = end - System.currentTimeMillis()
                if (delayMs > 0) delay(delayMs)
                expectLocalUserPause = true
                exoPlayer?.pause()
                userSettings.setSleepTimerEnd(null)
            }
        }
    }
}
