package app.eightcloud.gallery.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

/** Pure bitmap operations for the basic editor. Never touches the original file. */
object ImageOps {

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flipHorizontal(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        // Fractions 0..1 of the source size.
        val x = (src.width * left).toInt().coerceIn(0, src.width - 1)
        val y = (src.height * top).toInt().coerceIn(0, src.height - 1)
        val w = (src.width * (right - left)).toInt().coerceIn(1, src.width - x)
        val h = (src.height * (bottom - top)).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /**
     * Brightness/contrast/saturation in one pass.
     * brightness/contrast/saturation: -1f..1f (0f = unchanged).
     */
    fun adjust(src: Bitmap, brightness: Float, contrast: Float, saturation: Float): Bitmap {
        val cm = ColorMatrix()
        val sat = ColorMatrix()
        sat.setSaturation((1f + saturation).coerceIn(0f, 2f))
        cm.postConcat(sat)
        val c = 1f + contrast
        val translate = 128f * (1f - c) + 255f * brightness
        val con = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(con)
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Simple named filters as ColorMatrix presets. */
    fun filter(src: Bitmap, name: String): Bitmap = when (name) {
        "Mono" -> adjust(src, 0f, 0f, -1f)
        "Warm" -> tint(src, 1.1f, 1.0f, 0.9f)
        "Cool" -> tint(src, 0.9f, 1.0f, 1.1f)
        "Fade" -> adjust(src, 0.08f, -0.15f, -0.2f)
        else -> src
    }

    private fun tint(src: Bitmap, r: Float, g: Float, b: Float): Bitmap {
        val cm = ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    val FILTER_NAMES = listOf("Original", "Mono", "Warm", "Cool", "Fade")
}
