package com.swonal.kiosk.server

import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.util.CryptoCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class PayloadTooLargeException(message: String = "Request payload too large") : RuntimeException(message)

/**
 * Embedded LAN HTTP & WebSocket server running directly inside Android on port 3000 (configurable).
 * Powers 100% standalone Kiosk internal web views (/history, /settings, /admin), Phone Companion
 * (/companion), local /ws real-time updates, and bundled media form diagrams with zero central server.
 */
class EmbeddedSwonalServer(
    val repo: SwonalRepository,
    port: Int = 53013,
    val mediaDir: File,
    private val webAssetOpener: (String) -> InputStream?,
    private val mediaAssetOpener: (String) -> InputStream?,
    private val allowTestRemoteIpHeader: Boolean = false
) : NanoWSD("0.0.0.0", port) {

    data class RequestAuthContext(
        val isLoopback: Boolean,
        val authenticated: Boolean,
        val authEnabled: Boolean,
        val hasValidCredentials: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val router = EmbeddedApiRouter(this, repo)
    private val authSessions = ConcurrentHashMap<String, Long>()
    private val sockets = CopyOnWriteArraySet<SwonalWsClient>()
    private var broadcastJob: Job? = null

    fun startServer() {
        mediaDir.mkdirs()
        start(60_000, true)
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            repo.wsBroadcastFlow.collect { frameText ->
                broadcastRawWsFrame(frameText)
            }
        }
    }

    fun stopServer() {
        broadcastJob?.cancel()
        broadcastJob = null
        sockets.forEach { ws ->
            runCatching { ws.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Server stopping", false) }
        }
        sockets.clear()
        runCatching { stop() }
        scope.cancel()
    }

    fun broadcastRawWsFrame(rawJson: String) {
        for (client in sockets) {
            if (!client.isOpen) {
                sockets.remove(client)
                continue
            }
            val ok = runCatching { client.send(rawJson) }.isSuccess
            if (!ok) {
                sockets.remove(client)
            }
        }
    }

    fun broadcastWsEvent(type: String, builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val text = SwonalJson.encodeToString(
            buildJsonObject {
                put("type", type)
                builder()
            }
        )
        broadcastRawWsFrame(text)
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()
        if (isWebsocketRequested(session)) {
            val origin = session.headers["origin"]
            if (origin != null && !isAllowedOrigin(origin)) {
                return textResponse(Response.Status.FORBIDDEN, "CORS origin not allowed")
            }
            if (path != "/ws" && !path.startsWith("/ws/")) {
                return applyCors(session, textResponse(Response.Status.NOT_FOUND, "Unknown WebSocket path"))
            }
            val auth = runBlocking { checkRequestAuth(session) }
            if (!auth.authenticated) {
                return applyCors(session, jsonError(Response.Status.UNAUTHORIZED, "Authentication required"))
            }
            return super.serve(session)
        }
        return serveHttp(session)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val origin = session.headers["origin"]
        if (origin != null && !isAllowedOrigin(origin)) {
            return textResponse(Response.Status.FORBIDDEN, "CORS origin not allowed")
        }
        if (session.method == Method.OPTIONS) {
            val preflight = newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            return applyCors(session, preflight)
        }

        val response = try {
            val rawPath = session.uri ?: "/"
            when {
                rawPath == "/api" || rawPath.startsWith("/api/") -> handleApi(session, rawPath)
                rawPath.startsWith("/media/") -> handleMediaRequest(rawPath)
                else -> handleStaticWebRequest(rawPath)
            }
        } catch (tooBig: PayloadTooLargeException) {
            jsonError(Response.Status.PAYLOAD_TOO_LARGE, tooBig.message ?: "Payload too large")
        } catch (arg: IllegalArgumentException) {
            jsonError(Response.Status.BAD_REQUEST, arg.message ?: "Bad request")
        } catch (t: Throwable) {
            jsonError(Response.Status.INTERNAL_ERROR, t.message ?: "Internal server error")
        }
        return applyCors(session, response)
    }

    private fun handleApi(session: IHTTPSession, path: String): Response {
        val publicApiPath = path == "/api/health" ||
            path == "/api/auth/status" ||
            path == "/api/auth/login" ||
            path == "/api/auth/logout" ||
            path == "/api/spotify/callback"

        val auth = runBlocking { checkRequestAuth(session) }
        if (!publicApiPath && !auth.authenticated) {
            return jsonError(Response.Status.UNAUTHORIZED, "Passcode authentication required")
        }
        return router.dispatch(session, path, auth)
    }

    private fun handleMediaRequest(rawPath: String): Response {
        val relEncoded = rawPath.removePrefix("/media/")
        val rel = runCatching { URLDecoder.decode(relEncoded, "UTF-8") }.getOrNull()
            ?: return textResponse(Response.Status.BAD_REQUEST, "Malformed media path")
        if (rel.isBlank() || rel.contains("..") || rel.contains("\\") || rel.contains('\u0000') || rel.startsWith("/")) {
            return textResponse(Response.Status.FORBIDDEN, "Invalid media path")
        }

        val canonicalBase = mediaDir.canonicalFile
        val targetFile = File(canonicalBase, rel).canonicalFile
        val expectedPrefix = canonicalBase.path.trimEnd(File.separatorChar) + File.separator
        if (!targetFile.path.startsWith(expectedPrefix)) {
            return textResponse(Response.Status.FORBIDDEN, "Path traversal rejected")
        }
        if (targetFile.exists() && targetFile.isFile) {
            val stream = FileInputStream(targetFile)
            return newChunkedResponse(Response.Status.OK, guessMimeType(rel), stream).apply {
                addHeader("Cache-Control", "public, max-age=3600")
            }
        }

        // Single-segment fallback to APK bundled SVG diagrams in assets/media/
        if (!rel.contains('/') && SAFE_SINGLE_FILE.matches(rel)) {
            val assetStream = mediaAssetOpener(rel)
            if (assetStream != null) {
                return newChunkedResponse(Response.Status.OK, guessMimeType(rel), assetStream).apply {
                    addHeader("Cache-Control", "public, max-age=86400")
                }
            }
        }
        return textResponse(Response.Status.NOT_FOUND, "Media file not found")
    }

    private fun handleStaticWebRequest(rawPath: String): Response {
        val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrNull()
            ?: return textResponse(Response.Status.BAD_REQUEST, "Malformed path")
        if (decoded.contains("..") || decoded.contains("\\") || decoded.contains('\u0000')) {
            return textResponse(Response.Status.FORBIDDEN, "Invalid path")
        }
        val clean = decoded.trimStart('/')
        val hasExtension = clean.contains('.') && !clean.endsWith("/")
        if (hasExtension) {
            val assetStream = webAssetOpener(clean)
            if (assetStream != null) {
                val res = newChunkedResponse(Response.Status.OK, guessMimeType(clean), assetStream)
                if (clean.startsWith("assets/")) {
                    res.addHeader("Cache-Control", "public, max-age=31536000, immutable")
                } else {
                    res.addHeader("Cache-Control", "no-store")
                }
                return res
            }
            return textResponse(Response.Status.NOT_FOUND, "Asset not found")
        }

        val indexStream = webAssetOpener("index.html")
            ?: return textResponse(
                Response.Status.OK,
                """<!DOCTYPE html><html><body style="font-family:sans-serif;background:#0A0D14;color:#E2E8F0;padding:24px;"><h2>Swonal Standalone Server Ready</h2><p>Web SPA assets not bundled yet.</p></body></html>""",
                mimeType = "text/html; charset=utf-8"
            )
        return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", indexStream).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    suspend fun checkRequestAuth(session: IHTTPSession): RequestAuthContext {
        val isLoop = isLoopbackClient(session)
        val sessionId = extractSessionCookie(session)
        val now = System.currentTimeMillis()
        var validCookie = false
        if (!sessionId.isNullOrBlank()) {
            val exp = authSessions[sessionId]
            if (exp != null && exp > now) {
                validCookie = true
            } else if (exp != null) {
                authSessions.remove(sessionId)
            }
        }
        val bearer = extractBearerToken(session)
        val validToken = if (!bearer.isNullOrBlank()) repo.localEngine.verifyApiToken(bearer) else false
        val hasCredentials = validCookie || validToken
        val status = repo.localEngine.getAuthStatus(
            isLoopbackClient = isLoop,
            isSessionAuthenticated = hasCredentials
        )
        return RequestAuthContext(
            isLoopback = isLoop,
            authenticated = status.authenticated,
            authEnabled = status.enabled,
            hasValidCredentials = hasCredentials
        )
    }

    fun issueAuthSessionCookie(): String {
        val sid = CryptoCompat.randomHex(24)
        authSessions[sid] = System.currentTimeMillis() + SESSION_TTL_MS
        return sid
    }

    fun revokeAuthSessionCookie(session: IHTTPSession) {
        val sid = extractSessionCookie(session)
        if (!sid.isNullOrBlank()) {
            authSessions.remove(sid)
        }
    }

    private fun extractSessionCookie(session: IHTTPSession): String? {
        val cookieHeader = session.headers["cookie"] ?: return null
        for (part in cookieHeader.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                val name = part.substring(0, eq).trim()
                if (name == COOKIE_NAME) {
                    return part.substring(eq + 1).trim()
                }
            }
        }
        return null
    }

    private fun extractBearerToken(session: IHTTPSession): String? {
        val auth = session.headers["authorization"]
        if (!auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        return null
    }

    private fun isLoopbackClient(session: IHTTPSession): Boolean {
        val rawIp = if (allowTestRemoteIpHeader) {
            session.headers["x-swonal-test-remote-ip"] ?: session.remoteIpAddress
        } else {
            session.remoteIpAddress
        }.orEmpty().trim()
        return rawIp == "127.0.0.1" ||
            rawIp == "::1" ||
            rawIp == "0:0:0:0:0:0:0:1" ||
            rawIp.equals("localhost", ignoreCase = true)
    }

    fun readRequestBody(session: IHTTPSession, maxBytes: Long = DEFAULT_JSON_MAX_BYTES): String {
        if (session.method !in setOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)) {
            return ""
        }
        val rawLen = session.headers["content-length"]?.trim()
        val contentLength = if (!rawLen.isNullOrEmpty()) {
            rawLen.toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("Invalid Content-Length header")
        } else {
            null
        }
        if (contentLength != null && contentLength > maxBytes) {
            throw PayloadTooLargeException("Body exceeds max limit (${maxBytes / (1024 * 1024)} MB)")
        }
        val transferEncoding = session.headers["transfer-encoding"]?.lowercase(Locale.US).orEmpty()
        if (transferEncoding.contains("chunked")) {
            return readChunkedBounded(session.inputStream, maxBytes)
        }
        val len = contentLength ?: return ""
        if (len == 0L) return ""
        return readFixedBounded(session.inputStream, len, maxBytes)
    }

    private fun readFixedBounded(stream: InputStream, expectedBytes: Long, maxBytes: Long): String {
        val out = ByteArrayOutputStream(minOf(expectedBytes, 8192L).toInt())
        val buf = ByteArray(8192)
        var remaining = expectedBytes
        var total = 0L
        while (remaining > 0L) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw PayloadTooLargeException("Body exceeds max limit (${maxBytes / (1024 * 1024)} MB)")
            }
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readChunkedBounded(stream: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream(4096)
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val sizeLine = readCrLfLine(stream, 1024) ?: break
            val hexLen = sizeLine.substringBefore(';').trim()
            if (hexLen.isEmpty()) continue
            val chunkSize = hexLen.toLongOrNull(16)?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("Malformed HTTP chunk header")
            if (chunkSize == 0L) {
                while (true) {
                    val trailer = readCrLfLine(stream, 1024)
                    if (trailer.isNullOrEmpty()) break
                }
                break
            }
            if (total + chunkSize > maxBytes) {
                throw PayloadTooLargeException("Body exceeds max limit (${maxBytes / (1024 * 1024)} MB)")
            }
            var remaining = chunkSize
            while (remaining > 0L) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = stream.read(buf, 0, toRead)
                if (n <= 0) throw IllegalArgumentException("Unexpected EOF in chunked request body")
                out.write(buf, 0, n)
                total += n
                remaining -= n
            }
            readCrLfLine(stream, 8)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readCrLfLine(stream: InputStream, maxLineLen: Int): String? {
        val sb = StringBuilder()
        var prevCr = false
        while (sb.length < maxLineLen) {
            val b = stream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                return if (prevCr && sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.substring(0, sb.length - 1)
                } else {
                    sb.toString()
                }
            }
            prevCr = (b == '\r'.code)
            sb.append(b.toChar())
        }
        throw IllegalArgumentException("Malformed HTTP chunk line")
    }

    fun jsonResponse(status: Response.Status, bodyJson: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", bodyJson).apply {
            addHeader("Cache-Control", "no-store")
        }

    fun jsonError(status: Response.Status, message: String): Response =
        jsonResponse(status, SwonalJson.encodeToString(buildJsonObject { put("error", message) }))

    fun textResponse(
        status: Response.Status,
        body: String,
        mimeType: String = "text/plain; charset=utf-8"
    ): Response = newFixedLengthResponse(status, mimeType, body)

    fun fileAttachmentResponse(file: File, downloadName: String, contentType: String): Response {
        val autoDeleteStream = object : FilterInputStream(FileInputStream(file)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { file.delete() }
                }
            }
        }
        return newChunkedResponse(Response.Status.OK, contentType, autoDeleteStream).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$downloadName\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        val url = runCatching { URL(origin) }.getOrNull() ?: return false
        val proto = url.protocol.lowercase(Locale.US)
        if (proto != "http" && proto != "https") return false
        val host = url.host.orEmpty().lowercase(Locale.US).removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
            return true
        }
        if (LAN_V4_10.matches(host) || LAN_V4_192.matches(host) || LAN_V4_172.matches(host)) {
            return true
        }
        val publicOverride = runBlocking { runCatching { repo.localDb.getSetting("public_url_override") }.getOrNull() }
        if (!publicOverride.isNullOrBlank()) {
            val pubUrl = runCatching { URL(publicOverride) }.getOrNull()
            if (pubUrl != null && pubUrl.protocol.equals(url.protocol, ignoreCase = true) &&
                pubUrl.host.orEmpty().removePrefix("[").removeSuffix("]").equals(host, ignoreCase = true) &&
                effectivePort(pubUrl) == effectivePort(url)
            ) {
                return true
            }
        }
        return false
    }

    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort

    private fun applyCors(session: IHTTPSession, response: Response): Response {
        val origin = session.headers["origin"]
        if (!origin.isNullOrBlank() && isAllowedOrigin(origin)) {
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Access-Control-Allow-Credentials", "true")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            response.addHeader("Vary", "Origin")
        }
        return response
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = SwonalWsClient(handshake)

    private inner class SwonalWsClient(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            sockets.add(this)
            val ui = repo.uiStateFlow.value
            val frame = repo.latestTelemetryFrame()
            runCatching {
                send(
                    SwonalJson.encodeToString(
                        buildJsonObject {
                            put("type", "daemon_status")
                            put("connected", ui.daemonConnected)
                        }
                    )
                )
                send(
                    SwonalJson.encodeToString(
                        buildJsonObject {
                            put("type", "session_state")
                            put(
                                "state",
                                SwonalJson.encodeToJsonElement(
                                    com.swonal.kiosk.model.SharedSessionState.serializer(),
                                    ui.sessionState
                                )
                            )
                        }
                    )
                )
                send(
                    SwonalJson.encodeToString(
                        buildJsonObject {
                            put("type", "telemetry")
                            put("mode", frame.mode)
                            put("weight_enabled", frame.weightEnabled)
                            put("target_weight_lb", frame.targetWeightLb)
                            put("applied_resistance_lb", frame.appliedResistanceLb)
                            put("profile_mode", frame.profileMode)
                            put("modifier_pct", frame.modifierPct)
                            put("rep_count", frame.repCount)
                            put("accessory", frame.accessory)
                            put("left_cable", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.CableData.serializer(), frame.leftCable))
                            put("right_cable", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.CableData.serializer(), frame.rightCable))
                            put("arm_positions", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.ArmPositions.serializer(), frame.armPositions))
                        }
                    )
                )
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sockets.remove(this)
            // Crucial Safety Boundary: never turn off Kiosk resistance when a phone Companion disconnects
            // while the Kiosk screen/UI heartbeat is actively running!
            if (sockets.isEmpty() && !repo.isUiAliveForWatchdog() && repo.isWeightCurrentlyEngaged()) {
                repo.disableWeight()
            }
        }

        override fun onMessage(message: WebSocketFrame?) {
            val rawText = message?.textPayload ?: return
            val obj = runCatching { SwonalJson.parseToJsonElement(rawText).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "ping" -> runCatching { send("""{"type":"pong"}""") }
                "set_weight" -> {
                    val w = obj["weight_lb"]?.jsonPrimitive?.doubleOrNull ?: return
                    if (w !in 5.0..200.0) {
                        runCatching { send("""{"type":"error","message":"Invalid weight_lb"}""") }
                        return
                    }
                    val rawProfile = obj["profile"]?.jsonPrimitive?.contentOrNull?.trim()
                    val profile = rawProfile?.uppercase(Locale.US)?.takeIf { it in VALID_WEIGHT_PROFILES }
                    if (rawProfile != null && profile == null) {
                        runCatching { send("""{"type":"error","message":"Invalid weight profile"}""") }
                        return
                    }
                    val mod = obj["modifier_pct"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100)
                    repo.setWeight(w, profile, mod, allowWhileEngaged = false)
                }
                "disable_weight" -> repo.disableWeight()
                "enable_weight" -> {
                    if (repo.isUiAliveForWatchdog() && repo.uiStateFlow.value.safetyEvent == null) {
                        repo.setWeightEnabled(true)
                    }
                }
                "toggle_weight" -> {
                    if (repo.isWeightCurrentlyEngaged()) {
                        repo.disableWeight()
                    } else if (repo.isUiAliveForWatchdog() && repo.uiStateFlow.value.safetyEvent == null) {
                        repo.setWeightEnabled(true)
                    }
                }
                "set_accessory" -> obj["accessory"]?.jsonPrimitive?.contentOrNull?.let { repo.setAccessory(it) }
                "reset_reps" -> repo.resetReps()
                "set_active_user" -> obj["userId"]?.jsonPrimitive?.contentOrNull?.let { repo.switchUser(it) }
                "set_partner_mode" -> {
                    val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    val ids = (obj["partnerUserIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    repo.localEngine.setPartnerMode(enabled, ids)
                }
                "skip_rest_timer" -> repo.skipRestTimer()
                "start_rest_timer" -> {
                    val cur = repo.uiStateFlow.value.restSecondsRemaining
                    val sec = (obj["seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 3600)
                    repo.addRestSeconds((sec - cur).let { if (cur == 0) sec else it })
                }
                "clear_queued_workout" -> repo.localEngine.clearQueuedWorkout()
                "music_updated" -> broadcastRawWsFrame(rawText)
            }
        }

        override fun onPong(pong: WebSocketFrame?) = Unit

        override fun onException(exception: IOException?) {
            sockets.remove(this)
        }
    }

    companion object {
        const val DEFAULT_JSON_MAX_BYTES = 2L * 1024 * 1024
        const val RESTORE_JSON_MAX_BYTES = 10L * 1024 * 1024
        const val SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        const val COOKIE_NAME = "swonal_session"

        private val VALID_WEIGHT_PROFILES = setOf("STANDARD", "ECCENTRIC", "CHAINS", "SPOTTER", "BURNOUT")

        private val SAFE_SINGLE_FILE = Regex("^[A-Za-z0-9._-]+$")
        private val LAN_V4_10 = Regex("^10\\.\\d+\\.\\d+\\.\\d+$")
        private val LAN_V4_192 = Regex("^192\\.168\\.\\d+\\.\\d+$")
        private val LAN_V4_172 = Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+$")

        fun discoverLanIpv4s(): List<String> {
            val ranked = mutableListOf<Pair<Int, String>>()
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    val priority = when {
                        iface.name.startsWith("wlan") -> 0
                        iface.name.startsWith("eth") -> 1
                        iface.name.startsWith("en") -> 2
                        else -> 3
                    }
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            if (addr.isSiteLocalAddress || host.startsWith("10.") || host.startsWith("192.168.")) {
                                ranked += priority to host
                            }
                        }
                    }
                }
            }
            return ranked.sortedBy { it.first }.map { it.second }.distinct()
        }

        fun guessMimeType(path: String): String {
            val lower = path.lowercase(Locale.US)
            return when {
                lower.endsWith(".html") -> "text/html; charset=utf-8"
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript; charset=utf-8"
                lower.endsWith(".css") -> "text/css; charset=utf-8"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".json") || lower.endsWith(".map") -> "application/json; charset=utf-8"
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".woff") -> "font/woff"
                lower.endsWith(".ico") -> "image/x-icon"
                else -> "application/octet-stream"
            }
        }
    }
}
