package com.swonal.kiosk.integrations

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.model.HomeAssistantStatus
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.util.IsoTime
import com.swonal.kiosk.util.SecretCipher
import com.swonal.kiosk.util.UrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Serializable
data class StoredHaConfig(
    val webhookUrl: String = "",
    val webhookTokenEnc: String? = null,
    val haUrl: String = "",
    val haTokenEnc: String? = null
)

/**
 * Direct on-device Home Assistant webhook & REST publisher (works in Standalone Mode over LAN/HTTPS).
 */
class LocalHomeAssistantClient(
    private val db: SwonalDatabase,
    private val cipher: SecretCipher,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(4, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
        writeTimeout(4, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    private val recentEvents = CopyOnWriteArrayList<JsonObject>()

    suspend fun loadConfig(): StoredHaConfig {
        val raw = db.getSetting(SETTING_HA_CONFIG) ?: return StoredHaConfig()
        return runCatching { SwonalJson.decodeFromString<StoredHaConfig>(raw) }.getOrDefault(StoredHaConfig())
    }

    suspend fun updateConfig(
        webhookUrl: String? = null,
        webhookToken: String? = null,
        haUrl: String? = null,
        haToken: String? = null
    ): HomeAssistantStatus {
        val cur = loadConfig()
        val nextWebhook = webhookUrl?.let { sanitizeUrl(it) } ?: cur.webhookUrl
        val nextHaUrl = haUrl?.let { sanitizeUrl(it) } ?: cur.haUrl
        val nextWebhookTok = when {
            webhookToken == null -> cur.webhookTokenEnc
            webhookToken.isBlank() -> null
            else -> cipher.encrypt(webhookToken.trim())
        }
        val nextHaTok = when {
            haToken == null -> cur.haTokenEnc
            haToken.isBlank() -> null
            else -> cipher.encrypt(haToken.trim())
        }
        val updated = StoredHaConfig(nextWebhook, nextWebhookTok, nextHaUrl, nextHaTok)
        db.setSetting(SETTING_HA_CONFIG, SwonalJson.encodeToString(updated))
        return getStatus()
    }

    suspend fun getStatus(): HomeAssistantStatus {
        val cfg = loadConfig()
        return HomeAssistantStatus(
            mqttConfigured = false,
            mqttConnected = false,
            mqttTopicPrefix = "swonal",
            lastMqttError = null,
            webhookConfigured = cfg.webhookUrl.isNotBlank(),
            recentEvents = recentEvents.toList()
        )
    }

    suspend fun getHaUrlAndToken(): Pair<String, String?> {
        val cfg = loadConfig()
        val tok = cfg.haTokenEnc?.takeIf { it.isNotBlank() }?.let { cipher.decrypt(it) }
        return cfg.haUrl to tok
    }

    suspend fun publishEvent(eventType: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val cfg = loadConfig()
        val ts = IsoTime.nowIso()
        val record = buildJsonObject {
            put("event", eventType)
            put("topic", "swonal/events/$eventType")
            put("timestamp", ts)
            put("summary", "$eventType event")
            put("payload", payload)
        }
        recentEvents.add(0, record)
        while (recentEvents.size > 15) {
            runCatching { recentEvents.removeAt(recentEvents.lastIndex) }
        }

        if (cfg.webhookUrl.isNotBlank()) {
            val reqBuilder = Request.Builder()
                .url(cfg.webhookUrl)
                .post(SwonalJson.encodeToString(record).toRequestBody(jsonMediaType))
            val tok = cfg.webhookTokenEnc?.takeIf { it.isNotBlank() }?.let { cipher.decrypt(it) }
            if (!tok.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $tok")
            }
            runCatching {
                client.newCall(reqBuilder.build()).execute().use { /* fire-and-forget */ }
            }
        }
        record
    }

    suspend fun callHaService(domain: String, service: String, body: JsonObject): Boolean = withContext(Dispatchers.IO) {
        val (haUrl, haToken) = getHaUrlAndToken()
        if (haUrl.isBlank() || haToken.isNullOrBlank()) return@withContext false
        val url = "${haUrl.removeSuffix("/")}/api/services/$domain/$service"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $haToken")
                .post(SwonalJson.encodeToString(body).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun listMediaPlayers(): List<JsonObject> = withContext(Dispatchers.IO) {
        val (haUrl, haToken) = getHaUrlAndToken()
        if (haUrl.isBlank() || haToken.isNullOrBlank()) return@withContext emptyList()
        val url = "${haUrl.removeSuffix("/")}/api/states"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $haToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = SwonalJson.parseToJsonElement(resp.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonArray
                    ?: return@use emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val entityId = obj["entity_id"]?.toString()?.trim('"') ?: return@mapNotNull null
                    if (entityId.startsWith("media_player.")) obj else null
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sanitizeUrl(raw: String): String =
        UrlSecurity.sanitizeExternalUrl(raw, allowLoopback = false)

    companion object {
        const val SETTING_HA_CONFIG = "ha_config_json"
    }
}
