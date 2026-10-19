package app.eightcloud.gallery.labels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.room.withTransaction
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.FaceEntity
import app.eightcloud.gallery.backup.FaceScan
import app.eightcloud.gallery.backup.Person
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Groups faces into per-person clusters, fully on-device: ML Kit detection,
 * SFace embeddings, greedy nearest-centroid assignment (FaceClusterer math).
 * Everything persists in Room only - nothing ever leaves the device.
 */
class FaceGrouper(private val context: Context) : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )
    private val embedder = FaceEmbedder(context)

    fun loadBitmap(uri: Uri, maxSize: Int): Bitmap? {
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

    /** Detects faces, embeds, assigns to persons, persists. Returns faces stored. */
    suspend fun process(mediaStoreId: Long, bitmap: Bitmap): Int {
        val db = BackupDb.get(context)
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()
        if (faces.isEmpty()) {
            db.faceScanDao().insert(FaceScan(mediaStoreId, System.currentTimeMillis()))
            return 0
        }
        return db.withTransaction {
        if (mediaStoreId in db.faceScanDao().processedIds()) return@withTransaction 0
        val persons = db.personDao().all().toMutableList()
        val centroids = persons.map { FaceClusterer.bytesToFloats(it.centroid) }.toMutableList()
        val entities = mutableListOf<FaceEntity>()
        val usedPersons = mutableSetOf<Long>()
        for (face in faces) {
            val box = face.boundingBox
            val emb = embedder.embed(bitmap, face) ?: continue
            val available = persons.indices.filter { persons[it].id !in usedPersons }
            val nearest = FaceClusterer.bestMatch(emb, available.map { centroids[it] })
            val match = if (nearest >= 0) available[nearest] else -1
            val personId: Long
            if (match >= 0) {
                val p = persons[match]
                val newCentroid = FaceClusterer.updatedCentroid(centroids[match], p.faceCount, emb)
                centroids[match] = newCentroid
                db.personDao().updateCluster(p.id, FaceClusterer.floatsToBytes(newCentroid), p.faceCount + 1)
                persons[match] = p.copy(faceCount = p.faceCount + 1)
                coverJpeg(bitmap, box)?.let { db.personDao().setCoverIfMissing(p.id, it) }
                personId = p.id
            } else {
                val id = db.personDao().insert(
                    Person(
                        name = null,
                        centroid = FaceClusterer.floatsToBytes(emb),
                        faceCount = 1,
                        coverJpeg = coverJpeg(bitmap, box),
                        createdAt = System.currentTimeMillis()
                    )
                )
                persons.add(
                    Person(id, null, FaceClusterer.floatsToBytes(emb), 1, null, System.currentTimeMillis())
                )
                centroids.add(emb)
                personId = id
            }
            usedPersons += personId
            entities.add(
                FaceEntity(
                    mediaStoreId = mediaStoreId,
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat(),
                    embedding = FaceClusterer.floatsToBytes(emb),
                    personId = personId
                )
            )
        }
        db.faceDao().insertAll(entities)
        check(entities.isNotEmpty()) { "No usable face landmarks found" }
        db.faceScanDao().insert(FaceScan(mediaStoreId, System.currentTimeMillis()))
        entities.size
        }
    }

    override fun close() {
        detector.close()
        embedder.close()
    }

    private fun coverJpeg(bitmap: Bitmap, box: Rect): ByteArray? = runCatching {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, 160, 160, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }.getOrNull()
}
