package io.github.gess79.amp.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.github.gess79.amp.R
import io.github.gess79.amp.ui.components.formatMs
import kotlin.math.max

private val OnCover = Color.White
private val OnCoverMuted = Color.White.copy(alpha = 0.86f)
private val Halo = Color.Black.copy(alpha = 0.75f)
private val TextHalo = Color.Black.copy(alpha = 0.55f)
private val LightTextHalo = Color.White.copy(alpha = 0.72f)
/** Uniform zoom so a thin black frame leaves the sides. The cover box clips the bottom. */
private const val COVER_BLEED = 1.12f
/** About five rows. The mini player is hidden on this tab, and the queue uses that space. */
private val QueueHeight = 130.dp
private val HaloShift = 1.25.dp
private val HaloOffsets = listOf(
    -HaloShift to 0.dp,
    HaloShift to 0.dp,
    0.dp to -HaloShift,
    0.dp to HaloShift,
    -HaloShift to -HaloShift,
    HaloShift to -HaloShift,
    -HaloShift to HaloShift,
    HaloShift to HaloShift,
)

@Composable
fun NowPlayingScreen(
    onOpenSettings: () -> Unit = {},
    vm: NowPlayingViewModel = hiltViewModel(),
) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val queueInfo by vm.queueInfo.collectAsStateWithLifecycle()
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val queueTracks by vm.queueTracks.collectAsStateWithLifecycle()
    val shuffleOn by vm.shuffleEnabled.collectAsStateWithLifecycle()
    val waveform by vm.waveform.collectAsStateWithLifecycle()

    var scrub by remember(playback.mediaId) { mutableFloatStateOf(-1f) }
    val progress = if (scrub >= 0f) scrub else {
        if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
    }
    val seekFinished = {
        if (scrub >= 0f && playback.durationMs > 0) {
            vm.seekTo((scrub * playback.durationMs).toLong())
        }
        scrub = -1f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val landscape = maxWidth > viewportHeight
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (landscape) Modifier.verticalScroll(scroll) else Modifier),
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (landscape) Modifier.height(viewportHeight) else Modifier.weight(1f))
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )
            val coverMod = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = COVER_BLEED
                    scaleY = COVER_BLEED
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    clip = false
                }
            if (playback.artworkUri.isNullOrBlank()) {
                VinylPlaceholder(coverMod)
            } else {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(playback.artworkUri)
                        .transformations(TrimNearBlackEdges)
                        .build(),
                    contentDescription = null,
                    modifier = coverMod,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    error = { VinylPlaceholder(Modifier.fillMaxSize()) },
                )
            }
            val ground = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.34f to Color.Black.copy(alpha = 0.18f),
                            0.58f to ground.copy(alpha = 0.35f),
                            0.8f to ground.copy(alpha = 0.82f),
                            1f to ground,
                        ),
                    ),
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                HaloIcon(
                    icon = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    modifier = Modifier.requiredSize(24.dp),
                    tint = OnCover,
                )
            }
            val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
            val titleColor = if (lightTheme) MaterialTheme.colorScheme.onBackground else OnCover
            val metaColor = if (lightTheme) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            } else {
                OnCoverMuted
            }
            val textHalo = if (lightTheme) LightTextHalo else TextHalo
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp),
            ) {
                OutlinedText(
                    text = playback.title.ifBlank { stringResource(R.string.now_playing_idle) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = titleColor,
                    halo = textHalo,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedText(
                    text = playback.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = metaColor,
                    halo = textHalo,
                )
                queueInfo?.let {
                    val shuffleLabel = if (shuffleOn) {
                        stringResource(R.string.shuffle_on)
                    } else {
                        stringResource(R.string.shuffle_off)
                    }
                    OutlinedText(
                        text = stringResource(
                            R.string.now_playing_round_info,
                            shuffleLabel,
                            it.generation,
                            it.progressLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (shuffleOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            metaColor
                        },
                        halo = textHalo,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val accent = MaterialTheme.colorScheme.primary
                val peaks = waveform
                if (peaks != null) {
                    WaveformSeekBar(
                        peaks = peaks,
                        progress = progress.coerceIn(0f, 1f),
                        onScrub = { scrub = it },
                        onScrubFinished = seekFinished,
                        onSeek = { fraction ->
                            if (playback.durationMs > 0) {
                                vm.seekTo((fraction * playback.durationMs).toLong())
                            }
                        },
                        color = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(56.dp),
                    )
                } else {
                    Slider(
                        value = progress.coerceIn(0f, 1f),
                        onValueChange = { scrub = it },
                        onValueChangeFinished = seekFinished,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = OnCover.copy(alpha = 0.45f),
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedText(
                        formatMs(if (scrub >= 0f) (scrub * playback.durationMs).toLong() else playback.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        halo = textHalo,
                    )
                    OutlinedText(
                        formatMs(playback.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        halo = textHalo,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(
                        onClick = { vm.skipToPrevious() },
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.cd_previous),
                    )
                    TransportButton(
                        onClick = { vm.playPause() },
                        icon = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play_pause),
                        containerSize = 76.dp,
                        iconSize = 44.dp,
                    )
                    TransportButton(
                        onClick = { vm.skipToNext() },
                        icon = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.cd_next),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(
                        onClick = vm::toggleShuffle,
                        icon = Icons.Default.Shuffle,
                        contentDescription = if (shuffleOn) {
                            stringResource(R.string.shuffle_on)
                        } else {
                            stringResource(R.string.shuffle_off)
                        },
                        tint = if (shuffleOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                    )
                    TransportButton(
                        onClick = vm::toggleFavorite,
                        icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.cd_favorite),
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    TransportButton(
                        onClick = { vm.setSleepTimer(30) },
                        icon = Icons.Default.Timer,
                        contentDescription = stringResource(R.string.cd_sleep_30_min),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::resumeShuffle) {
                        Text(stringResource(R.string.resume_round))
                    }
                    TextButton(onClick = { vm.setSleepTimer(0) }) {
                        Text(stringResource(R.string.sleep_timer_off))
                    }
                }
            }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.queue_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            LazyColumn(modifier = Modifier.height(QueueHeight)) {
                itemsIndexed(queueTracks, key = { _, t -> t.id }) { index, track ->
                    val current = queueInfo?.currentIndex == index
                    Text(
                        text = "${index + 1}. ${track.title} — ${track.artist}",
                        style = if (current) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun VinylPlaceholder(modifier: Modifier = Modifier) {
    val label = MaterialTheme.colorScheme.primary
    val hole = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val radius = max(size.width, size.height) * 0.62f
        val center = Offset(size.width / 2f, size.height * 0.38f)
        drawCircle(Color(0xFF16181A), radius = radius, center = center)
        val grooves = 32
        for (i in 1 until grooves) {
            val grooveRadius = radius * (0.30f + 0.66f * i / grooves)
            drawCircle(
                color = if (i % 2 == 0) {
                    Color.White.copy(alpha = 0.05f)
                } else {
                    Color.Black.copy(alpha = 0.45f)
                },
                radius = grooveRadius,
                center = center,
                style = Stroke(width = radius * 0.010f),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.14f),
                0.45f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.28f),
                center = center + Offset(-radius * 0.18f, -radius * 0.22f),
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        val labelRadius = radius * 0.17f
        drawCircle(label, radius = labelRadius, center = center)
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = labelRadius * 0.78f,
            center = center,
            style = Stroke(width = labelRadius * 0.05f),
        )
        drawCircle(hole, radius = labelRadius * 0.12f, center = center)
    }
}

@Composable
private fun OutlinedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = OnCover,
    halo: Color = TextHalo,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val shift = if (style.fontSize.type == TextUnitType.Sp) {
        (style.fontSize.value * 0.042f).dp.coerceIn(0.4.dp, 1.dp)
    } else {
        0.7.dp
    }
    val offsets = listOf(
        -shift to 0.dp,
        shift to 0.dp,
        0.dp to -shift,
        0.dp to shift,
        -shift to -shift,
        shift to -shift,
        -shift to shift,
        shift to shift,
    )
    Box(modifier) {
        offsets.forEach { (x, y) ->
            Text(
                text = text,
                style = style,
                color = halo,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier.offset(x, y),
            )
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Composable
private fun WaveformSeekBar(
    peaks: ByteArray,
    progress: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onSeek: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_seek)
    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(peaks) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(peaks) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrub((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() },
                ) { change, _ ->
                    change.consume()
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val count = peaks.size
        if (count == 0) return@Canvas
        val stroke = 1.5.dp.toPx()
        val gap = 1.5.dp.toPx()
        val innerWidth = (size.width - stroke * 2).coerceAtLeast(1f)
        val barW = ((innerWidth - gap * (count - 1)) / count).coerceAtLeast(1f)
        val minH = 3.dp.toPx()
        for (i in 0 until count) {
            val amp = (peaks[i].toInt() and 0xFF) / 255f
            val h = (amp * (size.height - stroke * 2)).coerceAtLeast(minH)
            val x = stroke + i * (barW + gap)
            val top = (size.height - h) / 2f
            val played = (i + 0.5f) / count <= progress
            drawRoundRect(
                color = Halo,
                topLeft = Offset(x - stroke, top - stroke),
                size = Size(barW + stroke * 2, h + stroke * 2),
                cornerRadius = CornerRadius((barW + stroke * 2) / 2f, (barW + stroke * 2) / 2f),
            )
            drawRoundRect(
                color = if (played) color else OnCover.copy(alpha = 0.45f),
                topLeft = Offset(x, top),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
private fun TransportButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    containerSize: Dp = 64.dp,
    iconSize: Dp = 36.dp,
    tint: Color? = null,
) {
    Box(
        modifier = Modifier
            .requiredSize(containerSize)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.requiredSize(iconSize),
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HaloIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier,
    tint: Color,
) {
    Box(contentAlignment = Alignment.Center) {
        HaloOffsets.forEach { (x, y) ->
            Icon(
                icon,
                contentDescription = null,
                modifier = modifier.offset(x, y),
                tint = Halo,
            )
        }
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}
