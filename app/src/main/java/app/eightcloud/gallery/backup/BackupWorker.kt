package app.eightcloud.gallery.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eightcloud.gallery.telegram.TdClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!manual && !prefs.getBoolean("backupEnabled", false)) return Result.success()
        val td = TdClient.get(applicationContext)
        td.start()
        if (td.authState !is TdApi.AuthorizationStateReady) return Result.retry()
        val ids = inputData.getLongArray(KEY_IDS)?.toSet()
        app.eightcloud.gallery.AppLog.log("backup", "worker start (manual=" + manual + ")")
        return try {
            setForegroundAsync(foregroundInfo(0, 0, "Preparing backup..."))
            coroutineScope {
                // The engine pushes fine-grained progress (per-part bytes,
                // speed, ETA, pause state); this collector owns the notification.
                val notifier = launch {
                    var lastPosted = 0L
                    // StateFlow already conflates to the latest value.
                    BackupProgress.state.collect { s ->
                        val now = System.currentTimeMillis()
                        // Post at most once per second; the system caps us anyway.
                        if (s.running && now - lastPosted >= 1_000) {
                            lastPosted = now
                            setForegroundAsync(foregroundInfo(s))
                        }
                    }
                }
                try {
                    val engine = BackupEngine(applicationContext)
                    // Fresh install with an existing cloud destination: rebuild
                    // dedupe rows from captions before scanning, so already
                    // uploaded files are not uploaded again.
                    if (ids == null && engine.isIndexEmpty()) {
                        engine.rebuildIndexFromCloud()
                    }
                    engine.scan(ids)
                    engine.uploadPending(ids) { done, total, _, _, name ->
                        setProgressAsync(
                            Data.Builder()
                                .putInt(KEY_DONE, done)
                                .putInt(KEY_TOTAL, total)
                                .putString(KEY_NAME, name)
                                .build()
                        )
                    }
                    engine.exportIndex()
                } finally {
                    notifier.cancel()
                }
            }
            notifyManager().cancel(NOTIFICATION_ID)
            Result.success()
        } catch (e: Exception) {
            app.eightcloud.gallery.AppLog.log("backup", "worker error: " + (e.message ?: e.javaClass.simpleName))
            e.printStackTrace()
            notifyManager().cancel(NOTIFICATION_ID)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private fun notifyManager(): NotificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun foregroundInfo(done: Int, total: Int, text: String): ForegroundInfo {
        val channelId = "backup_progress"
        val channel = NotificationChannel(channelId, "Cloud backup", NotificationManager.IMPORTANCE_LOW)
        notifyManager().createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("8CloudGallery backup")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /** Live notification with per-byte progress, speed, ETA and a pause action. */
    private fun foregroundInfo(s: BackupProgress.State): ForegroundInfo {
        val channelId = "backup_progress"
        val channel = NotificationChannel(channelId, "Cloud backup", NotificationManager.IMPORTANCE_LOW)
        notifyManager().createNotificationChannel(channel)
        val title = if (s.paused) "8CloudGallery backup (paused)" else "8CloudGallery backup"
        val line1 = "${s.done} of ${s.total} - ${s.currentName}"
        val parts = mutableListOf<String>()
        if (s.bytesPerSec > 0) parts.add(BackupProgress.formatSpeed(s.bytesPerSec))
        val eta = BackupProgress.formatEta(s.etaSeconds)
        if (eta.isNotEmpty()) parts.add(eta + " left")
        val line2 = parts.joinToString(" - ")
        val toggleIntent = android.app.PendingIntent.getBroadcast(
            applicationContext, 0,
            android.content.Intent(applicationContext, BackupControlReceiver::class.java)
                .setAction(BackupControlReceiver.ACTION_TOGGLE_PAUSE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val maxBytes = if (s.bytesTotal > 0) 1_000 else 0
        val curBytes = if (s.bytesTotal > 0)
            ((s.bytesDone * 1_000) / s.bytesTotal).toInt() else 0
        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(
                if (s.paused) android.R.drawable.ic_media_pause
                else android.R.drawable.stat_sys_upload
            )
            .setContentTitle(title)
            .setContentText(line1)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (line2.isEmpty()) line1 else line1 + "\n" + line2
            ))
            .setProgress(maxBytes, curBytes, s.bytesTotal <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (s.paused) "Resume" else "Pause", toggleIntent)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val PERIODIC_NAME = "eightcloud-periodic-backup"
        private const val ONEOFF_NAME = "eightcloud-oneoff-backup"
        private const val NOTIFICATION_ID = 42
        private const val KEY_MANUAL = "manual"
        private const val KEY_IDS = "ids"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_NAME = "name"

        fun schedule(context: Context, wifiOnly: Boolean, chargingOnly: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        fun runNow(context: Context, wifiOnly: Boolean, ids: Set<Long>? = null) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val data = Data.Builder().putBoolean(KEY_MANUAL, true)
            if (ids != null) data.putLongArray(KEY_IDS, ids.toLongArray())
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONEOFF_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(constraints)
                    .setInputData(data.build())
                    .build()
            )
        }
    }
}
