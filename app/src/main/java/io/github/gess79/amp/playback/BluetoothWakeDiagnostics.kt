package io.github.gess79.amp.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.gess79.amp.R
import io.github.gess79.amp.util.AmpLog

object BluetoothWakeDiagnostics {
    fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun statusText(context: Context): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            context.getString(R.string.bt_wake_no_runtime_permission, Build.VERSION.SDK_INT)
        } else if (hasConnectPermission(context)) {
            context.getString(R.string.bt_wake_permission_granted)
        } else {
            context.getString(R.string.bt_wake_permission_missing)
        }

    fun logStatus(context: Context, where: String) {
        val granted = hasConnectPermission(context)
        AmpLog.i(
            "Playback",
            "BT wake diag ($where): sdk=${Build.VERSION.SDK_INT} " +
                "BLUETOOTH_CONNECT=$granted",
        )
    }
}
