package io.github.gess79.amp.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

private val Amber = Color(0xFFD97B4A)
private val Ink = Color(0xFF1B2A2F)
private val Cream = Color(0xFFF2EDE6)
private val Slate = Color(0xFF2F3E44)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Cream,
    background = Ink,
    surface = Slate,
    onBackground = Cream,
    onSurface = Cream,
)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Cream,
    secondary = Ink,
    background = Cream,
    surface = Color(0xFFE7E1D8),
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun AmpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme) {
            // DayNight follows the system theme. When that disagrees with the app theme,
            // Android 16 keeps flipping the status-bar icons. Align the activity theme once.
            val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night != darkTheme) {
                AppCompatDelegate.setDefaultNightMode(
                    if (darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
                )
            }
            (view.context.findActivity() as? ComponentActivity)?.applyAmpSystemBars(darkTheme)
            onDispose { }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Same scrims enableEdgeToEdge uses by default. The choice is fixed, not SystemBarStyle.auto. */
private val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)

fun ComponentActivity.applyAmpSystemBars(darkTheme: Boolean) {
    val statusBar = if (darkTheme) {
        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
    } else {
        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
    }
    val navigationBar = if (darkTheme) {
        SystemBarStyle.dark(DARK_NAV_SCRIM)
    } else {
        SystemBarStyle.light(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM)
    }
    enableEdgeToEdge(statusBarStyle = statusBar, navigationBarStyle = navigationBar)
}
