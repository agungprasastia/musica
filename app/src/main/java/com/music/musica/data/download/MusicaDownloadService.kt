package com.music.musica.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.music.musica.MusicaApplication
import com.music.musica.R
import java.util.concurrent.Executors

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
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloads_title))
            .setContentText(getString(R.string.downloads_status_downloading, downloads.firstOrNull()?.percentDownloaded?.toInt()?.coerceIn(0, 100) ?: 0))
            .setOngoing(active > 0)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 2042
        private const val CHANNEL_ID = "musica_downloads_channel"

        fun createDownloadManager(context: Context, cacheProvider: AudioCacheProvider): DownloadManager {
            return DownloadManager(
                context,
                cacheProvider.databaseProvider,
                cacheProvider.downloadCache,
                cacheProvider.upstreamHttpFactory,
                Executors.newFixedThreadPool(2),
            ).apply {
                maxParallelDownloads = 2
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
