package com.swonal.kiosk.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val changelogBullets: List<String>,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class UpdateInstallResult {
    data object InstalledAndRelaunching : UpdateInstallResult()
    data class FallbackInstallerLaunched(val file: File) : UpdateInstallResult()
    data class Failed(val message: String) : UpdateInstallResult()
}

/**
 * Checks GitHub Releases for newer Swonal Kiosk APKs, parses human-readable changelog bullets,
 * downloads the verified APK over HTTPS (using bundled modern root CAs on Android 6.0),
 * acquires a daemon-enforced motor maintenance lock, and performs a verified root install.
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val DEFAULT_LATEST_RELEASE_URL =
        "https://api.github.com/repos/brombomb/swonal-ui/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var tlsConfigRef: SwonalTlsConfig? = null

    /** True while an update download/staging/install transaction owns the motor maintenance lock. */
    val isUpdateInProgress = AtomicBoolean(false)

    @Volatile
    private var cachedClient: OkHttpClient? = null

    /**
     * Wire the app's bundled modern CA trust configuration into this isolated, cookie-less
     * update client so Android 6.0 (API 23) devices trust GitHub's modern TLS certificates.
     */
    fun configureTls(config: SwonalTlsConfig) {
        tlsConfigRef = config
        cachedClient = null
    }

    private fun getHttpClient(): OkHttpClient {
        cachedClient?.let { return it }
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        tlsConfigRef?.let { tls ->
            builder.sslSocketFactory(tls.sslSocketFactory, tls.trustManager)
        }
        return builder.build().also { cachedClient = it }
    }

    @Volatile
    var latestAvailableUpdate: AppUpdateInfo? = null
        private set

    @Volatile
    var dismissedVersionInSession: String? = null

    /**
     * Acquire or release the local daemon's motor maintenance lock (`POST /api/motor/maintenance`).
     * While locked, the daemon immediately disengages weight and rejects all engagement paths
     * (BLE handle buttons, WebSocket, and HTTP) until the updated kiosk releases the lock.
     */
    suspend fun setDaemonMaintenanceLock(
        localDaemonUrl: String,
        locked: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val base = SwonalPreferences.normalizeLoopbackDaemonUrl(localDaemonUrl).trimEnd('/')
        val body = """{"locked":$locked}""".toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("$base/api/motor/maintenance")
            .post(body)
            .build()
        runCatching {
            getHttpClient().newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun checkForUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME,
        releaseApiUrl: String = DEFAULT_LATEST_RELEASE_URL
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(releaseApiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SwonalKiosk/$currentVersion")
                .get()
                .build()

            getHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("Update server returned HTTP ${resp.code}")
                }
                val raw = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(raw).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (tag.isBlank()) {
                    return@withContext UpdateCheckResult.Error("Release response missing tag_name")
                }
                val cleanVersion = tag.removePrefix("v").removePrefix("V")
                if (!isNewerVersion(cleanVersion, currentVersion)) {
                    latestAvailableUpdate = null
                    return@withContext UpdateCheckResult.UpToDate(currentVersion)
                }

                val assets = obj["assets"]?.jsonArray.orEmpty()
                val apkAsset = assets.mapNotNull { it.jsonObject }.firstOrNull { asset ->
                    val name = asset["name"]?.jsonPrimitive?.content?.lowercase().orEmpty()
                    name.endsWith(".apk")
                } ?: return@withContext UpdateCheckResult.Error("Release $tag has no APK asset yet")

                val downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                if (!isAllowedDownloadUrl(downloadUrl)) {
                    return@withContext UpdateCheckResult.Error("Blocked untrusted APK download host")
                }

                val sizeBytes = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val title = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Swonal $tag"
                val body = obj["body"]?.jsonPrimitive?.content.orEmpty()
                val publishedAt = obj["published_at"]?.jsonPrimitive?.content.orEmpty()

                val info = AppUpdateInfo(
                    versionName = cleanVersion,
                    tagName = tag,
                    title = title,
                    changelogBullets = formatChangelogBullets(body),
                    apkDownloadUrl = downloadUrl,
                    apkSizeBytes = sizeBytes,
                    publishedAt = publishedAt
                )
                latestAvailableUpdate = info
                UpdateCheckResult.UpdateAvailable(info)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            UpdateCheckResult.Error(e.message ?: "Unable to check for updates")
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdateInfo,
        localDaemonUrl: String = SwonalPreferences.DEFAULT_DAEMON_URL,
        isWeightEngaged: () -> Boolean,
        onProgress: (percent: Int, status: String) -> Unit
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        if (isWeightEngaged()) {
            return@withContext UpdateInstallResult.Failed("Disengage weight before installing updates")
        }
        if (!isAllowedDownloadUrl(update.apkDownloadUrl)) {
            return@withContext UpdateInstallResult.Failed("Untrusted download URL")
        }
        if (!isUpdateInProgress.compareAndSet(false, true)) {
            return@withContext UpdateInstallResult.Failed("An update is already in progress")
        }

        val tmpFile = File(context.cacheDir, "swonal-update.apk.tmp")
        val finalFile = File(context.cacheDir, "swonal-update.apk")
        runCatching { tmpFile.delete() }
        runCatching { finalFile.delete() }

        var maintenanceLocked = false
        var keepLockForRelaunch = false
        try {
            onProgress(0, "Connecting to GitHub Releases...")
            val req = Request.Builder()
                .url(update.apkDownloadUrl)
                .header("User-Agent", "SwonalKiosk/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            getHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateInstallResult.Failed("Download failed (HTTP ${resp.code})")
                }
                val finalUrl = resp.request.url.toString()
                if (!isAllowedDownloadUrl(finalUrl)) {
                    return@withContext UpdateInstallResult.Failed("Blocked redirect to untrusted host")
                }
                val body = resp.body ?: return@withContext UpdateInstallResult.Failed("Empty download body")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: update.apkSizeBytes

                var downloaded = 0L
                var lastReportedPct = -1
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    val mbDone = downloaded / (1024.0 * 1024.0)
                                    val mbTotal = totalBytes / (1024.0 * 1024.0)
                                    onProgress(pct, String.format("Downloading %.1f / %.1f MB...", mbDone, mbTotal))
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Verify APK ZIP magic header (PK\x03\x04)
            if (!isValidApkFile(tmpFile)) {
                runCatching { tmpFile.delete() }
                return@withContext UpdateInstallResult.Failed("Downloaded file is not a valid APK")
            }
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                runCatching { tmpFile.delete() }
            }

            if (isWeightEngaged()) {
                return@withContext UpdateInstallResult.Failed("Weight engaged — cancelled install for safety")
            }

            // Acquire daemon-enforced maintenance lock so BLE buttons or other clients cannot
            // energize the motor while the kiosk APK is being replaced.
            onProgress(100, "Locking motor safety for update...")
            maintenanceLocked = setDaemonMaintenanceLock(localDaemonUrl, locked = true)
            if (!maintenanceLocked) {
                return@withContext UpdateInstallResult.Failed(
                    "Could not acquire motor safety lock from daemon — update aborted"
                )
            }
            if (isWeightEngaged()) {
                return@withContext UpdateInstallResult.Failed("Weight engaged — cancelled install for safety")
            }

            onProgress(100, "Staging Swonal v${update.versionName}...")
            val stagedPath = "/data/local/tmp/swonal-update.apk"
            val srcPath = finalFile.absolutePath

            // Step 1: Synchronously stage APK and verify exit status
            val stageRes = RootShell.run("cp \"$srcPath\" \"$stagedPath\" && chmod 644 \"$stagedPath\"")
            if (stageRes.isSuccess) {
                onProgress(100, "Installing Swonal v${update.versionName}...")
                // Step 2: Run pm install synchronously so failures (e.g. signature mismatch) are surfaced;
                // only background the post-install relaunch after pm install succeeds.
                val installCmd =
                    "pm install -r \"$stagedPath\" && rm -f \"$stagedPath\" && " +
                        "(sleep 1 && am start -n com.swonal.kiosk/.ui.MainActivity >/dev/null 2>&1 &)"
                val installRes = RootShell.run(installCmd)
                if (installRes.isSuccess) {
                    // Keep maintenance lock until the newly launched MainActivity clears it in onCreate
                    keepLockForRelaunch = true
                    return@withContext UpdateInstallResult.InstalledAndRelaunching
                }
                val errDetail = installRes.stderr.ifBlank { installRes.stdout }.ifBlank { "pm install exited ${installRes.exitCode}" }
                Log.e(TAG, "Root pm install failed: $errDetail")
                runCatching { RootShell.run("rm -f \"$stagedPath\"") }
                return@withContext UpdateInstallResult.Failed("Install failed: $errDetail")
            }

            Log.w(TAG, "Root staging unavailable (${stageRes.exitCode}); trying FileProvider system installer")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return@withContext UpdateInstallResult.Failed("Enable 'Install unknown apps' permission, then retry")
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updateprovider",
                finalFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            UpdateInstallResult.FallbackInstallerLaunched(finalFile)
        } catch (e: Exception) {
            Log.e(TAG, "Update download/install failed", e)
            runCatching { tmpFile.delete() }
            UpdateInstallResult.Failed(e.message ?: "Update failed")
        } finally {
            if (!keepLockForRelaunch) {
                if (maintenanceLocked) {
                    withContext(NonCancellable) {
                        setDaemonMaintenanceLock(localDaemonUrl, locked = false)
                    }
                }
                isUpdateInProgress.set(false)
            }
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        return runCatching {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) == 4 } &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }.getOrDefault(false)
    }

    /**
     * Strict allowlist for APK downloads: HTTPS only on github.com or *.githubusercontent.com.
     */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase().orEmpty()
        return host == "github.com" ||
            host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    /**
     * Compare two semver strings (e.g., "v1.2.0" > "1.1.1").
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseSemVer(remote) ?: return false
        val c = parseSemVer(current) ?: return false
        for (i in 0..2) {
            if (r[i] != c[i]) return r[i] > c[i]
        }
        return false
    }

    private fun parseSemVer(raw: String): IntArray? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        val parts = cleaned.split('.')
        if (parts.isEmpty()) return null
        val nums = IntArray(3)
        for (i in 0..2) {
            nums[i] = parts.getOrNull(i)?.toIntOrNull() ?: 0
        }
        return nums
    }

    /**
     * Convert raw GitHub Release / CHANGELOG.md markdown into short, human-readable bullet strings.
     */
    fun formatChangelogBullets(rawMarkdown: String): List<String> {
        if (rawMarkdown.isBlank()) {
            return listOf("Bug fixes, performance improvements, and stability updates.")
        }
        val bullets = mutableListOf<String>()
        rawMarkdown.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.startsWith("#")) return@forEach
            if (trimmed.startsWith("**Full Changelog**", ignoreCase = true)) return@forEach
            if (trimmed.startsWith("Full Changelog:", ignoreCase = true)) return@forEach

            var cleaned = trimmed
                .removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("• ")
                .trim()

            // Strip markdown bold/code formatting and trailing GitHub PR/commit links
            cleaned = cleaned
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace("`", "")
                .replace(Regex("\\s+by\\s+@[A-Za-z0-9_-]+\\s+in\\s+https?://\\S+"), "")
                .replace(Regex("\\s*\\(https?://\\S+\\)"), "")
                .trim()

            if (cleaned.length >= 4 && bullets.size < 10) {
                bullets += cleaned
            }
        }
        return bullets.ifEmpty {
            listOf("Bug fixes, performance improvements, and stability updates.")
        }
    }
}
