package io.github.gess79.amp.data.settings

/** A Bluetooth music output the app has seen, and whether it may autoplay. */
data class AutoplayDevice(
    val address: String,
    val name: String,
    val allowed: Boolean,
)

internal fun encodeAutoplayDevices(devices: List<AutoplayDevice>): String =
    devices.joinToString("\n") { device ->
        val name = device.name.replace('\t', ' ').replace('\n', ' ')
        "${device.address}\t$name\t${if (device.allowed) 1 else 0}"
    }

internal fun decodeAutoplayDevices(stored: String?): List<AutoplayDevice> {
    if (stored.isNullOrBlank()) return emptyList()
    return stored.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
        AutoplayDevice(
            address = parts[0],
            name = parts[1],
            allowed = parts[2] != "0",
        )
    }.distinctBy { it.address }.sortedBy { it.name.lowercase() }.toList()
}
