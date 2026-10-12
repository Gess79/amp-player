package io.github.gess79.amp

import android.app.Application
import io.github.gess79.amp.data.settings.AppLocale
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.playback.BluetoothWakeDiagnostics
import io.github.gess79.amp.util.AmpLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AmpApplication : Application() {

    @Inject lateinit var userSettings: UserSettings

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AmpLog.init(this)
        AmpLog.installCrashHandler()
        BluetoothWakeDiagnostics.logStatus(this, "Application.onCreate")
        appScope.launch {
            AppLocale.apply(userSettings.appLanguage.first())
        }
    }
}
