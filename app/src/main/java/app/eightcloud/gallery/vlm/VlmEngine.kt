package app.eightcloud.gallery.vlm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wraps the Gemma engine. Every action is serialized by one mutex (overlapping load/ask/close
 * crashed natively in testing). The engine is closed after cancelProcess with settle delays.
 */
class VlmEngine(private val context: Context) {
    private val lock = Mutex()
    private var engine: Engine? = null
    @Volatile private var active: Conversation? = null

    private val prompt = "Look at this image. Answer in exactly this format, nothing else:\n" +
        "MEME: yes or no (image with caption text, or a meme template)\n" +
        "DRAWN: yes or no (drawing, cartoon, anime, manga or illustration)\n" +
        "SCREENSHOT: yes or no (phone or computer screenshot)\n" +
        "DESCRIPTION: at most 12 words"

    suspend fun load() = lock.withLock {
        if (engine != null) return@withLock
        withContext(Dispatchers.Default) {
            var last: Throwable? = null
            for (vb in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
                try {
                    val e = Engine(
                        EngineConfig(
                            modelPath = VlmModel.file(context).absolutePath,
                            backend = Backend.CPU(),
                            visionBackend = vb,
                            maxNumTokens = 4000
                        )
                    )
                    e.initialize()
                    engine = e
                    return@withContext
                } catch (t: Throwable) { last = t }
            }
            throw IllegalStateException("Model failed to load: ${last?.message}")
        }
    }

    /** Returns the parsed verdict, or null for refusal / malformed output / generation failure. */
    suspend fun analyze(uri: Uri): VlmVerdict? = lock.withLock {
        val e = engine ?: return@withLock null
        val img = withContext(Dispatchers.IO) { prepare(uri) } ?: return@withLock null
        try {
            withContext(Dispatchers.Default) {
                val sb = StringBuilder()
                e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))).use { conv ->
                    active = conv
                    try {
                        conv.sendMessageAsync(Contents.of(Content.ImageFile(img.absolutePath), Content.Text(prompt)))
                            .collect { sb.append(it.toString()) }
                    } finally { active = null }
                }
                delay(300)
                VlmParse.parse(sb.toString())
            }
        } finally { img.delete() }
    }

    suspend fun close() = lock.withLock {
        val e = engine ?: return@withLock
        withContext(Dispatchers.Default) {
            runCatching { active?.cancelProcess() }
            delay(500)
            runCatching { e.close() }
            delay(800)
        }
        engine = null
    }

    private fun prepare(uri: Uri): File? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / (s * 2) >= 768 || b.outHeight / (s * 2) >= 768) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val f = File(context.cacheDir, "vlm_in.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        f
    }.getOrNull()
}
