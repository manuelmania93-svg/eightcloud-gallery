package app.eightcloud.gallery.labels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.FloatBuffer

/** OpenCV SFace, Apache-2.0 model-specific license bundled in assets. */
class FaceEmbedder(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        env.createSession(context.assets.open("sface.onnx").use { it.readBytes() }, options)
    }

    /** Five-landmark alignment, followed by RGB NCHW 112x112 inference. */
    fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        fun point(type: Int): PointF? = face.getLandmark(type)?.position
        val eyes = listOfNotNull(point(FaceLandmark.LEFT_EYE), point(FaceLandmark.RIGHT_EYE)).sortedBy { it.x }
        val mouth = listOfNotNull(point(FaceLandmark.MOUTH_LEFT), point(FaceLandmark.MOUTH_RIGHT)).sortedBy { it.x }
        val nose = point(FaceLandmark.NOSE_BASE) ?: return null
        if (eyes.size != 2 || mouth.size != 2) return null
        val src = listOf(eyes[0], eyes[1], nose, mouth[0], mouth[1])
        val dst = listOf(
            PointF(38.2946f, 51.6963f), PointF(73.5318f, 51.5014f),
            PointF(56.0252f, 71.7366f), PointF(41.5493f, 92.3655f), PointF(70.7299f, 92.2041f)
        )
        val sx = src.map { it.x }.average().toFloat()
        val sy = src.map { it.y }.average().toFloat()
        val dx = dst.map { it.x }.average().toFloat()
        val dy = dst.map { it.y }.average().toFloat()
        var den = 0f; var aa = 0f; var bb = 0f
        for (i in src.indices) {
            val x = src[i].x - sx; val y = src[i].y - sy
            val u = dst[i].x - dx; val v = dst[i].y - dy
            den += x * x + y * y
            aa += x * u + y * v
            bb += x * v - y * u
        }
        if (den <= 0f) return null
        val a = aa / den; val b = bb / den
        val matrix = Matrix().apply {
            setValues(floatArrayOf(a, -b, dx - a * sx + b * sy, b, a, dy - b * sx - a * sy, 0f, 0f, 1f))
        }
        val aligned = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        try {
            Canvas(aligned).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            return embedAligned(aligned)
        } finally {
            aligned.recycle()
        }
    }

    /** Input is already aligned. Values stay in 0..255, matching OpenCV's reference. */
    internal fun embedAligned(bitmap: Bitmap): FloatArray {
        require(bitmap.width == 112 && bitmap.height == 112)
        val pixels = IntArray(112 * 112)
        bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val values = FloatArray(pixels.size * 3)
        pixels.forEachIndexed { i, c ->
            values[i] = ((c shr 16) and 255).toFloat()
            values[pixels.size + i] = ((c shr 8) and 255).toFloat()
            values[2 * pixels.size + i] = (c and 255).toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, 3, 112, 112)).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                val output = FloatArray(buffer.remaining())
                buffer.get(output)
                check(output.size == FaceClusterer.DIM && output.all { it.isFinite() })
                return FaceClusterer.l2Normalize(output)
            }
        }
    }

    override fun close() = session.close()
}
