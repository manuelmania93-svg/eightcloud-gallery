package app.eightcloud.gallery.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val dateMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucket: String,
    val mimeType: String
)
