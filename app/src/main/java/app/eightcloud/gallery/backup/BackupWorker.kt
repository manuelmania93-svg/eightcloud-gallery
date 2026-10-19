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
            val engine = BackupEngine(applicationContext)
            engine.scan(ids)
            engine.uploadPending(ids) { done, total, _, _, name ->
                val text = if (total > 0) "$done of $total - $name" else "Scanning..."
                setProgressAsync(
                    Data.Builder()
                        .putInt(KEY_DONE, done)
                        .putInt(KEY_TOTAL, total)
                        .putString(KEY_NAME, name)
                        .build()
                )
                setForegroundAsync(foregroundInfo(done, total, text))
            }
            engine.exportIndex()
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
