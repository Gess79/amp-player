package io.github.gess79.amp.playback

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.github.gess79.amp.data.settings.AutoplayDevice

/**
 * What is connected as a music output, and where media would actually play.
 * A bare radio link (a lamp) is not a music output.
 */
class BluetoothMediaRoute(private val context: Context) {

    fun connectedBluetoothMedia(): List<AutoplayDevice> {
        val manager = audioManager() ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isBluetoothMedia() && !it.isObviouslyNotAudio() }
            .map { it.toAutoplayDevice() }
            .distinctBy { it.address }
    }

    /**
     * True when music would go to the built-in speaker and not to Bluetooth.
     * Older than Android 12 cannot tell the active route, so this stays false there.
     */
    fun phoneSpeakerIsMediaRoute(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val manager = audioManager() ?: return false
        val routed = manager.getAudioDevicesForAttributes(mediaAttributes())
        if (routed.isEmpty()) return false
        val bluetooth = routed.any { it.isBluetoothMedia() }
        val speaker = routed.any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        return speaker && !bluetooth
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private fun mediaAttributes(): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun AudioDeviceInfo.isBluetoothMedia(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

    private fun AudioDeviceInfo.toAutoplayDevice(): AutoplayDevice {
        val rawAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) address else ""
        val mac = rawAddress.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }
        val product = productName?.toString()?.trim().orEmpty()
        val name = mac?.let { bluetoothName(it) }?.takeIf { it.isNotBlank() } ?: product
        val stable = mac ?: "audio:${type}:${name.ifBlank { id.toString() }}"
        return AutoplayDevice(address = stable, name = name, allowed = true)
    }

    private fun bluetoothName(mac: String): String {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return ""
        val device = runCatching { manager.adapter?.getRemoteDevice(mac) }.getOrNull() ?: return ""
        return runCatching { device.name }.getOrNull()?.trim().orEmpty()
    }

    private fun AudioDeviceInfo.isObviouslyNotAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val mac = address.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" } ?: return false
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val device = runCatching { manager.adapter?.getRemoteDevice(mac) }.getOrNull() ?: return false
        return isObviouslyNotAudio(device)
    }

    companion object {
        /** ACL from a keyboard, lamp, or similar. Unknown class still counts as possible audio. */
        fun isLikelyAudio(intent: Intent): Boolean {
            val device = deviceExtra(intent) ?: return true
            return !isObviouslyNotAudio(device)
        }

        private fun deviceExtra(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        private fun isObviouslyNotAudio(device: BluetoothDevice): Boolean {
            val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
                ?: return false
            return major != BluetoothClass.Device.Major.AUDIO_VIDEO &&
                major != BluetoothClass.Device.Major.WEARABLE &&
                major != BluetoothClass.Device.Major.UNCATEGORIZED
        }
    }
}
