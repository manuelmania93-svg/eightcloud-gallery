package app.eightcloud.gallery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.RemoteViews
import app.eightcloud.gallery.AppLog
import app.eightcloud.gallery.MainActivity
import app.eightcloud.gallery.R

/**
 * Home-screen widget: one random photo per day (stable within a day via a
 * date-seeded pick), tap opens the app. Honors the widget feature toggle.
 */
class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_photo)
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_image, openApp)

            val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("feat_widget", true)
            val bitmap = if (enabled) runCatching { loadDailyPhoto(context) }.getOrNull() else null
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, bitmap)
            } else {
                views.setImageViewResource(R.id.widget_image, android.R.drawable.ic_menu_gallery)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun loadDailyPhoto(context: Context): Bitmap? {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                MediaStore.Images.Media.DATE_TAKEN + " DESC"
            ) ?: return null
            val id = cursor.use {
                val count = it.count
                if (count == 0) return null
                val day = System.currentTimeMillis() / 86_400_000L
                val position = (java.util.Random(day).nextInt(count))
                if (!it.moveToPosition(position)) return null
                it.getLong(0)
            }
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            AppLog.log("widget", "daily photo id=" + id)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(720, 720), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
        }
    }
}
