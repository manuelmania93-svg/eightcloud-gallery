package app.eightcloud.gallery.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore

/**
 * One editing session for a single photo. The original file is never
 * modified: saving always writes a new MediaStore entry next to it.
 */
class EditSession(private val context: Context, val sourceUri: Uri, val sourceName: String) {

    var rotationQuarterTurns = 0
    var flipped = false
    var brightness = 0f
    var contrast = 0f
    var saturation = 0f
    var filterName = "Original"
    // Crop as fractions of the (post-rotate) image; null = no crop.
    var cropRect: FloatArray? = null // [left, top, right, bottom]

    val isDirty: Boolean
        get() = rotationQuarterTurns != 0 || flipped || brightness != 0f ||
            contrast != 0f || saturation != 0f || filterName != "Original" || cropRect != null

    fun loadPreview(maxSize: Int = 1280): Bitmap? = load(maxSize)

    fun applyAll(src: Bitmap): Bitmap {
        var bmp = src
        repeat(((rotationQuarterTurns % 4) + 4) % 4) { bmp = ImageOps.rotate90(bmp) }
        if (flipped) bmp = ImageOps.flipHorizontal(bmp)
        cropRect?.let { r -> bmp = ImageOps.crop(bmp, r[0], r[1], r[2], r[3]) }
        if (filterName != "Original") bmp = ImageOps.filter(bmp, filterName)
        if (brightness != 0f || contrast != 0f || saturation != 0f) {
            bmp = ImageOps.adjust(bmp, brightness, contrast, saturation)
        }
        return bmp
    }

    /** Save the edited image as a NEW file; returns the new uri or null. */
    fun saveCopy(): Uri? {
        val full = load(4096) ?: return null
        val edited = applyAll(full)
        val baseName = sourceName.substringBeforeLast('.')
        val outName = baseName + "_edited.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, outName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                edited.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } ?: return null
        }.isSuccess
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun load(maxSize: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
