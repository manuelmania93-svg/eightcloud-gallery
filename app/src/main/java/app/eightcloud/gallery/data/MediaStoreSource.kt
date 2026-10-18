package app.eightcloud.gallery.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

object MediaStoreSource {

    fun query(context: Context): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val cId = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val cType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val cName = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val cTaken = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val cModified = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val cSize = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val cWidth = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val cHeight = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val cDuration = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            val cBucket = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val cMime = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cId)
                val isVideo = cursor.getInt(cType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val taken = cursor.getLong(cTaken)
                val modified = cursor.getLong(cModified)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        isVideo = isVideo,
                        name = cursor.getString(cName) ?: "unknown",
                        dateMillis = if (taken > 0) taken else modified * 1000L,
                        sizeBytes = cursor.getLong(cSize),
                        width = cursor.getInt(cWidth),
                        height = cursor.getInt(cHeight),
                        durationMs = cursor.getLong(cDuration),
                        bucket = cursor.getString(cBucket) ?: "",
                        mimeType = cursor.getString(cMime) ?: ""
                    )
                )
            }
        }
        return items
    }
}
