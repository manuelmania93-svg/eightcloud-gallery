package app.eightcloud.gallery.labels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.MediaLabelEntry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device analysis: face count + image labels per media item, stored in
 * Room (media_labels). Runs incrementally over the library; videos only get
 * their first frame analyzed via a thumbnail-free path is skipped for now.
 */
class MediaScanner(private val context: Context) {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    private val nsfwScorer by lazy { NsfwScorer(context) }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.6f)
            .build()
    )

    /** Scan one image; returns null when the bitmap cannot be decoded. */
    suspend fun scan(mediaStoreId: Long, uri: Uri): MediaLabelEntry? {
        val bitmap = loadBitmap(uri, 1024) ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = runCatching { faceDetector.process(image).await() }.getOrNull()
        val labels = runCatching { labeler.process(image).await() }.getOrNull()
        val nsfw = nsfwScorer.score(bitmap)
        val entry = MediaLabelEntry(
            mediaStoreId = mediaStoreId,
            faceCount = faces?.size ?: 0,
            labels = labels?.joinToString(",") { it.text } ?: "",
            nsfwScore = nsfw,
            scannedAt = System.currentTimeMillis()
        )
        BackupDb.get(context).mediaLabelDao().upsert(entry)
        return entry
    }

    private fun loadBitmap(uri: Uri, maxSize: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
}
