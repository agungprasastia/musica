package com.music.musica.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.NerdStats
import com.music.musica.core.model.PlaybackSourceMode
import com.music.musica.core.model.formatDuration
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.IosSwitch
import com.music.musica.ui.components.LiquidGlassDialog
import com.music.musica.ui.components.LiquidGlassMenuRow
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.ThinSlider
import com.music.musica.ui.components.glassContentColor
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    hazeState: HazeState? = null,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val selectedPresetIdx = presets.indexOfFirst { abs(it - currentSpeed) < 0.03f }
    val fg = glassContentColor()

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_speed_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg,
                )
                Text(
                    text = "%.2f×".format(currentSpeed),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            ThinSlider(
                value = currentSpeed,
                onValueChange = { raw ->
                    val snapped = (raw * 20f).roundToInt() / 20f
                    onSpeedChange(snapped.coerceIn(0.5f, 2.0f))
                },
                valueRange = 0.5f..2.0f,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = fg.copy(alpha = 0.22f),
            )
            Spacer(Modifier.height(16.dp))
            PillSegmentedControl(
                options = presets.map { "%.2g×".format(it) },
                selectedIndex = selectedPresetIdx.coerceAtLeast(0),
                onSelect = { idx -> onSpeedChange(presets[idx]) },
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
fun SleepTimerSheet(
    remainingMs: Long,
    stopAfterTrack: Boolean,
    fadeOutEnabled: Boolean = true,
    hazeState: HazeState? = null,
    onToggleFadeOut: (Boolean) -> Unit = {},
    onSelectMinutes: (Int) -> Unit,
    onStopAfterTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(10, 15, 30, 45, 60)
    val activeMinutes = if (remainingMs > 0L) ((remainingMs + 59_999L) / 60_000L).toInt() else 0
    var customMinutes by remember(activeMinutes) {
        mutableFloatStateOf(if (activeMinutes > 0) activeMinutes.toFloat().coerceIn(5f, 120f) else 30f)
    }
    val fg = glassContentColor()

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_sleep_timer),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg,
                )
                if (remainingMs > 0L) {
                    Text(
                        text = stringResource(R.string.player_sleep_remaining, formatDuration(remainingMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Custom minutes slider
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val roundedMin = customMinutes.roundToInt()
                    Text(
                        text = pluralStringResource(R.plurals.player_sleep_minutes, roundedMin, roundedMin),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = fg,
                    )
                    GlassDialogButton(
                        text = stringResource(R.string.action_save),
                        primary = true,
                        onClick = {
                            onSelectMinutes(customMinutes.roundToInt())
                            onDismiss()
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                ThinSlider(
                    value = customMinutes,
                    onValueChange = { customMinutes = (it / 5f).roundToInt() * 5f },
                    valueRange = 5f..120f,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = fg.copy(alpha = 0.22f),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                thickness = 0.5.dp,
                color = fg.copy(alpha = 0.14f),
            )

            // Fade out toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_sleep_fade_out),
                    style = MaterialTheme.typography.bodyLarge,
                    color = fg,
                )
                IosSwitch(checked = fadeOutEnabled, onCheckedChange = onToggleFadeOut)
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                thickness = 0.5.dp,
                color = fg.copy(alpha = 0.14f),
            )

            presets.forEach { min ->
                val isSelected = !stopAfterTrack && activeMinutes == min
                LiquidGlassMenuRow(
                    label = pluralStringResource(R.plurals.player_sleep_minutes, min, min),
                    selected = isSelected,
                    onClick = { onSelectMinutes(min); onDismiss() },
                )
            }
            LiquidGlassMenuRow(
                label = stringResource(R.string.player_sleep_end_of_track),
                selected = stopAfterTrack,
                onClick = { onStopAfterTrack(); onDismiss() },
            )
            if (remainingMs > 0L || stopAfterTrack) {
                LiquidGlassMenuRow(
                    label = stringResource(R.string.player_sleep_off),
                    selected = false,
                    destructive = true,
                    onClick = { onCancelTimer(); onDismiss() },
                )
            }
        }
    }
}

@Composable
fun StatsForNerdsDialog(
    stats: NerdStats,
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
) {
    val modeLabel = when (stats.sourceMode) {
        PlaybackSourceMode.OFFLINE -> stringResource(R.string.stats_offline)
        PlaybackSourceMode.CACHED -> stringResource(R.string.stats_cached)
        PlaybackSourceMode.STREAMING -> stringResource(R.string.stats_streaming)
    }

    LiquidGlassDialog(
        hazeState = hazeState,
        onDismiss = onDismiss,
        title = stringResource(R.string.stats_title),
        actions = {
            GlassDialogButton(
                text = stringResource(R.string.action_close),
                primary = true,
                onClick = onDismiss,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatLine(stringResource(R.string.stats_video_id), stats.videoId.ifBlank { "—" })
            StatLine(
                stringResource(R.string.stats_codec),
                listOf(stats.mimeType, stats.codecs).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "—" },
            )
            StatLine(
                stringResource(R.string.stats_bitrate),
                if (stats.bitrateKbps > 0) "${stats.bitrateKbps} kbps" else "—",
            )
            StatLine(
                stringResource(R.string.stats_sample_rate),
                if (stats.sampleRateHz > 0) "${stats.sampleRateHz} Hz" else "—",
            )
            StatLine(
                stringResource(R.string.stats_channels),
                if (stats.channelCount > 0) "${stats.channelCount} ch" else "—",
            )
            StatLine(
                stringResource(R.string.stats_loudness),
                stats.loudnessDb?.let { "%.2f dB".format(it) } ?: "—",
            )
            StatLine(stringResource(R.string.stats_source_client), stats.sourceClient.ifBlank { "—" })
            StatLine(stringResource(R.string.stats_playback_mode), modeLabel)
            StatLine(stringResource(R.string.stats_buffered), "${stats.bufferedMs / 1000L} s")
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    val fg = glassContentColor()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.65f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
