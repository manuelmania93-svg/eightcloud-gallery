package app.eightcloud.gallery.labels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceClustererTest {

    private fun emb(seed: Int): FloatArray {
        // Deterministic pseudo-embedding, L2-normalized.
        val raw = FloatArray(FaceClusterer.DIM) { ((seed * (it + 7)) % 31 - 15).toFloat() }
        return FaceClusterer.l2Normalize(raw)
    }

    private fun noisy(base: FloatArray, noise: Float): FloatArray {
        val out = FloatArray(base.size) { base[it] + noise * (((it * 13) % 7) - 3) }
        return FaceClusterer.l2Normalize(out)
    }

    @Test
    fun byteRoundTripPreservesValues() {
        val f = emb(3)
        val back = FaceClusterer.bytesToFloats(FaceClusterer.floatsToBytes(f))
        assertEquals(f.size, back.size)
        for (i in f.indices) assertEquals(f[i], back[i], 1e-6f)
    }

    @Test
    fun cosineOfIdenticalIsOne() {
        val a = emb(5)
        assertEquals(1f, FaceClusterer.cosine(a, a), 1e-4f)
    }

    @Test
    fun samePersonMatchesAboveThreshold() {
        val a = emb(11)
        val b = noisy(a, 0.01f)
        assertTrue(FaceClusterer.cosine(a, b) >= FaceClusterer.SAME_PERSON_COSINE)
        assertEquals(0, FaceClusterer.bestMatch(b, listOf(a)))
    }

    @Test
    fun differentPersonDoesNotMatch() {
        val a = emb(1)
        val b = emb(2)
        assertTrue(FaceClusterer.cosine(a, b) < FaceClusterer.SAME_PERSON_COSINE)
        assertEquals(-1, FaceClusterer.bestMatch(b, listOf(a)))
    }

    @Test
    fun bestMatchPicksNearestCentroid() {
        val a = emb(1)
        val b = emb(2)
        val probe = noisy(b, 0.005f)
        assertEquals(1, FaceClusterer.bestMatch(probe, listOf(a, b)))
    }

    @Test
    fun centroidUpdateStaysNormalizedAndCloser() {
        val a = emb(7)
        val b = noisy(a, 0.02f)
        val updated = FaceClusterer.updatedCentroid(a, 1, b)
        var norm = 0.0
        for (v in updated) norm += v * v
        assertEquals(1.0, norm, 1e-4)
        assertTrue(FaceClusterer.cosine(updated, b) > FaceClusterer.cosine(a, b))
    }
}
