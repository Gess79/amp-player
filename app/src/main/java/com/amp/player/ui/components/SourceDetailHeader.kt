package com.amp.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amp.player.R
import com.amp.player.data.db.TrackEntity
import com.amp.player.loudness.LoudnessPlaylistStatus
import com.amp.player.loudness.LoudnessScanState

data class SourceStats(
    val trackCount: Int,
    val totalDurationLabel: String,
)

fun sourceStatsOf(tracks: List<TrackEntity>): SourceStats? =
    if (tracks.isEmpty()) null
    else SourceStats(tracks.size, formatMs(tracks.sumOf { it.durationMs }))

/** Track count, total time, ReplayGain status and scan/reset buttons of a playlist or folder. */
@Composable
fun SourceDetailHeader(
    sourceId: String,
    stats: SourceStats?,
    loudnessStatus: LoudnessPlaylistStatus?,
    loudnessScan: LoudnessScanState,
    replayGainEnabled: Boolean,
    onAnalyze: () -> Unit,
    onResetLoudness: () -> Unit,
) {
    var showReset by remember { mutableStateOf(false) }
    Text(
        text = stats?.let {
            stringResource(R.string.playlist_stats, it.trackCount, it.totalDurationLabel)
        } ?: stringResource(R.string.playlist_stats_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val status = loudnessStatus ?: return
    val scanForThis = loudnessScan.sourceId == sourceId
    Text(
        text = if (scanForThis && (loudnessScan.running || loudnessScan.message != null)) {
            loudnessScan.message ?: status.label
        } else {
            status.label
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (status.needsAnalysis) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    if (!replayGainEnabled) {
        Text(
            text = stringResource(R.string.playlist_replay_gain_disabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onAnalyze,
            enabled = !loudnessScan.running,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (loudnessScan.running && scanForThis) R.string.playlist_analyzing
                    else R.string.playlist_analyze_loudness,
                ),
            )
        }
        OutlinedButton(
            onClick = { showReset = true },
            enabled = !loudnessScan.running && status.total > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.playlist_reset_replay_gain))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.dialog_reset_replay_gain_title)) },
            text = { Text(stringResource(R.string.dialog_reset_replay_gain_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetLoudness()
                        showReset = false
                    },
                ) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
