package io.github.gess79.amp.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.gess79.amp.data.settings.AppLocale
import io.github.gess79.amp.playback.PlaybackService
import io.github.gess79.amp.R

/** Expanded player: compact controls + progress + playlist/next + shuffle */
class ShufflePlayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val str = AppLocale.wrap(context)
        provideContent {
            val prefs = currentState<Preferences>()
            ExpandedPlayer(
                title = prefs[WidgetPrefs.title] ?: str.getString(R.string.app_name),
                artist = prefs[WidgetPrefs.artist]
                    ?: str.getString(R.string.widget_tap_to_open),
                isPlaying = prefs[WidgetPrefs.isPlaying] ?: false,
                positionLabel = prefs[WidgetPrefs.positionLabel] ?: "0:00",
                durationLabel = prefs[WidgetPrefs.durationLabel] ?: "0:00",
                progress = prefs[WidgetPrefs.progress] ?: 0f,
                playlistName = prefs[WidgetPrefs.playlistName],
                nextTitle = prefs[WidgetPrefs.nextTitle],
                shuffleEnabled = prefs[WidgetPrefs.shuffleEnabled] ?: true,
                hasQueue = prefs[WidgetPrefs.hasQueue] ?: false,
                queueProgress = prefs[WidgetPrefs.queueProgress],
                strings = str,
            )
        }
    }
}

@Composable
private fun ExpandedPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    positionLabel: String,
    durationLabel: String,
    progress: Float,
    playlistName: String?,
    nextTitle: String?,
    shuffleEnabled: Boolean,
    hasQueue: Boolean,
    queueProgress: String?,
    strings: Context,
) {
    val context = LocalContext.current
    val bg = ColorProvider(Color(0xFF1B2A2F))
    val accent = ColorProvider(Color(0xFF2F3E44))
    val fg = ColorProvider(Color(0xFFF2EDE6))
    val muted = ColorProvider(Color(0xFFB8B0A4))
    val bar = ColorProvider(Color(0xFFD97B4A))

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        CompactPlayerRow(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            artwork = null,
            positionLabel = "",
            strings = strings,
        )

        Spacer(GlanceModifier.height(4.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp),
            color = bar,
            backgroundColor = accent,
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = positionLabel,
                style = TextStyle(color = muted, fontSize = 10.sp),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = durationLabel,
                style = TextStyle(color = muted, fontSize = 10.sp),
            )
        }

        if (playlistName != null || queueProgress != null) {
            Text(
                text = listOfNotNull(
                    playlistName?.let {
                        strings.getString(R.string.widget_playlist_label, it)
                    },
                    queueProgress,
                ).joinToString(" · "),
                style = TextStyle(color = fg, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        if (nextTitle != null) {
            Text(
                text = strings.getString(R.string.widget_up_next, nextTitle),
                style = TextStyle(color = muted, fontSize = 11.sp),
                maxLines = 1,
            )
        }

        Spacer(GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceChip(
                label = if (shuffleEnabled) {
                    strings.getString(R.string.shuffle_on)
                } else {
                    strings.getString(R.string.shuffle_off)
                },
                modifier = GlanceModifier.defaultWeight().padding(end = 4.dp),
                background = if (shuffleEnabled) ColorProvider(Color(0xFF3D4F56)) else accent,
                color = fg,
                action = PlaybackService.ACTION_TOGGLE_SHUFFLE,
                context = context,
            )
            ServiceChip(
                label = if (hasQueue) {
                    strings.getString(R.string.widget_resume)
                } else {
                    strings.getString(R.string.widget_start)
                },
                modifier = GlanceModifier.defaultWeight().padding(start = 4.dp),
                background = accent,
                color = fg,
                action = PlaybackService.ACTION_RESUME_OR_SHUFFLE,
                context = context,
            )
        }
    }
}

@Composable
private fun ServiceChip(
    label: String,
    modifier: GlanceModifier,
    background: ColorProvider,
    color: ColorProvider,
    action: String,
    context: Context,
) {
    val intent = Intent(context, PlaybackService::class.java).setAction(action)
    Text(
        text = label,
        style = TextStyle(color = color, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        maxLines = 1,
        modifier = modifier
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(actionStartService(intent, isForegroundService = true)),
    )
}

class ShufflePlayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShufflePlayWidget()
}
