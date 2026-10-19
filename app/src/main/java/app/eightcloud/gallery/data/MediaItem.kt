package app.eightcloud.gallery.data

data class MediaItem(
    val id: Long,
    val uri: String,
    val isVideo: Boolean,
    val name: String,
    val dateMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucket: String,
    val mimeType: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)
