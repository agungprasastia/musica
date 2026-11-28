package com.music.musica.ui.player

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.musica.R
import com.music.musica.core.model.Song
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.components.IosSwitch
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.glassContentColor
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LyricsShareSheet(
    song: Song,
    pickedLines: List<LyricsShareLine>,
    backdropBitmap: Bitmap?,
    showLogo: Boolean,
    hazeState: HazeState?,
    onToggleShowLogo: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fg = glassContentColor()
    var format by remember { mutableStateOf(LyricsShareFormat.STORY) }
    var coverBitmap by remember(song.thumbnailUrl) { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(song.thumbnailUrl) {
        val url = upsizeArtworkUrl(song.thumbnailUrl, 400) ?: return@LaunchedEffect
        coverBitmap = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .size(400)
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
        }
    }

    LaunchedEffect(song, pickedLines, format, showLogo, backdropBitmap, coverBitmap) {
        previewBitmap = withContext(Dispatchers.Default) {
            LyricsShareCardRenderer.render(
                song = song,
                pickedLines = pickedLines,
                format = format,
                showLogo = showLogo,
                backdropBitmap = backdropBitmap,
                coverBitmap = coverBitmap,
            )
        }
    }

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lyrics_share_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = fg,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(10.dp))

            // Live card/story preview sized to keep action buttons always visible on compact screens
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 190.dp, max = 250.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = previewBitmap
                if (bmp != null) {
                    val ratio = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 1.3f)
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = song.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 244.dp)
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.25f)),
                    )
                } else {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            PillSegmentedControl(
                options = listOf(
                    stringResource(R.string.lyrics_share_format_story),
                    stringResource(R.string.lyrics_share_format_card),
                ),
                selectedIndex = if (format == LyricsShareFormat.STORY) 0 else 1,
                onSelect = { idx ->
                    format = if (idx == 0) LyricsShareFormat.STORY else LyricsShareFormat.CARD
                },
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleShowLogo(!showLogo) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_share_show_logo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                IosSwitch(
                    checked = showLogo,
                    onCheckedChange = onToggleShowLogo,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val savedMsg = stringResource(R.string.lyrics_share_saved)
                val failedMsg = stringResource(R.string.lyrics_share_failed)
                OutlinedButton(
                    onClick = {
                        val bmp = previewBitmap ?: return@OutlinedButton
                        if (busy) return@OutlinedButton
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LyricsShareCardRenderer.saveToGallery(context, bmp, song.title)
                            }
                            busy = false
                            Toast.makeText(context, if (ok) savedMsg else failedMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = previewBitmap != null && !busy,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_save),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = {
                        val bmp = previewBitmap ?: return@Button
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LyricsShareCardRenderer.sharePng(context, bmp, song.title)
                            }
                            busy = false
                            if (!ok) {
                                Toast.makeText(context, failedMsg, Toast.LENGTH_SHORT).show()
                            } else {
                                onDismiss()
                            }
                        }
                    },
                    enabled = previewBitmap != null && !busy,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.menu_share),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}
