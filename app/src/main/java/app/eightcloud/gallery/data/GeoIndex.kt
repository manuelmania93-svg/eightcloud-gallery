package app.eightcloud.gallery.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.GeoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GPS coordinates come from each file's EXIF data, cached in Room
 * (geo_entries). MediaStore's own latitude/longitude columns are not
 * queryable on Android 10+ without special access, so we read the files
 * directly; the scan is incremental and only unscanned items are parsed.
 */
object GeoIndex {

    data class Point(val mediaStoreId: Long, val lat: Double, val lon: Double)

    /** All cached points. */
    suspend fun located(context: Context): List<Point> = withContext(Dispatchers.IO) {
        BackupDb.get(context).geoDao().withLocation().map {
            Point(it.mediaStoreId, it.latitude!!, it.longitude!!)
        }
    }

    /**
     * Scan up to [maxItems] unscanned items; returns the number scanned now.
     * Call repeatedly (e.g. while the map is open) until it returns 0.
     */
    suspend fun scanBatch(context: Context, items: List<MediaItem>, maxItems: Int = 200): Int =
        withContext(Dispatchers.IO) {
            val dao = BackupDb.get(context).geoDao()
            val known = dao.scannedIds().toHashSet()
            val pending = items.filter { it.id !in known }.take(maxItems)
            if (pending.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            val resolver = context.contentResolver
            val rows = pending.map { item ->
                val latLong = runCatching {
                    resolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                        ExifInterface(input).latLong
                    }
                }.getOrNull()
                GeoEntry(
                    mediaStoreId = item.id,
                    latitude = latLong?.getOrNull(0),
                    longitude = latLong?.getOrNull(1),
                    scannedAt = now
                )
            }
            dao.upsertAll(rows)
            rows.size
        }
}
