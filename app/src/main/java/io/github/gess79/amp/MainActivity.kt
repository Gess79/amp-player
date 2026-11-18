package io.github.gess79.amp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.gess79.amp.data.library.LibrarySync
import io.github.gess79.amp.data.playlist.PlaylistFolders
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.playback.BluetoothWakeDiagnostics
import io.github.gess79.amp.playback.PlaybackService
import io.github.gess79.amp.ui.components.rememberPlaylistFolderPicker
import io.github.gess79.amp.ui.navigation.AmpRoot
import io.github.gess79.amp.ui.theme.AmpTheme
import io.github.gess79.amp.ui.theme.applyAmpSystemBars
import io.github.gess79.amp.util.AmpLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val FOREGROUND_SYNC_MIN_GAP_MS = 10_000L

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var librarySync: LibrarySync
    @Inject lateinit var userSettings: UserSettings
    @Inject lateinit var playlistFolders: PlaylistFolders

    private var hasAudioPermission by mutableStateOf(false)
    private var lastSyncElapsed = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            maybeRequestNotificationPermission()
            maybeRequestBluetoothConnectPermission()
            refreshLibrary()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* media FGS works without it; notification visibility is nicer with it */ }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AmpLog.i("Playback", "BLUETOOTH_CONNECT permission result=$granted")
        BluetoothWakeDiagnostics.logStatus(this, "after-permission-dialog")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default theme is dark. AmpTheme replaces this once the saved choice is known.
        applyAmpSystemBars(darkTheme = true)
        hasAudioPermission = hasAudioPermission()
        if (hasAudioPermission) {
            maybeRequestNotificationPermission()
            maybeRequestBluetoothConnectPermission()
            refreshLibrary()
        } else {
            requestAudioPermission()
        }

        setContent {
            val dark by userSettings.darkTheme.collectAsStateWithLifecycle(initialValue = true)
            // Initial true: never flash the folder prompt before DataStore has loaded.
            val folderPromptDone by userSettings.playlistFolderPromptDone
                .collectAsStateWithLifecycle(initialValue = true)
            val pickFolder = rememberPlaylistFolderPicker { uri ->
                lifecycleScope.launch {
                    playlistFolders.add(uri)
                    librarySync.refreshAll()
                }
            }
            AmpTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        !hasAudioPermission -> PermissionGate(
                            message = stringResource(R.string.permission_rationale),
                            actionLabel = stringResource(R.string.grant_permission),
                            onRequest = ::requestAudioPermission,
                        )
                        !folderPromptDone -> PermissionGate(
                            message = stringResource(R.string.playlist_folder_prompt),
                            actionLabel = stringResource(R.string.playlist_folder_choose),
                            onRequest = pickFolder,
                            secondaryLabel = stringResource(R.string.skip_for_now),
                            onSecondary = {
                                lifecycleScope.launch { userSettings.setPlaylistFolderPromptDone(true) }
                            },
                        )
                        else -> AmpRoot()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground: pick up playlist files edited elsewhere (e.g. copied from a PC).
        if (hasAudioPermission &&
            SystemClock.elapsedRealtime() - lastSyncElapsed > FOREGROUND_SYNC_MIN_GAP_MS
        ) {
            refreshLibrary()
        }
    }

    override fun onResume() {
        super.onResume()
        val audio = hasAudioPermission()
        if (audio != hasAudioPermission) {
            hasAudioPermission = audio
            if (audio) refreshLibrary()
        }
        BluetoothWakeDiagnostics.logStatus(this, "MainActivity.onResume")
        maybeRequestBluetoothConnectPermission()
        runCatching {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_REFRESH_WIDGETS))
        }
    }

    private fun refreshLibrary() {
        lastSyncElapsed = SystemClock.elapsedRealtime()
        lifecycleScope.launch { librarySync.refreshAll() }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (BluetoothWakeDiagnostics.hasConnectPermission(this)) return
        // One ask per process. Repeating from onResume after a denial reopens the
        // dialog in a loop and looks like the app is closing. Settings can ask again.
        if (!bluetoothConnectAsked.compareAndSet(false, true)) return
        AmpLog.i("Playback", "Requesting BLUETOOTH_CONNECT for ACL/A2DP wake")
        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    companion object {
        private val bluetoothConnectAsked = java.util.concurrent.atomic.AtomicBoolean(false)
    }

}

@androidx.compose.runtime.Composable
private fun PermissionGate(
    message: String,
    actionLabel: String,
    onRequest: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text(actionLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}
