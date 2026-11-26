package com.music.musica.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.music.musica.MainActivity
import com.music.musica.MusicaApplication
import com.music.musica.R
import com.music.musica.data.stream.StreamResolver
import com.music.musica.playback.MusicaPlaybackService
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Official Media3 foreground [DownloadService] (`foregroundServiceType="dataSync"`).
 *
 * Resolves `musica://track/{videoId}` URIs dynamically using the user's configured
 * `downloadQuality` ceiling and fetches audio through [AudioCacheProvider.chunkedHttpFactory]
 * so offline downloads run at full speed and survive screen-off / backgrounding.
 */
@OptIn(UnstableApi::class)
class MusicaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_notification_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager =
        (application as MusicaApplication).container.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        ensureChannel(this)

        val activeDownloads = downloads.filter {
            it.state == Download.STATE_DOWNLOADING ||
                it.state == Download.STATE_QUEUED ||
                it.state == Download.STATE_RESTARTING
        }
        val totalCount = activeDownloads.size.coerceAtLeast(1)
        val completedInBatch = downloads.count { it.state == Download.STATE_COMPLETED }
        val batchTotal = (activeDownloads.size + completedInBatch).coerceAtLeast(totalCount)
        val currentIndex = (completedInBatch + 1).coerceAtMost(batchTotal)

        val percentValues = activeDownloads.map { it.percentDownloaded }.filter { it >= 0f }
        val hasDeterminateProgress = percentValues.isNotEmpty()
        val overallPercent = if (hasDeterminateProgress) {
            percentValues.average().roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val waitingForWifi = (notMetRequirements and Requirements.NETWORK_UNMETERED) != 0
        val contentText = if (waitingForWifi) {
            getString(R.string.downloads_notification_waiting_wifi)
        } else {
            getString(R.string.downloads_notification_progress, currentIndex, batchTotal, overallPercent)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloads_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setProgress(100, overallPercent, waitingForWifi || !hasDeterminateProgress)
            .setOngoing(activeDownloads.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 2042
        private const val CHANNEL_ID = "musica_downloads_channel"
        private const val MAX_PARALLEL_DOWNLOADS = 2

        fun requirementsForWifiOnly(wifiOnly: Boolean): Requirements =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

        fun createDownloadManager(
            context: Context,
            cacheProvider: AudioCacheProvider,
            streamResolver: StreamResolver,
            downloadQualityKbpsProvider: () -> Int,
            wifiOnlyProvider: () -> Boolean,
        ): DownloadManager {
            val resolvingFactory = ResolvingDataSource.Factory(
                cacheProvider.chunkedHttpFactory,
                object : ResolvingDataSource.Resolver {
                    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                        val uri = dataSpec.uri
                        if (uri.scheme != MusicaPlaybackService.MUSICA_SCHEME) return dataSpec
                        val videoId = uri.lastPathSegment
                            ?: throw IOException("Missing videoId in download URI: $uri")

                        val maxKbps = downloadQualityKbpsProvider()
                        val resolved = runBlocking {
                            streamResolver.resolve(
                                videoId = videoId,
                                forceRefresh = false,
                                overrideMaxKbps = maxKbps,
                            )
                        }.getOrElse { err ->
                            throw IOException("Failed to resolve download stream for $videoId", err)
                        }

                        val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                            putAll(resolved.headers)
                        }
                        return dataSpec.buildUpon()
                            .setUri(resolved.url.toUri())
                            .setKey(videoId)
                            .setHttpRequestHeaders(headers)
                            .build()
                    }
                },
            )

            return DownloadManager(
                context,
                cacheProvider.databaseProvider,
                cacheProvider.downloadCache,
                resolvingFactory,
                Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
            ).apply {
                maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
                requirements = requirementsForWifiOnly(wifiOnlyProvider())
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.downloads_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }
}
