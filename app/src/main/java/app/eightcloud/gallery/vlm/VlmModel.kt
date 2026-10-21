package app.eightcloud.gallery.vlm

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** The one supported model: official Google Gemma 4 E2B (LiteRT-LM build, Apache-2.0). */
object VlmModel {
    const val FILE = "gemma-4-E2B-it.litertlm"
    const val URL_ = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val PROMPT_VER = 1

    val busy = AtomicBoolean(false)
    @Volatile var cancel = false
    val messages = ConcurrentLinkedQueue<String>()
    @Volatile var progress = ""

    fun file(c: Context) = File(c.filesDir, FILE)
    private fun marker(c: Context) = File(c.filesDir, "$FILE.verified")

    fun isVerified(c: Context): Boolean {
        val f = file(c)
        val m = marker(c)
        return f.exists() && f.length() == BYTES && m.exists() && m.readText().trim() == "${f.length()}:${f.lastModified()}:$SHA256"
    }

    fun onDiskBytes(c: Context): Long = file(c).let { if (it.exists()) it.length() else 0L }

    /** Streams the file through SHA-256. Blocking; call off the main thread. */
    fun verify(c: Context): Boolean {
        val f = file(c)
        if (!f.exists() || f.length() != BYTES) { marker(c).delete(); return false }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return if (hex == SHA256) {
            marker(c).writeText("${f.length()}:${f.lastModified()}:$SHA256"); true
        } else { marker(c).delete(); false }
    }

    /** Deletes the model file only. Tags, descriptions and categories stay (they live in the database). */
    fun delete(c: Context) { file(c).delete(); marker(c).delete() }

    fun isOnWifi(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Blocking resumable download with retries. Returns true when the verified file is in place. */
    fun download(c: Context, onProgress: (Long) -> Unit): Boolean {
        val f = file(c)
        marker(c).delete()
        var attempt = 0
        while (attempt < 30 && !cancel) {
            attempt++
            if (f.exists() && f.length() > BYTES) f.delete()
            var have = if (f.exists()) f.length() else 0L
            if (have == BYTES) break
            try {
                val con = URL(URL_).openConnection() as HttpURLConnection
                con.instanceFollowRedirects = true
                if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
                con.connectTimeout = 20000
                con.readTimeout = 30000
                val code = con.responseCode
                if (code != 200 && code != 206) { messages.add("Download failed: HTTP $code"); return false }
                if (code == 200 && have > 0) { f.delete(); have = 0 }
                con.inputStream.use { input ->
                    java.io.FileOutputStream(f, have > 0).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (have < BYTES && !cancel) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), BYTES - have).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val now = System.currentTimeMillis()
                            if (now - last > 1500) { last = now; onProgress(have) }
                        }
                        out.fd.sync()
                    }
                }
            } catch (t: Throwable) {
                messages.add("Network error, retrying in 5 s (${attempt}/30)")
                Thread.sleep(5000)
            }
        }
        if (cancel) return false
        if (f.length() != BYTES) { messages.add("Download incomplete (${f.length() / 1_000_000} of ${BYTES / 1_000_000} MB). Tap Download to resume."); return false }
        progress = "Verifying file..."
        val ok = verify(c)
        if (!ok) { f.delete(); messages.add("Checksum mismatch. The file was deleted. Tap Download to try again.") }
        return ok
    }
}
