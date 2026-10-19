package app.eightcloud.gallery

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eightcloud.gallery.labels.FaceEmbedder
import app.eightcloud.gallery.labels.FaceClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceEmbedderTest {
    @Test
    fun bundledSFaceRunsAndProducesNormalized128Vector() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF997755.toInt())
        try {
            FaceEmbedder(context).use { embedder ->
                val first = embedder.embedAligned(bitmap)
                val second = embedder.embedAligned(bitmap)
                assertEquals(128, first.size)
                assertTrue(first.all { it.isFinite() })
                assertEquals(1f, FaceClusterer.cosine(first, second), 0.001f)
                assertEquals(1.0, first.sumOf { (it * it).toDouble() }, 0.001)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
