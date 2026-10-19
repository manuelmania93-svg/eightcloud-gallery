package app.eightcloud.gallery.labels

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure clustering math for grouping face embeddings into "persons".
 * No Android deps, fully unit-testable.
 */
object FaceClusterer {

    const val DIM = 128

    /**
     * Minimum cosine similarity to count a face as the same person.
     * FaceNet 128-d embeddings: same person typically scores 0.6+, different
     * people rarely exceed 0.4. False merges are worse than splits, so aim high.
     */
    const val SAME_PERSON_COSINE = 0.6f

    fun floatsToBytes(f: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        f.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun bytesToFloats(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }

    fun l2Normalize(f: FloatArray): FloatArray {
        var sum = 0.0
        for (v in f) sum += v * v
        val norm = sqrt(sum).toFloat()
        return if (norm > 0f) FloatArray(f.size) { f[it] / norm } else f
    }

    /** Cosine similarity; assumes nothing about normalization. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding size mismatch" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /**
     * Index of the centroid closest to [embedding] when its cosine reaches
     * [threshold], else -1 (a new person should be created).
     */
    fun bestMatch(
        embedding: FloatArray,
        centroids: List<FloatArray>,
        threshold: Float = SAME_PERSON_COSINE
    ): Int {
        var best = -1
        var bestSim = threshold
        centroids.forEachIndexed { i, c ->
            val sim = cosine(embedding, c)
            if (sim >= bestSim) {
                bestSim = sim
                best = i
            }
        }
        return best
    }

    /** Running-mean centroid after adding one more member; re-normalized. */
    fun updatedCentroid(old: FloatArray, oldCount: Int, newEmbedding: FloatArray): FloatArray {
        require(oldCount >= 0 && old.size == newEmbedding.size)
        val merged = FloatArray(old.size) { (old[it] * oldCount + newEmbedding[it]) / (oldCount + 1) }
        return l2Normalize(merged)
    }
}
