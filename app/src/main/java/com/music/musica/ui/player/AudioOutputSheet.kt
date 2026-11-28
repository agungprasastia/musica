package com.music.musica.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.playback.AudioOutputDevice
import com.music.musica.playback.AudioOutputKind
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

internal fun audioOutputIcon(kind: AudioOutputKind?): ImageVector = when (kind) {
    AudioOutputKind.BUDS -> Icons.Rounded.Earbuds
    AudioOutputKind.HEADPHONES -> Icons.Rounded.Headphones
    AudioOutputKind.SPEAKER -> Icons.Rounded.Speaker
    AudioOutputKind.CAR -> Icons.Rounded.DirectionsCar
    AudioOutputKind.WIRED -> Icons.Rounded.Headset
    AudioOutputKind.USB -> Icons.Rounded.Usb
    AudioOutputKind.HDMI -> Icons.Rounded.Tv
    AudioOutputKind.PHONE, null -> Icons.Rounded.Smartphone
}

/**
 * Liquid Glass bottom sheet listing connected Android audio output routes (Built-in Speaker,
 * Bluetooth Buds/Headphones/Speaker/Car, Wired, USB, HDMI) with one-tap ExoPlayer route switching
 * and an inline hardware volume slider.
 */
@Composable
fun AudioOutputSheet(
    devices: List<AudioOutputDevice>,
    activeDeviceId: Int?,
    needsBluetoothPermission: Boolean,
    hazeState: HazeState?,
    onSelectDevice: (Int?) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fg = glassContentColor()
    val secondaryFg = fg.copy(alpha = 0.66f)

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_audio_output),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg,
                )
                if (activeDeviceId != null) {
                    GlassDialogButton(
                        text = stringResource(R.string.player_audio_output_auto),
                        onClick = {
                            onSelectDevice(null)
                            onDismiss()
                        },
                    )
                }
            }

            if (needsBluetoothPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(fg.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            tint = MusicaAccentRed,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.player_audio_output_bt_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryFg,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        GlassDialogButton(
                            text = stringResource(R.string.player_audio_output_allow_bt),
                            onClick = onRequestBluetoothPermission,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            val displayList = devices.ifEmpty {
                listOf(
                    AudioOutputDevice(
                        id = -1,
                        name = stringResource(R.string.player_this_phone),
                        kind = AudioOutputKind.PHONE,
                        deviceType = 2,
                        isBuiltIn = true,
                    ),
                )
            }

            displayList.forEach { device ->
                val isSelected = device.id == activeDeviceId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) fg.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable {
                            onSelectDevice(if (device.id < 0) null else device.id)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MusicaAccentRed.copy(alpha = 0.22f) else fg.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = audioOutputIcon(device.kind),
                            contentDescription = null,
                            tint = if (isSelected) MusicaAccentRed else fg,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSelected) {
                            Text(
                                text = stringResource(R.string.player_audio_output_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MusicaAccentRed,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MusicaAccentRed,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                color = fg.copy(alpha = 0.14f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            VolumeControlRow(
                tint = fg,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
        }
    }
}
