package app.eightcloud.gallery

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rolling on-device log. Never leaves the device except when the user
 * explicitly shares it from Settings. Capped so it cannot grow unbounded.
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_BYTES = 256 * 1024

    @Volatile private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val f = file ?: return
        runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts $tag: $message\n")
            if (f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(lines.size * 3 / 4).joinToString("\n"))
            }
        }
    }

    fun read(context: Context): String {
        val f = file ?: File(context.filesDir, FILE_NAME)
        if (!f.exists()) return ""
        return runCatching { f.readText() }.getOrDefault("")
    }

    @Synchronized
    fun clear(context: Context) {
        val f = file ?: File(context.filesDir, FILE_NAME)
        runCatching { f.delete() }
    }

    /** Records how the process last died (ANR, OOM kill, native crash). */
    fun recordLastExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            am.getHistoricalProcessExitReasons(context.packageName, 0, 3).forEach { info ->
                log("exit", "previous exit: ${reasonName(info.reason)}" +
                    (info.description?.let { " ($it)" } ?: "") +
                    " at " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp)))
            }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "USER_REQUESTED"
        2 -> "USER_STOPPED"
        3 -> "LOW_MEMORY_KILL"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        9 -> "PERMISSION_CHANGE"
        10 -> "EXCESSIVE_RESOURCE_USAGE"
        12 -> "FREEZER"
        13 -> "OTHER($reason)"
        else -> "REASON_$reason"
    }
}
