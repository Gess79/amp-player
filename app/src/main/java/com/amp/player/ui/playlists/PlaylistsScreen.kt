package com.amp.player.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amp.player.R
import com.amp.player.ui.components.SourceDetailHeader
import com.amp.player.ui.components.SourcePlaybackControls
import com.amp.player.ui.components.TrackRow
import com.amp.player.ui.components.rememberPlaylistFolderPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onOpenSettings: () -> Unit,
    vm: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPlaylistId.collectAsStateWithLifecycle()
    val selectedPlaylist by vm.selectedPlaylist.collectAsStateWithLifecycle()
    val tracks by vm.selectedTracks.collectAsStateWithLifecycle()
    val detailStats by vm.detailStats.collectAsStateWithLifecycle()
    val detailLoading by vm.detailLoading.collectAsStateWithLifecycle()
    val listStatus by vm.listStatus.collectAsStateWithLifecycle()
    val playlistPlaying by vm.isCurrentPlaylistPlaying.collectAsStateWithLifecycle()
    val shuffleChipSelected by vm.shuffleChipSelected.collectAsStateWithLifecycle()
    val sourceProgress by vm.sourceProgress.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val replayGainEnabled by vm.replayGainEnabled.collectAsStateWithLifecycle()
    val loudnessStatus by vm.loudnessStatus.collectAsStateWithLifecycle()
    val loudnessScan by vm.loudnessScan.collectAsStateWithLifecycle()
    val hasPlaylistFolders by vm.hasPlaylistFolders.collectAsStateWithLifecycle()
    val pickPlaylistFolder = rememberPlaylistFolderPicker(vm::addPlaylistFolder)
    val currentTrackId = playback.mediaId?.toLongOrNull()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    BackHandler(enabled = selectedId != null) { vm.selectPlaylist(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedId == null) {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.playlists_title)) },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                        )
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_new_playlist),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                },
            )
            listStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!hasPlaylistFolders) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.playlists_folder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = pickPlaylistFolder,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.playlist_folder_choose))
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectPlaylist(playlist.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                val subtitle = when {
                                    playlist.isFavorites -> stringResource(R.string.playlist_type_favorites)
                                    playlist.sourcePath?.startsWith("/") == true ->
                                        playlist.sourcePath.substringAfterLast('/')
                                    playlist.sourcePath != null -> stringResource(R.string.playlist_type_file)
                                    else -> stringResource(R.string.playlist_type_app)
                                }
                                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.shufflePlaylist(playlist.id) }) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = stringResource(R.string.cd_shuffle_play),
                                )
                            }
                            if (!playlist.isFavorites) {
                                IconButton(onClick = { vm.deletePlaylist(playlist.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.cd_delete),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(selectedPlaylist?.name ?: stringResource(R.string.playlist_fallback_title))
                },
                navigationIcon = {
                    IconButton(onClick = { vm.selectPlaylist(null) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )

            if (detailLoading && tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.playlist_loading),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                SourceDetailHeader(
                    sourceId = "playlist:$selectedId",
                    stats = detailStats,
                    loudnessStatus = loudnessStatus,
                    loudnessScan = loudnessScan,
                    replayGainEnabled = replayGainEnabled,
                    onAnalyze = vm::analyzeLoudness,
                    onResetLoudness = vm::resetLoudness,
                )
                SourcePlaybackControls(
                    isPlaying = playlistPlaying,
                    shuffleOn = shuffleChipSelected,
                    progress = sourceProgress,
                    onPlayPause = vm::playPauseSelected,
                    onToggleShuffle = vm::toggleShufflePlay,
                )
                if (detailLoading) {
                    Text(
                        text = stringResource(R.string.playlist_updating_tracks),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                LazyColumn {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            onClick = { vm.playTrackInSelected(track.id) },
                            trailing = {
                                IconButton(onClick = { vm.removeFromSelected(track.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.cd_remove),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.dialog_new_playlist)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createPlaylist(newName)
                        newName = ""
                        showCreate = false
                    },
                ) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
