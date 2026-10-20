package app.eightcloud.vlmtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object Dl {
    val busy = AtomicBoolean(false)
    @Volatile var cancel = false
    val messages = ConcurrentLinkedQueue<String>()
}

class DownloadService : Service() {
    private fun notif(text: String, done: Long, total: Long): Notification {
        val b = Notification.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("8Cloud VLM test: model download")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(100, (done * 100 / total).toInt(), false)
        return b.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("dl", "Model download", NotificationManager.IMPORTANCE_LOW))
        val name = intent?.getStringExtra("name") ?: return START_NOT_STICKY.also { stopSelf() }
        val url = intent.getStringExtra("url") ?: return START_NOT_STICKY.also { stopSelf() }
        val total = intent.getLongExtra("bytes", 0L)
        startForeground(1, notif("Starting", 0, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!Dl.busy.compareAndSet(false, true)) {
            Dl.messages.add("A download is already running. Ignoring this press.")
            return START_NOT_STICKY
        }
        Dl.cancel = false
        Thread {
            val f = File(filesDir, name)
            val marker = File(filesDir, "$name.verified")
            try {
                if (f.exists() && f.length() > total) { Dl.messages.add("File was larger than expected (${f.length()}). Deleted, starting over."); f.delete(); marker.delete() }
                var have = if (f.exists()) f.length() else 0L
                if (have == total) { Dl.messages.add("Model already complete. Press 1c."); return@Thread }
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.connectTimeout = 20000
                c.readTimeout = 30000
                val code = c.responseCode
                if (code != 200 && code != 206) { Dl.messages.add("HTTP $code"); return@Thread }
                if (code == 200 && have > 0) { Dl.messages.add("Server ignored Range (HTTP 200). Restarting from 0."); f.delete(); have = 0 }
                marker.delete()
                Dl.messages.add("Downloading from ${have / 1_000_000} MB (HTTP $code) ...")
                var lastN = 0L
                c.inputStream.use { input ->
                    java.io.FileOutputStream(f, have > 0).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (have < total && !Dl.cancel) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), total - have).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val now = System.currentTimeMillis()
                            if (now - lastN > 2000) {
                                lastN = now
                                nm.notify(1, notif("${have / 1_000_000} / ${total / 1_000_000} MB (${have * 100 / total}%)", have, total))
                            }
                        }
                        out.fd.sync()
                    }
                }
                Dl.messages.add(
                    if (Dl.cancel) "Stopped at ${f.length()} bytes. Press 1 to resume."
                    else if (f.length() == total) "Download complete, size matches (${f.length()} bytes). Press 1c to verify."
                    else "Incomplete: ${f.length()} of $total. Press 1 to resume."
                )
            } catch (t: Throwable) {
                Dl.messages.add("Download error: ${t.message}. Press 1 to resume.")
            } finally {
                Dl.busy.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
