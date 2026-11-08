package app.eightcloud.gallery.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * In-app updater: checks the latest published release, downloads its APK only after the
 * user taps, verifies the download (SHA-256 digest from the signed release manifest, then
 * package name, versionCode, and signing certificate against the installed app), and hands
 * the verified file to the system package installer. The user confirms the system install
 * sheet; the app never installs silently.
 *
 * Release layout this expects: the latest GitHub release carries a `8cloud-update.json` asset
 * with {"versionCode", "versionName", "apkUrl", "sha256", "notes"?} pointing at the APK
 * asset on this repository's own release page.
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val info: UpdateInfo?) : UpdateState
}

object AppUpdate {
    internal const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/manuelmania93-svg/eightcloud-gallery/releases/latest"
    internal const val MANIFEST_ASSET_NAME = "8cloud-update.json"
    internal const val RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/manuelmania93-svg/eightcloud-gallery/releases/download/"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    // Test hooks for the on-device A->B handoff fixture: androidTest points the updater
    // at a local fixture server. Never set in production code paths.
    internal var latestReleaseUrlOverride: String? = null
    internal var allowedApkUrlPrefixOverride: String? = null

    // CI diagnostics, read by UpdateFlowTest failure messages to pin a failure to the
    // click path (count never moves) or the fetch path (lastFetch names url + outcome).
    @Volatile internal var debugCheckCount: Int = 0
    @Volatile internal var debugLastFetch: String? = null
    // Identity diagnostics: a screenVm/checkVm mismatch (or creates climbing mid-test)
    // proves the tap reached a cleared ViewModel after an activity recreation.
    @Volatile internal var debugActivityCreates: Int = 0
    @Volatile internal var debugScreenVm: Int = 0
    @Volatile internal var debugLastCheckVm: Int = 0

    // Timestamped event trace of the whole check path (test-only diagnostics).
    internal val debugTrace = StringBuilder()
    internal fun trace(event: String) {
        val line = android.os.SystemClock.elapsedRealtime().toString() + " [" + Thread.currentThread().name + "] " + event
        android.util.Log.i("UFTrace", line)
        synchronized(debugTrace) {
            if (debugTrace.length < 12000) {
                debugTrace.append(line).append('\n')
            }
        }
    }

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun isNewer(info: UpdateInfo, installedVersionCode: Long): Boolean =
        info.versionCode > installedVersionCode

    /** Finds the download URL of a release asset by file name; null when absent. */
    internal fun findAssetUrl(releaseJson: String, assetName: String): String? {
        val assets = JSONObject(releaseJson).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == assetName) {
                return asset.optString("browser_download_url").ifBlank { null }
            }
        }
        return null
    }

    /**
     * Parses the update manifest; null on any missing/invalid field. The APK URL must live
     * on this repository's own release page, so a tampered manifest cannot redirect the
     * download elsewhere.
     */
    internal fun parseManifest(manifestJson: String): UpdateInfo? = runCatching {
        val m = JSONObject(manifestJson)
        val apkUrl = m.getString("apkUrl")
        val sha256 = m.getString("sha256").lowercase()
        require(apkUrl.startsWith(allowedApkUrlPrefixOverride ?: RELEASE_DOWNLOAD_PREFIX)) { "unexpected apkUrl" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "bad sha256" }
        UpdateInfo(
            versionCode = m.getLong("versionCode"),
            versionName = m.getString("versionName"),
            apkUrl = apkUrl,
            sha256 = sha256,
            notes = m.optString("notes").ifBlank { null },
        )
    }.getOrNull()

    /**
     * Fetches the latest-release update manifest. Returns null on network failure or when
     * the release carries no manifest (older releases) - both are "no update info", not errors.
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val releaseUrl = latestReleaseUrlOverride ?: LATEST_RELEASE_URL
        trace("fetch entry url=$releaseUrl")
        val info = runCatching {
            val manifestUrl = findAssetUrl(get(releaseUrl), MANIFEST_ASSET_NAME) ?: return@runCatching null
            parseManifest(get(manifestUrl))
        }.onFailure { trace("fetch error ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
        debugLastFetch = "url=$releaseUrl found=${info != null}"
        trace("fetch exit found=${info != null}")
        info
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Downloads the update APK into the app cache and verifies its SHA-256 against the
     * manifest digest. Throws (and deletes the file) on any mismatch or network error.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "update.apk")
            try {
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 60_000
                }
                try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val percent = ((done * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (!sha256Hex(out).equals(info.sha256, ignoreCase = true)) {
                    throw java.io.IOException("digest mismatch")
                }
                out
            } catch (e: Exception) {
                out.delete()
                throw e
            }
        }

    /** Facts extracted from a downloaded APK, checked before the install sheet is offered. */
    internal data class CandidateApk(val packageName: String, val versionCode: Long, val signerSha256: ByteArray)

    /**
     * The pure acceptance rule, unit-tested directly: the candidate must be this app's
     * package, strictly newer than the installed build, and signed with the same
     * certificate as the installed app. Anything else is refused before the system
     * install sheet is offered (which itself enforces signature continuity again).
     */
    internal fun checkCandidate(
        candidate: CandidateApk?,
        ownPackage: String,
        installedVersionCode: Long,
        ownSignerSha256: ByteArray?,
    ): Boolean {
        if (candidate == null || ownSignerSha256 == null) return false
        if (candidate.packageName != ownPackage) return false
        if (candidate.versionCode <= installedVersionCode) return false
        return candidate.signerSha256.contentEquals(ownSignerSha256)
    }

    /**
     * Verifies the downloaded APK beyond its digest: package name, versionCode and signing
     * certificate are read from the APK itself (a manifest lying about versionCode or
     * pointing at a different build is caught here) and checked with [checkCandidate].
     */
    fun verifyApk(context: Context, apk: File, installedVersionCode: Long): Boolean {
        val pm = context.packageManager
        val info = packageArchiveInfo(pm, apk.absolutePath)
        if (info == null) {
            trace("verifyApk: getPackageArchiveInfo returned null for ${apk.absolutePath}")
            return false
        }
        val apkVersionCode =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val apkSigner = firstSigner(info)
        val ownSigner = packageInfo(pm, context.packageName)?.let { firstSigner(it) }
        val candidate = CandidateApk(info.packageName, apkVersionCode, apkSigner?.let { sha256(it) } ?: ByteArray(0))
        val result = checkCandidate(
            candidate,
            context.packageName,
            installedVersionCode,
            ownSigner?.let { sha256(it) },
        )
        trace("verifyApk: pkg=${info.packageName} vc=$apkVersionCode installed=$installedVersionCode " +
            "signingInfo=${info.signingInfo != null} signer=${apkSigner?.let { hex(sha256(it)) } ?: "null"} " +
            "ownSigner=${ownSigner?.let { hex(sha256(it)) } ?: "null"} result=$result")
        return result
    }

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    // API 29 quirk, proven on the CI emulator (run 42453757686): getPackageArchiveInfo
    // with GET_SIGNING_CERTIFICATES returns signingInfo=NULL for a valid v1+v2 signed
    // archive (while getPackageInfo on the installed package fills it). Request the
    // legacy GET_SIGNATURES too and fall back to the deprecated signatures array, or
    // every update is rejected as unverifiable on those devices.
    private fun packageArchiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        }

    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    internal fun firstSigner(info: PackageInfo): ByteArray? =
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            ?: info.signatures?.firstOrNull()?.toByteArray()

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Resume decision after the user was sent to the unknown-sources grant page: offer the
     * install sheet only when a verified download is still staged and the grant is now on.
     */
    internal fun shouldAutoPromptOnResume(pendingGrantReturn: Boolean, state: UpdateState, canInstall: Boolean): Boolean =
        pendingGrantReturn && state is UpdateState.ReadyToInstall && canInstall

    /** Whether this app may request package installs (unknown-sources grant for the app). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user grants this app the one-time install permission. */
    fun openUnknownSourcesSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the verified APK to the system package installer; the user confirms the sheet. */
    fun promptInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        trace("promptInstall uri=$uri")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
