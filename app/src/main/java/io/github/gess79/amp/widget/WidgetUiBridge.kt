package io.github.gess79.amp.widget

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import io.github.gess79.amp.data.db.TrackEntity
import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.R
import io.github.gess79.amp.data.settings.AppLocale
import io.github.gess79.amp.shuffle.ShuffleQueue
import io.github.gess79.amp.ui.components.formatMs
import io.github.gess79.amp.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal object WidgetPrefs {
    val title = stringPreferencesKey("title")
    val artist = stringPreferencesKey("artist")
    val artworkUri = stringPreferencesKey("artwork_uri")
    val positionMs = longPreferencesKey("position_ms")
    val durationMs = longPreferencesKey("duration_ms")
    val isPlaying = booleanPreferencesKey("is_playing")
    val shuffleEnabled = booleanPreferencesKey("shuffle_enabled")
    val playlistName = stringPreferencesKey("playlist_name")
    val nextTitle = stringPreferencesKey("next_title")
    val hasQueue = booleanPreferencesKey("has_queue")
    val progress = floatPreferencesKey("progress")
    val positionLabel = stringPreferencesKey("position_label")
    val durationLabel = stringPreferencesKey("duration_label")
    val queueProgress = stringPreferencesKey("queue_progress")
}

@Singleton
class WidgetUiBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var lastPublishElapsed = 0L
    private var lastPublishKey = ""

    private fun strings(): Context = AppLocale.wrap(context)

    fun publish(
        queue: ShuffleQueue?,
        current: TrackEntity?,
        positionMs: Long,
        isPlaying: Boolean,
        shuffleEnabled: Boolean,
        force: Boolean = false,
    ) {
        val key = "${current?.id}|$isPlaying|$shuffleEnabled|${queue?.currentIndex}|${queue?.generation}"
        val now = SystemClock.elapsedRealtime()
        val sameTrack = key == lastPublishKey
        if (!force && sameTrack) {
            // Progress ticks: only while the phone is awake and unlocked.
            // Android does not tell us which home-screen page is visible.
            if (!isPhoneUnlocked()) return
        }
        lastPublishKey = key
        lastPublishElapsed = now
        scope.launch {
            runCatching {
                val str = strings()
                val next = queue?.peekNextId()?.let { library.getTrack(it) }
                val playlistName = queue?.sourceId?.let { resolveSourceLabel(str, it) }
                val durationMs = current?.durationMs ?: 0L
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                writeAndUpdate { prefs ->
                    prefs[WidgetPrefs.title] = current?.title
                        ?: str.getString(R.string.app_name)
                    prefs[WidgetPrefs.artist] = current?.artist
                        ?: if (queue == null) {
                            str.getString(R.string.widget_tap_to_open)
                        } else {
                            str.getString(R.string.placeholder_em_dash)
                        }
                    val art = current?.albumArtUri
                    if (art != null) prefs[WidgetPrefs.artworkUri] = art
                    else prefs.remove(WidgetPrefs.artworkUri)
                    prefs[WidgetPrefs.positionMs] = positionMs
                    prefs[WidgetPrefs.durationMs] = durationMs
                    prefs[WidgetPrefs.isPlaying] = isPlaying
                    prefs[WidgetPrefs.shuffleEnabled] = shuffleEnabled
                    prefs[WidgetPrefs.hasQueue] = queue != null
                    prefs[WidgetPrefs.progress] = progress
                    prefs[WidgetPrefs.positionLabel] = formatMs(positionMs)
                    prefs[WidgetPrefs.durationLabel] = formatMs(durationMs)
                    if (playlistName != null) prefs[WidgetPrefs.playlistName] = playlistName
                    else prefs.remove(WidgetPrefs.playlistName)
                    if (next != null) prefs[WidgetPrefs.nextTitle] = "${next.title} — ${next.artist}"
                    else prefs.remove(WidgetPrefs.nextTitle)
                    if (queue != null) prefs[WidgetPrefs.queueProgress] = queue.progressLabel
                    else prefs.remove(WidgetPrefs.queueProgress)
                }
            }.onFailure { AmpLog.e("Widget", "publish failed title=${current?.title}", it) }
        }
    }

    fun setPlaying(playing: Boolean) {
        if (!playing) {
            scope.launch { runCatching { refreshWidgets() } }
        }
    }

    fun updateAll() {
        scope.launch { runCatching { refreshWidgets() } }
    }

    private suspend fun writeAndUpdate(block: (MutablePreferences) -> Unit) = mutex.withLock {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(NowPlayingWidget::class.java) +
            manager.getGlanceIds(ShufflePlayWidget::class.java)
        if (ids.isEmpty()) return
        for (id in ids) {
            updateAppWidgetState(context, id) { prefs ->
                block(prefs)
            }
        }
        NowPlayingWidget().updateAll(context)
        ShufflePlayWidget().updateAll(context)
    }

    private suspend fun refreshWidgets() = mutex.withLock {
        NowPlayingWidget().updateAll(context)
        ShufflePlayWidget().updateAll(context)
    }

    private fun isPhoneUnlocked(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power?.isInteractive == true
        val locked = keyguard?.isKeyguardLocked == true
        return interactive && !locked
    }

    private suspend fun resolveSourceLabel(str: Context, sourceId: String): String? = when {
        sourceId.startsWith("playlist:") -> {
            val id = sourceId.removePrefix("playlist:").toLongOrNull() ?: return null
            library.getPlaylist(id)?.name
        }
        sourceId.startsWith("dir:") -> sourceId.removePrefix("dir:").substringAfterLast('/')
        sourceId == "all" -> str.getString(R.string.source_all_tracks)
        sourceId.startsWith("search") -> str.getString(R.string.source_search)
        else -> null
    }
}
