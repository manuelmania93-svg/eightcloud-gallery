package app.eightcloud.vlmtest

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Throwaway feasibility test for an on-device vision-language model (Gemma 4 E2B via LiteRT-LM).
 * Not part of the gallery app. Measures load time, memory, time-to-first-token, total time and
 * temperature per photo, so the numbers come from the real phone.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var question: EditText
    private val results = StringBuilder()
    private var engine: Engine? = null
    private var backendName = ""
    private var photos: List<File> = emptyList()
    private var job: Job? = null

    private var useGpuVariant = false
    private val modelName get() = if (useGpuVariant) "gemma-4-E2B-it-gpu.litertlm" else "gemma-4-E2B-it.litertlm"
    private val modelUrl get() = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$modelName"
    private val modelBytes get() = if (useGpuVariant) 2_008_432_640L else 2_588_147_712L
    // SHA-256 from the Hugging Face LFS record of each file.
    private val modelSha get() = if (useGpuVariant) "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"
        else "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private val modelFile get() = File(filesDir, modelName)
    private val oldModelFile get() = File(getExternalFilesDir(null), modelName)

    private val prompt = "Look at this image. Answer in exactly this format, nothing else:\n" +
        "MEME: yes or no (image with caption text, or a meme template)\n" +
        "DRAWN: yes or no (drawing, cartoon, anime, manga or illustration)\n" +
        "SCREENSHOT: yes or no (phone or computer screenshot)\n" +
        "DESCRIPTION: at most 12 words"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fun btn(text: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
        }
        btn("1. Download model (Wi-Fi only, 2.6 GB)") { download() }
        btn("0. Switch model file (generic 2.6 GB / GPU variant 2.0 GB)") {
            useGpuVariant = !useGpuVariant
            say("Model file now: $modelName (${modelBytes / 1_000_000} MB). On disk: ${if (modelFile.exists()) modelFile.length() / 1_000_000 else -1} MB. Verified: ${isVerified()}")
        }
        btn("1c. Verify model SHA-256 (required before load)") { verify() }
        btn("1b. Move old model to internal storage (no re-download)") { moveOld() }
        btn("2a. Load model on CPU") { load(false) }
        btn("2b. Load model on GPU") { load(true) }
        btn("3. Pick photos (up to 30)") { pick() }
        btn("4. Run benchmark on picked photos") { benchmark() }
        question = EditText(this).apply {
            hint = "Ask AI (text only if no photo picked)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(question)
        btn("5. Ask AI") { ask() }
        btn("Delete current model file") { modelFile.delete(); verifiedMarker.delete(); say("Deleted $modelName.") }
        btn("6. Memory / mmap diagnostics") { diagnostics() }
        btn("Copy results") { copyResults() }
        btn("Stop") { job?.cancel(); say("stopped") }
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)
        setContentView(root)
        say("Device: ${android.os.Build.MODEL} (${android.os.Build.HARDWARE}), RAM " +
            "${(getSystemService(ACTIVITY_SERVICE) as ActivityManager).let { am ->
                ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_000_000
            }} MB")
        say(if (modelFile.exists()) "Model file present: ${modelFile.length() / 1_000_000} MB" else "Model not downloaded yet")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { engine?.close() }
        scope.cancel()
    }

    private fun say(s: String) {
        runOnUiThread {
            log.append(s + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun rec(s: String) {
        results.append(s).append('\n')
        say(s)
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun tempC(): Float {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun battery(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun pssMb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024L

    private fun download() {
        if (!onWifi()) { say("Not on unmetered Wi-Fi. Download refused."); return }
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = modelFile
                    var have = if (f.exists()) f.length() else 0L
                    if (have >= modelBytes) { say("Model already complete."); return@runCatching }
                    val c = URL(modelUrl).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    val code = c.responseCode
                    if (code != 200 && code != 206) { say("HTTP $code"); return@runCatching }
                    if (code == 200 && have > 0) { f.delete(); have = 0 }
                    say("Downloading from ${have / 1_000_000} MB ...")
                    var last = SystemClock.elapsedRealtime()
                    c.inputStream.use { input ->
                        java.io.FileOutputStream(f, have > 0).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                val now = SystemClock.elapsedRealtime()
                                if (now - last > 5000) { last = now; say("  ${have * 100 / modelBytes}% (${have / 1_000_000} MB)") }
                            }
                        }
                    }
                    say(if (f.length() == modelBytes) "Download complete and size matches (${f.length()} bytes). Press 1c to verify."
                        else "Size mismatch: ${f.length()} vs expected $modelBytes. Press download again to resume.")
                }.onFailure { say("Download error: ${it.message}. Press download again to resume.") }
            }
        }
    }

    private fun load(gpu: Boolean) {
        if (!modelFile.exists() || modelFile.length() != modelBytes) { say("Model missing or incomplete (${if (modelFile.exists()) modelFile.length() / 1_000_000 else -1} MB of ${modelBytes / 1_000_000} MB)."); return }
        if (!isVerified()) { say("Model not SHA-256 verified yet. Press 1c first."); return }
        job?.cancel()
        job = scope.launch {
            runCatching { engine?.close() }
            engine = null
            backendName = if (gpu) "GPU" else "CPU"
            rec("--- LOAD $backendName --- $modelName ${modelFile.length() / 1_000_000} MB, temp ${tempC()}C battery ${battery()}% PSS ${pssMb()} MB")
            val t0 = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Default) {
                runCatching {
                    val cfg = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
                        cacheDir = cacheDir.absolutePath
                    )
                    val e = Engine(cfg)
                    e.initialize()
                    engine = e
                }.onFailure { rec("LOAD FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            }
            if (engine != null) {
                rec("loaded in ${(SystemClock.elapsedRealtime() - t0) / 1000.0} s, PSS ${pssMb()} MB")
                val r = withContext(Dispatchers.Default) { runCatching { runText("Say hi in three words.") } }
                r.onSuccess { (txt, first, total) -> rec("TEXT SMOKE TEST OK first=${first}ms total=${total}ms :: ${txt.replace("\n", " ")}") }
                    .onFailure { rec("TEXT SMOKE TEST FAILED (engine loaded but unusable): ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("fine for a throwaway test app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        data.data?.let { uris += it }
        scope.launch {
            photos = withContext(Dispatchers.IO) { uris.take(30).mapIndexedNotNull { i, u -> prepare(u, i) } }
            say("Picked ${photos.size} photos (downscaled to 768 px).")
        }
    }

    private fun prepare(uri: Uri, idx: Int): File? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / (s * 2) >= 768 || b.outHeight / (s * 2) >= 768) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val f = File(cacheDir, "p$idx.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f
    }.getOrNull()

    private val verifiedMarker get() = File(filesDir, "$modelName.verified")

    private fun isVerified(): Boolean {
        val f = modelFile
        val m = verifiedMarker
        return f.exists() && m.exists() && m.readText().trim() == "${f.length()}:${f.lastModified()}:$modelSha"
    }

    private fun verify() {
        val f = modelFile
        if (!f.exists()) { say("No model file."); return }
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    say("Hashing $modelName (${f.length() / 1_000_000} MB) ...")
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    f.inputStream().use { i ->
                        val buf = ByteArray(1 shl 20)
                        while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    if (f.length() == modelBytes && hex == modelSha) {
                        verifiedMarker.writeText("${f.length()}:${f.lastModified()}:$modelSha")
                        rec("VERIFY OK $modelName: size ${f.length()} and SHA-256 match Hugging Face.")
                    } else {
                        verifiedMarker.delete()
                        rec("VERIFY FAILED $modelName: size ${f.length()} (expected $modelBytes), sha ${hex.take(12)}... (expected ${modelSha.take(12)}...). Delete and re-download.")
                    }
                }.onFailure { rec("Verify error: ${it.message}") }
            }
        }
    }

    private suspend fun runText(text: String): Triple<String, Long, Long> {
        val e = engine ?: error("engine not loaded")
        val sb = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        e.createConversation().use { conv ->
            conv.sendMessageAsync(Contents.of(Content.Text(text))).collect { chunk ->
                if (first < 0) first = SystemClock.elapsedRealtime() - t0
                sb.append(chunk.toString())
            }
        }
        return Triple(sb.toString().trim(), first, SystemClock.elapsedRealtime() - t0)
    }

    private fun moveOld() {
        val src = oldModelFile
        if (!src.exists()) { say("No old model on external storage."); return }
        if (modelFile.exists() && modelFile.length() == modelBytes) { say("Internal model already complete."); return }
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val free = filesDir.usableSpace
                    say("Free internal space: ${free / 1_000_000} MB, copying ${src.length() / 1_000_000} MB ...")
                    if (free < src.length() + 300_000_000L) { say("Not enough free internal storage."); return@runCatching }
                    src.inputStream().use { i -> modelFile.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    if (modelFile.length() == modelBytes) { src.delete(); say("Moved. Size matches. Old copy deleted. Press 1c to verify.") }
                    else say("Copy size mismatch: ${modelFile.length()}")
                }.onFailure { say("Move error: ${it.message}") }
            }
        }
    }

    private fun diagnostics() {
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) {
                rec("--- DIAGNOSTICS --- model=$modelName verified=${isVerified()}")
                rec("internal free ${filesDir.usableSpace / 1_000_000} MB; model internal ${if (modelFile.exists()) modelFile.length() / 1_000_000 else -1} MB; model external ${if (oldModelFile.exists()) oldModelFile.length() / 1_000_000 else -1} MB")
                rec("SDK ${android.os.Build.VERSION.SDK_INT}, ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                runCatching { File("/proc/self/limits").readLines().filter { it.startsWith("Max address") || it.startsWith("Max locked") || it.startsWith("Max open") }.forEach { rec(it) } }
                runCatching { File("/proc/meminfo").readLines().take(3).forEach { rec(it) } }
                runCatching { File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.forEach { rec(it) } }
                runCatching { rec("overcommit=" + File("/proc/sys/vm/overcommit_memory").readText().trim() + " max_map_count=" + File("/proc/sys/vm/max_map_count").readText().trim()) }
                for (f in listOf(modelFile, oldModelFile)) {
                    if (!f.exists()) continue
                    // mmap test on the real file: map 3 slices of 700 MB (JVM FileChannel.map is limited to 2 GB each)
                    runCatching {
                        java.io.RandomAccessFile(f, "r").use { raf ->
                            val ch = raf.channel
                            val len = ch.size()
                            val maps = ArrayList<java.nio.MappedByteBuffer>()
                            var off = 0L
                            val slice = 700L * 1024 * 1024
                            while (off < len) {
                                val n = minOf(slice, len - off)
                                maps += ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, off, n)
                                off += n
                            }
                            // touch one byte per 64 MB so pages really fault in
                            var sum = 0
                            for (m in maps) { var i = 0; while (i < m.limit()) { sum += m.get(i); i += 64 * 1024 * 1024 } }
                            rec("MMAP OK ${f.parentFile?.name}/${f.name}: ${maps.size} slices, ${len / 1_000_000} MB (checksum $sum)")
                        }
                    }.onFailure { rec("MMAP FAILED ${f.parentFile?.name}: ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }
    }

    private suspend fun runOne(file: File, text: String): Triple<String, Long, Long> {
        val e = engine ?: error("engine not loaded")
        val sb = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        e.createConversation().use { conv ->
            conv.sendMessageAsync(Contents.of(Content.ImageFile(file.absolutePath), Content.Text(text)))
                .collect { chunk ->
                    if (first < 0) first = SystemClock.elapsedRealtime() - t0
                    sb.append(chunk.toString())
                }
        }
        return Triple(sb.toString().trim(), first, SystemClock.elapsedRealtime() - t0)
    }

    private fun benchmark() {
        if (engine == null) { say("Load the model first."); return }
        if (photos.isEmpty()) { say("Pick photos first."); return }
        job?.cancel()
        job = scope.launch {
            rec("--- BENCHMARK $backendName n=${photos.size} --- temp ${tempC()}C battery ${battery()}%")
            val totals = mutableListOf<Long>()
            val firsts = mutableListOf<Long>()
            for ((i, f) in photos.withIndex()) {
                val r = withContext(Dispatchers.Default) { runCatching { runOne(f, prompt) } }
                r.onSuccess { (txt, first, total) ->
                    totals += total; firsts += first
                    rec("#$i first=${first}ms total=${total}ms temp=${tempC()}C :: ${txt.replace("\n", " | ")}")
                }.onFailure { rec("#$i ERROR ${it.javaClass.simpleName}: ${it.message}") }
            }
            if (totals.isNotEmpty()) {
                val s = totals.sorted()
                rec("SUMMARY $backendName: median total ${s[s.size / 2]} ms, p90 ${s[(s.size * 9 / 10).coerceAtMost(s.size - 1)]} ms, " +
                    "mean first-token ${firsts.average().toLong()} ms, end temp ${tempC()}C battery ${battery()}% PSS ${pssMb()} MB")
            }
        }
    }

    private fun ask() {
        if (engine == null) { say("Load the model first."); return }
        val f = photos.lastOrNull()
        val q = question.text.toString().ifBlank { if (f != null) "Describe this photo." else "Say hi in three words." }
        job?.cancel()
        job = scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { if (f != null) runOne(f, q) else runText(q) } }
            r.onSuccess { (txt, first, total) -> rec("ASK first=${first}ms total=${total}ms :: Q: $q :: A: ${txt.replace("\n", " ")}") }
                .onFailure { rec("ASK ERROR ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun copyResults() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vlm results", results.toString()))
        runCatching { File(getExternalFilesDir(null), "results.txt").writeText(results.toString()) }
        say("Results copied to clipboard (${results.length} chars).")
    }
}
