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

    private fun batteryTempC(): Float {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0) 100 else l * 100 / sc
    }

    private var hot = false

    /** Why the pass must wait right now, or null to go on. Heat has hysteresis: stop at 42 C, resume at 38 C. */
    private fun waitReason(fullRun: Boolean): String? {
        if (VlmState.paused.value) return "Paused by you."
        val t = batteryTempC()
        var thermal = 0
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            thermal = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
        }
        hot = VlmGate.hotNext(hot, t, thermal)
        if (hot) return "Auto-paused: phone is hot (${"%.0f".format(t)} C). Resumes at 38 C."
        val charging = isCharging(this)
        if (VlmGate.lowBattery(charging, batteryPct())) return "Auto-paused: battery under 15% and not charging."
        if (fullRun && !charging) return "Waiting for the charger (full run needs it)."
        return null
    }

    private suspend fun runPass(limit: Int) {
        if (!VlmModel.isVerified(this)) { VlmState.status("Model is not downloaded or not verified."); return }
        val fullRun = limit == 0
        val queue = if (fullRun) VlmSelect.fullQueue(this) else VlmSelect.pending(this).take(limit).map { VlmSelect.Job(it, true) }
        if (queue.isEmpty()) { VlmState.status("Nothing left to check."); return }
        val engine = VlmEngine(this)
        val dao = BackupDb.get(this).vlmDao()
        var done = 0
        var ignored = 0
        var loaded = false
        var activeMs = 0L
        VlmState.paused(false)
        VlmState.progress(0, queue.size)
        try {
            for (job in queue) {
                var why = waitReason(fullRun)
                while (why != null) {
                    if (loaded) { engine.close(); loaded = false } // free the model memory while waiting
                    if (VlmState.paused.value) VlmState.autoPause("") else VlmState.autoPause(why)
                    note(why)
                    delay(5000)
                    why = waitReason(fullRun)
                }
                VlmState.autoPause("")
                if (!loaded) { note("Loading model..."); engine.load(); loaded = true }
                val t = System.currentTimeMillis()
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, job.id)
                val v = runCatching { engine.analyze(uri) }.getOrNull()
                if (v == null) {
                    ignored++
                    // ver -1: tried, no usable answer (refusal / bad format / unreadable); never used for tags.
                    dao.upsert(VlmResult(job.id, false, false, false, "", -1, System.currentTimeMillis()))
                } else {
                    // ver 1 = unsure photo (verdict may change Memes/Drawn); ver 2 = bulk photo (description only).
                    dao.upsert(VlmResult(job.id, v.meme, v.drawn, v.screenshot, v.description, if (job.unsure) 1 else 2, System.currentTimeMillis()))
                }
                done++
                activeMs += System.currentTimeMillis() - t
                if (done % 10 == 0) VlmState.bump()
                VlmState.progress(done, queue.size)
                val per = activeMs / done / 1000.0
                val etaH = per * (queue.size - done) / 3600.0
                note("Checked $done of ${queue.size} (ignored $ignored), ${"%.1f".format(per)} s per photo, about ${"%.1f".format(etaH)} h left")
                delay(50)
            }
            VlmState.status("Done: checked $done photos, ignored $ignored (refusal or bad format).")
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { engine.close() }
            VlmState.autoPause("")
            VlmState.paused(false)
        }
    }
}
