package com.swonal.kiosk.net

import com.swonal.kiosk.model.CalibrationStatus
import com.swonal.kiosk.model.DaemonHealth
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.HandleEvent
import com.swonal.kiosk.model.SafetyEvent
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.TelemetryFrame
import com.swonal.kiosk.model.WsInboundMessage
import com.swonal.kiosk.model.WsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3 — Local Fail-Safe Fast Path (`tonal-daemon` Direct Control on http://127.0.0.1:8765).
 *
 * Hardened for physical motor safety:
 * - Loopback-only target URL enforcement
 * - 2s WebSocket ping + 2.5s dead-socket liveness watchdog
 * - Pings gated by Main-thread UI liveness so daemon's 5s safety watchdog still fires on UI freeze
 * - Emergency disengage fires BOTH verified-open WS `disable_weight` and fast HTTP fallback in parallel
 */
class LocalDaemonFailSafeClient(
    private val daemonBaseUrlProvider: () -> String,
    private val scope: CoroutineScope,
    private val uiAliveCheck: () -> Boolean = { true },
    client: OkHttpClient? = null
) {
    private val wsClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .pingInterval(2, TimeUnit.SECONDS)
        .build()

    private val emergencyHttpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .writeTimeout(250, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastLocalTelemetry = MutableStateFlow<TelemetryFrame?>(null)
    val lastLocalTelemetry: StateFlow<TelemetryFrame?> = _lastLocalTelemetry.asStateFlow()

    private val _localTelemetry = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 32)
    val localTelemetry: SharedFlow<TelemetryFrame> = _localTelemetry.asSharedFlow()

    private val _localSafety = MutableSharedFlow<SafetyEvent>(extraBufferCapacity = 8)
    val localSafety: SharedFlow<SafetyEvent> = _localSafety.asSharedFlow()

    private val _localHandleEvents = MutableSharedFlow<HandleEvent>(extraBufferCapacity = 16)
    val localHandleEvents: SharedFlow<HandleEvent> = _localHandleEvents.asSharedFlow()

    private val _localErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val localErrors: SharedFlow<String> = _localErrors.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var lastFrameAtMs: Long = 0L
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var pingJob: Job? = null

    private val started = AtomicBoolean(false)

    private fun safeLoopbackBaseUrl(): String =
        SwonalPreferences.normalizeLoopbackDaemonUrl(daemonBaseUrlProvider())

    fun start() {
        if (!started.compareAndSet(false, true)) return
        connectLoop()
    }

    fun stop() {
        started.set(false)
        reconnectJob?.cancel()
        pingJob?.cancel()
        reconnectJob = null
        pingJob = null
        webSocket?.close(1000, "Stopped")
        webSocket = null
        _connected.value = false
    }

    fun reconnectNow() {
        if (!started.get()) {
            start()
            return
        }
        webSocket?.cancel()
        webSocket = null
        _connected.value = false
    }

    private fun connectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 500L
            while (isActive && started.get()) {
                if (!_connected.value && webSocket == null) {
                    openSocket()
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                } else {
                    backoffMs = 500L
                }
                delay(backoffMs)
            }
        }
    }

    private fun openSocket() {
        val wsUrl = SwonalPreferences.toWebSocketUrl(safeLoopbackBaseUrl())
        val req = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                lastFrameAtMs = System.currentTimeMillis()
                _connected.value = true
                startPingLoop()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                lastFrameAtMs = System.currentTimeMillis()
                when (val msg = WsMessageParser.parse(text)) {
                    is WsInboundMessage.Telemetry -> {
                        _lastLocalTelemetry.value = msg.frame
                        _localTelemetry.tryEmit(msg.frame)
                    }
                    is WsInboundMessage.Safety -> _localSafety.tryEmit(msg.event)
                    is WsInboundMessage.Handle -> _localHandleEvents.tryEmit(msg.event)
                    is WsInboundMessage.ServerError -> _localErrors.tryEmit(msg.message)
                    WsInboundMessage.Pong -> Unit
                    else -> Unit
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) {
                    webSocket = null
                    _connected.value = false
                    pingJob?.cancel()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) {
                    webSocket = null
                    _connected.value = false
                    pingJob?.cancel()
                }
            }
        })
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connected.value) {
                val now = System.currentTimeMillis()
                // Detect half-open / stalled local socket within 2.5s
                if (lastFrameAtMs > 0L && now - lastFrameAtMs > 2500L) {
                    webSocket?.cancel()
                    webSocket = null
                    _connected.value = false
                    break
                }
                // Only feed daemon's 5s hardware safety watchdog when Main UI thread is alive
                if (uiAliveCheck()) {
                    sendIfOpen(buildJsonObject { put("type", "ping") })
                }
                delay(1500L)
            }
        }
    }

    private fun sendIfOpen(payload: JsonObject): Boolean {
        if (!_connected.value) return false
        val ws = webSocket ?: return false
        return ws.send(SwonalJson.encodeToString(payload))
    }

    /**
     * Safety-first emergency disengage:
     * Fires verified-open WS `disable_weight` AND fast loopback HTTP fallback in parallel,
     * retrying up to 3 times if neither confirms delivery.
     */
    suspend fun emergencyDisableWeight(): Boolean = coroutineScope {
        val wsSent = sendIfOpen(buildJsonObject { put("type", "disable_weight") })
        val httpJob = async(Dispatchers.IO) {
            postEmergencyDisableHttp()
        }
        val httpOk = httpJob.await()
        if (wsSent || httpOk) return@coroutineScope true

        repeat(2) {
            delay(80L)
            if (sendIfOpen(buildJsonObject { put("type", "disable_weight") })) return@coroutineScope true
            if (postEmergencyDisableHttp()) return@coroutineScope true
        }
        false
    }

    fun enableWeightDirect(): Boolean =
        sendIfOpen(buildJsonObject { put("type", "enable_weight") })

    fun setWeightDirect(weightLb: Double, profile: String, modifierPct: Int): Boolean {
        return sendIfOpen(
            buildJsonObject {
                put("type", "set_weight")
                put("weight_lb", weightLb)
                put("profile", profile)
                put("modifier_pct", modifierPct)
            }
        )
    }

    fun setAccessoryDirect(accessory: String): Boolean {
        return sendIfOpen(
            buildJsonObject {
                put("type", "set_accessory")
                put("accessory", accessory)
            }
        )
    }

    fun resetRepsDirect(): Boolean =
        sendIfOpen(buildJsonObject { put("type", "reset_reps") })

    suspend fun fetchDirectHealth(): DaemonHealth? = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/health"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            emergencyHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                SwonalJson.decodeFromString<DaemonHealth>(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun switchToStockModeDirect(): Boolean = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/mode/stock"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun switchToCustomModeDirect(): Boolean = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/mode/custom"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun getCalibrationDirect(): CalibrationStatus? = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/calibration"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            emergencyHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                SwonalJson.decodeFromString<CalibrationStatus>(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun zeroCalibrationDirect(): CalibrationStatus? = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/calibration/zero"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            emergencyHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                SwonalJson.decodeFromString<CalibrationStatus>(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun triggerMockBleDirect(action: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/mock/ble-event"
        runCatching {
            val body = SwonalJson.encodeToString(buildJsonObject { put("action", action) })
                .toRequestBody(jsonMediaType)
            val req = Request.Builder().url(url).post(body).build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun triggerMockBarTiltDirect(
        delta: Double = 48.0,
        durationSec: Double = 0.7,
        autoEnable: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/mock/bar-tilt"
        runCatching {
            val body = SwonalJson.encodeToString(
                buildJsonObject {
                    put("delta", delta)
                    put("duration_sec", durationSec)
                    put("auto_enable", autoEnable)
                }
            ).toRequestBody(jsonMediaType)
            val req = Request.Builder().url(url).post(body).build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun setMockArmPositionsDirect(exercise: Exercise): Boolean = withContext(Dispatchers.IO) {
        val url = "${safeLoopbackBaseUrl()}/api/mock/arm-positions"
        runCatching {
            val body = SwonalJson.encodeToString(
                buildJsonObject {
                    put("accessory", exercise.accessory)
                    put("height_left", exercise.recommendedHeight)
                    put("height_right", exercise.recommendedHeight)
                    put("rot_left", exercise.recommendedRotation)
                    put("rot_right", exercise.recommendedRotation)
                    put("angle_left", exercise.recommendedAngle)
                    put("angle_right", exercise.recommendedAngle)
                }
            ).toRequestBody(jsonMediaType)
            val req = Request.Builder().url(url).post(body).build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun postDirectJson(path: String, jsonBody: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val url = "${safeLoopbackBaseUrl()}$cleanPath"
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        emergencyHttpClient.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    suspend fun getDirectJson(path: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val url = "${safeLoopbackBaseUrl()}$cleanPath"
        val req = Request.Builder().url(url).get().build()
        emergencyHttpClient.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    /**
     * Tries a dedicated `/api/motor/disable` endpoint first (if supported by daemon),
     * then falls back to `/api/mock/ble-event` `emergency_stop`.
     */
    private suspend fun postEmergencyDisableHttp(): Boolean = withContext(Dispatchers.IO) {
        val base = safeLoopbackBaseUrl()
        val dedicatedOk = runCatching {
            val req = Request.Builder()
                .url("$base/api/motor/disable")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (dedicatedOk) return@withContext true

        runCatching {
            val body = SwonalJson.encodeToString(buildJsonObject { put("action", "emergency_stop") })
                .toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$base/api/mock/ble-event").post(body).build()
            emergencyHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
