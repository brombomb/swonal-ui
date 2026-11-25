package com.swonal.kiosk.music

import com.swonal.kiosk.db.StoredMusicIntegration
import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.getAnyMusicIntegrationWithToken
import com.swonal.kiosk.db.getMusicIntegration
import com.swonal.kiosk.db.upsertMusicIntegration
import com.swonal.kiosk.model.MusicDevice
import com.swonal.kiosk.model.MusicPlaybackState
import com.swonal.kiosk.model.MusicPlaylist
import com.swonal.kiosk.model.MusicProviderCapabilities
import com.swonal.kiosk.model.MusicProviderInfo
import com.swonal.kiosk.model.MusicTrack
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.LocalDaemonFailSafeClient
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.util.Base64Compat
import com.swonal.kiosk.util.CryptoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Serverless on-device Spotify Connect + Web API + Keymaster S256 PKCE controller.
 * Pairs Tonal Speakers (librespot) directly via 127.0.0.1:8765 and controls Spotify playback
 * directly over TLS with modern bundled Root CAs for Android 6.0 (API 23).
 */
class LocalSpotifyController(
    private val db: SwonalDatabase,
    private val localDaemon: LocalDaemonFailSafeClient,
    private val scope: CoroutineScope,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null,
    private val accountsBaseUrl: String = "https://accounts.spotify.com",
    private val apiBaseUrl: String = "https://api.spotify.com"
) {
    data class PkceSession(val userId: String, val codeVerifier: String, val createdAtMs: Long)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(6, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
        writeTimeout(6, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    private val pkceSessions = ConcurrentHashMap<String, PkceSession>()
    @Volatile
    private var latestPkceSession: PkceSession? = null

    private data class CachedPlaylists(val timestampMs: Long, val playlists: List<MusicPlaylist>)
    private val playlistCache = ConcurrentHashMap<String, CachedPlaylists>()
    private val duckJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    var rateLimitedUntilMs: Long = 0L
        private set

    fun resetRateLimit() {
        rateLimitedUntilMs = 0L
    }

    fun getProviderInfo(): MusicProviderInfo = MusicProviderInfo(
        id = "spotify",
        displayName = "Spotify Connect",
        configured = true,
        capabilities = MusicProviderCapabilities(
            supportsConnectDevices = true,
            supportsVolumeDucking = true,
            supportsPlaylists = true
        )
    )

    fun buildSpeakerPkceUrl(userId: String): String {
        val verifierBytes = CryptoCompat.randomBytes(48)
        val codeVerifier = Base64Compat.encodeUrlNoPadding(verifierBytes)
        val challenge = Base64Compat.encodeUrlNoPadding(CryptoCompat.sha256(codeVerifier.toByteArray(Charsets.US_ASCII)))
        val session = PkceSession(userId, codeVerifier, System.currentTimeMillis())
        latestPkceSession = session
        pkceSessions[userId] = session

        val url = "${accountsBaseUrl.removeSuffix("/")}/authorize".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("client_id", KEYMASTER_CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", KEYMASTER_REDIRECT_URI)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("scope", KEYMASTER_SCOPES)
            .addQueryParameter("state", userId)
            .build()
        return url.toString()
    }

    suspend fun exchangeKeymasterPkceCode(userId: String, rawCodeOrUrl: String): JsonObject = withContext(Dispatchers.IO) {
        val code = extractAuthCode(rawCodeOrUrl)
        require(code.isNotBlank()) { "Missing Spotify authorization code" }

        val session = pkceSessions[userId] ?: latestPkceSession
            ?: throw IllegalStateException("No active speaker pairing session found. Open Pair Tonal Speakers first.")

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", KEYMASTER_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", KEYMASTER_REDIRECT_URI)
            .add("code_verifier", session.codeVerifier)
            .build()

        val req = Request.Builder()
            .url("${accountsBaseUrl.removeSuffix("/")}/api/token")
            .post(form)
            .build()

        val (accessToken, refreshToken, expiresIn) = client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Speaker OAuth token exchange failed (${resp.code}): ${raw.take(200)}")
            }
            val obj = SwonalJson.parseToJsonElement(raw).jsonObject
            Triple(
                obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
            )
        }
        require(accessToken.isNotBlank()) { "Spotify returned empty access token" }

        val tokenExpiry = ((System.currentTimeMillis() / 1000L) + expiresIn).toInt()
        val meta = SwonalJson.encodeToString(
            buildJsonObject {
                put("authMode", "keymaster_pkce")
                put("clientId", KEYMASTER_CLIENT_ID)
            }
        )
        db.upsertMusicIntegration(
            userId = session.userId,
            provider = "spotify",
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiry = tokenExpiry,
            preferredDeviceId = "tonal-gym-librespot",
            metadataJson = meta,
            preserveExistingTokensWhenNull = false
        )

        val speakerStatus = provisionTonalSpeakers(accessToken, reset = false)
        scope.launch {
            delay(1800L)
            runCatching { transferPlayback(session.userId, "tonal-gym-librespot") }
        }

        buildJsonObject {
            put("success", true)
            if (speakerStatus != null) put("speakerStatus", speakerStatus)
        }
    }

    suspend fun getTonalSpeakerStatus(): JsonObject {
        val (code, raw) = runCatching { localDaemon.getDirectJson("/api/spotify/status") }
            .getOrElse { return buildJsonObject { put("daemonOnline", false); put("running", false); put("deviceName", "Tonal Speakers") } }
        if (code !in 200..299 || raw.isBlank()) {
            return buildJsonObject { put("daemonOnline", false); put("running", false); put("deviceName", "Tonal Speakers") }
        }
        val st = runCatching { SwonalJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return buildJsonObject { put("daemonOnline", false) }
        return buildJsonObject {
            put("daemonOnline", true)
            put("running", st["running"]?.jsonPrimitive?.booleanOrNull ?: false)
            put("hasCachedCredentials", st["has_cached_credentials"]?.jsonPrimitive?.booleanOrNull ?: false)
            st["authenticated_user"]?.let { put("authenticatedUser", it) }
            put("deviceName", st["device_name"]?.jsonPrimitive?.contentOrNull ?: "Tonal Speakers")
            put("activeAudioClients", st["active_audio_clients"]?.jsonPrimitive?.intOrNull ?: 0)
            put("isStreamingAudio", st["is_streaming_audio"]?.jsonPrimitive?.booleanOrNull ?: false)
            st["wlan_ip"]?.let { put("wlanIp", it) }
        }
    }

    suspend fun provisionTonalSpeakers(accessToken: String?, reset: Boolean): JsonObject? {
        val payload = buildJsonObject {
            if (!accessToken.isNullOrBlank()) put("access_token", accessToken)
            put("reset", reset)
        }
        val (code, raw) = runCatching {
            localDaemon.postDirectJson("/api/spotify/auth", SwonalJson.encodeToString(payload))
        }.getOrNull() ?: return null
        if (code !in 200..299 || raw.isBlank()) return null
        return runCatching { SwonalJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    suspend fun wakeTonalSpeakers(): JsonObject? {
        val (code, raw) = runCatching { localDaemon.postDirectJson("/api/spotify/wake", "{}") }.getOrNull() ?: return null
        if (code !in 200..299 || raw.isBlank()) return null
        return runCatching { SwonalJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private suspend fun findIntegration(userId: String): StoredMusicIntegration? {
        val own = db.getMusicIntegration(userId, "spotify")
        if (!own?.accessToken.isNullOrBlank()) return own
        return db.getAnyMusicIntegrationWithToken("spotify") ?: own
    }

    private suspend fun getAccessToken(userId: String): String {
        val integration = findIntegration(userId)
        val token = integration?.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Spotify account is not connected yet. Tap 'Tonal Speakers' to pair.")
        val nowSec = System.currentTimeMillis() / 1000L
        val exp = integration.tokenExpiry ?: 0
        if (exp > 0 && exp <= nowSec + 60 && !integration.refreshToken.isNullOrBlank()) {
            return refreshAccessToken(integration)
        }
        return token
    }

    private suspend fun refreshAccessToken(integration: StoredMusicIntegration): String = withContext(Dispatchers.IO) {
        val refresh = integration.refreshToken ?: return@withContext integration.accessToken.orEmpty()
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", KEYMASTER_CLIENT_ID)
            .build()
        val req = Request.Builder()
            .url("${accountsBaseUrl.removeSuffix("/")}/api/token")
            .post(form)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Spotify token refresh failed (HTTP ${resp.code})")
            }
            val obj = SwonalJson.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val newAccess = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Spotify refresh returned empty access_token")
            val newRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: refresh
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
            val newExpiry = ((System.currentTimeMillis() / 1000L) + expiresIn).toInt()
            db.upsertMusicIntegration(
                userId = integration.userId,
                provider = "spotify",
                accessToken = newAccess,
                refreshToken = newRefresh,
                tokenExpiry = newExpiry,
                preserveExistingTokensWhenNull = true
            )
            newAccess
        }
    }

    private suspend fun spotifyApi(
        userId: String,
        method: String,
        path: String,
        jsonBody: String? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (rateLimitedUntilMs > now) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L + 1L).coerceAtLeast(1L)
            throw IOException("Spotify API rate-limited. Retrying in ${waitSec}s.")
        }

        suspend fun doCall(token: String): Pair<Int, String> {
            val url = "${apiBaseUrl.removeSuffix("/")}$path"
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post((jsonBody ?: "").toRequestBody(if (jsonBody != null) jsonMediaType else null))
                "PUT" -> builder.put((jsonBody ?: "").toRequestBody(if (jsonBody != null) jsonMediaType else null))
            }
            return client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) {
                    val retrySec = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(5L, 60L) ?: 10L
                    rateLimitedUntilMs = System.currentTimeMillis() + retrySec * 1000L
                }
                resp.code to (resp.body?.string().orEmpty())
            }
        }

        val token = getAccessToken(userId)
        val first = doCall(token)
        if (first.first == 401) {
            val integration = findIntegration(userId)
            if (integration?.refreshToken != null) {
                val refreshed = runCatching { refreshAccessToken(integration) }.getOrNull()
                if (!refreshed.isNullOrBlank()) {
                    return@withContext doCall(refreshed)
                }
            }
        }
        first
    }

    suspend fun getPlaybackState(userId: String): MusicPlaybackState {
        val integration = findIntegration(userId)
        val connected = !integration?.accessToken.isNullOrBlank()
        if (!connected) {
            return MusicPlaybackState(isConnected = false)
        }
        val (code, raw) = runCatching { spotifyApi(userId, "GET", "/v1/me/player") }.getOrNull()
            ?: return MusicPlaybackState(isConnected = true)
        if (code == 204 || code !in 200..299 || raw.isBlank()) {
            return MusicPlaybackState(isConnected = true)
        }
        val obj = runCatching { SwonalJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return MusicPlaybackState(isConnected = true)
        val item = obj["item"] as? JsonObject
        val artists = (item?.get("artists") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")
            .orEmpty()
        val album = item?.get("album") as? JsonObject
        val artwork = (album?.get("images") as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
        val dev = obj["device"] as? JsonObject
        return MusicPlaybackState(
            isPlaying = obj["is_playing"]?.jsonPrimitive?.booleanOrNull ?: false,
            trackName = item?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            artistName = artists,
            albumName = album?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            artworkUrl = artwork,
            progressMs = obj["progress_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationMs = item?.get("duration_ms")?.jsonPrimitive?.longOrNull ?: 0L,
            activeDeviceId = dev?.get("id")?.jsonPrimitive?.contentOrNull,
            volumePct = dev?.get("volume_percent")?.jsonPrimitive?.intOrNull ?: 70,
            isDucked = false,
            isConnected = true
        )
    }

    suspend fun getDevices(userId: String): List<MusicDevice> {
        val list = mutableListOf<MusicDevice>()
        val connected = !findIntegration(userId)?.accessToken.isNullOrBlank()
        if (connected) {
            val (code, raw) = runCatching { spotifyApi(userId, "GET", "/v1/me/player/devices") }
                .getOrDefault(0 to "")
            if (code in 200..299 && raw.isNotBlank()) {
                val devs = runCatching {
                    SwonalJson.parseToJsonElement(raw).jsonObject["devices"]?.jsonArray
                }.getOrNull().orEmpty()
                for (el in devs) {
                    val d = el as? JsonObject ?: continue
                    val id = d["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val rawName = d["name"]?.jsonPrimitive?.contentOrNull ?: "Speaker"
                    val displayName = if (rawName.contains("librespot", ignoreCase = true) || rawName.contains("tonal", ignoreCase = true)) {
                        "Tonal Speakers"
                    } else {
                        rawName
                    }
                    list += MusicDevice(
                        id = id,
                        name = displayName,
                        type = d["type"]?.jsonPrimitive?.contentOrNull ?: "Speaker",
                        isActive = d["is_active"]?.jsonPrimitive?.booleanOrNull ?: false,
                        volumePct = d["volume_percent"]?.jsonPrimitive?.intOrNull ?: 70
                    )
                }
            }
        }
        val hasTonal = list.any { it.name.contains("Tonal", ignoreCase = true) }
        if (!hasTonal) {
            val speakerStatus = getTonalSpeakerStatus()
            val running = speakerStatus["running"]?.jsonPrimitive?.booleanOrNull ?: false
            val label = if (running) "Tonal Speakers" else "Tonal Speakers (Tap to Pair)"
            list.add(0, MusicDevice(id = "tonal-gym-librespot", name = label, type = "Speaker", isActive = false, volumePct = 85))
        }
        return list
    }

    suspend fun transferPlayback(userId: String, deviceId: String): MusicPlaybackState {
        var resolvedId = deviceId
        if (deviceId == "tonal-gym-librespot") {
            wakeTonalSpeakers()
            delay(500L)
            val real = getDevices(userId).firstOrNull { it.id != "tonal-gym-librespot" && it.name.contains("Tonal", ignoreCase = true) }
            if (real != null) resolvedId = real.id
        }
        val body = SwonalJson.encodeToString(
            buildJsonObject {
                put("device_ids", buildJsonArray { add(JsonPrimitive(resolvedId)) })
                put("play", true)
            }
        )
        spotifyApi(userId, "PUT", "/v1/me/player", body)
        db.upsertMusicIntegration(userId, "spotify", preferredDeviceId = resolvedId)
        delay(250L)
        return getPlaybackState(userId)
    }

    suspend fun play(userId: String, contextUri: String? = null, deviceId: String? = null): MusicPlaybackState {
        val targetDevice = resolveTargetDeviceId(userId, deviceId)
        val qs = if (!targetDevice.isNullOrBlank()) "?device_id=${URLEncoder.encode(targetDevice, "UTF-8")}" else ""
        val bodyObj = buildJsonObject {
            if (!contextUri.isNullOrBlank()) {
                if (contextUri.contains(":track:")) {
                    put("uris", buildJsonArray { add(JsonPrimitive(contextUri)) })
                } else {
                    put("context_uri", contextUri)
                }
            }
        }
        val body = if (bodyObj.isEmpty()) "{}" else SwonalJson.encodeToString(bodyObj)
        val (code, raw) = spotifyApi(userId, "PUT", "/v1/me/player/play$qs", body)
        if (code == 404 || raw.contains("NO_ACTIVE_DEVICE")) {
            val fallbackDev = targetDevice ?: getDevices(userId).firstOrNull()?.id
            if (!fallbackDev.isNullOrBlank() && fallbackDev != "tonal-gym-librespot") {
                transferPlayback(userId, fallbackDev)
                spotifyApi(userId, "PUT", "/v1/me/player/play?device_id=${URLEncoder.encode(fallbackDev, "UTF-8")}", body)
            }
        }
        delay(200L)
        return getPlaybackState(userId)
    }

    suspend fun pause(userId: String): MusicPlaybackState {
        spotifyApi(userId, "PUT", "/v1/me/player/pause", "{}")
        delay(150L)
        return getPlaybackState(userId)
    }

    suspend fun nextTrack(userId: String): MusicPlaybackState {
        spotifyApi(userId, "POST", "/v1/me/player/next", "")
        delay(200L)
        return getPlaybackState(userId)
    }

    suspend fun previousTrack(userId: String): MusicPlaybackState {
        spotifyApi(userId, "POST", "/v1/me/player/previous", "")
        delay(200L)
        return getPlaybackState(userId)
    }

    suspend fun setVolume(userId: String, volumePct: Int): MusicPlaybackState {
        val clamped = volumePct.coerceIn(0, 100)
        spotifyApi(userId, "PUT", "/v1/me/player/volume?volume_percent=$clamped", "")
        return getPlaybackState(userId).copy(volumePct = clamped)
    }

    suspend fun duckVolume(userId: String, targetPct: Int = 25, durationMs: Int = 1200): MusicPlaybackState {
        val st = getPlaybackState(userId)
        val original = st.volumePct ?: 70
        val duckTarget = minOf(original, targetPct.coerceIn(0, 100))
        duckJobs.remove(userId)?.cancel()
        runCatching { spotifyApi(userId, "PUT", "/v1/me/player/volume?volume_percent=$duckTarget", "") }
        duckJobs[userId] = scope.launch {
            delay(durationMs.toLong().coerceIn(200L, 10_000L))
            runCatching { spotifyApi(userId, "PUT", "/v1/me/player/volume?volume_percent=$original", "") }
        }
        return st.copy(volumePct = duckTarget, isDucked = true)
    }

    suspend fun getPlaylists(userId: String): List<MusicPlaylist> {
        val now = System.currentTimeMillis()
        playlistCache[userId]?.let { cached ->
            if (now - cached.timestampMs < 600_000L && cached.playlists.isNotEmpty()) {
                return cached.playlists
            }
        }
        val integration = findIntegration(userId)
        if (!integration?.accessToken.isNullOrBlank()) {
            val (code, raw) = runCatching { spotifyApi(userId, "GET", "/v1/me/playlists?limit=25") }
                .getOrDefault(0 to "")
            if (code in 200..299 && raw.isNotBlank()) {
                val items = runCatching { SwonalJson.parseToJsonElement(raw).jsonObject["items"]?.jsonArray }
                    .getOrNull().orEmpty()
                val parsed = items.mapNotNull { el ->
                    val p = el as? JsonObject ?: return@mapNotNull null
                    val id = p["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = p["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val uri = p["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:playlist:$id"
                    val img = (p["images"] as? JsonArray)?.firstOrNull()
                        ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                    val count = (p["tracks"] as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull ?: 0
                    MusicPlaylist(id, name, uri, img, count)
                }
                if (parsed.isNotEmpty()) {
                    playlistCache[userId] = CachedPlaylists(now, parsed)
                    return parsed
                }
            }
        }
        return FALLBACK_PLAYLISTS
    }

    suspend fun getPlaylistTracks(playlistId: String, userId: String): List<MusicTrack> {
        if (findIntegration(userId)?.accessToken.isNullOrBlank()) return emptyList()
        val encoded = URLEncoder.encode(playlistId, "UTF-8")
        val (code, raw) = runCatching {
            spotifyApi(userId, "GET", "/v1/playlists/$encoded/tracks?limit=50")
        }.getOrDefault(0 to "")
        if (code !in 200..299 || raw.isBlank()) return emptyList()
        val items = runCatching { SwonalJson.parseToJsonElement(raw).jsonObject["items"]?.jsonArray }
            .getOrNull().orEmpty()
        return items.mapNotNull { el ->
            val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
            val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = track["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val uri = track["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:track:$id"
            val artists = (track["artists"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")
                .orEmpty()
            val album = track["album"] as? JsonObject
            val art = (album?.get("images") as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
            MusicTrack(
                id = id,
                name = name,
                uri = uri,
                artistName = artists,
                albumName = album?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = track["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                artworkUrl = art
            )
        }
    }

    suspend fun getFavoritePlaylists(userId: String): List<String> {
        val raw = db.getSetting(SETTING_FAVORITES) ?: return emptyList()
        val map = runCatching { SwonalJson.decodeFromString<Map<String, List<String>>>(raw) }.getOrDefault(emptyMap())
        return map[userId].orEmpty()
    }

    suspend fun toggleFavoritePlaylist(userId: String, playlistId: String): List<String> {
        val raw = db.getSetting(SETTING_FAVORITES)
        val map = raw?.let { runCatching { SwonalJson.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }
            ?.toMutableMap() ?: mutableMapOf()
        val current = map[userId].orEmpty().toMutableList()
        if (current.contains(playlistId)) current.remove(playlistId) else current.add(playlistId)
        map[userId] = current
        db.setSetting(SETTING_FAVORITES, SwonalJson.encodeToString(map))
        return current
    }

    private suspend fun resolveTargetDeviceId(userId: String, explicitDeviceId: String?): String? {
        if (!explicitDeviceId.isNullOrBlank() && explicitDeviceId != "tonal-gym-librespot") {
            return explicitDeviceId
        }
        val devs = getDevices(userId)
        val tonal = devs.firstOrNull { it.id != "tonal-gym-librespot" && it.name.contains("Tonal", ignoreCase = true) }
        if (tonal != null) return tonal.id
        if (explicitDeviceId == "tonal-gym-librespot") {
            wakeTonalSpeakers()
            delay(500L)
            val refreshed = getDevices(userId).firstOrNull { it.id != "tonal-gym-librespot" && it.name.contains("Tonal", ignoreCase = true) }
            if (refreshed != null) return refreshed.id
        }
        return devs.firstOrNull { it.isActive }?.id ?: devs.firstOrNull { it.id != "tonal-gym-librespot" }?.id
    }

    companion object {
        const val KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        const val KEYMASTER_REDIRECT_URI = "http://127.0.0.1:8898/login"
        const val SETTING_FAVORITES = "favorite_playlists_by_user"

        val KEYMASTER_SCOPES = listOf(
            "playlist-read-private",
            "playlist-read-collaborative",
            "streaming",
            "user-library-read",
            "user-read-email",
            "user-read-private",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-recently-played",
            "app-remote-control"
        ).joinToString(" ")

        fun extractAuthCode(rawCodeOrUrl: String): String {
            val trimmed = rawCodeOrUrl.trim()
            if (!trimmed.contains("code=")) return trimmed
            val full = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://127.0.0.1/${trimmed.trimStart('/')}"
            }
            full.toHttpUrlOrNull()?.queryParameter("code")?.let { return it }
            val m = Regex("[?&]code=([^&#\\s]+)").find(trimmed)
            return m?.groupValues?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: trimmed
        }

        val FALLBACK_PLAYLISTS = listOf(
            MusicPlaylist("37i9dQZF1DXdLEN7aqioXM", "Beast Mode", "spotify:playlist:37i9dQZF1DXdLEN7aqioXM", "https://i.scdn.co/image/ab67706f0000000292496e27be37ff7b120c1840", 50),
            MusicPlaylist("37i9dQZF1DX76Wlfdnj7AP", "Beast Mode Hip-Hop", "spotify:playlist:37i9dQZF1DX76Wlfdnj7AP", "https://i.scdn.co/image/ab67706f000000027f30013b190f70a169e5d4cb", 50),
            MusicPlaylist("37i9dQZF1DXe6bg6qolagv", "Workout Motivation", "spotify:playlist:37i9dQZF1DXe6bg6qolagv", "https://i.scdn.co/image/ab67706f000000021c2c317ff6e65bb6d3fa96a1", 40),
            MusicPlaylist("37i9dQZF1DX70RN3TfWWJh", "Workout Beats", "spotify:playlist:37i9dQZF1DX70RN3TfWWJh", "https://i.scdn.co/image/ab67706f000000028a3f8516be4c86fb1ebdbd45", 45),
            MusicPlaylist("37i9dQZF1DWZq9KNPUuuAf", "Rock Workout", "spotify:playlist:37i9dQZF1DWZq9KNPUuuAf", "https://i.scdn.co/image/ab67706f000000025fa2d58066f10842db13e536", 50),
            MusicPlaylist("37i9dQZF1DX35Nm1nHV5vd", "Power Workout", "spotify:playlist:37i9dQZF1DX35Nm1nHV5vd", "https://i.scdn.co/image/ab67706f00000002497eb1df308c1d5f3032ce93", 50),
            MusicPlaylist("37i9dQZF1DX0XUsuxWHRQd", "Rap Workout", "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd", "https://i.scdn.co/image/ab67706f000000028d7ba37a3c3325c27ad6e6df", 50)
        )
    }
}
