package app.ypn.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.ypn.browser.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground shell for downloads: keeps the process alive while any
 * transfer is active and shows one ongoing notification with the
 * combined progress. The transfers themselves run on the
 * [DownloadController]'s own scope — this service only observes the
 * singleton's state once a second and stops itself when nothing is
 * active anymore.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = DownloadController.get(this)
        startInForeground(buildNotification(controller))
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                while (true) {
                    delay(1_000)
                    val current = DownloadController.get(this@DownloadService)
                    if (!current.hasActiveDownloads()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(current))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(controller: DownloadController): Notification {
        val active = controller.items.filter { it.isActive }
        val first = active.firstOrNull()
        val title = when {
            first == null -> "Downloads"
            active.size > 1 -> "Downloading ${active.size} files"
            else -> "Downloading ${first?.fileName ?: "file"}"
        }
        val known = active.filter { it.totalBytes > 0 }
        val totalSum = known.sumOf { it.totalBytes }
        val doneSum = active.sumOf { it.downloadedBytes }
        val text = if (totalSum > 0) {
            "${formatBytes(doneSum)} of ${formatBytes(totalSum)}"
        } else {
            "${formatBytes(doneSum)} downloaded"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (totalSum > 0) {
            val pct = ((doneSum * 100) / totalSum).toInt().coerceIn(0, 100)
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4301

        /**
         * Start the foreground shell. Called when a download becomes
         * active — normally from the foreground UI, so the Android 12+
         * background-start restriction doesn't bite; if it ever does,
         * the download still runs and only the notification is lost.
         */
        fun ensureStarted(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, DownloadService::class.java)
                )
            } catch (e: Exception) {
                // See above — transfers are not held hostage to this.
            }
        }
    }
}
