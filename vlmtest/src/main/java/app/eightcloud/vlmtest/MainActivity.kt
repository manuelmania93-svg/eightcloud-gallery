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
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
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
    private lateinit var statusView: TextView
    private var lastSize = 0L
    private var lastTick = 0L
    private var speed = ""

    private class Model(val label: String, val file: String, val url: String, val bytes: Long, val sha: String, val freshEngine: Boolean, val note: String)
    private val catalog = listOf(
        Model("A: Gemma 4 E2B official", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", false, "Google, Apache-2.0"),
        Model("B: Qwen3.5-2B-VL int8", "Qwen3.5-2B-VL_int8.litertlm",
            "https://huggingface.co/litert-community/Qwen3.5-2B/resolve/main/Qwen3.5-2B-VL_int8.litertlm",
            3_152_378_448L, "33d9d9014eb36f6349e41efd272596756380ff4d0bce2290d0b77fb39c487120", true, "Qwen, Apache-2.0, litert-community conversion"),
        Model("C: Huihui abliterated Gemma 4 E2B int8", "Huihui-gemma-4-E2B-it-abliterated.litertlm",
            "https://huggingface.co/edp1096/Huihui-gemma-4-E2B-it-abliterated-litert-lm/resolve/main/Huihui-gemma-4-E2B-it-abliterated.litertlm",
            5_247_716_576L, "ebd54bad60896b14acd717a12e0bf6b1020eb69a7c73d9de17a14ee18cf6087e", false, "community abliterated, Apache-2.0, unaudited")
    )
    private var modelIdx = 0
    /** One engine action at a time. Overlapping load/ask/close calls on the native engine crashed v0.1.16 (SIGSEGV). */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var activeConv: com.google.ai.edge.litertlm.Conversation? = null

    @Volatile private var engineUsed = false

    private val resultsFile get() = File(filesDir, "results_keep.txt")
    private val restartMarker get() = File(filesDir, "restart_reason.txt")

    private fun availMb(): Long = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.availMem / 1_000_000

    /** Frees ALL native memory for real by restarting the process. Log and results are on disk and shown again after the restart. */
    private fun restartProcess(reason: String) {
        rec("RESTARTING APP to free native memory: $reason (log and results are kept)")
        runCatching { restartMarker.writeText(reason) }
        val i = packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        android.os.Handler(mainLooper).postDelayed({
            startActivity(i)
            android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }, 700)
    }
    @Volatile private var actionName = ""
    @Volatile private var actionStart = 0L
    @Volatile private var actionStep = ""

    private fun exclusive(name: String = "model action", block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            both("Busy: '$actionName' has been running for ${(SystemClock.elapsedRealtime() - actionStart) / 1000} s. Step: ${actionStep.ifBlank { "-" }}. Wait for its result line, or press Stop (clears this lock).")
            return
        }
        val myStart = SystemClock.elapsedRealtime(); actionName = name; actionStart = myStart; actionStep = "starting"
        job = scope.launch {
            try { block() }
            catch (t: kotlinx.coroutines.CancellationException) { rec("'$name' cancelled."); answer("'$name' cancelled.") }
            catch (t: Throwable) { rec("'$name' FAILED: ${t.javaClass.simpleName}: ${t.message}"); answer("'$name' FAILED: ${t.javaClass.simpleName}: ${t.message}") }
            finally { if (actionStart == myStart) { busy.set(false); actionName = ""; actionStep = "" } }
        }
    }

    /** Stops generation, waits, then closes the engine and waits again so native threads can finish. */
    private suspend fun closeEngineSafely() {
        val e = engine ?: return
        withContext(Dispatchers.Default) {
            runCatching { activeConv?.cancelProcess() }
            kotlinx.coroutines.delay(500)
            runCatching { e.close() }
            kotlinx.coroutines.delay(800)
        }
        engine = null
        visionLoaded = false
    }
    private var panel: ScrollView? = null
    private lateinit var answerView: TextView
    private lateinit var answerScroll: ScrollView
    @Volatile private var wantVision = false
    private val cur get() = catalog[modelIdx]
    private val modelName get() = cur.file
    private val modelUrl get() = cur.url
    private val modelBytes get() = cur.bytes
    private val modelSha get() = cur.sha
    private fun freeMb() = filesDir.usableSpace / 1_000_000
    private val modelFile get() = File(filesDir, modelName)
    private val oldModelFile get() = File(getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")

    private val prompt = "Look at this image. Answer in exactly this format, nothing else:\n" +
        "MEME: yes or no (image with caption text, or a meme template)\n" +
        "DRAWN: yes or no (drawing, cartoon, anime, manga or illustration)\n" +
        "SCREENSHOT: yes or no (phone or computer screenshot)\n" +
        "DESCRIPTION: at most 12 words"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelIdx = getSharedPreferences("sel", Context.MODE_PRIVATE).getInt("idx", 0).coerceIn(0, catalog.size - 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        var inRow = 0
        fun btn(text: String, onClick: () -> Unit) {
            if (row == null || inRow == 2) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(row)
                inRow = 0
            }
            inRow++
            row!!.addView(Button(this).apply {
                this.text = text; textSize = 11f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(6, 2, 6, 2)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            })
        }
        btn("1. Download / resume selected model (Wi-Fi only)") { download() }
        btn("1a. Allow background (disable battery optimization)") {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) say("Battery optimization already disabled for this app.")
            else startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        btn("0. Select next model (A Gemma / B Qwen3.5 / C Huihui)") {
            if (Dl.busy.get()) { say("Download in progress. Press Stop first."); return@btn }
            if (busy.get()) { say("Busy: wait for the running model action to finish."); return@btn }
            exclusive("unload") { closeEngineSafely() }
            modelIdx = (modelIdx + 1) % catalog.size
            getSharedPreferences("sel", Context.MODE_PRIVATE).edit().putInt("idx", modelIdx).apply()
            say("Selected ${cur.label} (${cur.note}). ${modelBytes / 1_000_000} MB. On disk: ${if (modelFile.exists()) modelFile.length() / 1_000_000 else 0} MB. Verified: ${isVerified()}. Free storage: ${freeMb()} MB. Engine unloaded.")
        }
        btn("1d. Delete selected model and download it again") {
            if (Dl.busy.get()) { say("Press Stop first, wait for it to stop."); return@btn }
            if (busy.get()) { say("Busy: wait for the running model action to finish."); return@btn }
            exclusive("delete+redownload") { closeEngineSafely(); modelFile.delete(); verifiedMarker.delete(); say("Deleted ${cur.label}. Free storage now ${freeMb()} MB. Starting download."); download() }
        }
        btn("1c. Verify model SHA-256 (required before load)") { verify() }
        btn("1b. Move old model to internal storage (no re-download)") { moveOld() }
        btn("2a. Load model on CPU") { load(false) }
        btn("2b. Load model on GPU") { load(true) }
        btn("3. Pick photos (up to 30)") { pick() }
        btn("4. Run benchmark on picked photos") { benchmark() }
        question = EditText(this).apply {
            hint = "Question for the AI"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btn("Delete selected model file") { if (Dl.busy.get()) { say("Press Stop first, wait for it to stop, then delete."); return@btn }; if (busy.get()) { say("Busy: wait for the running model action to finish."); return@btn }; exclusive("delete") { closeEngineSafely(); modelFile.delete(); verifiedMarker.delete(); say("Deleted $modelName. Free storage ${freeMb()} MB.") } }
        btn("6. Memory / mmap diagnostics") { diagnostics() }
        btn("Copy results") { copyResults() }
        btn("Stop") { Dl.cancel = true; runCatching { activeConv?.cancelProcess() }; getSharedPreferences("dl", Context.MODE_PRIVATE).edit().clear().apply(); if (busy.get() || engine != null) { job?.cancel(); say("Stop: restarting the app to free the model memory for real."); restartProcess("Stop pressed during '$actionName'") } else say("stopped") }
        statusView = TextView(this).apply { textSize = 11f; setPadding(6, 4, 6, 4); maxLines = 3; setBackgroundColor(0xFFEEEEEE.toInt()) }
        root.addView(statusView, 0)
        log = TextView(this).apply { textSize = 12f; setTextColor(0xFF222222.toInt()); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        fun toggle(label: String, target: () -> android.view.View?) = Button(this).apply {
            text = label; textSize = 11f; isAllCaps = false; minHeight = 0; minimumHeight = 0; setPadding(4, 2, 4, 2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { target()?.let { it.visibility = if (it.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE } }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggle("Buttons on/off") { panel })
            addView(toggle("Answer on/off") { answerScroll })
            addView(toggle("Log on/off") { scroll })
        })
        root.addView(object : ScrollView(this) {
            // Sized to its content, but never taller than 45% of the screen; scrolls beyond that.
            override fun onMeasure(w: Int, h: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(buttons)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }.also { panel = it })
        answerView = TextView(this).apply { textSize = 18f; setTextColor(0xFF000000.toInt()); setBackgroundColor(0xFFFFFFE0.toInt()); setPadding(12, 8, 12, 8); setTextIsSelectable(true); text = "AI answers appear here (big text, you can select and copy).\n" }
        answerScroll = ScrollView(this).apply {
            addView(answerView)
            setBackgroundColor(0xFFFFFFE0.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f)
        }
        root.addView(answerScroll)
        root.addView(scroll)
        // Input at the bottom: its own full-width row, then two short buttons. Always visible, keyboard-safe (adjustResize).
        root.addView(question)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "5. Ask text only"; textSize = 12f; isAllCaps = false; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { ask(false) } })
            addView(Button(this@MainActivity).apply { text = "5b. Ask last photo"; textSize = 12f; isAllCaps = false; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { ask(true) } })
        })
        setContentView(root)
        scope.launch {
            while (true) { refreshStatus(); kotlinx.coroutines.delay(1000) }
        }
        showPrevious()
        run {
            val pmx = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pmx.isIgnoringBatteryOptimizations(packageName)) say("Hint: press 1a and allow, so the download survives leaving the app. Also set battery to Unrestricted in system app settings.")
            if (intent?.getBooleanExtra("demo", false) == true) {
                answerView.text = "Q: Describe this photo.\nA (4.2 s): MEME: no\nDRAWN: no\nSCREENSHOT: no\nDESCRIPTION: a man standing on a beach at sunset\n\n#2  (3.9 s)\nMEME: yes\nDRAWN: no\nSCREENSHOT: no\nDESCRIPTION: cartoon bear with text caption\n\n"
                if (intent?.getBooleanExtra("selftest", false) == true) {
                    answerView.text = "AI answers appear here (placeholder)"
                    showAskResult("hi", Result.success(Triple("Hello, friend!", 900L, 1700L)))
                    showAskResult("fail case", Result.failure(IllegalStateException("example error")))
                    answer("#3  (4.0 s)\n" + judge("I'm sorry, I can't describe that image."))
                    answer("Busy: 'ask' has been running for 12 s. Step: generating.")
                }
                for (i in 1..30) say("demo log line $i: first=1200ms total=4000ms temp=41.0C")
            }
        }
        autoResume()
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

    private fun refreshStatus() {
        while (true) { say(Dl.messages.poll() ?: break) }
        val f = modelFile
        val size = if (f.exists()) f.length() else 0L
        val now = SystemClock.elapsedRealtime()
        if (lastTick > 0 && now > lastTick && size >= lastSize) {
            val bps = (size - lastSize) * 1000 / (now - lastTick)
            speed = if (Dl.busy.get()) " | ${bps / 1_000_000}.${(bps / 100_000) % 10} MB/s" else ""
        }
        lastSize = size; lastTick = now
        val pct = size * 100 / modelBytes
        val state = when {
            Dl.busy.get() -> "DOWNLOADING"
            size == modelBytes && isVerified() -> "COMPLETE, VERIFIED (ready to load)"
            size == modelBytes -> "COMPLETE, NOT VERIFIED (press 1c)"
            size == 0L -> "NOT DOWNLOADED"
            else -> "INCOMPLETE (press 1 to resume)"
        }
        val eng = if (engine != null) " | engine loaded ($backendName)" else ""
        val short = cur.label.substringBefore(":") + ": " + cur.label.substringAfter(": ").take(28)
        val running = if (busy.get()) "\nRUNNING: $actionName, step: ${actionStep.ifBlank { "-" }}, ${(SystemClock.elapsedRealtime() - actionStart) / 1000} s" else ""
        statusView.text = "$short | free ${freeMb() / 1000} GB\n${size / 1_000_000}/${modelBytes / 1_000_000} MB ($pct%) $state$speed$eng$running"
    }

    private val liveLog get() = File(filesDir, "livelog.txt")
    private val prevLog get() = File(filesDir, "prevlog.txt")
    private val crashFile get() = File(filesDir, "lastcrash.txt")

    private fun showPrevious() {
        runCatching {
            val restarted = restartMarker.exists()
            val keptResults = if (restarted && resultsFile.exists()) resultsFile.readText() else ""
            runCatching { resultsFile.delete() }
            if (restarted) { rec("=== APP WAS RESTARTED: ${restartMarker.readText()} ==="); restartMarker.delete() }
            if (keptResults.isNotEmpty()) { results.append(keptResults); runCatching { resultsFile.writeText(keptResults) } }
            // keep the previous run's log, then start a fresh one
            if (liveLog.exists()) { liveLog.copyTo(prevLog, overwrite = true); liveLog.delete() }
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { crashFile.writeText("Uncaught in ${t.name}: ${android.util.Log.getStackTraceString(e).take(4000)}") }
                Runtime.getRuntime().halt(2)
            }
            if (crashFile.exists()) { rec("--- PREVIOUS JAVA CRASH ---\n" + crashFile.readText()); crashFile.delete() }
            if (prevLog.exists()) {
                val lines = prevLog.readLines().takeLast(if (restarted) 120 else 25)
                rec("--- LAST ${lines.size} LOG LINES OF PREVIOUS RUN ---")
                lines.forEach { rec(it.take(300)) }
            }
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            val names = mapOf(1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE", 6 to "ANR",
                7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE", 9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED",
                11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE", 16 to "PACKAGE_UPDATED")
            rec("--- EXIT REASONS (newest first) ---")
            for (x in exits) {
                rec("${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(x.timestamp))} reason=${names[x.reason] ?: x.reason} status=${x.status} pss=${x.pss / 1024}MB rss=${x.rss / 1024}MB importance=${x.importance} desc=${x.description}")
                if (x.reason == 5) {
                    runCatching {
                        val bytes = x.traceInputStream?.use { it.readBytes() } ?: ByteArray(0)
                        val txt = String(bytes, Charsets.ISO_8859_1)
                        val strs = Regex("[ -~]{6,}").findAll(txt).map { it.value }.filter { w ->
                            Regex("signal|SIG|abort|Abort|backtrace|\\.so|lib|fault|#\\d+|pc ", RegexOption.IGNORE_CASE).containsMatchIn(w)
                        }.take(40).toList()
                        rec("  native trace (${bytes.size} bytes, readable lines):")
                        strs.forEach { rec("  " + it.take(200)) }
                    }.onFailure { rec("  trace unavailable: ${it.message}") }
                }
            }
        }.onFailure { rec("previous-run info error: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun say(s: String) {
        runCatching { liveLog.appendText(s + "\n") }
        runOnUiThread {
            log.append(s + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** Writes to the log and to the big answer area, so nothing important stays hidden in the log. */
    private fun both(s: String) { say(s); answer(s) }

    private fun answer(s: String) {
        runOnUiThread {
            if (answerView.text.startsWith("AI answers appear here")) answerView.text = ""
            answerView.append(s + "\n\n")
            answerScroll.post { answerScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun rec(s: String) {
        results.append(s).append('\n')
        runCatching { resultsFile.appendText(s + "\n") }
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

    /** If a download was requested and is not finished (app was killed), resume it now. */
    private fun autoResume() {
        val prefs = getSharedPreferences("dl", Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: return
        val bytes = prefs.getLong("bytes", 0L)
        val f = File(filesDir, name)
        if (bytes <= 0L || (f.exists() && f.length() == bytes)) { prefs.edit().clear().apply(); return }
        if (Dl.busy.get()) return
        if (!onWifi()) { say("Unfinished download of $name (${(if (f.exists()) f.length() else 0L) / 1_000_000} of ${bytes / 1_000_000} MB). Not on unmetered Wi-Fi, not resuming."); return }
        say("Unfinished download of $name found. Resuming automatically.")
        runCatching { startForegroundService(Intent(this, DownloadService::class.java)) }
            .onFailure { say("Could not resume: ${it.message}. Press 1.") }
    }

    private fun download() {
        if (!onWifi()) { say("Not on unmetered Wi-Fi. Download refused."); return }
        if (Dl.busy.get()) { say("A download is already running. Ignoring this press."); return }
        val have = if (modelFile.exists()) modelFile.length() else 0L
        val need = (modelBytes - have) / 1_000_000
        say("Selected ${cur.label}. Needs ${need} MB more, free storage ${freeMb()} MB.")
        if (freeMb() < need + 500) { say("Not enough free storage (need ${need + 500} MB incl. 500 MB margin). Delete another model first (button 0 to select it, then Delete)."); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        startForegroundService(Intent(this, DownloadService::class.java)
            .putExtra("name", modelName).putExtra("url", modelUrl).putExtra("bytes", modelBytes))
        say("Download started in background service (notification shows progress). You can leave the app.")
    }

    @Volatile private var loadedGpu = false
    @Volatile private var visionLoaded = false

    private fun load(gpu: Boolean) {
        if (gpu && cur.freshEngine) { say("GPU is not supported for ${cur.label} on this phone (model card: Android GPU only verified on one Galaxy S26, 54-93 s init, 3-4 GB). Use 2a (CPU)."); return }
        if (availMb() < 3000L) { say("Only ${availMb()} MB RAM available (need about 3000 MB). Not loading. Close other apps or restart this app."); return }
        if (!modelFile.exists() || modelFile.length() != modelBytes) { say("Model missing or incomplete (${if (modelFile.exists()) modelFile.length() / 1_000_000 else -1} MB of ${modelBytes / 1_000_000} MB)."); return }
        if (!isVerified()) { say("Model not SHA-256 verified yet. Press 1c first."); return }
        exclusive("load ${if (gpu) "GPU" else "CPU"}") { loadEngine(gpu, vision = false, smoke = true) }
    }

    /** Loads the engine. vision=true also loads the vision encoder (GPU first as in Gallery, CPU if that fails). */
    private suspend fun loadEngine(gpu: Boolean, vision: Boolean, smoke: Boolean, quiet: Boolean = false) {
        closeEngineSafely()
        engine = null
        visionLoaded = false
        backendName = if (gpu) "GPU" else "CPU"
        loadedGpu = gpu
        wantVision = vision
        engineUsed = false
        if (!quiet) rec("--- LOAD $backendName vision=${if (vision) "on" else "off"} --- $modelName ${modelFile.length() / 1_000_000} MB, temp ${tempC()}C battery ${battery()}% PSS ${pssMb()} MB")
        val t0 = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) {
            val visionOptions: List<Backend?> = if (vision) (if (cur.freshEngine) listOf(Backend.CPU()) else listOf(Backend.GPU(), Backend.CPU())) else listOf(null)
            for (vb in visionOptions) {
                val ok = runCatching {
                    val cfg = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        visionBackend = vb,
                        maxNumTokens = 4000
                    )
                    if (!quiet) rec("config: backend=$backendName vision=${if (vb == null) "off" else if (vb is Backend.GPU) "GPU" else "CPU"} maxNumTokens=4000 cacheDir=default")
                    val e = Engine(cfg)
                    e.initialize()
                    engine = e
                    visionLoaded = vb != null
                }.onFailure { rec("LOAD FAILED: ${it.javaClass.simpleName}: ${it.message}"); answer("LOAD FAILED: ${it.javaClass.simpleName}: ${it.message}"); nativeLog("after load failure") }.isSuccess
                if (ok) break
            }
        }
        if (engine == null && !quiet) { restartProcess("load failed, engine memory may be stuck in this process"); return }
        if (engine != null) {
            if (!quiet) rec("loaded in ${(SystemClock.elapsedRealtime() - t0) / 1000.0} s, PSS ${pssMb()} MB")
            if (smoke) {
                val r = withContext(Dispatchers.Default) { runCatching { runText("Say hi in three words.") } }
                r.onSuccess { (txt, first, total) -> rec("TEXT SMOKE TEST OK first=${first}ms total=${total}ms :: ${txt.replace("\n", " ")}"); answer("Model loaded. Smoke test OK (${total} ms): ${stripThink(txt)}") }
                    .onFailure { rec("TEXT SMOKE TEST FAILED (engine loaded but unusable): ${it.javaClass.simpleName}: ${it.message}"); answer("SMOKE TEST FAILED: ${it.javaClass.simpleName}: ${it.message}") }
                nativeLog("after smoke test")
            }
        }
    }

    /** Makes sure the engine has the vision encoder; reloads automatically when needed. */
    private suspend fun ensureVision(): Boolean {
        if (engine != null && visionLoaded) return true
        if (engine == null && wantVision) rec("(engine was missing although vision was wanted: a previous load failed or was closed)")
        if (!modelFile.exists() || modelFile.length() != modelBytes || !isVerified()) { rec("Model missing or not verified. Download and verify first."); return false }
        rec("Vision encoder not loaded (engine=${engine != null}, visionLoaded=$visionLoaded, wantVision=$wantVision). Reloading ${if (engine != null) backendName else "CPU"} engine with vision (automatic).")
        loadEngine(if (engine != null) loadedGpu else false, vision = true, smoke = true)
        return engine != null && visionLoaded
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
        if (Dl.busy.get()) { say("Download in progress. Wait for COMPLETE, then press 1c."); return }
        val f = modelFile
        val modelName = this.modelName
        val modelBytes = this.modelBytes
        val modelSha = this.modelSha
        val marker = verifiedMarker
        if (!f.exists()) { say("No model file."); return }
        scope.launch {
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
                        marker.writeText("${f.length()}:${f.lastModified()}:$modelSha")
                        rec("VERIFY OK $modelName: size ${f.length()} and SHA-256 match Hugging Face.")
                    } else {
                        marker.delete()
                        rec("VERIFY FAILED $modelName: size ${f.length()} (expected $modelBytes), sha ${hex.take(12)}... (expected ${modelSha.take(12)}...). Delete and re-download.")
                    }
                }.onFailure { rec("Verify error: ${it.message}") }
            }
        }
    }

    private fun sampler() = ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0))

    /** Qwen3.5 keeps recurrent state across conversations on one Engine (model card note), so use a fresh Engine per request. */
    private suspend fun freshIfNeeded() {
        if (!cur.freshEngine || engine == null || !engineUsed) return
        actionStep = "building fresh engine"
        val gpu = loadedGpu; val vis = wantVision
        val t0 = SystemClock.elapsedRealtime()
        loadEngine(gpu, vis, false, quiet = true)
        actionStep = "generating"
        say("(fresh engine for this request: ${SystemClock.elapsedRealtime() - t0} ms)")
    }

    private fun stripThink(raw: String): String = raw.replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "").trim()

    private val refusal = Regex("""i (can('|’)?t|cannot|am unable to|won('|’)?t|'m not able to|am not able to)\b|i('|’)?m sorry|unable to (help|assist|describe)|as an ai|against my (guidelines|policy)|not (able|allowed) to (describe|help|assist)""", RegexOption.IGNORE_CASE)

    /** Cleans model output: strips think blocks and flags refusals or format failures, so they are never used as tags. */
    private fun judge(raw: String): String {
        val t = stripThink(raw)
        val hasFormat = Regex("""MEME:\s*(yes|no)""", RegexOption.IGNORE_CASE).containsMatchIn(t)
        return when {
            !hasFormat && refusal.containsMatchIn(t) -> "[REFUSAL - IGNORED, NO TAGS] $t"
            !hasFormat -> "[FORMAT FAIL - IGNORED] $t"
            else -> t
        }
    }

    private suspend fun runText(text: String): Triple<String, Long, Long> {
        freshIfNeeded()
        val e = engine ?: error("engine not loaded")
        val sb = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        e.createConversation(sampler()).use { conv ->
            activeConv = conv
            try {
                conv.sendMessageAsync(Contents.of(Content.Text(text))).collect { chunk ->
                    if (first < 0) first = SystemClock.elapsedRealtime() - t0
                    sb.append(chunk.toString())
                }
            } finally { activeConv = null }
        }
        engineUsed = true
        kotlinx.coroutines.delay(300)
        return Triple(sb.toString().trim(), first, SystemClock.elapsedRealtime() - t0)
    }

    private fun moveOld() {
        val src = oldModelFile
        if (!src.exists()) { say("No old model on external storage."); return }
        if (modelFile.exists() && modelFile.length() == modelBytes) { say("Internal model already complete."); return }
        scope.launch {
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

    private fun nativeLog(label: String) {
        runCatching {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-t", "1500"))
            val lines = proc.inputStream.bufferedReader().readLines()
            val keys = Regex("section|map|embed|litert|per_layer|memory|alloc|fail|error|warn", RegexOption.IGNORE_CASE)
            val hits = lines.filter { keys.containsMatchIn(it) }.takeLast(30)
            rec("--- NATIVE LOG ($label): ${hits.size} matching of ${lines.size} lines ---")
            hits.forEach { rec(it.take(300)) }
        }.onFailure { rec("NATIVE LOG unavailable: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun diagnostics() {
        scope.launch {
            withContext(Dispatchers.IO) {
                rec("--- DIAGNOSTICS --- model=$modelName verified=${isVerified()}")
                rec("internal free ${filesDir.usableSpace / 1_000_000} MB; model internal ${if (modelFile.exists()) modelFile.length() / 1_000_000 else -1} MB; model external ${if (oldModelFile.exists()) oldModelFile.length() / 1_000_000 else -1} MB")
                rec("SDK ${android.os.Build.VERSION.SDK_INT}, ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                runCatching { File("/proc/self/limits").readLines().filter { it.startsWith("Max address") || it.startsWith("Max locked") || it.startsWith("Max open") }.forEach { rec(it) } }
                runCatching { File("/proc/meminfo").readLines().filter { it.startsWith("Mem") || it.startsWith("Commit") || it.startsWith("Swap") || it.startsWith("Cached") }.forEach { rec(it) } }
                runCatching { File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.forEach { rec(it) } }
                runCatching { rec("overcommit=" + File("/proc/sys/vm/overcommit_memory").readText().trim() + " max_map_count=" + File("/proc/sys/vm/max_map_count").readText().trim()) }
                for (f in listOf(modelFile, oldModelFile)) {
                    if (!f.exists()) continue
                    // mmap test on the real file: map 3 slices of 700 MB (JVM FileChannel.map is limited to 2 GB each)
                    runCatching {
                        java.io.RandomAccessFile(f, "rw").use { raf ->
                            val ch = raf.channel
                            val len = ch.size()
                            val maps = ArrayList<java.nio.MappedByteBuffer>()
                            var off = 0L
                            val slice = 700L * 1024 * 1024
                            while (off < len) {
                                val n = minOf(slice, len - off)
                                maps += ch.map(java.nio.channels.FileChannel.MapMode.PRIVATE, off, n)
                                off += n
                            }
                            // touch one byte per 64 MB so pages really fault in
                            var sum = 0
                            for (m in maps) { var i = 0; while (i < m.limit()) { sum += m.get(i); i += 64 * 1024 * 1024 } }
                            rec("MMAP PRIVATE (copy-on-write, like the runtime) OK ${f.parentFile?.name}/${f.name}: ${maps.size} slices, ${len / 1_000_000} MB (checksum $sum)")
                        }
                    }.onFailure { rec("MMAP FAILED ${f.parentFile?.name}: ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }
    }

    private suspend fun runOne(file: File, text: String): Triple<String, Long, Long> {
        freshIfNeeded()
        val e = engine ?: error("engine not loaded")
        val sb = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        e.createConversation(sampler()).use { conv ->
            activeConv = conv
            try {
                conv.sendMessageAsync(Contents.of(Content.ImageFile(file.absolutePath), Content.Text(text)))
                    .collect { chunk ->
                        if (first < 0) first = SystemClock.elapsedRealtime() - t0
                        sb.append(chunk.toString())
                    }
            } finally { activeConv = null }
        }
        engineUsed = true
        kotlinx.coroutines.delay(300)
        return Triple(sb.toString().trim(), first, SystemClock.elapsedRealtime() - t0)
    }

    private fun benchmark() {
        if (photos.isEmpty()) { say("Pick photos first (button 3)."); return }
        exclusive("benchmark") {
            if (!ensureVision()) { both("Benchmark aborted: vision engine could not be loaded. See the log."); return@exclusive }
            if (cur.freshEngine) rec("NOTE: ${cur.label} needs a fresh engine per photo, about 25 s each, so about ${photos.size * 25 / 60 + 1} min for ${photos.size} photos.")
            rec("--- BENCHMARK $backendName n=${photos.size} --- temp ${tempC()}C battery ${battery()}%")
            val totals = mutableListOf<Long>()
            val firsts = mutableListOf<Long>()
            for ((i, f) in photos.withIndex()) {
                actionStep = "photo ${i + 1} of ${photos.size}"
                say("... photo ${i + 1}/${photos.size} started (${if (cur.freshEngine) "fresh engine first, about 20 s" else "running"})")
                val r = withContext(Dispatchers.Default) { runCatching { runOne(f, prompt) } }
                r.onSuccess { (txt, first, total) ->
                    totals += total; firsts += first
                    rec("#$i first=${first}ms total=${total}ms temp=${tempC()}C :: ${judge(txt).replace("\n", " | ")}")
                    answer("#${i + 1}  (${total / 1000.0} s)\n${judge(txt)}")
                }.onFailure { rec("#$i ERROR ${it.javaClass.simpleName}: ${it.message}"); answer("#${i + 1} ERROR: ${it.javaClass.simpleName}: ${it.message}") }
            }
            if (totals.isNotEmpty()) {
                val s = totals.sorted()
                rec("SUMMARY $backendName: median total ${s[s.size / 2]} ms, p90 ${s[(s.size * 9 / 10).coerceAtMost(s.size - 1)]} ms, " +
                    "mean first-token ${firsts.average().toLong()} ms, end temp ${tempC()}C battery ${battery()}% PSS ${pssMb()} MB")
            }
        }
    }

    private fun showAskResult(q: String, r: Result<Triple<String, Long, Long>>) {
        r.onSuccess { (txt, first, total) ->
            val clean = stripThink(txt)
            rec("ASK first=${first}ms total=${total}ms :: Q: $q :: A: ${clean.replace("\n", " ")}")
            answer("Q: $q\nA (${total / 1000.0} s): ${clean.ifBlank { "(the model returned an empty answer)" }}")
        }.onFailure {
            rec("ASK ERROR ${it.javaClass.simpleName}: ${it.message}")
            answer("ASK ERROR: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun ask(withPhoto: Boolean = false) {
        if (!withPhoto && engine == null) { both("Load the model first (2a or 2b), then wait for 'loaded in ... s' and the smoke test line."); return }
        val f = if (withPhoto) photos.lastOrNull() else null
        if (withPhoto && f == null) { both("No photo picked. Press 3 first."); return }
        val q = question.text.toString().ifBlank { if (f != null) "Describe this photo." else "Say hi in three words." }
        both("ASK started (${if (f != null) "with photo" else "text only"}, engine $backendName): $q\nWorking... for Qwen this takes about 25-40 s (fresh engine). The status line above shows the step.")
        exclusive("ask") {
            if (f != null && !ensureVision()) { answer("ASK aborted: vision engine could not be loaded. See the log."); rec("ASK aborted: vision engine could not be loaded."); return@exclusive }
            val r = withContext(Dispatchers.Default) { runCatching { if (f != null) runOne(f, q) else runText(q) } }
            showAskResult(q, r)
        }
    }

    private fun copyResults() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vlm results", results.toString()))
        runCatching { File(getExternalFilesDir(null), "results.txt").writeText(results.toString()) }
        say("Results copied to clipboard (${results.length} chars).")
    }
}
