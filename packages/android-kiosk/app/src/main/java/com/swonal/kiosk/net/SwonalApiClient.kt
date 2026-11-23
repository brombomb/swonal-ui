package com.swonal.kiosk.net

import com.swonal.kiosk.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiHttpException(val statusCode: Int, message: String) : IOException(message)

class SwonalApiClient(
    private var baseUrlProvider: () -> String,
    private var tokenProvider: () -> String? = { null },
    private var sessionCookieStore: SessionCookieStore = InMemorySessionCookieStore(),
    tlsConfig: SwonalTlsConfig? = null,
    httpClient: OkHttpClient? = null
) {
    interface SessionCookieStore {
        fun getCookie(): String?
        fun saveCookie(cookieValue: String?)
    }

    class InMemorySessionCookieStore : SessionCookieStore {
        @Volatile
        private var cookie: String? = null
        override fun getCookie(): String? = cookie
        override fun saveCookie(cookieValue: String?) {
            cookie = cookieValue
        }
    }

    private val cookieCache = ConcurrentHashMap<String, List<Cookie>>()

    private fun configuredOrigin(): HttpUrl? =
        SwonalPreferences.normalizeBaseUrl(baseUrlProvider(), SwonalPreferences.DEFAULT_SERVER_URL)
            .toHttpUrlOrNull()

    private fun matchesConfiguredOrigin(url: HttpUrl): Boolean {
        val origin = configuredOrigin() ?: return false
        return url.scheme.equals(origin.scheme, ignoreCase = true) &&
            url.host.equals(origin.host, ignoreCase = true) &&
            url.port == origin.port
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Only accept swonal_session cookies from the configured server origin
            if (!matchesConfiguredOrigin(url)) return
            cookieCache[url.host] = cookies
            val swonalCookie = cookies.firstOrNull { it.name == "swonal_session" }
            if (swonalCookie != null) {
                sessionCookieStore.saveCookie(if (swonalCookie.value.isBlank()) null else swonalCookie.value)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Never leak Swonal auth cookies to any host/port/scheme other than the configured server
            if (!matchesConfiguredOrigin(url)) return emptyList()
            val saved = cookieCache[url.host].orEmpty().toMutableList()
            val storedVal = sessionCookieStore.getCookie()
            if (!storedVal.isNullOrBlank() && saved.none { it.name == "swonal_session" }) {
                val builder = Cookie.Builder()
                    .name("swonal_session")
                    .value(storedVal)
                    .domain(url.host)
                    .path("/")
                    .httpOnly()
                if (url.isHttps) builder.secure()
                saved += builder.build()
            }
            return saved
        }
    }

    val okHttpClient: OkHttpClient = httpClient ?: OkHttpClient.Builder().apply {
        cookieJar(cookieJar)
        // Prevent automatic cross-origin redirects from leaking Authorization / Cookie headers
        followRedirects(false)
        followSslRedirects(false)
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(12, TimeUnit.SECONDS)
        writeTimeout(10, TimeUnit.SECONDS)
        pingInterval(2, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String =
        SwonalPreferences.normalizeBaseUrl(baseUrlProvider(), SwonalPreferences.DEFAULT_SERVER_URL)

    fun clearCookies() {
        cookieCache.clear()
        sessionCookieStore.saveCookie(null)
    }

    /**
     * Attaches Authorization Bearer token ONLY to the configured server origin (and never over
     * non-local cleartext HTTP). Session cookies are attached cleanly by [cookieJar] without duplication.
     */
    fun buildAuthHeaders(builder: Request.Builder, targetUrl: String? = null): Request.Builder {
        val origin = configuredOrigin()
        val parsedTarget = targetUrl?.toHttpUrlOrNull() ?: origin
        val isHttpsOrLocal = parsedTarget != null &&
            (parsedTarget.isHttps || SwonalPreferences.isLoopbackOrPrivateHost(parsedTarget.host))
        val token = tokenProvider()
        if (!token.isNullOrBlank() && isHttpsOrLocal && matchesConfiguredOrigin(parsedTarget)) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}$path"
        val req = buildAuthHeaders(Request.Builder().url(url).get(), url).build()
        executeAndDecode(req)
    }

    private suspend inline fun <reified T> post(path: String, body: JsonObject = buildJsonObject {}): T =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl()}$path"
            val reqBody = SwonalJson.encodeToString(body).toRequestBody(jsonMediaType)
            val req = buildAuthHeaders(Request.Builder().url(url).post(reqBody), url).build()
            executeAndDecode(req)
        }

    private suspend inline fun <reified T> patch(path: String, body: JsonObject): T =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl()}$path"
            val reqBody = SwonalJson.encodeToString(body).toRequestBody(jsonMediaType)
            val req = buildAuthHeaders(Request.Builder().url(url).patch(reqBody), url).build()
            executeAndDecode(req)
        }

    private suspend inline fun <reified T> put(path: String, body: JsonObject): T =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl()}$path"
            val reqBody = SwonalJson.encodeToString(body).toRequestBody(jsonMediaType)
            val req = buildAuthHeaders(Request.Builder().url(url).put(reqBody), url).build()
            executeAndDecode(req)
        }

    suspend fun updateTemplateSchedule(templateId: String, scheduleDays: List<Int>): WorkoutTemplate =
        put(
            "/api/templates/${encPath(templateId)}",
            buildJsonObject {
                put("scheduleDays", kotlinx.serialization.json.JsonArray(scheduleDays.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        )

    private inline fun <reified T> executeAndDecode(request: Request): T {
        okHttpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = runCatching {
                    SwonalJson.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull() ?: "HTTP ${response.code}"
                throw ApiHttpException(response.code, errMsg)
            }
            return SwonalJson.decodeFromString(raw)
        }
    }

    private fun encPath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun requireSecureOrLocalForSecrets(actionName: String) {
        val origin = configuredOrigin() ?: return
        if (!origin.isHttps && !SwonalPreferences.isLoopbackOrPrivateHost(origin.host)) {
            throw IOException("Refusing to send $actionName over public cleartext HTTP (${origin.host}); use HTTPS")
        }
    }

    // --- Bootstrap & Auth ---
    suspend fun getAuthStatus(): AuthStatus = get("/api/auth/status")

    suspend fun login(passcode: String): JsonObject {
        requireSecureOrLocalForSecrets("passcode")
        return post(
            "/api/auth/login",
            buildJsonObject { put("passcode", passcode) }
        )
    }

    suspend fun getState(): AppStateResponse = get("/api/state")

    suspend fun getDaemonHealth(): DaemonHealth = get("/api/daemon/health")

    suspend fun getNetworkInfo(): NetworkInfo = get("/api/network-info")

    // --- Users ---
    suspend fun createUser(
        name: String,
        accentColor: String,
        avatarColor: String = accentColor
    ): User = post(
        "/api/users",
        buildJsonObject {
            put("name", name)
            put("accentColor", accentColor)
            put("avatarColor", avatarColor)
        }
    )

    suspend fun patchUser(
        userId: String,
        name: String? = null,
        accentColor: String? = null,
        avatarColor: String? = null,
        themeMode: String? = null,
        weightUnit: String? = null,
        preferredMusicProvider: String? = null
    ): User = patch(
        "/api/users/${encPath(userId)}",
        buildJsonObject {
            name?.let { put("name", it) }
            accentColor?.let { put("accentColor", it) }
            avatarColor?.let { put("avatarColor", it) }
            themeMode?.let { put("themeMode", it) }
            weightUnit?.let { put("weightUnit", it) }
            preferredMusicProvider?.let { put("preferredMusicProvider", it) }
        }
    )

    suspend fun getDemoPip(userId: String): DemoPipResponse = get("/api/users/${encPath(userId)}/demo-pip")

    suspend fun setDemoPip(userId: String, enabled: Boolean): DemoPipResponse = patch(
        "/api/users/${encPath(userId)}/demo-pip",
        buildJsonObject { put("enabled", enabled) }
    )

    // --- Sessions, Sets, PRs & Overload ---
    suspend fun getSessions(userId: String): List<WorkoutSession> =
        get("/api/sessions?userId=${encQuery(userId)}")

    suspend fun createSession(userId: String, templateId: String? = null): WorkoutSession = post(
        "/api/sessions",
        buildJsonObject {
            put("userId", userId)
            if (!templateId.isNullOrBlank()) put("templateId", templateId)
        }
    )

    suspend fun logSet(
        sessionId: String,
        userId: String,
        exerciseId: String,
        setNumber: Int,
        mode: String,
        modifierPct: Int,
        weightLb: Double,
        repsCompleted: Int,
        restSeconds: Int
    ): SetLogResponse = post(
        "/api/sessions/${encPath(sessionId)}/sets",
        buildJsonObject {
            put("userId", userId)
            put("exerciseId", exerciseId)
            put("setNumber", setNumber)
            put("mode", mode)
            put("modifierPct", modifierPct)
            put("weightLb", weightLb)
            put("repsCompleted", repsCompleted)
            put("restSeconds", restSeconds)
        }
    )

    suspend fun completeSession(sessionId: String): WorkoutSession =
        post("/api/sessions/${encPath(sessionId)}/complete")

    suspend fun getPrs(userId: String): List<PersonalRecord> = get("/api/prs/${encPath(userId)}")

    suspend fun getOverloadSuggestion(userId: String, exerciseId: String): OverloadSuggestion =
        get("/api/overload/${encPath(userId)}/${encPath(exerciseId)}")

    // --- Music ---
    suspend fun getMusicProviders(): List<MusicProviderInfo> = get("/api/music/providers")

    suspend fun getMusicFavorites(userId: String): MusicFavoritesResponse =
        get("/api/music/favorites?userId=${encQuery(userId)}")

    suspend fun toggleMusicFavorite(userId: String, playlistId: String): MusicFavoritesResponse = post(
        "/api/music/favorites/toggle",
        buildJsonObject {
            put("userId", userId)
            put("playlistId", playlistId)
        }
    )

    suspend fun getMusicState(provider: String, userId: String): MusicPlaybackState =
        get("/api/music/${encPath(provider)}/state?userId=${encQuery(userId)}")

    suspend fun playMusic(
        provider: String,
        userId: String,
        contextUri: String? = null,
        deviceId: String? = null
    ): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/play",
        buildJsonObject {
            put("userId", userId)
            contextUri?.let { put("contextUri", it) }
            deviceId?.let { put("deviceId", it) }
        }
    )

    suspend fun pauseMusic(provider: String, userId: String): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/pause",
        buildJsonObject { put("userId", userId) }
    )

    suspend fun nextTrack(provider: String, userId: String): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/next",
        buildJsonObject { put("userId", userId) }
    )

    suspend fun previousTrack(provider: String, userId: String): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/previous",
        buildJsonObject { put("userId", userId) }
    )

    suspend fun setMusicVolume(provider: String, userId: String, volumePct: Int): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/volume",
        buildJsonObject {
            put("userId", userId)
            put("volumePct", volumePct.coerceIn(0, 100))
        }
    )

    suspend fun duckMusic(
        provider: String,
        userId: String,
        targetPct: Int = 25,
        durationMs: Int = 1200
    ): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/duck",
        buildJsonObject {
            put("userId", userId)
            put("targetPct", targetPct)
            put("durationMs", durationMs)
        }
    )

    suspend fun getMusicPlaylists(provider: String, userId: String): List<MusicPlaylist> =
        get("/api/music/${encPath(provider)}/playlists?userId=${encQuery(userId)}")

    suspend fun getPlaylistTracks(
        provider: String,
        playlistId: String,
        userId: String
    ): List<MusicTrack> =
        get("/api/music/${encPath(provider)}/playlists/${encPath(playlistId)}/tracks?userId=${encQuery(userId)}")

    suspend fun getMusicDevices(provider: String, userId: String): List<MusicDevice> =
        get("/api/music/${encPath(provider)}/devices?userId=${encQuery(userId)}")

    suspend fun transferMusicDevice(
        provider: String,
        userId: String,
        deviceId: String
    ): MusicPlaybackState = post(
        "/api/music/${encPath(provider)}/transfer",
        buildJsonObject {
            put("userId", userId)
            put("deviceId", deviceId)
        }
    )

    suspend fun completeSpotifySpeakerAuth(
        userId: String?,
        codeOrUrl: String
    ): JsonObject = post(
        "/api/spotify/speaker-auth/complete",
        buildJsonObject {
            if (!userId.isNullOrBlank()) put("userId", userId)
            put("codeOrUrl", codeOrUrl)
        }
    )

    // --- Swocial / Nostr P2P Friends Feed ---
    suspend fun getSwocialIdentity(userId: String): JsonObject =
        get("/api/friends/identity/${encPath(userId)}")

    suspend fun getSwocialFriends(userId: String): kotlinx.serialization.json.JsonArray =
        get("/api/friends/${encPath(userId)}")

    suspend fun getSwocialFeed(userId: String, limit: Int = 25): kotlinx.serialization.json.JsonArray =
        get("/api/friends/feed/${encPath(userId)}?limit=$limit")

    suspend fun syncSwocialFeed(userId: String): JsonObject =
        post("/api/friends/sync/${encPath(userId)}")

    suspend fun addSwocialFriend(userId: String, npubOrHex: String, displayName: String?): JsonObject = post(
        "/api/friends/${encPath(userId)}",
        buildJsonObject {
            put("npubOrHex", npubOrHex)
            if (!displayName.isNullOrBlank()) put("displayName", displayName)
        }
    )

    suspend fun reactSwocialPost(userId: String, targetEventId: String, targetPubkey: String, emoji: String): JsonObject = post(
        "/api/friends/react/${encPath(userId)}",
        buildJsonObject {
            put("targetEventId", targetEventId)
            put("targetPubkey", targetPubkey)
            put("emoji", emoji)
        }
    )

    suspend fun importSwocialRoutine(userId: String, eventId: String): WorkoutTemplate =
        post("/api/friends/import-routine/${encPath(userId)}/${encPath(eventId)}")

    // --- Daemon Proxy Controls ---
    suspend fun switchToStockMode(): JsonObject = post("/api/daemon/mode/stock")

    suspend fun switchToCustomMode(): JsonObject = post("/api/daemon/mode/custom")

    suspend fun triggerMockBle(action: String): JsonObject = post(
        "/api/daemon/mock/ble-event",
        buildJsonObject { put("action", action) }
    )

    suspend fun triggerMockBarTilt(
        delta: Double = 48.0,
        durationSec: Double = 0.7,
        autoEnable: Boolean = false
    ): JsonObject = post(
        "/api/daemon/mock/bar-tilt",
        buildJsonObject {
            put("delta", delta)
            put("duration_sec", durationSec)
            put("auto_enable", autoEnable)
        }
    )

    suspend fun syncMockArmPositions(exercise: Exercise): JsonObject = post(
        "/api/daemon/mock/arm-positions",
        buildJsonObject {
            put("accessory", exercise.accessory)
            put("height_left", exercise.recommendedHeight)
            put("height_right", exercise.recommendedHeight)
            put("rot_left", exercise.recommendedRotation)
            put("rot_right", exercise.recommendedRotation)
            put("angle_left", exercise.recommendedAngle)
            put("angle_right", exercise.recommendedAngle)
        }
    )

    suspend fun getCalibration(): CalibrationStatus = get("/api/daemon/calibration")

    suspend fun zeroCalibration(): CalibrationStatus = post("/api/daemon/calibration/zero")

    // --- Home Assistant & AI Coach ---
    suspend fun getHomeAssistantStatus(): HomeAssistantStatus = get("/api/integrations/ha/status")

    suspend fun testHomeAssistantEvent(note: String = "Kiosk test ping"): JsonObject = post(
        "/api/integrations/ha/test",
        buildJsonObject { put("note", note) }
    )

    suspend fun getCoachConfig(): CoachConfig = get("/api/coach/config")

    suspend fun saveCoachConfig(
        provider: String,
        model: String,
        apiKey: String?,
        baseUrl: String?
    ): CoachConfig {
        if (!apiKey.isNullOrBlank()) {
            requireSecureOrLocalForSecrets("API key")
        }
        return post(
            "/api/coach/config",
            buildJsonObject {
                put("provider", provider)
                put("model", model)
                if (!apiKey.isNullOrBlank()) put("apiKey", apiKey)
                if (!baseUrl.isNullOrBlank()) put("baseUrl", baseUrl)
            }
        )
    }

    suspend fun askCoach(
        userId: String,
        prompt: String,
        exerciseName: String?,
        currentWeightLb: Double,
        repCount: Int,
        meanVelocity: Double,
        velocityDropPct: Double
    ): CoachAskResponse = post(
        "/api/coach/ask",
        buildJsonObject {
            put("userId", userId)
            put("prompt", prompt)
            exerciseName?.let { put("exerciseName", it) }
            put("currentWeightLb", currentWeightLb)
            put("repCount", repCount)
            put("meanVelocity", meanVelocity)
            put("velocityDropPct", velocityDropPct)
        }
    )
}
