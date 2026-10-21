package app.eightcloud.gallery.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eightcloud.gallery.telegram.TdClient
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit

/**
 * Weekly spot-check: verifies a random sample of cloud copies and warns with
 * a notification when any are missing. Silent when everything is fine.
 */
class HealthCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("backupEnabled", false)) return Result.success()
        val td = TdClient.get(applicationContext)
        td.start()
        if (td.authState !is TdApi.AuthorizationStateReady) return Result.retry()
        val missing = runCatching { BackupEngine(applicationContext).verifySample(25) }
            .getOrElse { return Result.retry() }
        if (missing > 0) warn(missing)
        return Result.success()
    }

    private fun warn(missing: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup health", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val tap = launch?.let {
            PendingIntent.getActivity(
                applicationContext, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Backup health check")
            .setContentText("$missing backed-up files are missing in the cloud - open to re-upload")
            .setAutoCancel(true)
            .apply { tap?.let { setContentIntent(it) } }
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PERIODIC_NAME = "eightcloud-weekly-health"
        private const val CHANNEL_ID = "backup_health"
        private const val NOTIFICATION_ID = 43

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<HealthCheckWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
            )
        }

        /** Enqueue without disturbing an already-scheduled run (app-start bootstrap). */
        fun keepScheduled(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthCheckWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
