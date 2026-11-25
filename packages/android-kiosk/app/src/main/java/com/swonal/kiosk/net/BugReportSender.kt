package com.swonal.kiosk.net

import android.os.Build
import android.os.Process
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.util.BugReportSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BugReportContext(
    val userNote: String,
    val includeLogs: Boolean,
    val syncModeLabel: String,
    val daemonConnected: Boolean,
    val daemonState: String,
    val pendingSyncCount: Int,
    val redactNames: List<String>
)

/**
 * Collects PII-scrubbed diagnostic logs and posts a structured bug report embed + log attachment
 * to a configured Discord webhook. Uses [SwonalTlsConfig] for Android 6.0 (API 23) TLS 1.2 + modern CA trust.
 */
object BugReportSender {

    fun resolveWebhookUrl(prefs: SwonalPreferences): String {
        val override = prefs.bugReportWebhookUrl?.trim().orEmpty()
        if (override.isNotEmpty()) return override
        return BuildConfig.BUG_REPORT_WEBHOOK_URL.trim()
    }

    suspend fun sendReport(
        webhookUrl: String,
        ctx: BugReportContext,
        tlsConfig: SwonalTlsConfig?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanWebhook = webhookUrl.trim().removeSuffix("/github")
            if (!BugReportSanitizer.isValidDiscordWebhookUrl(cleanWebhook)) {
                throw IllegalArgumentException(
                    "Invalid Discord webhook URL (must start with https://discord.com/api/webhooks/)"
                )
            }

            val versionDisplay = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
            val deviceDisplay = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val safeNote = BugReportSanitizer.sanitize(
                ctx.userNote.trim().ifEmpty { "(No description provided)" },
                redactNames = ctx.redactNames,
                maxChars = 1500
            )

            val payloadJson = buildJsonObject {
                put("username", "Swonal Bug Reports")
                put("embeds", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Swonal Kiosk Bug Report — $versionDisplay")
                        put("description", safeNote)
                        put("color", 0xEF4444)
                        put("fields", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "App Version")
                                put("value", versionDisplay)
                                put("inline", true)
                            })
                            add(buildJsonObject {
                                put("name", "Sync Mode")
                                put("value", "${ctx.syncModeLabel} (${ctx.pendingSyncCount} pending)")
                                put("inline", true)
                            })
                            add(buildJsonObject {
                                put("name", "Daemon")
                                put("value", if (ctx.daemonConnected) "Connected (${ctx.daemonState})" else "Disconnected")
                                put("inline", true)
                            })
                            add(buildJsonObject {
                                put("name", "Device / OS")
                                put("value", deviceDisplay)
                                put("inline", false)
                            })
                        })
                    })
                })
            }.toString()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payloadJson)

            if (ctx.includeLogs) {
                val rawLogs = collectDiagnosticsText(versionDisplay, deviceDisplay, ctx)
                val sanitizedLogs = BugReportSanitizer.sanitize(
                    rawLogs,
                    redactNames = ctx.redactNames,
                    maxChars = 55_000
                )
                val fileName = "swonal-bug-report-v${BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt"
                multipart.addFormDataPart(
                    "files[0]",
                    fileName,
                    sanitizedLogs.toRequestBody("text/plain; charset=utf-8".toMediaType())
                )
            }

            val client = OkHttpClient.Builder().apply {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
                if (tlsConfig != null) {
                    sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
                }
            }.build()

            val req = Request.Builder()
                .url(cleanWebhook)
                .header("User-Agent", "SwonalKiosk/${BuildConfig.VERSION_NAME}")
                .post(multipart.build())
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(200)
                    throw IOException("Discord returned HTTP ${resp.code}: $body")
                }
            }
        }
    }

    private fun collectDiagnosticsText(
        versionDisplay: String,
        deviceDisplay: String,
        ctx: BugReportContext
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== Swonal Diagnostic Report ===")
        sb.appendLine("App Version: $versionDisplay")
        sb.appendLine("Device: $deviceDisplay")
        sb.appendLine("Sync Mode: ${ctx.syncModeLabel} (pending=${ctx.pendingSyncCount})")
        sb.appendLine("Daemon: connected=${ctx.daemonConnected}, state=${ctx.daemonState}")
        sb.appendLine()
        sb.appendLine("--- App Logcat (last 250 lines) ---")
        sb.appendLine(readLogcatTail())
        val daemonTail = readDaemonLogTail()
        if (daemonTail.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- tonal-daemon.log (tail) ---")
            sb.appendLine(daemonTail)
        }
        return sb.toString()
    }

    private fun readLogcatTail(): String {
        val pid = Process.myPid().toString()
        val attempts = listOf(
            arrayOf("logcat", "-d", "-v", "time", "-t", "250", "--pid=$pid"),
            arrayOf("logcat", "-d", "-v", "time", "-t", "250")
        )
        for (cmd in attempts) {
            val out = runCatching {
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (proc.exitValue() == 0 && text.isNotBlank()) text else null
            }.getOrNull()
            if (!out.isNullOrBlank()) return out
        }
        return "(logcat unavailable)"
    }

    private fun readDaemonLogTail(): String = runCatching {
        val file = File("/data/swonal/daemon.log")
        if (!file.exists() || !file.canRead()) return@runCatching ""
        file.readLines().takeLast(120).joinToString("\n")
    }.getOrDefault("")
}
