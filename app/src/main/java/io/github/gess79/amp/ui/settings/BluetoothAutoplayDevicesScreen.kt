package io.github.gess79.amp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gess79.amp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothAutoplayDevicesScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel,
) {
    val devices by vm.autoplayDevices.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.settings_bluetooth_autoplay_devices)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.settings_bluetooth_autoplay_devices_empty))
            }
            devices.forEach { device ->
                val title = device.name.ifBlank {
                    stringResource(R.string.settings_bluetooth_autoplay_device_unknown)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, modifier = Modifier.weight(1f))
                    Switch(
                        checked = device.allowed,
                        onCheckedChange = { allowed ->
                            vm.setAutoplayDeviceAllowed(device.address, allowed)
                        },
                    )
                }
            }
        }
    }
}
