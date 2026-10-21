package app.eightcloud.gallery.edit

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageOpsTest {

    private fun bmp(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun rotate90SwapsDimensions() {
        val out = ImageOps.rotate90(bmp(100, 50))
        assertEquals(50, out.width)
        assertEquals(100, out.height)
    }

    @Test
    fun flipKeepsDimensions() {
        val out = ImageOps.flipHorizontal(bmp(100, 50))
        assertEquals(100, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun cropToHalfSize() {
        val out = ImageOps.crop(bmp(100, 100), 0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(50, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun monoFilterReturnsNewBitmap() {
        val src = bmp(10, 10)
        val out = ImageOps.filter(src, "Mono")
        assertNotSame(src, out)
        assertEquals(10, out.width)
    }

    @Test
    fun adjustKeepsSize() {
        val out = ImageOps.adjust(bmp(20, 10), 0.2f, 0.1f, -0.3f)
        assertEquals(20, out.width)
        assertEquals(10, out.height)
    }
}
