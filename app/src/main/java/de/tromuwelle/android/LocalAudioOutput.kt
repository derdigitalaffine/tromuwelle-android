package de.tromuwelle.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The actual Media3 player lives in our MediaLibraryService, not in MainActivity. */
@OptIn(UnstableApi::class)
object LocalAudioOutput {
    @Volatile private var servicePlayer: ExoPlayer? = null
    private var selectedDeviceId: Int? = null

    fun bind(player: ExoPlayer, context: Context) {
        servicePlayer = player
        applyPreference(context)
    }

    fun unbind(player: ExoPlayer) {
        if (servicePlayer === player) servicePlayer = null
    }

    fun devices(context: Context): List<AudioDeviceInfo> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            catch (_: SecurityException) { emptyList() }
            .sortedWith(compareBy({ it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
                { it.productName.toString() }))
    }

    fun selected(): Int? = selectedDeviceId

    fun choose(context: Context, deviceId: Int?) {
        selectedDeviceId = deviceId
        applyPreference(context)
    }

    private fun applyPreference(context: Context) {
        val device = devices(context).firstOrNull { it.id == selectedDeviceId }
        // This is a preferred playback route, not a SCO/telephone routing override.
        servicePlayer?.setPreferredAudioDevice(device)
    }
}

fun localOutputLabel(device: AudioDeviceInfo): String {
    val type = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Gerätelautsprecher"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Hörer"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (Musik)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (Freisprechen)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Lautsprecher"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Kopfhörer"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-Audio"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI-Audio"
        else -> "Audioausgang"
    }
    val name = device.productName?.toString().orEmpty().trim()
    return if (name.isBlank() || name.equals("default", true)) type else "$type · $name"
}

@Composable
fun LocalAudioOutputSheet(onClose: () -> Unit, onCast: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var routes by remember { mutableStateOf(LocalAudioOutput.devices(context)) }
    var selected by remember { mutableStateOf(LocalAudioOutput.selected()) }
    var notice by remember { mutableStateOf("") }
    var permissionGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        routes = LocalAudioOutput.devices(context)
        if (!granted) notice = "Bluetooth-Geräte können ohne Freigabe eingeschränkt angezeigt werden."
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Audioausgabe", style = MaterialTheme.typography.headlineSmall)
        Text("Wähle den Ausgang für die Wiedergabe auf diesem Gerät. " +
            "Android kann bei Anrufen oder einer neuen Bluetooth-Verbindung die Route ändern.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!permissionGranted && Build.VERSION.SDK_INT >= 31) {
            Button(onClick = {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Bluetooth-Audiogeräte freigeben")
            }
        }
        OutlinedButton(onClick = { routes = LocalAudioOutput.devices(context) },
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Verbundene Audiogeräte aktualisieren")
        }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
            item {
                ListItem(headlineContent = { Text("Android automatisch wählen") },
                    supportingContent = { Text("Systemausgabe inklusive Bluetooth") },
                    leadingContent = { Icon(Icons.Rounded.Speaker, null) },
                    trailingContent = {
                        RadioButton(selected = selected == null, onClick = {
                            LocalAudioOutput.choose(context, null)
                            selected = null; notice = "Automatische Geräteausgabe aktiviert."
                        })
                    }, modifier = Modifier.fillMaxWidth())
            }
            items(routes, key = { it.id }) { route ->
                val selectedRoute = route.id == selected
                ListItem(headlineContent = { Text(localOutputLabel(route)) },
                    supportingContent = { Text("Verbunden · Ausgang ${route.id}") },
                    leadingContent = { Icon(
                        if (route.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
                            Icons.Rounded.BluetoothAudio else Icons.Rounded.Speaker, null) },
                    trailingContent = {
                        RadioButton(selected = selectedRoute, onClick = {
                            LocalAudioOutput.choose(context, route.id)
                            selected = route.id
                            notice = "Bevorzugter Audioausgang aktualisiert."
                        })
                    })
            }
        }
        HorizontalDivider()
        Button(onClick = { onClose(); onCast() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Cast, null)
            Spacer(Modifier.width(8.dp))
            Text("Auf Fernseher / Cast-Gerät streamen")
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Schließen") }
    }
}
