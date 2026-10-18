package app.eightcloud.gallery.telegram

import android.content.Context
import android.os.Build
import android.util.Base64
import app.eightcloud.gallery.BuildConfig
import app.eightcloud.gallery.crypto.BackupCrypto
import app.eightcloud.gallery.crypto.KeystoreKeys
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class TdException(val code: Int, message: String) : Exception("TDLib error $code: $message")

/**
 * Thin coroutine wrapper around the TDLib JSON client.
 * Logs in as a real Telegram user (the second account) and sends encrypted
 * files as documents to that account's Saved Messages chat.
 */
class TdClient private constructor(private val appContext: Context) {

    companion object {
        @Volatile private var instance: TdClient? = null

        fun get(context: Context): TdClient = instance ?: synchronized(this) {
            instance ?: TdClient(context.applicationContext).also { instance = it }
        }
    }

    private var client: Client? = null

    @Volatile
    var authState: TdApi.AuthorizationState? = null
        private set

    @Volatile
    var me: TdApi.User? = null
        private set

    private val authWatchers = ConcurrentHashMap.newKeySet<(TdApi.AuthorizationState) -> Unit>()
    private val pendingUploads = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()

    @Volatile
    private var savedMessagesChatId: Long = 0

    fun addAuthWatcher(watcher: (TdApi.AuthorizationState) -> Unit) {
        authWatchers.add(watcher)
        authState?.let(watcher)
    }

    fun removeAuthWatcher(watcher: (TdApi.AuthorizationState) -> Unit) {
        authWatchers.remove(watcher)
    }

    fun start() {
        if (client != null) return
        client = Client.create(
            { update -> handleUpdate(update) },
            { it.printStackTrace() },
            { it.printStackTrace() }
        )
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                val state = update.authorizationState
                authState = state
                when (state) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
                    is TdApi.AuthorizationStateReady -> {
                        client?.send(TdApi.GetMe()) { obj ->
                            if (obj is TdApi.User) me = obj
                        }
                    }
                    is TdApi.AuthorizationStateClosed -> {
                        me = null
                        savedMessagesChatId = 0
                    }
                    else -> {}
                }
                authWatchers.forEach { it(state) }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                pendingUploads.remove(update.oldMessageId)?.complete(update.message)
            }
            is TdApi.UpdateMessageSendFailed -> {
                pendingUploads.remove(update.oldMessageId)
                    ?.completeExceptionally(TdException(update.error.code, update.error.message))
            }
            else -> {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: throw IllegalStateException("TdClient not started")
            c.send(function) { obj ->
                if (obj is TdApi.Error) {
                    cont.resumeWithException(TdException(obj.code, obj.message))
                } else {
                    cont.resume(obj as T) { }
                }
            }
        }

    private fun sendParameters() {
        val apiId = BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0
        val apiHash = BuildConfig.TELEGRAM_API_HASH
        val dir = File(appContext.filesDir, "tdlib").apply { mkdirs() }
        val params = TdApi.SetTdlibParameters(
            false,
            dir.absolutePath,
            dir.absolutePath,
            databaseKey(),
            true, true, true, false,
            apiId,
            apiHash,
            "en",
            Build.MODEL ?: "Android",
            "Android ${Build.VERSION.RELEASE}",
            BuildConfig.VERSION_NAME
        )
        client?.send(params) { obj ->
            if (obj is TdApi.Error) {
                // Surface as closed so the UI can show the failure instead of hanging.
                authState = TdApi.AuthorizationStateClosed()
                authWatchers.forEach { it(authState!!) }
            }
        }
    }

    /** Random 32-byte TDLib database key, stored encrypted with the keystore key. */
    private fun databaseKey(): ByteArray {
        val prefs = appContext.getSharedPreferences("tdlib", Context.MODE_PRIVATE)
        prefs.getString("dbkey", null)?.let { stored ->
            runCatching {
                BackupCrypto.decrypt(KeystoreKeys.backupKey(), Base64.decode(stored, Base64.NO_WRAP))
            }.getOrNull()?.let { return it }
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = BackupCrypto.encrypt(KeystoreKeys.backupKey(), key)
        prefs.edit().putString("dbkey", Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
        return key
    }

    suspend fun setPhone(phone: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    suspend fun checkCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun checkPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun logout() {
        send(TdApi.LogOut())
    }

    suspend fun savedMessagesChatId(): Long {
        if (savedMessagesChatId != 0L) return savedMessagesChatId
        val user = me ?: send(TdApi.GetMe()).also { me = it }
        val chat = send(TdApi.CreatePrivateChat(user.id, false))
        savedMessagesChatId = chat.id
        return chat.id
    }

    /** Sends an encrypted file as a document; completes when Telegram confirms the upload. */
    suspend fun uploadDocument(chatId: Long, filePath: String, caption: String, threadId: Long? = null): TdApi.Message =
        withContext(Dispatchers.Default) {
            val content = TdApi.InputMessageDocument(
                TdApi.InputDocument(TdApi.InputFileLocal(filePath), null, false),
                TdApi.FormattedText(caption, null)
            )
            val topic = threadId?.let { TdApi.MessageTopicForum(it.toInt()) }
            val sent = send(TdApi.SendMessage(chatId, topic, null, null, null, content))
            val deferred = CompletableDeferred<TdApi.Message>()
            pendingUploads[sent.id] = deferred
            try {
                kotlinx.coroutines.withTimeout(30L * 60 * 1000) { deferred.await() }
            } finally {
                pendingUploads.remove(sent.id)
            }
        }

    /** Groups the account belongs to: (chatId, title). */
    suspend fun myGroups(): List<Pair<Long, String>> {
        val chats = send(TdApi.GetChats(TdApi.ChatListMain(), 200))
        val result = ArrayList<Pair<Long, String>>()
        for (id in chats.chatIds) {
            val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull() ?: continue
            if (chat.type is TdApi.ChatTypeSupergroup || chat.type is TdApi.ChatTypeBasicGroup) {
                result.add(id to chat.title)
            }
        }
        return result
    }

    /** Forum topics of a supergroup: (topicId, name). Empty for non-forum chats. */
    suspend fun forumTopics(chatId: Long): List<Pair<Long, String>> {
        val res = send(TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100))
        val result = ArrayList<Pair<Long, String>>()
        for (topic in res.topics) {
            result.add(topic.info.forumTopicId.toLong() to topic.info.name)
        }
        return result
    }

    /** Latest documents in a chat (for index restore / verify). */
    suspend fun chatHistory(chatId: Long, fromMessageId: Long = 0, limit: Int = 50): TdApi.Messages =
        send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))

    /** Fetches specific messages by id (topic-independent existence check). */
    suspend fun getMessages(chatId: Long, messageIds: LongArray): TdApi.Messages =
        send(TdApi.GetMessages(chatId, messageIds))

    /** History of one forum topic. */
    suspend fun forumTopicHistory(chatId: Long, forumTopicId: Int, fromMessageId: Long = 0, limit: Int = 50): TdApi.Messages =
        send(TdApi.GetForumTopicHistory(chatId, forumTopicId, fromMessageId, 0, limit))

    /** Downloads a remote file and returns the local path. */
    suspend fun downloadFile(fileId: Int): String {
        val file = send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
        if (!file.local.isDownloadingCompleted) {
            throw TdException(0, "download did not complete")
        }
        return file.local.path
    }

    fun stop() {
        client = null
        instance = null
    }
}
