package com.amp.player.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.amp.player.R
import com.amp.player.ui.components.formatMs

@Composable
fun NowPlayingScreen(vm: NowPlayingViewModel = hiltViewModel()) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val queueInfo by vm.queueInfo.collectAsStateWithLifecycle()
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val queueTracks by vm.queueTracks.collectAsStateWithLifecycle()
    val shuffleOn by vm.shuffleEnabled.collectAsStateWithLifecycle()

    var scrub by remember(playback.mediaId) { mutableFloatStateOf(-1f) }
    val progress = if (scrub >= 0f) scrub else {
        if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        AsyncImage(
            model = playback.artworkUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = playback.title.ifBlank { stringResource(R.string.now_playing_idle) },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = playback.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        queueInfo?.let {
            val shuffleLabel = if (shuffleOn) {
                stringResource(R.string.shuffle_on)
            } else {
                stringResource(R.string.shuffle_off)
            }
            Text(
                text = stringResource(
                    R.string.now_playing_round_info,
                    shuffleLabel,
                    it.generation,
                    it.progressLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (shuffleOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                if (scrub >= 0f && playback.durationMs > 0) {
                    vm.seekTo((scrub * playback.durationMs).toLong())
                }
                scrub = -1f
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(playback.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(playback.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.skipToPrevious() }) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_previous),
                )
            }
            IconButton(onClick = { vm.playPause() }) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                )
            }
            IconButton(onClick = { vm.skipToNext() }) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                )
            }
            IconButton(onClick = vm::toggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (shuffleOn) {
                        stringResource(R.string.shuffle_on)
                    } else {
                        stringResource(R.string.shuffle_off)
                    },
                    tint = if (shuffleOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
            IconButton(onClick = vm::toggleFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(R.string.cd_favorite),
                )
            }
            IconButton(onClick = { vm.setSleepTimer(30) }) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = stringResource(R.string.cd_sleep_30_min),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::resumeShuffle) {
                Text(stringResource(R.string.resume_round))
            }
            TextButton(onClick = { vm.setSleepTimer(0) }) {
                Text(stringResource(R.string.sleep_timer_off))
            }
        }
        Text(
            text = stringResource(R.string.queue_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(queueTracks, key = { _, t -> t.id }) { index, track ->
                val current = queueInfo?.currentIndex == index
                Text(
                    text = "${index + 1}. ${track.title} — ${track.artist}",
                    style = if (current) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
