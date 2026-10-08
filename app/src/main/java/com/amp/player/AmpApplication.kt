package com.amp.player

import android.app.Application
import com.amp.player.data.settings.AppLocale
import com.amp.player.data.settings.UserSettings
import com.amp.player.playback.BluetoothWakeDiagnostics
import com.amp.player.util.AmpLog
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
