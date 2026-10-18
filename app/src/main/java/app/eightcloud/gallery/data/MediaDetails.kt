package app.eightcloud.gallery.data

import android.content.Context
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaDetails(
    val name: String,
    val sizeText: String,
    val resolution: String,
    val dateText: String,
    val durationText: String?,
    val album: String,
    val mimeType: String,
    val camera: String?,
    val iso: String?,
    val aperture: String?,
    val focalLength: String?,
    val exposure: String?,
    val cloudStatus: String
)

object DetailsLoader {

    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    fun load(context: Context, item: MediaItem): MediaDetails {
        var make: String? = null
        var model: String? = null
        var iso: String? = null
        var aperture: String? = null
        var focal: String? = null
        var exposure: String? = null
        if (!item.isVideo) {
            try {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
                    focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "$it s" }
                }
            } catch (_: Exception) {
                // EXIF unavailable for this item
            }
        }
        val camera = listOfNotNull(make, model).joinToString(" ").ifBlank { null }
        return MediaDetails(
            name = item.name,
            sizeText = Formatter.formatShortFileSize(context, item.sizeBytes),
            resolution = if (item.width > 0 && item.height > 0) "${item.width} x ${item.height}" else "unknown",
            dateText = dateFormat.format(Date(item.dateMillis)),
            durationText = if (item.isVideo && item.durationMs > 0) formatDuration(item.durationMs) else null,
            album = item.bucket,
            mimeType = item.mimeType,
            camera = camera,
            iso = iso,
            aperture = aperture,
            focalLength = focal,
            exposure = exposure,
            cloudStatus = "Local only"
        )
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
