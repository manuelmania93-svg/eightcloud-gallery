package app.eightcloud.gallery

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Last-resort crash capture: writes the uncaught exception to a file so the
 * user can share it from Settings after the app restarts. Chains to the
 * previous handler so the system still sees the crash.
 */
object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    append("8CloudGallery ").append(BuildConfig.VERSION_NAME).append('\n')
                    append("Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append(Date().toString()).append("\n\n")
                    append("Thread: ").append(thread.name).append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                File(appContext.filesDir, FILE_NAME).writeText(text)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
