package app.eightcloud.gallery.vlm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.MediaStore
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.VlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) for the model download and the second pass.
 * The second pass only runs while charging with the screen on, except the explicit 50-photo test.
 * The engine is closed whenever the pass ends, pauses or is cancelled.
 */
class VlmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    companion object {
        const val ACTION_DOWNLOAD = "download"
        const val ACTION_PASS = "pass"
        const val ACTION_STOP = "stop"
        const val EXTRA_LIMIT = "limit"

        fun start(c: Context, action: String, limit: Int = 0) {
            val i = Intent(c, VlmService::class.java).setAction(action).putExtra(EXTRA_LIMIT, limit)
            c.startForegroundService(i)
        }

        fun isCharging(c: Context): Boolean {
            val i = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }

    private fun notif(text: String): Notification =
        Notification.Builder(this, "vlm")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("8Cloud Gallery: AI model")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun note(text: String) {
        VlmState.status(text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(77, notif(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("vlm", "AI model", NotificationManager.IMPORTANCE_LOW))
        val action = intent?.action
        if (action == null) { stopSelf(); return START_NOT_STICKY }
        val ok = runCatching {
            startForeground(77, notif("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.isSuccess
        if (!ok) { VlmState.status("Android did not allow the background service. Open the app and try again."); stopSelf(); return START_NOT_STICKY }
        if (action == ACTION_STOP) {
            VlmModel.cancel = true
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) { VlmState.status("Already running."); return START_NOT_STICKY }
        VlmModel.cancel = false
        val limit = intent.getIntExtra(EXTRA_LIMIT, 0)
        job = scope.launch {
            VlmState.running(true)
            try {
                if (action == ACTION_DOWNLOAD) runDownload() else runPass(limit)
            } catch (t: kotlinx.coroutines.CancellationException) {
                VlmState.status("Stopped.")
            } catch (t: Throwable) {
                VlmState.status("Error: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                VlmState.running(false)
                VlmState.bump()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    private fun runDownload() {
        if (!VlmModel.busy.compareAndSet(false, true)) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "8cloud:vlmdl")
        wl.acquire(90 * 60 * 1000L)
        try {
            val ok = VlmModel.download(this) { have ->
                val txt = "${have / 1_000_000} / ${VlmModel.BYTES / 1_000_000} MB (${have * 100 / VlmModel.BYTES}%)"
                VlmModel.progress = txt
                note("Downloading: $txt")
            }
            VlmState.status(
                if (ok) "Model ready (checksum verified)."
                else VlmModel.messages.toList().lastOrNull() ?: "Download stopped. Tap Download to resume."
            )
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
            VlmModel.busy.set(false)
            VlmModel.progress = ""
        }
    }

    private suspend fun runPass(limit: Int) {
        if (!VlmModel.isVerified(this)) { VlmState.status("Model is not downloaded or not verified."); return }
        val pending = VlmSelect.pending(this).let { if (limit > 0) it.take(limit) else it }
        if (pending.isEmpty()) { VlmState.status("No unsure photos left to check."); return }
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val engine = VlmEngine(this)
        val dao = BackupDb.get(this).vlmDao()
        var done = 0
        var ignored = 0
        val t0 = System.currentTimeMillis()
        try {
            note("Loading model...")
            engine.load()
            for (id in pending) {
                if (limit == 0 && (!isCharging(this) || !pm.isInteractive)) {
                    VlmState.status("Paused after $done photos: needs charger and screen on. Start again to continue.")
                    return
                }
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val v = runCatching { engine.analyze(uri) }.getOrNull()
                if (v == null) {
                    ignored++
                    dao.upsert(VlmResult(id, false, false, false, "", -1, System.currentTimeMillis())) // ver -1: tried, no usable answer, never used
                } else {
                    dao.upsert(VlmResult(id, v.meme, v.drawn, v.screenshot, v.description, VlmModel.PROMPT_VER, System.currentTimeMillis()))
                }
                done++
                // Refusals and malformed output are not stored, so they stay pending but never change tags.
                if (done % 10 == 0) VlmState.bump()
                val perPhoto = (System.currentTimeMillis() - t0) / done / 1000.0
                note("Checked $done of ${pending.size} (ignored $ignored), ${"%.1f".format(perPhoto)} s per photo")
                delay(50)
            }
            VlmState.status("Done: checked $done photos, ignored $ignored (refusal or bad format).")
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { engine.close() }
        }
    }
}
