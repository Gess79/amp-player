package io.github.gess79.amp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import io.github.gess79.amp.R
import io.github.gess79.amp.data.db.TrackEntity
import io.github.gess79.amp.playback.PlaybackUiState
import io.github.gess79.amp.playback.SourceProgress

@Composable
fun TrackRow(
    track: TrackEntity,
    onClick: () -> Unit,
    isCurrent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(track.albumArtUri)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun TrackArtwork(uri: String?) {
    val slot = Modifier.size(48.dp)
    if (uri.isNullOrBlank()) {
        MissingArtwork(slot)
    } else {
        SubcomposeAsyncImage(
            model = uri,
            contentDescription = null,
            modifier = slot,
            contentScale = ContentScale.Crop,
            error = { MissingArtwork(Modifier.fillMaxSize()) },
        )
    }
}

/** Two beamed eighth notes. The right one sits a little higher. Color follows the theme. */
@Composable
private fun MissingArtwork(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val stem = size.width * 0.055f
        val headW = size.width * 0.28f
        val headH = size.height * 0.20f
        val left = Offset(size.width * 0.32f, size.height * 0.70f)
        val right = Offset(size.width * 0.68f, size.height * 0.56f)
        fun stemLeft(head: Offset) = head.x + headW * 0.28f
        val leftX = stemLeft(left)
        val rightX = stemLeft(right)
        val leftTop = size.height * 0.24f
        val rightTop = size.height * 0.12f
        val beam = size.height * 0.085f
        drawPath(
            Path().apply {
                moveTo(leftX, leftTop)
                lineTo(rightX + stem, rightTop)
                lineTo(rightX + stem, rightTop + beam)
                lineTo(leftX, leftTop + beam)
                close()
            },
            color,
        )
        drawRect(color, Offset(leftX, leftTop), Size(stem, left.y - leftTop))
        drawRect(color, Offset(rightX, rightTop), Size(stem, right.y - rightTop))
        drawNoteHead(color, left, headW, headH)
        drawNoteHead(color, right, headW, headH)
    }
}

private fun DrawScope.drawNoteHead(
    color: Color,
    center: Offset,
    headW: Float,
    headH: Float,
) {
    rotate(degrees = -22f, pivot = center) {
        drawOval(
            color = color,
            topLeft = Offset(center.x - headW / 2f, center.y - headH / 2f),
            size = Size(headW, headH),
        )
    }
}

/** Play/Pause and Shuffle for a source (playlist, folder) plus its position in the round. */
@Composable
fun SourcePlaybackControls(
    isPlaying: Boolean,
    shuffleOn: Boolean,
    progress: SourceProgress?,
    onPlayPause: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlayPause, modifier = Modifier.weight(1f)) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Text(
                "  " + stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
            )
        }
        if (shuffleOn) {
            Button(onClick = onToggleShuffle, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, contentDescription = null)
                Text("  ${stringResource(R.string.shuffle_on)}")
            }
        } else {
            OutlinedButton(onClick = onToggleShuffle, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text("  ${stringResource(R.string.shuffle_off)}")
            }
        }
    }
    progress?.let {
        Text(
            text = stringResource(
                if (it.shuffle) R.string.source_progress_shuffle else R.string.source_progress,
                it.position,
                it.total,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
fun MiniPlayerBar(
    state: PlaybackUiState,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    if (state.title.isBlank() && state.mediaId == null) return
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownPositionMs = dragFraction?.let { (it * state.durationMs).toLong() } ?: state.positionMs
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ScrubBar(
                progress = progress,
                durationMs = state.durationMs,
                dragFraction = dragFraction,
                onDrag = { dragFraction = it },
                onSeek = { fraction ->
                    dragFraction = null
                    onSeek((fraction * state.durationMs).toLong())
                },
                onCancel = { dragFraction = null },
            )
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.placeholder_em_dash) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${formatMs(shownPositionMs)} / ${formatMs(state.durationMs)}" +
                            if (state.artist.isNotBlank()) " · ${state.artist}" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.cd_previous),
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play_pause),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.cd_next),
                    )
                }
            }
        }
    }
}

/**
 * Thin progress line; long-press anywhere on it, then drag sideways to move the playhead.
 * Dragging is relative, so the playhead does not jump to the finger. Seeks on release.
 */
@Composable
private fun ScrubBar(
    progress: Float,
    durationMs: Long,
    dragFraction: Float?,
    onDrag: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentProgress by rememberUpdatedState(progress)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(durationMs > 0) {
                if (durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    longPress.consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var fraction = currentProgress
                    currentOnDrag(fraction)
                    val released = drag(longPress.id) { change ->
                        fraction = (fraction + change.positionChange().x / size.width).coerceIn(0f, 1f)
                        change.consume()
                        currentOnDrag(fraction)
                    }
                    if (released) currentOnSeek(fraction) else currentOnCancel()
                }
            },
    ) {
        val shown = dragFraction ?: progress
        val thickness = if (dragFraction != null) 4.dp.toPx() else 2.dp.toPx()
        val top = (size.height - thickness) / 2f
        drawRect(inactive, topLeft = Offset(0f, top), size = Size(size.width, thickness))
        drawRect(active, topLeft = Offset(0f, top), size = Size(size.width * shown, thickness))
        if (dragFraction != null) {
            drawCircle(active, radius = 7.dp.toPx(), center = Offset(size.width * shown, size.height / 2f))
        }
    }
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
