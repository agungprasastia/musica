package com.music.musica.playback

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

enum class AudioOutputKind {
    PHONE,
    BUDS,
    HEADPHONES,
    SPEAKER,
    CAR,
    WIRED,
    USB,
    HDMI,
}

@Immutable
data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val kind: AudioOutputKind,
    val deviceType: Int,
    val isBuiltIn: Boolean,
)

@Immutable
data class AudioOutputSnapshot(
    val devices: List<AudioOutputDevice>,
    val activeDevice: AudioOutputDevice?,
)

internal data class RawOutputDescriptor(
    val id: Int,
    val productName: String,
    val deviceType: Int,
)

object AudioOutputRouter {
    // Explicit integer mirrors of Android AudioDeviceInfo.TYPE_* constants for minSdk 26 compatibility
    // without triggering NewApi lint warnings when checking newer BLE/HDMI types.
    internal const val TYPE_BUILTIN_EARPIECE = 1
    internal const val TYPE_BUILTIN_SPEAKER = 2
    internal const val TYPE_WIRED_HEADSET = 3
    internal const val TYPE_WIRED_HEADPHONES = 4
    internal const val TYPE_BLUETOOTH_SCO = 7
    internal const val TYPE_BLUETOOTH_A2DP = 8
    internal const val TYPE_HDMI = 9
    internal const val TYPE_HDMI_ARC = 10
    internal const val TYPE_USB_DEVICE = 11
    internal const val TYPE_USB_ACCESSORY = 12
    internal const val TYPE_DOCK = 13
    internal const val TYPE_USB_HEADSET = 22
    internal const val TYPE_HEARING_AID = 23
    internal const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    internal const val TYPE_BLE_HEADSET = 26
    internal const val TYPE_BLE_SPEAKER = 27
    internal const val TYPE_HDMI_EARC = 29
    internal const val TYPE_BLE_BROADCAST = 30

    private val BUDS_TOKENS = listOf(
        "buds", "airpods", "pods", "freebuds", "earbuds", "wf-", "tws", "ear (", "ear ",
        "pixel buds", "galaxy buds", "linkbuds", "soundcore liberty", "nothing ear",
        "jabra elite", "beats fit", "beats studio buds", "powerbeats", "tozo", "encobuds", "enco ",
    )
    private val CAR_TOKENS = listOf(
        "car", "auto", "sync", "uconnect", "carplay", "mbux", "idrive", "mychevy",
        "toyota", "honda", "mazda", "hyundai", "kia", "ford", "subaru", "bmw", "audi", "tesla", "nissan",
    )
    private val SPEAKER_TOKENS = listOf(
        "speaker", "jbl", "flip", "charge", "xtreme", "boombox", "clip", "go ",
        "sonos", "soundbar", "marshall", "bose soundlink", "homepod", "nest", "echo",
        "megaboom", "wonderboom", "srs-", "pill", "harman", "soundcore motion",
    )

    fun needsBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun queryOutputs(context: Context, phoneLabel: String): List<AudioOutputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val raw = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { info ->
                RawOutputDescriptor(
                    id = info.id,
                    productName = info.productName?.toString().orEmpty(),
                    deviceType = info.type,
                )
            }
        }.getOrDefault(emptyList())
        return classifyAndDeduplicate(raw, phoneLabel)
    }

    /**
     * Pure, deterministic device classifier & deduplicator:
     * - Filters out non-media sinks (SCO call audio, earpiece, telephony, submix, unknown)
     * - Infers earbuds / headphones / speaker / car / wired / USB / HDMI from type + product name
     * - Deduplicates Bluetooth endpoints by `(normalizedName, kind)` preferring `A2DP` over BLE
     * - Orders connected external devices first, followed by the built-in phone speaker
     */
    internal fun classifyAndDeduplicate(
        raw: List<RawOutputDescriptor>,
        phoneLabel: String,
    ): List<AudioOutputDevice> {
        val candidates = ArrayList<AudioOutputDevice>(raw.size)
        for (entry in raw) {
            val mapped = mapDescriptor(entry, phoneLabel) ?: continue
            candidates += mapped
        }

        val byKey = LinkedHashMap<String, AudioOutputDevice>()
        for (device in candidates) {
            val key = if (device.isBuiltIn) {
                "builtin_phone"
            } else {
                "${device.name.trim().lowercase()}|${device.kind.name}"
            }
            val existing = byKey[key]
            if (existing == null || transportPriority(device.deviceType) > transportPriority(existing.deviceType)) {
                byKey[key] = device
            }
        }

        val sorted = byKey.values.sortedWith(
            compareBy<AudioOutputDevice> { it.isBuiltIn }
                .thenByDescending { transportPriority(it.deviceType) }
                .thenBy { it.name.lowercase() },
        )
        return sorted
    }

    internal fun resolveActiveDevice(
        devices: List<AudioOutputDevice>,
        preferredDeviceId: Int?,
    ): AudioOutputDevice? {
        if (devices.isEmpty()) return null
        if (preferredDeviceId != null) {
            devices.firstOrNull { it.id == preferredDeviceId }?.let { return it }
        }
        // System default media routing prefers external outputs (Bluetooth / USB / Wired / HDMI)
        // over built-in phone speaker whenever an external route is connected.
        return devices.firstOrNull { !it.isBuiltIn } ?: devices.firstOrNull()
    }

    private fun mapDescriptor(
        entry: RawOutputDescriptor,
        phoneLabel: String,
    ): AudioOutputDevice? {
        val cleanName = entry.productName.trim()
        return when (entry.deviceType) {
            TYPE_BUILTIN_SPEAKER,
            TYPE_BUILTIN_SPEAKER_SAFE,
            -> AudioOutputDevice(
                id = entry.id,
                name = phoneLabel,
                kind = AudioOutputKind.PHONE,
                deviceType = entry.deviceType,
                isBuiltIn = true,
            )

            TYPE_WIRED_HEADSET,
            TYPE_WIRED_HEADPHONES,
            -> AudioOutputDevice(
                id = entry.id,
                name = cleanName.takeIf { it.isNotBlank() && !isGenericModelName(it) } ?: "Wired Headphones",
                kind = AudioOutputKind.WIRED,
                deviceType = entry.deviceType,
                isBuiltIn = false,
            )

            TYPE_USB_DEVICE,
            TYPE_USB_HEADSET,
            TYPE_USB_ACCESSORY,
            -> AudioOutputDevice(
                id = entry.id,
                name = cleanName.ifBlank { "USB Audio" },
                kind = AudioOutputKind.USB,
                deviceType = entry.deviceType,
                isBuiltIn = false,
            )

            TYPE_HDMI,
            TYPE_HDMI_ARC,
            TYPE_HDMI_EARC,
            -> AudioOutputDevice(
                id = entry.id,
                name = cleanName.ifBlank { "HDMI Audio" },
                kind = AudioOutputKind.HDMI,
                deviceType = entry.deviceType,
                isBuiltIn = false,
            )

            TYPE_BLUETOOTH_A2DP,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            TYPE_BLE_BROADCAST,
            TYPE_HEARING_AID,
            -> {
                val inferred = when (entry.deviceType) {
                    TYPE_BLE_SPEAKER -> AudioOutputKind.SPEAKER
                    else -> inferBluetoothKind(cleanName)
                }
                AudioOutputDevice(
                    id = entry.id,
                    name = cleanName.ifBlank { "Bluetooth Audio" },
                    kind = inferred,
                    deviceType = entry.deviceType,
                    isBuiltIn = false,
                )
            }

            TYPE_DOCK -> AudioOutputDevice(
                id = entry.id,
                name = cleanName.ifBlank { "Audio Dock" },
                kind = AudioOutputKind.SPEAKER,
                deviceType = entry.deviceType,
                isBuiltIn = false,
            )

            // Explicitly ignore call-only SCO, earpiece, submix, telephony, FM, and mics
            TYPE_BLUETOOTH_SCO,
            TYPE_BUILTIN_EARPIECE,
            -> null

            else -> null
        }
    }

    private fun inferBluetoothKind(name: String): AudioOutputKind {
        val lower = name.lowercase()
        if (lower.isBlank()) return AudioOutputKind.HEADPHONES
        if (CAR_TOKENS.any { lower.contains(it) }) return AudioOutputKind.CAR
        if (BUDS_TOKENS.any { lower.contains(it) }) return AudioOutputKind.BUDS
        if (SPEAKER_TOKENS.any { lower.contains(it) }) return AudioOutputKind.SPEAKER
        return AudioOutputKind.HEADPHONES
    }

    private fun isGenericModelName(name: String): Boolean {
        val model = runCatching { Build.MODEL.orEmpty() }.getOrDefault("")
        return model.isNotBlank() && name.equals(model, ignoreCase = true)
    }

    private fun transportPriority(deviceType: Int): Int = when (deviceType) {
        TYPE_BLUETOOTH_A2DP -> 100
        TYPE_USB_HEADSET, TYPE_USB_DEVICE, TYPE_USB_ACCESSORY -> 90
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES -> 85
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> 75
        TYPE_HDMI_EARC, TYPE_HDMI_ARC, TYPE_HDMI -> 70
        TYPE_HEARING_AID -> 65
        TYPE_BLE_BROADCAST, TYPE_DOCK -> 55
        TYPE_BUILTIN_SPEAKER -> 20
        TYPE_BUILTIN_SPEAKER_SAFE -> 10
        else -> 0
    }
}

@Composable
fun rememberAudioOutputSnapshot(
    preferredDeviceId: Int?,
    phoneLabel: String,
    refreshKey: Int = 0,
): AudioOutputSnapshot {
    val context = LocalContext.current
    var devices by remember(context, phoneLabel, refreshKey) {
        mutableStateOf(AudioOutputRouter.queryOutputs(context, phoneLabel))
    }

    DisposableEffect(context, phoneLabel, refreshKey) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        fun refresh() {
            devices = AudioOutputRouter.queryOutputs(context, phoneLabel)
        }
        refresh()

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }
        val mainHandler = Handler(Looper.getMainLooper())
        runCatching { am?.registerAudioDeviceCallback(callback, mainHandler) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        onDispose {
            runCatching { am?.unregisterAudioDeviceCallback(callback) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val active = remember(devices, preferredDeviceId) {
        AudioOutputRouter.resolveActiveDevice(devices, preferredDeviceId)
    }
    return AudioOutputSnapshot(devices = devices, activeDevice = active)
}
