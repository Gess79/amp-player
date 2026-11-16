package io.github.gess79.amp.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gess79.amp.R
import io.github.gess79.amp.data.settings.AppLanguage
import io.github.gess79.amp.playback.BluetoothWakeDiagnostics
import io.github.gess79.amp.ui.components.rememberPlaylistFolderPicker
import io.github.gess79.amp.util.AmpLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val resume by vm.resumeShuffle.collectAsStateWithLifecycle()
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val spreadSimilar by vm.spreadSimilarTracks.collectAsStateWithLifecycle()
    val spreadArtist by vm.spreadSameArtist.collectAsStateWithLifecycle()
    val replayGain by vm.replayGainEnabled.collectAsStateWithLifecycle()
    val replayGainLimiter by vm.replayGainLimiter.collectAsStateWithLifecycle()
    val bluetoothAutoplay by vm.bluetoothAutoplay.collectAsStateWithLifecycle()
    var showAutoplayDevices by remember { mutableStateOf(false) }
    val appLanguage by vm.appLanguage.collectAsStateWithLifecycle()
    val importMessage by vm.importMessage.collectAsStateWithLifecycle()
    val playlistFolders by vm.playlistFolderList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var bluetoothWakeStatus by remember {
        mutableStateOf(BluetoothWakeDiagnostics.statusText(context))
    }

    LaunchedEffect(Unit) {
        bluetoothWakeStatus = BluetoothWakeDiagnostics.statusText(context)
    }

    BackHandler(enabled = showAutoplayDevices) {
        showAutoplayDevices = false
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) vm.importM3u(uri)
    }
    val pickPlaylistFolder = rememberPlaylistFolderPicker(vm::addPlaylistFolder)

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AmpLog.i("Playback", "BLUETOOTH_CONNECT from settings result=$granted")
        bluetoothWakeStatus = BluetoothWakeDiagnostics.statusText(context)
        vm.checkBluetoothWakePermission()
        Toast.makeText(
            context,
            if (granted) {
                context.getString(R.string.toast_bt_permission_granted)
            } else {
                context.getString(R.string.toast_bt_permission_denied)
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    if (showAutoplayDevices) {
        BluetoothAutoplayDevicesScreen(
            onBack = { showAutoplayDevices = false },
            vm = vm,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.settings_language_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = appLanguage == AppLanguage.SYSTEM,
                    onClick = {
                        vm.setAppLanguage(AppLanguage.SYSTEM)
                        (context as? Activity)?.recreate()
                    },
                    label = { Text(stringResource(R.string.language_system)) },
                )
                FilterChip(
                    selected = appLanguage == AppLanguage.GERMAN,
                    onClick = {
                        vm.setAppLanguage(AppLanguage.GERMAN)
                        (context as? Activity)?.recreate()
                    },
                    label = { Text(stringResource(R.string.language_german)) },
                )
                FilterChip(
                    selected = appLanguage == AppLanguage.ENGLISH,
                    onClick = {
                        vm.setAppLanguage(AppLanguage.ENGLISH)
                        (context as? Activity)?.recreate()
                    },
                    label = { Text(stringResource(R.string.language_english)) },
                )
            }

            SettingsToggle(
                title = stringResource(R.string.settings_resume_shuffle),
                subtitle = stringResource(R.string.settings_resume_shuffle_subtitle),
                checked = resume,
                onCheckedChange = vm::setResumeShuffle,
            )
            SettingsToggle(
                title = stringResource(R.string.settings_spread_similar),
                subtitle = stringResource(R.string.settings_spread_similar_subtitle),
                checked = spreadSimilar,
                onCheckedChange = vm::setSpreadSimilarTracks,
            )
            SettingsToggle(
                title = stringResource(R.string.settings_spread_artist),
                subtitle = stringResource(R.string.settings_spread_artist_subtitle),
                checked = spreadArtist,
                onCheckedChange = vm::setSpreadSameArtist,
            )
            SettingsToggle(
                title = stringResource(R.string.settings_dark_theme),
                subtitle = stringResource(R.string.settings_dark_theme_subtitle),
                checked = dark,
                onCheckedChange = vm::setDarkTheme,
            )
            SettingsToggle(
                title = stringResource(R.string.settings_replay_gain),
                subtitle = stringResource(R.string.settings_replay_gain_subtitle),
                checked = replayGain,
                onCheckedChange = vm::setReplayGainEnabled,
            )
            if (replayGain) {
                SettingsToggle(
                    title = stringResource(R.string.settings_replay_gain_limiter),
                    subtitle = stringResource(R.string.settings_replay_gain_limiter_subtitle),
                    checked = replayGainLimiter,
                    onCheckedChange = vm::setReplayGainLimiter,
                )
            }
            SettingsToggle(
                title = stringResource(R.string.settings_bluetooth_autoplay),
                subtitle = stringResource(R.string.settings_bluetooth_autoplay_subtitle),
                checked = bluetoothAutoplay,
                onCheckedChange = vm::setBluetoothAutoplay,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAutoplayDevices = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_bluetooth_autoplay_devices))
                    Text(
                        stringResource(R.string.settings_bluetooth_autoplay_devices_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            }

            Text(
                stringResource(R.string.settings_playlist_folders),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.settings_playlist_folders_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (playlistFolders.isEmpty()) {
                Text(
                    stringResource(R.string.settings_playlist_folders_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            playlistFolders.forEach { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        if (folder.hasAccess) {
                            folder.label
                        } else {
                            stringResource(R.string.settings_playlist_folder_no_access, folder.label)
                        },
                        color = if (folder.hasAccess) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.removePlaylistFolder(folder.uri) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.cd_remove_folder),
                        )
                    }
                }
            }
            TextButton(onClick = pickPlaylistFolder) {
                Text(stringResource(R.string.settings_playlist_folder_add))
            }

            TextButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "audio/x-mpegurl",
                            "application/xspf+xml",
                            "text/plain",
                            "*/*",
                        ),
                    )
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.settings_import_playlist_file))
            }
            TextButton(onClick = vm::refreshLibrary) {
                Text(stringResource(R.string.settings_refresh_library))
            }
            TextButton(onClick = { vm.shuffleAll() }) {
                Text(stringResource(R.string.settings_shuffle_all))
            }
            TextButton(
                onClick = vm::shareLogs,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.settings_share_logs))
            }
            TextButton(onClick = vm::clearLogs) {
                Text(stringResource(R.string.settings_clear_logs))
            }

            Text(
                stringResource(R.string.settings_bluetooth_wake_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                bluetoothWakeStatus,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            TextButton(
                onClick = {
                    val status = BluetoothWakeDiagnostics.statusText(context)
                    bluetoothWakeStatus = status
                    vm.checkBluetoothWakePermission()
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !BluetoothWakeDiagnostics.hasConnectPermission(context)
                    ) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
            ) {
                Text(stringResource(R.string.settings_check_bt_wake))
            }
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
            ) {
                Text(stringResource(R.string.settings_open_app_permissions))
            }
            importMessage?.let {
                Text(it, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
