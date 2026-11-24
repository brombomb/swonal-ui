package com.swonal.kiosk.sync

import com.swonal.kiosk.db.RestoreBundleDto
import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.applyPulledSync
import com.swonal.kiosk.db.collectSyncPushRequest
import com.swonal.kiosk.db.countDirtySyncRows
import com.swonal.kiosk.db.markPushedClean
import com.swonal.kiosk.engine.LocalWorkoutEngine
import com.swonal.kiosk.model.QueuedWorkoutPayload
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.TelemetryFrame
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.net.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional Power-User Home / Cloud Server synchronization engine.
 * - Strictly background / non-blocking: local Kiosk workouts never stall when offline.
 * - Handles seamless legacy upgrade import, idempotent push, LWW/tombstone pull,
 *   24/7 offline MCP queued-workout retrieval, and live /ws/sync event forwarding.
 */
class CloudSyncManager(
    private val db: SwonalDatabase,
    private val engine: LocalWorkoutEngine,
    private val prefs: SwonalPreferences,
    private val scope: CoroutineScope,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null,
    private val onStatusUpdate: (connected: Boolean, pendingCount: Int, lastSyncedAtMs: Long?, error: String?) -> Unit = { _, _, _, _ -> },
    private val onDataPulled: suspend () -> Unit = {},
    private val onWorkoutQueued: suspend (QueuedWorkoutPayload) -> Unit = {}
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)
        writeTimeout(10, TimeUnit.SECONDS)
        pingInterval(20, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    private val syncMutex = Mutex()
    private val running = AtomicBoolean(false)
    private val lastTelemetrySentMs = AtomicLong(0L)

    @Volatile
    private var syncWs: WebSocket? = null
    @Volatile
    private var wsConnected = false
    @Volatile
    private var lastSyncedAtMs: Long? = null
    @Volatile
    private var periodicJob: Job? = null
    @Volatile
    private var wsLoopJob: Job? = null
    @Volatile
    private var debounceJob: Job? = null

    private fun isEnabled(): Boolean =
        prefs.syncMode == SyncMode.SERVER_SYNC && prefs.syncServerUrl.trim().isNotBlank()

    private fun serverBaseUrl(): String = prefs.syncServerUrl.trim().removeSuffix("/")

    fun startOrReconfigure() {
        stop()
        if (!isEnabled()) {
            scope.launch {
                val dirty = runCatching { db.countDirtySyncRows() }.getOrDefault(0)
                onStatusUpdate(false, dirty, lastSyncedAtMs, null)
            }
            return
        }
        running.set(true)
        periodicJob = scope.launch(Dispatchers.IO) {
            syncNow()
            while (isActive && running.get()) {
                delay(60_000L)
                if (isEnabled()) {
                    syncNow()
                }
            }
        }
        wsLoopJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 2000L
            while (isActive && running.get()) {
                if (!wsConnected && syncWs == null && isEnabled()) {
                    openSyncSocket()
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                } else {
                    backoffMs = 2000L
                }
                delay(backoffMs)
            }
        }
    }

    fun stop() {
        running.set(false)
        debounceJob?.cancel()
        periodicJob?.cancel()
        wsLoopJob?.cancel()
        debounceJob = null
        periodicJob = null
        wsLoopJob = null
        wsConnected = false
        syncWs?.close(1000, "Sync disabled")
        syncWs = null
    }

    fun requestDebouncedSync() {
        if (!isEnabled()) return
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(1500L)
            syncNow()
        }
    }

    suspend fun syncNow(): Result<Unit> = syncMutex.withLock {
        if (!isEnabled()) {
            return Result.failure(IllegalStateException("Sync mode is Standalone"))
        }
        val base = serverBaseUrl()
        val res = runCatching {
            // Legacy upgrade import: pull server state before pushing initial seed records
            if (db.getSetting(LocalWorkoutEngine.SETTING_INITIAL_SERVER_IMPORT_DONE).isNullOrBlank()) {
                val initialPull = fetchPullBundle(base, sinceMs = 0L)
                db.applyPulledSync(initialPull)
                db.setSetting(LocalWorkoutEngine.SETTING_SYNC_CURSOR_MS, initialPull.serverTimeMs.toString())
                db.setSetting(LocalWorkoutEngine.SETTING_INITIAL_SERVER_IMPORT_DONE, "true")
                if (initialPull.pendingQueuedWorkout != null) {
                    deliverAndAckQueuedWorkout(base, initialPull.pendingQueuedWorkout)
                }
                onDataPulled()
            }

            // 1. Push dirty local rows idempotently
            val pushBundle = db.collectSyncPushRequest("tonal-kiosk")
            val pushReq = buildAuthedRequest("$base/api/sync/push")
                .post(SwonalJson.encodeToString(pushBundle).toRequestBody(jsonMediaType))
                .build()
            val pushRespText = withContext(Dispatchers.IO) {
                client.newCall(pushReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Sync push HTTP ${resp.code}: ${body.take(160)}")
                    body
                }
            }
            val pushResp = SwonalJson.decodeFromString(
                com.swonal.kiosk.db.SyncPushResponse.serializer(),
                pushRespText
            )
            db.markPushedClean(pushBundle, pushResp.exerciseIdRemap)

            // 2. Delta pull from server cursor
            val cursor = db.getSetting(LocalWorkoutEngine.SETTING_SYNC_CURSOR_MS)?.toLongOrNull() ?: 0L
            val pull = fetchPullBundle(base, sinceMs = cursor)
            db.applyPulledSync(pull)
            db.setSetting(LocalWorkoutEngine.SETTING_SYNC_CURSOR_MS, pull.serverTimeMs.toString())

            if (pull.pendingQueuedWorkout != null) {
                deliverAndAckQueuedWorkout(base, pull.pendingQueuedWorkout)
            }
            onDataPulled()
        }

        val pending = runCatching { db.countDirtySyncRows() }.getOrDefault(0)
        res.onSuccess {
            val now = System.currentTimeMillis()
            lastSyncedAtMs = now
            onStatusUpdate(true, pending, now, null)
        }.onFailure { err ->
            onStatusUpdate(false, pending, lastSyncedAtMs, err.message ?: "Sync unreachable")
        }
        return res
    }

    suspend fun restoreFromServer(): Result<Unit> = syncMutex.withLock {
        if (!isEnabled()) {
            return Result.failure(IllegalStateException("Configure a Home Server URL first"))
        }
        val base = serverBaseUrl()
        val res = runCatching {
            val req = buildAuthedRequest("$base/api/sync/restore-bundle")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            val raw = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Restore failed HTTP ${resp.code}: ${body.take(160)}")
                    body
                }
            }
            val bundle = SwonalJson.decodeFromString(RestoreBundleDto.serializer(), raw)
            val now = System.currentTimeMillis()
            db.applyPulledSync(
                com.swonal.kiosk.db.SyncPullResponse(
                    serverTimeMs = now,
                    users = bundle.users,
                    exercises = bundle.exercises,
                    templates = bundle.templates,
                    sessions = bundle.sessions,
                    setLogs = bundle.setLogs,
                    personalRecords = bundle.personalRecords,
                    friends = bundle.friends
                )
            )
            db.setSetting(LocalWorkoutEngine.SETTING_SYNC_CURSOR_MS, now.toString())
            db.setSetting(LocalWorkoutEngine.SETTING_INITIAL_SERVER_IMPORT_DONE, "true")
            onDataPulled()
        }
        val pending = runCatching { db.countDirtySyncRows() }.getOrDefault(0)
        res.onSuccess {
            val now = System.currentTimeMillis()
            lastSyncedAtMs = now
            onStatusUpdate(true, pending, now, null)
        }.onFailure { err ->
            onStatusUpdate(false, pending, lastSyncedAtMs, err.message ?: "Restore failed")
        }
        return res
    }

    fun forwardTelemetry(frame: TelemetryFrame) {
        val ws = syncWs ?: return
        if (!wsConnected) return
        val now = System.currentTimeMillis()
        if (now - lastTelemetrySentMs.get() < 500L) return
        lastTelemetrySentMs.set(now)
        val frameObj = SwonalJson.encodeToJsonElement(TelemetryFrame.serializer(), frame).jsonObject
        val json = SwonalJson.encodeToString(
            buildJsonObject {
                put("type", "telemetry")
                put("timestamp", now / 1000.0)
                for ((k, v) in frameObj) {
                    put(k, v)
                }
            }
        )
        runCatching { ws.send(json) }
    }

    fun forwardSessionEvent(eventType: String, payload: JsonObject) {
        val ws = syncWs ?: return
        if (!wsConnected) return
        val msg = SwonalJson.encodeToString(
            buildJsonObject {
                put("type", "session_event")
                put("eventType", eventType)
                put("payload", payload)
            }
        )
        runCatching { ws.send(msg) }
    }

    private suspend fun fetchPullBundle(base: String, sinceMs: Long): com.swonal.kiosk.db.SyncPullResponse {
        val req = buildAuthedRequest("$base/api/sync/pull?since=$sinceMs").get().build()
        val raw = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Sync pull HTTP ${resp.code}: ${body.take(160)}")
                body
            }
        }
        return SwonalJson.decodeFromString(com.swonal.kiosk.db.SyncPullResponse.serializer(), raw)
    }

    private suspend fun deliverAndAckQueuedWorkout(base: String, payload: QueuedWorkoutPayload) {
        onWorkoutQueued(payload)
        val ackJson = SwonalJson.encodeToString(
            buildJsonObject {
                put("templateId", payload.templateId)
                put("queuedAt", payload.queuedAt)
            }
        )
        val req = buildAuthedRequest("$base/api/sync/ack-queued-workout")
            .post(ackJson.toRequestBody(jsonMediaType))
            .build()
        runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().close()
            }
        }
    }

    private fun buildAuthedRequest(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        prefs.apiToken?.takeIf { it.isNotBlank() }?.let { tok ->
            b.header("Authorization", "Bearer $tok")
        }
        prefs.sessionCookie?.takeIf { it.isNotBlank() }?.let { cookie ->
            b.header("Cookie", cookie)
        }
        return b
    }

    private fun openSyncSocket() {
        val base = serverBaseUrl()
        if (base.isBlank()) return
        val wsUrl = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://${base.removePrefix("https://")}/ws/sync"
            base.startsWith("http://", ignoreCase = true) -> "ws://${base.removePrefix("http://")}/ws/sync"
            else -> return
        }
        val req = buildAuthedRequest(wsUrl).build()
        syncWs = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                scope.launch(Dispatchers.IO) {
                    val dirty = runCatching { db.countDirtySyncRows() }.getOrDefault(0)
                    onStatusUpdate(true, dirty, lastSyncedAtMs, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { SwonalJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "sync_poke" -> requestDebouncedSync()
                    "workout_queued" -> {
                        val wEl = obj["workout"] ?: return
                        val payload = runCatching {
                            SwonalJson.decodeFromJsonElement(QueuedWorkoutPayload.serializer(), wEl)
                        }.getOrNull() ?: return
                        scope.launch(Dispatchers.IO) {
                            onWorkoutQueued(payload)
                            val ackMsg = SwonalJson.encodeToString(
                                buildJsonObject {
                                    put("type", "ack_queued_workout")
                                    put("queuedAt", payload.queuedAt)
                                }
                            )
                            runCatching { webSocket.send(ackMsg) }
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                if (syncWs === webSocket) syncWs = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected = false
                if (syncWs === webSocket) syncWs = null
            }
        })
    }

    private companion object {
        // Alias matches LocalWorkoutEngine.SETTING_SYNC_CURSOR_MS
    }
}
