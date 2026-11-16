package io.github.gess79.amp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("amp_settings")

@Singleton
class UserSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resumeShuffleKey = booleanPreferencesKey("resume_shuffle")
    private val darkThemeKey = booleanPreferencesKey("dark_theme")
    private val sleepTimerEndKey = longPreferencesKey("sleep_timer_end")
    private val defaultPlaylistKey = longPreferencesKey("default_playlist_id")
    private val spreadSimilarKey = booleanPreferencesKey("shuffle_spread_similar")
    private val spreadArtistKey = booleanPreferencesKey("shuffle_spread_artist")
    private val resumeOnBluetoothKey = booleanPreferencesKey("resume_on_bluetooth")
    private val replayGainEnabledKey = booleanPreferencesKey("replay_gain_enabled")
    private val replayGainLimiterKey = booleanPreferencesKey("replay_gain_limiter")
    private val appLanguageKey = stringPreferencesKey("app_language")
    private val playlistFoldersKey = stringSetPreferencesKey("playlist_folder_uris")
    private val playlistFolderPromptDoneKey = booleanPreferencesKey("playlist_folder_prompt_done")
    private val bluetoothAutoplayKey = booleanPreferencesKey("bluetooth_autoplay")
    private val autoplayDevicesKey = stringPreferencesKey("bt_autoplay_devices")

    /** SAF tree URIs (persisted grants) that are scanned for m3u/xspf files. */
    val playlistFolderUris: Flow<Set<String>> =
        context.dataStore.data.map { it[playlistFoldersKey] ?: emptySet() }

    /** True once the user picked a folder or chose "Later" on the start prompt. */
    val playlistFolderPromptDone: Flow<Boolean> =
        context.dataStore.data.map { it[playlistFolderPromptDoneKey] ?: false }

    suspend fun addPlaylistFolder(uri: String) {
        context.dataStore.edit { it[playlistFoldersKey] = (it[playlistFoldersKey] ?: emptySet()) + uri }
    }

    suspend fun removePlaylistFolder(uri: String) {
        context.dataStore.edit { it[playlistFoldersKey] = (it[playlistFoldersKey] ?: emptySet()) - uri }
    }

    suspend fun setPlaylistFolderPromptDone(value: Boolean) {
        context.dataStore.edit { it[playlistFolderPromptDoneKey] = value }
    }

    val resumeShuffle: Flow<Boolean> =
        context.dataStore.data.map { it[resumeShuffleKey] ?: true }

    val darkTheme: Flow<Boolean> =
        context.dataStore.data.map { it[darkThemeKey] ?: true }

    val spreadSimilarTracks: Flow<Boolean> =
        context.dataStore.data.map { it[spreadSimilarKey] ?: true }

    val spreadSameArtist: Flow<Boolean> =
        context.dataStore.data.map { it[spreadArtistKey] ?: true }

    val sleepTimerEndEpochMs: Flow<Long?> =
        context.dataStore.data.map { it[sleepTimerEndKey] }

    val defaultPlaylistId: Flow<Long?> =
        context.dataStore.data.map { it[defaultPlaylistKey] }

    /** Internal: resume after A2DP reconnect unless the user explicitly paused. */
    val resumeOnBluetooth: Flow<Boolean> =
        context.dataStore.data.map { it[resumeOnBluetoothKey] ?: false }

    val replayGainEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[replayGainEnabledKey] ?: false }

    val replayGainLimiter: Flow<Boolean> =
        context.dataStore.data.map { it[replayGainLimiterKey] ?: false }

    val appLanguage: Flow<AppLanguage> =
        context.dataStore.data.map { prefs ->
            if (!prefs.contains(appLanguageKey)) AppLanguage.DEFAULT
            else AppLanguage.fromStored(prefs[appLanguageKey])
        }

    suspend fun setResumeShuffle(value: Boolean) {
        context.dataStore.edit { it[resumeShuffleKey] = value }
    }

    suspend fun setDarkTheme(value: Boolean) {
        context.dataStore.edit { it[darkThemeKey] = value }
    }

    suspend fun setSpreadSimilarTracks(value: Boolean) {
        context.dataStore.edit { it[spreadSimilarKey] = value }
    }

    suspend fun setSpreadSameArtist(value: Boolean) {
        context.dataStore.edit { it[spreadArtistKey] = value }
    }

    suspend fun setSleepTimerEnd(epochMs: Long?) {
        context.dataStore.edit {
            if (epochMs == null) it.remove(sleepTimerEndKey) else it[sleepTimerEndKey] = epochMs
        }
    }

    suspend fun setDefaultPlaylistId(id: Long?) {
        context.dataStore.edit {
            if (id == null) it.remove(defaultPlaylistKey) else it[defaultPlaylistKey] = id
        }
    }

    suspend fun setResumeOnBluetooth(value: Boolean) {
        context.dataStore.edit { it[resumeOnBluetoothKey] = value }
    }

    suspend fun setReplayGainEnabled(value: Boolean) {
        context.dataStore.edit { it[replayGainEnabledKey] = value }
    }

    suspend fun setReplayGainLimiter(value: Boolean) {
        context.dataStore.edit { it[replayGainLimiterKey] = value }
    }

    suspend fun setAppLanguage(value: AppLanguage) {
        context.dataStore.edit { it[appLanguageKey] = value.tag }
        AppLocale.apply(value)
    }

    /** User switch. Default on: connecting a music device may resume playback. */
    val bluetoothAutoplay: Flow<Boolean> =
        context.dataStore.data.map { it[bluetoothAutoplayKey] ?: true }

    val autoplayDevices: Flow<List<AutoplayDevice>> =
        context.dataStore.data.map { decodeAutoplayDevices(it[autoplayDevicesKey]) }

    suspend fun setBluetoothAutoplay(value: Boolean) {
        context.dataStore.edit { it[bluetoothAutoplayKey] = value }
    }

    suspend fun setAutoplayDeviceAllowed(address: String, allowed: Boolean) {
        context.dataStore.edit { prefs ->
            val devices = decodeAutoplayDevices(prefs[autoplayDevicesKey]).map { device ->
                if (device.address == address) device.copy(allowed = allowed) else device
            }
            prefs[autoplayDevicesKey] = encodeAutoplayDevices(devices)
        }
    }

    /**
     * Adds newly seen music outputs as allowed. An existing choice is kept.
     * A non-blank name replaces a blank one.
     */
    suspend fun rememberAutoplayDevices(seen: List<AutoplayDevice>) {
        if (seen.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = decodeAutoplayDevices(prefs[autoplayDevicesKey]).associateBy { it.address }
            val merged = current.toMutableMap()
            seen.forEach { device ->
                val existing = merged[device.address]
                merged[device.address] = when {
                    existing == null -> device.copy(allowed = true)
                    existing.name.isBlank() && device.name.isNotBlank() -> existing.copy(name = device.name)
                    else -> existing
                }
            }
            prefs[autoplayDevicesKey] = encodeAutoplayDevices(merged.values.toList())
        }
    }

    /** Unknown addresses are allowed until the user turns that device off. */
    suspend fun isAutoplayDeviceAllowed(address: String): Boolean {
        val devices = decodeAutoplayDevices(
            context.dataStore.data.map { it[autoplayDevicesKey] }.first(),
        )
        return devices.find { it.address == address }?.allowed ?: true
    }
}
