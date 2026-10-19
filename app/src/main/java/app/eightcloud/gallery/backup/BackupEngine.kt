package app.eightcloud.gallery.backup

import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import app.eightcloud.gallery.crypto.BackupCrypto
import app.eightcloud.gallery.crypto.KeystoreKeys
import app.eightcloud.gallery.data.Hasher
import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaStoreSource
import kotlinx.coroutines.flow.MutableStateFlow
import app.eightcloud.gallery.telegram.TdClient
import app.eightcloud.gallery.telegram.TdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.FileOutputStream

/**
 * Drives the backup pipeline: scan -> hash -> encrypt -> upload -> index.
 * Identity is the SHA-256 of the content, so renames/moves never re-upload.
 */
/** Process-wide live upload progress, observed by the backup screen and the worker notification. */
object BackupProgress {
    data class State(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentName: String,
        val paused: Boolean = false,
        val bytesPerSec: Double = 0.0,
        val etaSeconds: Long = -1
    )

    val state = MutableStateFlow(State(false, 0, 0, 0L, 0L, ""))

    fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000)
        bytesPerSec >= 1_000 -> "%.0f KB/s".format(bytesPerSec / 1_000)
        else -> "%.0f B/s".format(bytesPerSec)
    }

    fun formatEta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "<1 min"
        seconds < 3600 -> "~" + (seconds / 60) + " min"
        else -> "~" + (seconds / 3600) + " h " + ((seconds % 3600) / 60) + " min"
    }
}

/** Pause flag for uploads, persisted so the worker and the UI share it. */
object BackupControl {
    private const val KEY_PAUSED = "uploadPaused"

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, paused).apply()
        BackupProgress.state.value = BackupProgress.state.value.copy(paused = paused)
        app.eightcloud.gallery.AppLog.log("backup", if (paused) "uploads paused" else "uploads resumed")
    }
}

class BackupEngine(private val context: Context) {

    private val db = BackupDb.get(context)
    private val td = TdClient.get(context)
    private val chunkDir: File
        get() = File(context.cacheDir, "upload-chunks").apply { mkdirs() }

    /** One upload chunk stays well under the 2 GB Telegram document limit. */
    private val chunkSizeBytes = 1_500_000_000L

    companion object {
        // Official free-account per-file cap (telegram.org/faq).
        const val TELEGRAM_FREE_MAX_BYTES = 2_000_000_000L
    }

    /**
     * Adds files that are new to the index as PENDING. Returns the new count.
     * When [onlyIds] is given (manual targeted run), the folder-scope setting
     * is bypassed for those files.
     */
    suspend fun scan(onlyIds: Set<Long>? = null): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val selectedOnly = prefs.getString("backupScope", "ALL") == "SELECTED"
        val buckets = prefs.getStringSet("backupBuckets", emptySet()) ?: emptySet()
        val items = MediaStoreSource.query(context)
            .filter { if (onlyIds != null) it.id in onlyIds else (!selectedOnly || it.bucket in buckets) }
        var added = 0
        val now = System.currentTimeMillis()
        for (item in items) {
            if (db.dao().byMediaStoreId(item.id) != null) continue
            val hash = context.contentResolver.openInputStream(Uri.parse(item.uri))?.use {
                Hasher.sha256Hex(it)
            } ?: continue
            // Prefix match covers both full local hashes and the 12-char
            // prefixes recovered from cloud captions after a reinstall.
            val existing = db.dao().byHashPrefix(hash.take(12))
            if (existing != null) {
                // Rebuilt cloud rows start with mediaStoreId 0: adopt the real
                // local id so later scans skip the hash pass for this file.
                if (existing.mediaStoreId == 0L) {
                    db.dao().upsert(
                        existing.copy(
                            mediaStoreId = item.id,
                            name = item.name,
                            sizeBytes = item.sizeBytes,
                            isVideo = item.isVideo,
                            lastSeenAt = now
                        )
                    )
                }
                continue
            }
            db.dao().upsert(
                BackupEntry(
                    contentHash = hash,
                    mediaStoreId = item.id,
                    name = item.name,
                    sizeBytes = item.sizeBytes,
                    isVideo = item.isVideo,
                    status = BackupEntry.PENDING,
                    telegramMessageIds = "",
                    chunkIvs = "",
                    uploadedAt = 0,
                    lastSeenAt = now
                )
            )
            added++
        }
        app.eightcloud.gallery.AppLog.log("backup", "scan done: " + added + " new of " + items.size)
        added
    }

    /**
     * Uploads every PENDING entry, one at a time. A file that fails is marked
     * FAILED with its reason and the run continues with the next file.
     * [onProgress] gets (done, total, bytesDone, bytesTotal, currentName).
     */
    suspend fun uploadPending(
        onlyIds: Set<Long>? = null,
        onProgress: (done: Int, total: Int, bytesDone: Long, bytesTotal: Long, currentName: String) -> Unit = { _, _, _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // Re-queue FAILED entries first so they join this run's PENDING list.
        val healed = db.dao().resetFailed()
        if (healed > 0) app.eightcloud.gallery.AppLog.log("backup", "re-queued " + healed + " failed uploads")
        val pending = if (onlyIds == null) db.dao().byStatus(BackupEntry.PENDING)
        else db.dao().pendingByIds(onlyIds.toList())
        val total = pending.size
        val totalBytes = pending.sumOf { it.sizeBytes }
        var doneBytes = 0L
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val destChat = prefs.getLong("destChatId", 0L).takeIf { it != 0L } ?: td.savedMessagesChatId()
        val toGroup = prefs.getLong("destChatId", 0L) != 0L
        val topicPics = prefs.getLong("topicPics", 0L).takeIf { it != 0L }
        val topicVids = prefs.getLong("topicVids", 0L).takeIf { it != 0L }
        BackupProgress.state.value = BackupProgress.State(true, 0, total, 0L, totalBytes, "")
        app.eightcloud.gallery.AppLog.log("backup", "upload run start: " + total + " files, " + totalBytes + " bytes")
        var failures = 0
        val meter = SpeedMeter()
        val currentFileUploaded = java.util.concurrent.atomic.AtomicLong(0L)
        var lastPush = 0L
        fun pushState(index: Int, name: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPush < 500) return
            lastPush = now
            val inFlight = doneBytes + currentFileUploaded.get()
            meter.add(inFlight)
            val rate = meter.bytesPerSecond()
            BackupProgress.state.value = BackupProgress.State(
                true, index, total, inFlight, totalBytes, name,
                paused = BackupControl.isPaused(context),
                bytesPerSec = rate,
                etaSeconds = meter.etaSeconds(totalBytes - inFlight)
            )
        }
        pending.forEachIndexed { index, entry ->
            waitIfPaused()
            onProgress(index, total, doneBytes, totalBytes, entry.name)
            currentFileUploaded.set(0L)
            pushState(index, entry.name, force = true)
            try {
                val thread = if (toGroup) (if (entry.isVideo) topicVids else topicPics) else null
                uploadWithRetry(entry, destChat, thread) { uploaded, _ ->
                    currentFileUploaded.set(uploaded)
                    pushState(index, entry.name)
                }
            } catch (e: Exception) {
                failures++
                app.eightcloud.gallery.AppLog.log("backup", "FAILED " + entry.name + ": " + (e.message ?: e.javaClass.simpleName))
                db.dao().markFailed(entry.contentHash, e.message ?: e.javaClass.simpleName)
            }
            doneBytes += entry.sizeBytes
            currentFileUploaded.set(0L)
            onProgress(index + 1, total, doneBytes, totalBytes, entry.name)
            pushState(index + 1, entry.name, force = true)
        }
        BackupProgress.state.value = BackupProgress.State(false, total, total, totalBytes, totalBytes, "")
        app.eightcloud.gallery.AppLog.log("backup", "upload run end: " + total + " files, " + failures + " failed")
    }

    /** FAILED entries with their recorded reasons. */
    suspend fun failed(): List<BackupEntry> = withContext(Dispatchers.IO) {
        db.dao().byStatus(BackupEntry.FAILED)
    }

    /** Resets all FAILED entries to PENDING so the next run picks them up. */
    suspend fun retryFailed(): Int = withContext(Dispatchers.IO) {
        db.dao().resetFailed()
    }

    /** BACKED_UP entries whose local copy still exists: (content uri, sizeBytes). */
    suspend fun backedUpLocal(): List<Pair<android.net.Uri, Long>> = withContext(Dispatchers.IO) {
        db.dao().byStatus(BackupEntry.BACKED_UP).mapNotNull { entry ->
            val uri = uriFor(entry)
            val exists = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { } != null
            }.getOrDefault(false)
            if (exists) uri to entry.sizeBytes else null
        }
    }

    /** Blocks while uploads are paused (Settings pref shared with the UI and worker). */
    private suspend fun waitIfPaused() {
        while (BackupControl.isPaused(context)) {
            BackupProgress.state.value = BackupProgress.state.value.copy(paused = true)
            kotlinx.coroutines.delay(500)
        }
        if (BackupProgress.state.value.paused) {
            BackupProgress.state.value = BackupProgress.state.value.copy(paused = false)
        }
    }

    /** Retries a file on transient failures (network loss) before giving up. */
    private suspend fun uploadWithRetry(
        entry: BackupEntry,
        chatId: Long,
        threadId: Long?,
        onPartProgress: (uploadedInFile: Long, fileBytes: Long) -> Unit
    ) {
        val delaysMs = longArrayOf(15_000L, 60_000L)
        var attempt = 0
        while (true) {
            try {
                uploadOne(entry, chatId, threadId, onPartProgress)
                return
            } catch (e: Exception) {
                if (attempt >= delaysMs.size) throw e
                app.eightcloud.gallery.AppLog.log(
                    "backup",
                    "attempt " + (attempt + 1) + " failed for " + entry.name + ", retrying: " + (e.message ?: e.javaClass.simpleName)
                )
                kotlinx.coroutines.delay(delaysMs[attempt])
                attempt++
            }
        }
    }

    private suspend fun uploadOne(entry: BackupEntry, chatId: Long, threadId: Long? = null, onPartProgress: (Long, Long) -> Unit = { _, _ -> }) {
        db.dao().setStatus(entry.contentHash, BackupEntry.UPLOADING)
        val splitEnabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("splitLargeFiles", true)
        if (!splitEnabled && entry.sizeBytes > TELEGRAM_FREE_MAX_BYTES) {
            throw TdException(0, "over Telegram's 2 GB limit and splitting is off in Settings")
        }
        val uri = uriFor(entry)
        val key = KeystoreKeys.backupKey()
        // Resume support: reuse the stored IV on a retry so the re-encryption
        // produces the identical ciphertext and uploaded parts can be skipped.
        val storedIv = entry.chunkIvs.split(',').firstOrNull()?.trim().orEmpty()
        val iv = if (storedIv.isNotEmpty()) Base64.decode(storedIv, Base64.NO_WRAP)
        else BackupCrypto.randomIv()
        val base = entry.contentHash.take(16)
        // Single pass: encrypt straight into rotating part files, so the temp
        // footprint is ~1x the file size instead of ~2x (no full-size .enc
        // plus a second set of split parts). Splitting only kicks in above
        // chunkSizeBytes; smaller files land in a single part like before.
        val partFiles = mutableListOf<File>()
        try {
            val sink = RotatingPartOutputStream(chunkDir, base, chunkSizeBytes, partFiles)
            context.contentResolver.openInputStream(uri)?.use { input ->
                sink.use { out -> BackupCrypto.encryptStream(key, iv, input, out) }
            } ?: throw TdException(0, "cannot read local file")
            val iv64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            // Persist IV + checksums before uploading so an interrupted run
            // (reboot, process death, network loss) can resume part-wise.
            if (entry.chunkIvs.isBlank() || entry.partChecksums.isBlank()) {
                db.dao().setUploadStart(
                    entry.contentHash, iv64, sink.checksums.joinToString(",")
                )
            }
            uploadParts(chatId, partFiles, sink.checksums, entry, iv64, threadId, onPartProgress)
        } finally {
            partFiles.forEach { it.delete() }
        }
    }

    private suspend fun uploadParts(
        chatId: Long,
        parts: List<File>,
        checksums: List<String>,
        entry: BackupEntry,
        iv64: String,
        threadId: Long? = null,
        onPartProgress: (uploadedInFile: Long, fileBytes: Long) -> Unit = { _, _ -> }
    ) {
        val captionBase = entry.contentHash.take(12) + " " + entry.name
        // Positional list of already-uploaded part message ids (resume support).
        val prior = entry.uploadedPartIds.split(',').map { it.trim() }
        val partIds = MutableList(parts.size) { i -> prior.getOrNull(i).orEmpty() }
        var offset = 0L
        parts.forEachIndexed { i, part ->
            val base = offset
            offset += part.length()
            if (partIds[i].isNotBlank()) {
                // Already uploaded by an earlier run: count its bytes and skip.
                onPartProgress(offset, entry.sizeBytes)
                return@forEachIndexed
            }
            val caption = if (parts.size == 1) captionBase
                else "$captionBase [part ${i + 1}/${parts.size}]"
            val id = td.uploadDocument(chatId, part.absolutePath, caption, threadId) { up, _ ->
                onPartProgress(base + up, entry.sizeBytes)
            }.id
            partIds[i] = id.toString()
            // Persist immediately so a crash after this point skips this part.
            db.dao().setUploadedPartIds(entry.contentHash, partIds.joinToString(","))
        }
        db.dao().markUploadedWithChecksums(
            entry.contentHash,
            BackupEntry.BACKED_UP,
            partIds.joinToString(","),
            iv64,
            checksums.joinToString(","),
            System.currentTimeMillis()
        )
    }

    /**
     * OutputStream that rotates to a new part file once the current one
     * reaches partSize, computing a SHA-256 per part. Encryption streams
     * through it transparently, so parts are slices of one ciphertext.
     */
    private class RotatingPartOutputStream(
        private val dir: File,
        private val base: String,
        private val partSize: Long,
        private val partFiles: MutableList<File>
    ) : java.io.OutputStream() {
        val checksums = mutableListOf<String>()
        private var out: FileOutputStream? = null
        private var digest: java.security.MessageDigest? = null
        private var size = 0L
        private var index = 0

        private fun openNext() {
            val f = File(dir, "$base.part$index")
            index++
            partFiles.add(f)
            out = FileOutputStream(f)
            digest = java.security.MessageDigest.getInstance("SHA-256")
            size = 0L
        }

        private fun closeCurrent() {
            out?.close()
            out = null
            digest?.let { d -> checksums.add(d.digest().joinToString("") { "%02x".format(it) }) }
            digest = null
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var offset = off
            while (remaining > 0) {
                if (out == null) openNext()
                val n = minOf(remaining.toLong(), partSize - size).toInt()
                out!!.write(b, offset, n)
                digest!!.update(b, offset, n)
                size += n
                offset += n
                remaining -= n
                if (size >= partSize) closeCurrent()
            }
        }

        override fun close() {
            closeCurrent()
        }
    }

    /** Exports the whole index, encrypted, as the latest index document in Saved Messages. */
    suspend fun exportIndex() = withContext(Dispatchers.IO) {
        val entries = db.dao().all()
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("hash", e.contentHash)
                put("mediaStoreId", e.mediaStoreId)
                put("name", e.name)
                put("size", e.sizeBytes)
                put("isVideo", e.isVideo)
                put("status", e.status)
                put("messageIds", e.telegramMessageIds)
                put("ivs", e.chunkIvs)
                put("partChecksums", e.partChecksums)
                put("uploadedAt", e.uploadedAt)
            })
        }
        val payload = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("entries", array)
        }
        val encryptedBytes = BackupCrypto.encrypt(KeystoreKeys.backupKey(), payload.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(chunkDir, "8cloudgallery-index.enc")
        try {
            tmp.writeBytes(encryptedBytes)
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val destChat = prefs.getLong("destChatId", 0L).takeIf { it != 0L } ?: td.savedMessagesChatId()
            val general = prefs.getLong("topicGeneral", 0L).takeIf { it != 0L && prefs.getLong("destChatId", 0L) != 0L }
            td.uploadDocument(destChat, tmp.absolutePath, "8cloudgallery-index", general)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Exports settings + the whole backup index as a plain JSON file the user
     * picks via SAF. Not encrypted: meant to move between devices, where the
     * old device's keystore key no longer exists. Contains file names and
     * destination chat ids - the file deserves the same care as a backup.
     */
    suspend fun exportIndexToFile(target: Uri): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val settingsObj = JSONObject()
        prefs.all.forEach { (k, v) ->
            when (v) {
                is Set<*> -> settingsObj.put(k, JSONArray(v))
                else -> settingsObj.put(k, v)
            }
        }
        val array = JSONArray()
        db.dao().all().forEach { e ->
            array.put(JSONObject().apply {
                put("hash", e.contentHash)
                put("name", e.name)
                put("size", e.sizeBytes)
                put("isVideo", e.isVideo)
                put("status", e.status)
                put("messageIds", e.telegramMessageIds)
                put("ivs", e.chunkIvs)
                put("partChecksums", e.partChecksums)
                put("partIds", e.uploadedPartIds)
                put("uploadedAt", e.uploadedAt)
            })
        }
        val payload = JSONObject().apply {
            put("version", 1)
            put("kind", "8cloudgallery-file-export")
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsObj)
            put("entries", array)
        }
        context.contentResolver.openOutputStream(target)?.use { out ->
            out.write(payload.toString(2).toByteArray(Charsets.UTF_8))
        } ?: throw TdException(0, "cannot write export file")
        array.length()
    }

    /**
     * Imports a file produced by [exportIndexToFile]. Restores settings and
     * upserts index rows. Imported mediaStoreIds are dropped (they are
     * per-device); the next scan adopts local files by content hash.
     * Returns the number of entries imported.
     */
    suspend fun importIndexFromFile(source: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw TdException(0, "cannot read import file")
        val root = JSONObject(text)
        if (root.optString("kind") != "8cloudgallery-file-export") {
            throw TdException(0, "not an 8CloudGallery export file")
        }
        // Settings first.
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val settingsObj = root.optJSONObject("settings")
        if (settingsObj != null) {
            val editor = prefs.edit()
            val keys = settingsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = settingsObj.get(k)) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                    is String -> editor.putString(k, v)
                    is JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until v.length()) set.add(v.getString(i))
                        editor.putStringSet(k, set)
                    }
                    else -> {}
                }
            }
            editor.apply()
        }
        // Index rows: adopt-by-hash, so imported rows start with mediaStoreId 0.
        val array = root.getJSONArray("entries")
        var imported = 0
        val now = System.currentTimeMillis()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val hash = o.getString("hash")
            if (db.dao().byHash(hash) != null) continue
            db.dao().upsert(
                BackupEntry(
                    contentHash = hash,
                    mediaStoreId = 0,
                    name = o.getString("name"),
                    sizeBytes = o.getLong("size"),
                    isVideo = o.getBoolean("isVideo"),
                    status = o.getString("status"),
                    telegramMessageIds = o.optString("messageIds", ""),
                    chunkIvs = o.optString("ivs", ""),
                    partChecksums = o.optString("partChecksums", ""),
                    uploadedPartIds = o.optString("partIds", ""),
                    uploadedAt = o.optLong("uploadedAt", 0L),
                    lastSeenAt = now
                )
            )
            imported++
        }
        app.eightcloud.gallery.AppLog.log("backup", "file import: " + imported + " entries, settings restored")
        imported
    }

    /** Imports the newest index document found in Saved Messages. Returns imported count. */
    suspend fun importIndex(): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val chatId = destinationChat()
        val generalTopic = prefs.getLong("topicGeneral", 0L).takeIf {
            it != 0L && prefs.getLong("destChatId", 0L) != 0L
        }?.toInt()
        var fromId = 0L
        var indexDoc: TdApi.Document? = null
        // Walk back through history until the newest index document is found.
        var rounds = 0
        while (indexDoc == null && rounds < 20) {
            val page = if (generalTopic != null) td.forumTopicHistory(chatId, generalTopic, fromId, 100)
            else td.chatHistory(chatId, fromId, 100)
            if (page.messages.isEmpty()) break
            for (message in page.messages) {
                val content = message.content
                if (content is TdApi.MessageDocument && content.document.fileName == "8cloudgallery-index.enc") {
                    indexDoc = content.document
                    break
                }
            }
            fromId = page.messages.last().id
            rounds++
        }
        val doc = indexDoc ?: return@withContext 0
        val path = td.downloadFile(doc.document.id)
        val plain = BackupCrypto.decrypt(KeystoreKeys.backupKey(), File(path).readBytes())
        val root = JSONObject(String(plain, Charsets.UTF_8))
        val array = root.getJSONArray("entries")
        var imported = 0
        val now = System.currentTimeMillis()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val hash = o.getString("hash")
            if (db.dao().byHash(hash) != null) continue
            db.dao().upsert(
                BackupEntry(
                    contentHash = hash,
                    mediaStoreId = o.getLong("mediaStoreId"),
                    name = o.getString("name"),
                    sizeBytes = o.getLong("size"),
                    isVideo = o.getBoolean("isVideo"),
                    status = BackupEntry.CLOUD_ONLY,
                    telegramMessageIds = o.getString("messageIds"),
                    chunkIvs = o.getString("ivs"),
                    partChecksums = o.optString("partChecksums", ""),
                    uploadedAt = o.getLong("uploadedAt"),
                    lastSeenAt = now
                )
            )
            imported++
        }
        imported
    }

    /**
     * Checks recent Saved Messages history and flags entries whose upload
     * messages are gone as MISSING_IN_CLOUD. Bounded to the newest 200
     * messages on purpose.
     */
    /** Resolves the active backup destination chat (group or Saved Messages). */
    private suspend fun destinationChat(): Long {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getLong("destChatId", 0L).takeIf { it != 0L } ?: td.savedMessagesChatId()
    }

    suspend fun verify(): Int = withContext(Dispatchers.IO) {
        val chatId = destinationChat()
        var missing = 0
        db.dao().all()
            .filter { it.status == BackupEntry.BACKED_UP }
            .forEach { entry ->
                val ids = entry.telegramMessageIds.split(",").mapNotNull { it.toLongOrNull() }
                if (ids.isEmpty()) return@forEach
                val found = runCatching {
                    td.getMessages(chatId, ids.toLongArray()).messages.count { it != null }
                }.getOrDefault(0)
                if (found == 0) {
                    db.dao().setStatus(entry.contentHash, BackupEntry.MISSING_IN_CLOUD)
                    missing++
                }
            }
        missing
    }

    suspend fun isIndexEmpty(): Boolean = withContext(Dispatchers.IO) {
        db.dao().rowCount() == 0
    }

    /**
     * Spot-checks a random sample of backed-up entries against the cloud and
     * flags missing ones. Returns the number missing. Used by the weekly
     * backup-health check.
     */
    suspend fun verifySample(sampleSize: Int = 25): Int = withContext(Dispatchers.IO) {
        val chatId = destinationChat()
        val backedUp = db.dao().all().filter {
            it.status == BackupEntry.BACKED_UP && it.telegramMessageIds.isNotBlank()
        }
        if (backedUp.isEmpty()) return@withContext 0
        val sample = backedUp.shuffled().take(sampleSize)
        var missing = 0
        for (entry in sample) {
            val ids = entry.telegramMessageIds.split(",").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) continue
            val found = runCatching {
                td.getMessages(chatId, ids.toLongArray()).messages.count { it != null }
            }.getOrDefault(0)
            if (found == 0) {
                db.dao().setStatus(entry.contentHash, BackupEntry.MISSING_IN_CLOUD)
                missing++
            }
        }
        app.eightcloud.gallery.AppLog.log("backup", "health check: " + sample.size + " sampled, " + missing + " missing")
        missing
    }

    suspend fun counts(): Pair<Int, Int> {
        val backed = db.dao().backedUpCount()
        val pending = db.dao().byStatus(BackupEntry.PENDING).size
        return backed to pending
    }

    data class RestoreResult(val restored: Int, val failed: Int)

    suspend fun cloudOnlyEntries(): List<BackupEntry> = withContext(Dispatchers.IO) {
        db.dao().all().filter { it.status == BackupEntry.CLOUD_ONLY && it.chunkIvs.isNotBlank() }
    }

    /**
     * Rebuilds index rows from backup captions found in the destination chat.
     * Fallback for a reinstall without an index export: matching files are
     * skipped on the next scan instead of being uploaded again. Entries
     * rebuilt this way carry no encryption IV, so they are dedupe-only and
     * never offered for restore (chunkIvs stays blank).
     * Returns the number of files imported.
     */
    suspend fun rebuildIndexFromCloud(maxPages: Int = 20): Int = withContext(Dispatchers.IO) {
        val captionRe = Regex("""^([0-9a-f]{12}) (.+?)(?: \[part (\d+)/(\d+)\])?$""")
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val chatId = destinationChat()
        val toGroup = prefs.getLong("destChatId", 0L) != 0L
        val topics = if (toGroup) listOfNotNull(
            prefs.getLong("topicPics", 0L).takeIf { it != 0L }?.toInt(),
            prefs.getLong("topicVids", 0L).takeIf { it != 0L }?.toInt()
        ) else listOf(null)
        // hashPrefix -> (name, sorted part message ids, latest date, biggest size)
        data class Acc(var name: String, val ids: MutableList<Long>, var date: Long, var size: Long, var isVideo: Boolean)
        val found = HashMap<String, Acc>()
        for (topic in (if (toGroup) topics else listOf(null))) {
            var fromId = 0L
            var rounds = 0
            while (rounds < maxPages) {
                val page = if (topic != null) td.forumTopicHistory(chatId, topic, fromId, 100)
                else td.chatHistory(chatId, fromId, 100)
                if (page.messages.isEmpty()) break
                for (message in page.messages) {
                    val content = message.content as? TdApi.MessageDocument ?: continue
                    val doc = content.document
                    val captionText = content.caption?.text ?: ""
                    if (captionText.isEmpty()) continue
                    val m = captionRe.find(captionText) ?: continue
                    val prefix = m.groupValues[1]
                    val name = m.groupValues[2]
                    val part = m.groupValues[3].toIntOrNull() ?: 1
                    val acc = found.getOrPut(prefix) { Acc(name, mutableListOf(), 0L, 0L, false) }
                    while (acc.ids.size < part) acc.ids.add(0L)
                    acc.ids[part - 1] = message.id
                    acc.date = maxOf(acc.date, message.date.toLong() * 1000)
                    acc.size += doc.document.size
                }
                fromId = page.messages.last().id
                rounds++
            }
        }
        var imported = 0
        val now = System.currentTimeMillis()
        for ((prefix, acc) in found) {
            if (db.dao().byHashPrefix(prefix) != null) continue
            db.dao().upsert(
                BackupEntry(
                    contentHash = prefix,
                    mediaStoreId = 0,
                    name = acc.name,
                    sizeBytes = acc.size,
                    isVideo = acc.isVideo,
                    status = BackupEntry.CLOUD_ONLY,
                    telegramMessageIds = acc.ids.filter { it != 0L }.joinToString(","),
                    chunkIvs = "",
                    uploadedAt = acc.date,
                    lastSeenAt = now
                )
            )
            imported++
        }
        app.eightcloud.gallery.AppLog.log("backup", "cloud rebuild: " + imported + " files imported from captions")
        imported
    }

    suspend fun restore(
        hashes: Set<String>,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): RestoreResult = withContext(Dispatchers.IO) {
        val chatId = destinationChat()
        val key = KeystoreKeys.backupKey()
        val targets = db.dao().all().filter { it.contentHash in hashes }
        var restored = 0
        var failed = 0
        targets.forEachIndexed { index, entry ->
            onProgress(index, targets.size, entry.name)
            try {
                restoreOne(entry, chatId, key)
                restored++
            } catch (e: Exception) {
                failed++
            }
        }
        onProgress(targets.size, targets.size, "")
        app.eightcloud.gallery.AppLog.log("restore", "restored " + restored + ", failed " + failed)
        RestoreResult(restored, failed)
    }

    private suspend fun restoreOne(entry: BackupEntry, chatId: Long, key: javax.crypto.SecretKey) {
        val msgIds = entry.telegramMessageIds.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .sorted()
            .toLongArray()
        require(msgIds.isNotEmpty()) { "no telegram messages recorded" }
        val messages = td.getMessages(chatId, msgIds)
        val parts = messages.messages.filterNotNull().sortedBy { it.id }.map { msg ->
            val doc = (msg.content as? TdApi.MessageDocument)?.document
                ?: throw IllegalStateException("message ${msg.id} is not a document")
            td.downloadFile(doc.document.id)
        }
        // Verify part checksums recorded at upload time (older entries have none).
        val checksums = entry.partChecksums.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        parts.forEachIndexed { i, path ->
            if (i < checksums.size) {
                val actual = FileInputStream(File(path)).use { Hasher.sha256Hex(it) }
                if (!actual.equals(checksums[i], ignoreCase = true)) {
                    throw IllegalStateException("checksum mismatch on part ${i + 1}")
                }
            }
        }
        // The IV is stored Base64-encoded (same format uploadParts writes).
        val iv = Base64.decode(entry.chunkIvs.split(',').first().trim(), Base64.NO_WRAP)

        val isVideo = entry.isVideo
        val collection = if (isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/8CloudGallery" else "Pictures/8CloudGallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                if (parts.size == 1) {
                    FileInputStream(File(parts[0])).use { input ->
                        BackupCrypto.decryptStream(key, iv, input, out)
                    }
                } else {
                    val streams = parts.map { FileInputStream(File(it)) as InputStream }
                    SequenceInputStream(java.util.Collections.enumeration(streams)).use { input ->
                        BackupCrypto.decryptStream(key, iv, input, out)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        db.dao().upsert(
            entry.copy(
                mediaStoreId = ContentUris.parseId(uri),
                status = BackupEntry.BACKED_UP,
                lastSeenAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun entryFor(mediaStoreId: Long): BackupEntry? = withContext(Dispatchers.IO) {
        db.dao().byMediaStoreId(mediaStoreId)
    }

    /**
     * Downloads and decrypts a backed-up entry into the app cache so it can
     * be shared via FileProvider without restoring it to MediaStore.
     */
    suspend fun decryptToCache(entry: BackupEntry): File = withContext(Dispatchers.IO) {
        val chatId = destinationChat()
        val key = KeystoreKeys.backupKey()
        val msgIds = entry.telegramMessageIds.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .sorted()
            .toLongArray()
        require(msgIds.isNotEmpty()) { "no telegram messages recorded" }
        val messages = td.getMessages(chatId, msgIds)
        val parts = messages.messages.filterNotNull().sortedBy { it.id }.map { msg ->
            val doc = (msg.content as? TdApi.MessageDocument)?.document
                ?: throw IllegalStateException("message ${msg.id} is not a document")
            td.downloadFile(doc.document.id)
        }
        val checksums = entry.partChecksums.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        parts.forEachIndexed { i, path ->
            if (i < checksums.size) {
                val actual = FileInputStream(File(path)).use { Hasher.sha256Hex(it) }
                if (!actual.equals(checksums[i], ignoreCase = true)) {
                    throw IllegalStateException("checksum mismatch on part ${i + 1}")
                }
            }
        }
        val iv = Base64.decode(entry.chunkIvs.split(',').first().trim(), Base64.NO_WRAP)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(dir, entry.name)
        FileOutputStream(out).use { fileOut ->
            if (parts.size == 1) {
                FileInputStream(File(parts[0])).use { input ->
                    BackupCrypto.decryptStream(key, iv, input, fileOut)
                }
            } else {
                val streams = parts.map { FileInputStream(File(it)) as InputStream }
                SequenceInputStream(java.util.Collections.enumeration(streams)).use { input ->
                    BackupCrypto.decryptStream(key, iv, input, fileOut)
                }
            }
        }
        app.eightcloud.gallery.AppLog.log("share", "cloud copy decrypted: " + entry.name)
        out
    }

    private fun uriFor(entry: BackupEntry): Uri {
        val base = if (entry.isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return ContentUris.withAppendedId(base, entry.mediaStoreId)
    }
}
