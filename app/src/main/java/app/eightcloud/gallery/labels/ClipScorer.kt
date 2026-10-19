package app.eightcloud.gallery.labels

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/** Pure math on CLIP embeddings: zero-shot category masses. Unit-testable. */
class ClipPrompts(val cats: List<String>, val names: List<String>, val matrix: Array<FloatArray>) {

    /** Softmax over all prompts (CLIP logit scale 100), summed per category. */
    fun scores(emb: FloatArray, negatives: Map<String, List<FloatArray>> = emptyMap()): Map<String, Float> {
        val sims = FloatArray(matrix.size) { 100f * dot(matrix[it], emb) }
        val max = sims.max()
        var sum = 0f
        val ex = FloatArray(sims.size) { exp(sims[it] - max).also { v -> sum += v } }
        val out = LinkedHashMap<String, Float>()
        for (i in ex.indices) out[cats[i]] = (out[cats[i]] ?: 0f) + ex[i] / sum
        // Learned from "Not this category" taps: near-duplicates of a rejected photo lose that category.
        for ((cat, rejected) in negatives) {
            val v = out[cat] ?: continue
            if (rejected.any { dot(it, emb) >= NEGATIVE_COS }) out[cat] = v * 0.25f
        }
        return out
    }

    /** The n best-matching prompts with their softmax share (debug view). */
    fun top(emb: FloatArray, n: Int): List<Pair<String, Float>> {
        val sims = FloatArray(matrix.size) { 100f * dot(matrix[it], emb) }
        val max = sims.max()
        var sum = 0f
        val ex = FloatArray(sims.size) { exp(sims[it] - max).also { v -> sum += v } }
        return ex.indices.sortedByDescending { ex[it] }.take(n).map { names[it] to ex[it] / sum }
    }

    companion object {
        const val DIM = 512
        const val NEGATIVE_COS = 0.92f
        /** Bump when the prompt list changes so stored scores are recomputed. */
        const val PROMPT_VERSION = 1

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            val d = sqrt(n).coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / d }
        }

        fun fromBytes(promptLines: List<String>, bin: ByteArray): ClipPrompts {
            val cats = promptLines.map { it.substringBefore('|') }
            val names = promptLines.map { it.substringAfter('|') }
            val fb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val m = Array(cats.size) { FloatArray(DIM).also { row -> fb.get(row) } }
            return ClipPrompts(cats, names, m)
        }

        fun toBlob(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(v)
            return bb.array()
        }

        fun fromBlob(b: ByteArray): FloatArray {
            val out = FloatArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }

        /** Replaces any previous CLIP pseudo-labels ("~cat:score") inside a label:confidence csv. */
        fun mergeIntoLabelConfs(labelConfs: String, scores: Map<String, Float>): String {
            val kept = labelConfs.split(',').filter { it.isNotBlank() && !it.startsWith("~") }
            val clip = scores.map { "~" + it.key + ":" + String.format(java.util.Locale.US, "%.2f", it.value) }
            return (kept + clip).joinToString(",")
        }

        fun load(context: Context): ClipPrompts {
            val lines = context.assets.open("clip_prompts.txt").bufferedReader().use { it.readText() }
                .split('\n').filter { it.isNotBlank() }
            val bin = context.assets.open("clip_text_embs.bin").use { it.readBytes() }
            return fromBytes(lines, bin)
        }
    }
}

/** CLIP ViT-B/32 image encoder (int8 ONNX) running on the ONNX runtime the app already ships. */
class ClipScorer(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()

    val prompts: ClipPrompts by lazy { ClipPrompts.load(context) }

    private val session: OrtSession by lazy {
        val target = File(context.filesDir, "clip_vision_v1.onnx")
        if (!target.exists() || target.length() < 1_000_000L) {
            val tmp = File(context.filesDir, "clip_vision_v1.tmp")
            context.assets.open("clip_vision.onnx").use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.renameTo(target)
        }
        env.createSession(
            target.absolutePath,
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        )
    }

    /** L2-normalized 512-d embedding, or null on failure. */
    fun embed(bitmap: Bitmap): FloatArray? = runCatching {
        val size = 224
        val scale = size.toFloat() / minOf(bitmap.width, bitmap.height)
        val w = maxOf(size, Math.round(bitmap.width * scale))
        val h = maxOf(size, Math.round(bitmap.height * scale))
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val left = (w - size) / 2
        val top = (h - size) / 2
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, left, top, size, size)
        if (scaled !== bitmap) scaled.recycle()
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val data = FloatArray(3 * size * size)
        for (i in px.indices) {
            val c = px[i]
            data[i] = (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            data[size * size + i] = (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            data[2 * size * size + i] = ((c and 0xFF) / 255f - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong()))
            .use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    val fb = (result[0] as OnnxTensor).floatBuffer
                    val v = FloatArray(ClipPrompts.DIM)
                    fb.get(v)
                    ClipPrompts.normalize(v)
                }
            }
    }.getOrNull()

    private val textSession: OrtSession by lazy {
        val target = File(context.filesDir, "clip_text_v1.onnx")
        if (!target.exists() || target.length() < 1_000_000L) {
            val tmp = File(context.filesDir, "clip_text_v1.tmp")
            context.assets.open("clip_text.onnx").use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.renameTo(target)
        }
        env.createSession(
            target.absolutePath,
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        )
    }

    private val tokenizer: ClipTokenizer by lazy { ClipTokenizer.load(context) }

    /** L2-normalized 512-d embedding of a free-text query, or null on failure. */
    fun embedText(text: String): FloatArray? = runCatching {
        val ids = tokenizer.encode(text)
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            .use { tensor ->
                textSession.run(mapOf("input_ids" to tensor)).use { result ->
                    val fb = (result[0] as OnnxTensor).floatBuffer
                    val v = FloatArray(ClipPrompts.DIM)
                    fb.get(v)
                    ClipPrompts.normalize(v)
                }
            }
    }.getOrNull()

    fun close() {
        runCatching { textSession.close() }
        runCatching { session.close() }
    }
}
