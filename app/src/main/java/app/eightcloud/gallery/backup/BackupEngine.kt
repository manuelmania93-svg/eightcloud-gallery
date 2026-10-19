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
/** Process-wide live upload progress, observed by the backup screen. */
object BackupProgress {
    data class State(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentName: String
    )

    val state = MutableStateFlow(State(false, 0, 0, 0L, 0L, ""))
}

class BackupEngine(private val context: Context) {

    private val db = BackupDb.get(context)
    private val td = TdClient.get(context)
    private val chunkDir: File
        get() = File(context.cacheDir, "upload-chunks").apply { mkdirs() }

    /** One upload chunk stays well under the 2 GB Telegram document limit. */
    private val chunkSizeBytes = 1_500_000_000L

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
            if (db.dao().byHash(hash) != null) continue
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
        pending.forEachIndexed { index, entry ->
            onProgress(index, total, doneBytes, totalBytes, entry.name)
            BackupProgress.state.value =
                BackupProgress.State(true, index, total, doneBytes, totalBytes, entry.name)
            try {
                val thread = if (toGroup) (if (entry.isVideo) topicVids else topicPics) else null
                uploadOne(entry, destChat, thread)
            } catch (e: Exception) {
                db.dao().markFailed(entry.contentHash, e.message ?: e.javaClass.simpleName)
            }
            doneBytes += entry.sizeBytes
            onProgress(index + 1, total, doneBytes, totalBytes, entry.name)
            BackupProgress.state.value =
                BackupProgress.State(true, index + 1, total, doneBytes, totalBytes, entry.name)
        }
        BackupProgress.state.value = BackupProgress.State(false, total, total, totalBytes, totalBytes, "")
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

    private suspend fun uploadOne(entry: BackupEntry, chatId: Long, threadId: Long? = null) {
        db.dao().setStatus(entry.contentHash, BackupEntry.UPLOADING)
        val uri = uriFor(entry)
        val key = KeystoreKeys.backupKey()
        val iv = BackupCrypto.randomIv()
        val encrypted = File(chunkDir, entry.contentHash.take(16) + ".enc")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(encrypted).use { output ->
                    BackupCrypto.encryptStream(key, iv, input, output)
                }
            } ?: throw TdException(0, "cannot read local file")
            uploadChunks(chatId, encrypted, entry, listOf(iv), threadId)
        } finally {
            encrypted.delete()
        }
    }

    private suspend fun uploadChunks(chatId: Long, encrypted: File, entry: BackupEntry, ivs: List<ByteArray>, threadId: Long? = null) {
        val size = encrypted.length()
        val messageIds = mutableListOf<Long>()
        val captionBase = entry.contentHash.take(12) + " " + entry.name
        if (size <= chunkSizeBytes) {
            val msg = td.uploadDocument(chatId, encrypted.absolutePath, captionBase, threadId)
            messageIds.add(msg.id)
        } else {
            val parts = splitFile(encrypted, chunkSizeBytes)
            try {
                parts.forEachIndexed { i, part ->
                    val msg = td.uploadDocument(chatId, part.absolutePath, "$captionBase [part ${i + 1}/${parts.size}]", threadId)
                    messageIds.add(msg.id)
                }
            } finally {
                parts.forEach { it.delete() }
            }
        }
        val ivHex = ivs.joinToString(",") { Base64.encodeToString(it, Base64.NO_WRAP) }
        db.dao().markUploaded(
            entry.contentHash,
            BackupEntry.BACKED_UP,
            messageIds.joinToString(","),
            ivHex,
            System.currentTimeMillis()
        )
    }

    private fun splitFile(file: File, partSize: Long): List<File> {
        val parts = mutableListOf<File>()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024 * 1024)
            var partIndex = 0
            var currentOut: FileOutputStream? = null
            var currentSize = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (currentOut == null) {
                    val part = File(chunkDir, "${file.nameWithoutExtension}.part$partIndex")
                    parts.add(part)
                    currentOut = FileOutputStream(part)
                    currentSize = 0
                }
                currentOut!!.write(buffer, 0, read)
                currentSize += read
                if (currentSize >= partSize) {
                    currentOut.close()
                    currentOut = null
                    partIndex++
                }
            }
            currentOut?.close()
        }
        return parts
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

    suspend fun counts(): Pair<Int, Int> {
        val backed = db.dao().backedUpCount()
        val pending = db.dao().byStatus(BackupEntry.PENDING).size
        return backed to pending
    }

    data class RestoreResult(val restored: Int, val failed: Int)

    suspend fun cloudOnlyEntries(): List<BackupEntry> = withContext(Dispatchers.IO) {
        db.dao().all().filter { it.status == BackupEntry.CLOUD_ONLY }
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
        val ivHex = entry.chunkIvs.split(',').first().trim()
        val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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

    private fun uriFor(entry: BackupEntry): Uri {
        val base = if (entry.isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return ContentUris.withAppendedId(base, entry.mediaStoreId)
    }
}
