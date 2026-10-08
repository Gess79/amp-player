package com.amp.player.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.amp.player.MainActivity
import com.amp.player.R
import com.amp.player.data.settings.AppLocale
import com.amp.player.playback.PlaybackService

/** Compact one-row player: cover · title/artist · time · prev · play/pause · next */
class NowPlayingWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val str = AppLocale.wrap(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val title = prefs[WidgetPrefs.title] ?: str.getString(R.string.app_name)
            val artist = prefs[WidgetPrefs.artist]
                ?: str.getString(R.string.widget_tap_to_open)
            val isPlaying = prefs[WidgetPrefs.isPlaying] ?: false
            val positionLabel = prefs[WidgetPrefs.positionLabel].orEmpty()
            CompactPlayerRow(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                artwork = null,
                positionLabel = positionLabel,
                strings = str,
            )
        }
    }
}

@Composable
internal fun CompactPlayerRow(
    title: String,
    artist: String,
    isPlaying: Boolean,
    artwork: Bitmap?,
    positionLabel: String = "",
    strings: Context = LocalContext.current,
) {
    val context = LocalContext.current
    val bg = ColorProvider(Color(0xFF1B2A2F))
    val fg = ColorProvider(Color(0xFFF2EDE6))
    val muted = ColorProvider(Color(0xFFB8B0A4))

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = when {
                artwork != null -> ImageProvider(artwork)
                else -> ImageProvider(R.drawable.ic_widget_album)
            },
            contentDescription = strings.getString(R.string.cd_cover),
            modifier = GlanceModifier
                .size(40.dp)
                .cornerRadius(6.dp)
                .clickable(actionStartActivity<MainActivity>()),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                maxLines = 1,
            )
            Text(
                text = artist,
                style = TextStyle(color = muted, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        if (positionLabel.isNotBlank()) {
            Text(
                text = positionLabel,
                style = TextStyle(color = muted, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.padding(horizontal = 6.dp),
            )
        }
        ServiceControlButton(
            drawableRes = R.drawable.ic_widget_prev,
            action = PlaybackService.ACTION_PREVIOUS,
            context = context,
        )
        ServiceControlButton(
            drawableRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            action = PlaybackService.ACTION_PLAY_PAUSE,
            context = context,
        )
        ServiceControlButton(
            drawableRes = R.drawable.ic_widget_next,
            action = PlaybackService.ACTION_NEXT,
            context = context,
        )
    }
}

@Composable
internal fun ServiceControlButton(drawableRes: Int, action: String, context: Context) {
    val intent = Intent(context, PlaybackService::class.java).setAction(action)
    Box(
        modifier = GlanceModifier
            .size(40.dp)
            .clickable(actionStartService(intent, isForegroundService = true)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(drawableRes),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(ColorProvider(Color(0xFFF2EDE6))),
        )
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
