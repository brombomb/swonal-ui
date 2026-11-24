package com.swonal.kiosk.music

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.integrations.LocalHomeAssistantClient
import com.swonal.kiosk.model.MusicPlaybackState
import com.swonal.kiosk.model.MusicProviderCapabilities
import com.swonal.kiosk.model.MusicProviderInfo
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.util.SecretCipher
import com.swonal.kiosk.util.UrlSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class StoredLanMusicConfigs(
    val plexampUrl: String = "",
    val plexTokenEnc: String? = null,
    val ytmApiUrl: String = "",
    val ytmAuthTokenEnc: String? = null,
    val appleHaEntityId: String = "",
    val amazonHaEntityId: String = ""
)

/**
 * Lightweight app-first LAN controllers for optional secondary music providers:
 * - Headless Plexamp (:32500)
 * - YouTube Music Desktop Companion (:26538)
 * - Apple Music / Amazon Music via Home Assistant media_player REST calls
 */
class LocalLanMusicProviders(
    private val db: SwonalDatabase,
    private val cipher: SecretCipher,
    private val haClient: LocalHomeAssistantClient,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(3, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
        writeTimeout(3, TimeUnit.SECONDS)
        dns(UrlSecurity.createSafeDns(allowLoopback = false))
        followRedirects(false)
        followSslRedirects(false)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    suspend fun loadConfig(): StoredLanMusicConfigs {
        val raw = db.getSetting(SETTING_LAN_MUSIC) ?: return StoredLanMusicConfigs()
        return runCatching { SwonalJson.decodeFromString<StoredLanMusicConfigs>(raw) }
            .getOrDefault(StoredLanMusicConfigs())
    }

    suspend fun updateProviderConfig(providerId: String, body: JsonObject): StoredLanMusicConfigs {
        val cur = loadConfig()
        val clearToken = body["clearToken"]?.jsonPrimitive?.booleanOrNull == true
        val updated = when (providerId) {
            "plexamp" -> {
                val url = body["plexampUrl"]?.jsonPrimitive?.contentOrNull?.let { sanitizeHttpUrl(it) } ?: cur.plexampUrl
                val tok = body["plexToken"]?.jsonPrimitive?.contentOrNull
                cur.copy(
                    plexampUrl = url,
                    plexTokenEnc = if (clearToken) null else if (!tok.isNullOrBlank()) cipher.encrypt(tok.trim()) else cur.plexTokenEnc
                )
            }
            "youtube_music" -> {
                val url = body["apiUrl"]?.jsonPrimitive?.contentOrNull?.let { sanitizeHttpUrl(it) } ?: cur.ytmApiUrl
                val tok = body["authToken"]?.jsonPrimitive?.contentOrNull
                cur.copy(
                    ytmApiUrl = url,
                    ytmAuthTokenEnc = if (clearToken) null else if (!tok.isNullOrBlank()) cipher.encrypt(tok.trim()) else cur.ytmAuthTokenEnc
                )
            }
            "apple_music" -> {
                val ent = body["haEntityId"]?.jsonPrimitive?.contentOrNull?.trim() ?: cur.appleHaEntityId
                cur.copy(appleHaEntityId = ent)
            }
            "amazon_music" -> {
                val ent = body["haEntityId"]?.jsonPrimitive?.contentOrNull?.trim() ?: cur.amazonHaEntityId
                cur.copy(amazonHaEntityId = ent)
            }
            else -> cur
        }
        db.setSetting(SETTING_LAN_MUSIC, SwonalJson.encodeToString(updated))
        return updated
    }

    suspend fun getSecondaryProviders(): List<MusicProviderInfo> {
        val cfg = loadConfig()
        return listOf(
            MusicProviderInfo("youtube_music", "YouTube Music", cfg.ytmApiUrl.isNotBlank(), MusicProviderCapabilities(false, false, false)),
            MusicProviderInfo("apple_music", "Apple Music (HA)", cfg.appleHaEntityId.isNotBlank(), MusicProviderCapabilities(false, false, false)),
            MusicProviderInfo("amazon_music", "Amazon Music (HA)", cfg.amazonHaEntityId.isNotBlank(), MusicProviderCapabilities(false, false, false)),
            MusicProviderInfo("plexamp", "Plexamp", cfg.plexampUrl.isNotBlank(), MusicProviderCapabilities(false, false, false))
        )
    }

    suspend fun controlAction(providerId: String, action: String, volumePct: Int? = null): MusicPlaybackState = withContext(Dispatchers.IO) {
        val cfg = loadConfig()
        when (providerId) {
            "plexamp" -> {
                if (cfg.plexampUrl.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                val plexToken = cfg.plexTokenEnc?.takeIf { it.isNotBlank() }?.let { cipher.decrypt(it) }
                val base = cfg.plexampUrl.removeSuffix("/")
                if (action == "state") {
                    val reqBuilder = Request.Builder().url("$base/player/timeline/poll?wait=0").get()
                    if (!plexToken.isNullOrBlank()) reqBuilder.header("X-Plex-Token", plexToken)
                    return@withContext runCatching {
                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) return@use MusicPlaybackState(isConnected = false)
                            val xml = resp.body?.string().orEmpty()
                            val playing = Regex("""state="playing"""", RegexOption.IGNORE_CASE).containsMatchIn(xml)
                            MusicPlaybackState(isPlaying = playing, isConnected = true)
                        }
                    }.getOrDefault(MusicPlaybackState(isConnected = false))
                }
                val path = when (action) {
                    "play" -> "/player/playback/play"
                    "pause" -> "/player/playback/pause"
                    "next" -> "/player/playback/skipNext"
                    "previous" -> "/player/playback/skipPrevious"
                    "volume" -> "/player/playback/setParameters?volume=${(volumePct ?: 70).coerceIn(0, 100)}"
                    else -> ""
                }
                if (path.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                val ok = runCatching {
                    val reqBuilder = Request.Builder().url("$base$path").get()
                    if (!plexToken.isNullOrBlank()) {
                        reqBuilder.header("X-Plex-Token", plexToken)
                    }
                    client.newCall(reqBuilder.build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                MusicPlaybackState(isPlaying = ok && action != "pause", isConnected = ok)
            }
            "youtube_music" -> {
                if (cfg.ytmApiUrl.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                val ytmToken = cfg.ytmAuthTokenEnc?.let { cipher.decrypt(it) }
                val base = cfg.ytmApiUrl.removeSuffix("/")
                if (action == "state") {
                    val req = Request.Builder().url("$base/api/v1/song").get()
                    if (!ytmToken.isNullOrBlank()) req.header("Authorization", "Bearer $ytmToken")
                    return@withContext runCatching {
                        client.newCall(req.build()).execute().use { r ->
                            if (!r.isSuccessful) return@use MusicPlaybackState(isConnected = false)
                            val obj = SwonalJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                            MusicPlaybackState(
                                isPlaying = obj["isPaused"]?.jsonPrimitive?.booleanOrNull == false,
                                trackName = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                artistName = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                albumName = obj["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                artworkUrl = obj["imageSrc"]?.jsonPrimitive?.contentOrNull,
                                isConnected = true
                            )
                        }
                    }.getOrDefault(MusicPlaybackState(isConnected = false))
                }
                val (path, payloadJson) = when (action) {
                    "play" -> "/api/v1/play" to "{}"
                    "pause" -> "/api/v1/pause" to "{}"
                    "next" -> "/api/v1/next" to "{}"
                    "previous" -> "/api/v1/previous" to "{}"
                    "volume" -> "/api/v1/volume" to SwonalJson.encodeToString(
                        buildJsonObject { put("volume", (volumePct ?: 70).coerceIn(0, 100)) }
                    )
                    else -> "" to "{}"
                }
                if (path.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                val ok = runCatching {
                    val b = Request.Builder()
                        .url("$base$path")
                        .post(payloadJson.toRequestBody(jsonMediaType))
                    if (!ytmToken.isNullOrBlank()) b.header("Authorization", "Bearer $ytmToken")
                    client.newCall(b.build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                MusicPlaybackState(isPlaying = ok && action != "pause", isConnected = ok)
            }
            "apple_music", "amazon_music" -> {
                val entity = if (providerId == "apple_music") cfg.appleHaEntityId else cfg.amazonHaEntityId
                if (entity.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                if (action == "state") {
                    val st = haClient.getEntityState(entity)
                        ?: return@withContext MusicPlaybackState(isConnected = false)
                    val stateStr = st["state"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                    if (stateStr.isBlank() || stateStr == "unavailable" || stateStr == "unknown") {
                        return@withContext MusicPlaybackState(isConnected = false)
                    }
                    val attrs = st["attributes"] as? JsonObject
                    return@withContext MusicPlaybackState(
                        isPlaying = stateStr == "playing",
                        trackName = attrs?.get("media_title")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artistName = attrs?.get("media_artist")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        albumName = attrs?.get("media_album_name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        isConnected = true
                    )
                }
                val service = when (action) {
                    "play" -> "media_play"
                    "pause" -> "media_pause"
                    "next" -> "media_next_track"
                    "previous" -> "media_previous_track"
                    "volume" -> "volume_set"
                    else -> ""
                }
                if (service.isBlank()) return@withContext MusicPlaybackState(isConnected = false)
                val body = buildJsonObject {
                    put("entity_id", entity)
                    if (service == "volume_set") put("volume_level", ((volumePct ?: 70).coerceIn(0, 100) / 100.0))
                }
                val ok = haClient.callHaService("media_player", service, body)
                MusicPlaybackState(isPlaying = ok && action != "pause", isConnected = ok)
            }
            else -> MusicPlaybackState(isConnected = false)
        }
    }

    private fun sanitizeHttpUrl(raw: String): String =
        UrlSecurity.sanitizeExternalUrl(raw, allowLoopback = false)

    companion object {
        const val SETTING_LAN_MUSIC = "lan_music_providers_json"
    }
}
