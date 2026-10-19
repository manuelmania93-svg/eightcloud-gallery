package app.eightcloud.gallery.labels

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * NSFW scoring with the open_nsfw TFLite model (Yahoo open_nsfw, bundled as
 * assets/nsfw.tflite, same build as devzwy/open_nsfw_android, Apache-2.0).
 * Input: 224x224 center crop, BGR order, VGG mean subtracted.
 * Output: [sfw, nsfw] probabilities; we keep the nsfw score.
 */
class NsfwScorer(context: Context) {

    private val interpreter: Interpreter by lazy {
        val fd = context.assets.openFd("nsfw.tflite")
        Interpreter(
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        )
    }

    /** Returns the nsfw probability 0..1, or -1f on any failure. */
    fun score(bitmap: Bitmap): Float = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val left = ((scaled.width - 224) / 2).coerceAtLeast(0)
        val top = ((scaled.height - 224) / 2).coerceAtLeast(0)
        val input = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        val pixels = IntArray(224 * 224)
        scaled.getPixels(pixels, 0, 224, left, top, 224, 224)
        for (color in pixels) {
            input.putFloat((color and 0xFF) - 103.939f)          // B
            input.putFloat(((color shr 8) and 0xFF) - 116.779f)  // G
            input.putFloat(((color shr 16) and 0xFF) - 123.68f)  // R
        }
        input.rewind()
        val output = Array(1) { FloatArray(2) }
        interpreter.run(input, output)
        output[0][1]
    }.getOrDefault(-1f)
}
