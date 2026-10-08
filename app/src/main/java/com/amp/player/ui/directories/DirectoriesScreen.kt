package com.amp.player.ui.directories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoriesScreen(vm: DirectoriesViewModel = hiltViewModel()) {
    val directories by vm.directories.collectAsStateWithLifecycle()
    val selected by vm.selectedDirectory.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val directoryPlaying by vm.isCurrentDirectoryPlaying.collectAsStateWithLifecycle()
    val shuffleOn by vm.shuffleOn.collectAsStateWithLifecycle()
    val sourceProgress by vm.sourceProgress.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val loudnessStatus by vm.loudnessStatus.collectAsStateWithLifecycle()
    val loudnessScan by vm.loudnessScan.collectAsStateWithLifecycle()
    val replayGainEnabled by vm.replayGainEnabled.collectAsStateWithLifecycle()
    val currentTrackId = playback.mediaId?.toLongOrNull()

    BackHandler(enabled = selected != null) { vm.selectDirectory(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.directories_title)) },
            actions = {
                IconButton(onClick = vm::refresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                    )
                }
            },
        )

        if (selected == null) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(directories, key = { it }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectDirectory(dir) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Text(
                                text = dir,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            IconButton(onClick = { vm.shuffleDirectory(dir) }) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = stringResource(R.string.cd_shuffle),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.selectDirectory(null) }) {
                    Text(stringResource(R.string.action_back))
                }
                Text(
                    text = selected.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            SourceDetailHeader(
                sourceId = "dir:$selected",
                stats = stats,
                loudnessStatus = loudnessStatus,
                loudnessScan = loudnessScan,
                replayGainEnabled = replayGainEnabled,
                onAnalyze = vm::analyzeLoudness,
                onResetLoudness = vm::resetLoudness,
            )
            SourcePlaybackControls(
                isPlaying = directoryPlaying,
                shuffleOn = shuffleOn,
                progress = sourceProgress,
                onPlayPause = vm::playPauseSelected,
                onToggleShuffle = vm::toggleShuffleSelected,
            )
            LazyColumn {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = { vm.playFromDirectory(track.id) },
                    )
                }
            }
        }
    }
}
