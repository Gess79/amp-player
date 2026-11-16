package io.github.gess79.amp.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.gess79.amp.data.settings.UserSettings
import io.github.gess79.amp.util.AmpLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes [PlaybackService] when Bluetooth connects while auto-resume is armed.
 * Without this, a killed process never sees A2DP "device added" callbacks.
 *
 * Listens for ACL connect and A2DP profile state. On Android 12+ both require
 * runtime BLUETOOTH_CONNECT or the system delivers nothing.
 */
@AndroidEntryPoint
class BluetoothResumeReceiver : BroadcastReceiver() {

    @Inject lateinit var userSettings: UserSettings

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        // Always log first — if this never appears, the system is not delivering BT broadcasts.
        AmpLog.i(
            "Playback",
            "BT receiver fired action=$action connectPerm=${BluetoothWakeDiagnostics.hasConnectPermission(context)}",
        )
        if (!isRelevantAction(action, intent)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothWakeDiagnostics.hasConnectPermission(context)
        ) {
            AmpLog.w(
                "Playback",
                "BT wake skipped: BLUETOOTH_CONNECT not granted — ACL/A2DP broadcasts need it",
            )
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED &&
            intent != null &&
            !BluetoothMediaRoute.isLikelyAudio(intent)
        ) {
            AmpLog.i("Playback", "BT wake skipped, device class is not audio")
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resume = userSettings.resumeOnBluetooth.first()
                AmpLog.i("Playback", "BT ACL/A2DP connected resumeFlag=$resume action=$action")
                if (!resume) return@launch
                val service = Intent(context, PlaybackService::class.java).apply {
                    this.action = PlaybackService.ACTION_BT_RESUME
                }
                try {
                    ContextCompat.startForegroundService(context, service)
                    AmpLog.i("Playback", "BT wake → startForegroundService")
                } catch (t: Throwable) {
                    AmpLog.e("Playback", "BT wake startForegroundService failed, try startService", t)
                    runCatching {
                        context.startService(service)
                        AmpLog.i("Playback", "BT wake → startService fallback")
                    }.onFailure { AmpLog.e("Playback", "BT wake startService failed", it) }
                }
            } catch (t: Throwable) {
                AmpLog.e("Playback", "BT wake failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun isRelevantAction(action: String, intent: Intent?): Boolean =
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent?.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) ?: -1
                AmpLog.i(
                    "Playback",
                    "BT A2DP profile state=$state (${a2dpStateName(state)})",
                )
                state == BluetoothProfile.STATE_CONNECTED
            }
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1) ?: -1
                AmpLog.i("Playback", "BT adapter connection state=$state")
                state == BluetoothAdapter.STATE_CONNECTED
            }
            else -> false
        }

    private fun a2dpStateName(state: Int): String =
        when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "unknown"
        }
}
