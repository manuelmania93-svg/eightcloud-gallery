package app.eightcloud.gallery.labels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * FaceNet 128-d face embeddings, fully on-device.
 * Model: assets/facenet.tflite (Apache-2.0 distribution, see assets/facenet.LICENSE.txt).
 * Input: 160x160 RGB, per-image standardization ((x - mean) / std) as in the
 * FaceNet paper. Output: 128-d vector, L2-normalized here so cosine == dot.
 */
class FaceEmbedder(context: Context) {

    companion object {
        const val IMG_SIZE = 160
    }

    private val interpreter: Interpreter by lazy {
        val fd = context.assets.openFd("facenet.tflite")
        Interpreter(
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            ),
            Interpreter.Options().apply {
                numThreads = 4
                setUseXNNPACK(true)
            }
        )
    }

    /** Embedding for the face in [box] (clamped to the bitmap), or null on failure. */
    fun embed(bitmap: Bitmap, box: Rect): FloatArray? = runCatching {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, IMG_SIZE, IMG_SIZE, true)

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        scaled.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        val floats = FloatArray(pixels.size * 3)
        var mean = 0.0
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            floats[i * 3] = r; floats[i * 3 + 1] = g; floats[i * 3 + 2] = b
            mean += (r + g + b) / 3.0
        }
        mean /= pixels.size
        var stdSum = 0.0
        for (i in pixels.indices) {
            for (k in 0..2) {
                val d = floats[i * 3 + k] - mean
                stdSum += d * d
            }
        }
        var std = sqrt(stdSum / (pixels.size * 3))
        std = max(std, 1.0 / sqrt((pixels.size * 3).toDouble()))
        val input = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (v in floats) input.putFloat(((v - mean) / std).toFloat())
        input.rewind()

        val output = Array(1) { FloatArray(FaceClusterer.DIM) }
        interpreter.run(input, output)
        FaceClusterer.l2Normalize(output[0])
    }.getOrNull()
}
