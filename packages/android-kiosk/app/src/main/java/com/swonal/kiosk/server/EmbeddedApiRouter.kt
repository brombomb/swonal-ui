package com.swonal.kiosk.server

import com.swonal.kiosk.db.RestoreBundleDto
import com.swonal.kiosk.db.SyncPullResponse
import com.swonal.kiosk.db.applyPulledSync
import com.swonal.kiosk.db.exportRestoreBundle
import com.swonal.kiosk.db.getFriendActivityByEventId
import com.swonal.kiosk.engine.LocalWorkoutEngine
import com.swonal.kiosk.model.AppStateResponse
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.NetworkInfo
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.util.IsoTime
import com.swonal.kiosk.util.UrlSecurity
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * Modular REST dispatcher for [EmbeddedSwonalServer], providing 100% endpoint parity
 * with `packages/server-app` directly from on-device SQLite and native engines.
 */
class EmbeddedApiRouter(
    private val server: EmbeddedSwonalServer,
    private val repo: SwonalRepository
) {
    private val engine: LocalWorkoutEngine get() = repo.localEngine

    fun dispatch(
        session: IHTTPSession,
        path: String,
        auth: EmbeddedSwonalServer.RequestAuthContext
    ): Response = runBlocking {
        val m = session.method
        val segs = path.trim('/').split('/').map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (segs.isEmpty() || segs[0] != "api") {
            return@runBlocking server.jsonError(Status.NOT_FOUND, "Not found")
        }
        val resource = segs.getOrNull(1) ?: ""
        when (resource) {
            "health" -> handleHealth()
            "state" -> handleState()
            "network-info" -> handleNetworkInfo(session, m, segs)
            "auth" -> handleAuth(session, m, segs, auth)
            "users" -> handleUsers(session, m, segs)
            "exercises" -> handleExercises(session, m, segs)
            "templates" -> handleTemplates(session, m, segs)
            "sessions" -> handleSessions(session, m, segs)
            "sets" -> handleStandaloneSet(session, m)
            "prs" -> handlePrs(m, segs)
            "overload" -> handleOverload(m, segs)
            "export" -> handleExports(session, m, segs)
            "admin" -> handleAdmin(session, m, segs)
            "daemon" -> handleDaemon(session, m, segs)
            "integrations" -> handleIntegrations(session, m, segs)
            "coach" -> handleCoach(session, m, segs)
            "friends" -> handleFriends(session, m, segs)
            "spotify" -> handleSpotify(session, m, segs, auth)
            "music" -> handleMusic(session, m, segs)
            else -> server.jsonError(Status.NOT_FOUND, "Unknown API route: $path")
        }
    }

    private fun parseJsonBody(session: IHTTPSession, maxBytes: Long = EmbeddedSwonalServer.DEFAULT_JSON_MAX_BYTES): JsonObject {
        val raw = server.readRequestBody(session, maxBytes).trim()
        if (raw.isEmpty()) return buildJsonObject {}
        return runCatching { SwonalJson.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid JSON request body") }
    }

    private fun queryParam(session: IHTTPSession, key: String): String? =
        session.parameters[key]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun jsonOk(json: String, status: Status = Status.OK): Response = server.jsonResponse(status, json)

    // --- Health, State & Network Info ---

    private fun handleHealth(): Response {
        val ui = repo.uiStateFlow.value
        val payload = buildJsonObject {
            put("status", "ok")
            put("standalone", true)
            put("daemonConnected", ui.daemonConnected)
            put("sessionState", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.SharedSessionState.serializer(), engine.getSessionState()))
            put("timestamp", IsoTime.nowIso())
        }
        return jsonOk(SwonalJson.encodeToString(payload))
    }

    private suspend fun handleState(): Response {
        val daemonOk = repo.uiStateFlow.value.daemonConnected || repo.localDaemon.connected.value
        val state = engine.getAppState(daemonOk)
        return jsonOk(SwonalJson.encodeToString(AppStateResponse.serializer(), state))
    }

    private suspend fun handleNetworkInfo(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (m == Method.GET && segs.size == 2) {
            val info = repo.getEffectiveNetworkInfo()
            return jsonOk(SwonalJson.encodeToString(NetworkInfo.serializer(), info))
        }
        if (m == Method.POST && segs.size == 3 && segs[2] == "public-url") {
            val body = parseJsonBody(session)
            val rawUrl = body["publicUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawUrl.isNotEmpty()) {
                val safePublicUrl = UrlSecurity.sanitizeExternalUrl(rawUrl, allowLoopback = true)
                repo.localDb.setSetting("public_url_override", safePublicUrl)
            } else {
                repo.localDb.deleteSetting("public_url_override")
            }
            val info = repo.getEffectiveNetworkInfo()
            repo.notifyDirtySync()
            return jsonOk(SwonalJson.encodeToString(NetworkInfo.serializer(), info))
        }
        return server.jsonError(Status.NOT_FOUND, "Not found")
    }

    // --- Auth ---

    private suspend fun handleAuth(
        session: IHTTPSession,
        m: Method,
        segs: List<String>,
        auth: EmbeddedSwonalServer.RequestAuthContext
    ): Response {
        return when {
            m == Method.GET && segs.getOrNull(2) == "status" -> {
                val st = engine.getAuthStatus(auth.isLoopback, auth.hasValidCredentials)
                jsonOk(SwonalJson.encodeToString(st))
            }
            m == Method.POST && segs.getOrNull(2) == "login" -> {
                val body = parseJsonBody(session)
                val passcode = body["passcode"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val res = engine.verifyPasscode(passcode, session.remoteIpAddress ?: "lan")
                res.fold(
                    onSuccess = { ok ->
                        if (!ok) {
                            server.jsonError(Status.UNAUTHORIZED, "Invalid passcode")
                        } else {
                            val sid = server.issueAuthSessionCookie()
                            val tok = engine.getOrCreateLocalApiToken()
                            val resp = jsonOk(SwonalJson.encodeToString(buildJsonObject {
                                put("ok", true)
                                put("token", tok)
                            }))
                            resp.addHeader(
                                "Set-Cookie",
                                "${EmbeddedSwonalServer.COOKIE_NAME}=$sid; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"
                            )
                            resp
                        }
                    },
                    onFailure = { err ->
                        server.jsonError(Status.TOO_MANY_REQUESTS, err.message ?: "Too many attempts")
                    }
                )
            }
            m == Method.POST && segs.getOrNull(2) == "logout" -> {
                server.revokeAuthSessionCookie(session)
                val resp = jsonOk("""{"ok":true}""")
                resp.addHeader(
                    "Set-Cookie",
                    "${EmbeddedSwonalServer.COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
                )
                resp
            }
            m == Method.GET && segs.getOrNull(2) == "token" -> {
                val tok = engine.getOrCreateLocalApiToken()
                jsonOk(SwonalJson.encodeToString(buildJsonObject { put("token", tok) }))
            }
            m == Method.POST && segs.getOrNull(2) == "configure" -> {
                val body = parseJsonBody(session)
                val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull
                val passcode = body["passcode"]?.jsonPrimitive?.contentOrNull
                val trustLocal = body["trustLocalhost"]?.jsonPrimitive?.booleanOrNull
                val st = engine.configureAuth(enabled, passcode, trustLocal)
                repo.refreshAppState()
                jsonOk(SwonalJson.encodeToString(st))
            }
            else -> server.jsonError(Status.NOT_FOUND, "Auth route not found")
        }
    }

    // --- Users ---

    private suspend fun handleUsers(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (segs.size == 2 && m == Method.GET) {
            return jsonOk(SwonalJson.encodeToString(engine.listUsers()))
        }
        if (segs.size == 2 && m == Method.POST) {
            val b = parseJsonBody(session)
            val created = engine.createUser(
                name = b["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                avatarColor = b["avatarColor"]?.jsonPrimitive?.contentOrNull ?: "#3B82F6",
                accentColor = b["accentColor"]?.jsonPrimitive?.contentOrNull ?: "#00F0FF",
                themeMode = b["themeMode"]?.jsonPrimitive?.contentOrNull ?: "dark",
                weightUnit = b["weightUnit"]?.jsonPrimitive?.contentOrNull ?: "LB",
                preferredMusicProvider = b["preferredMusicProvider"]?.jsonPrimitive?.contentOrNull ?: "spotify"
            )
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("users_updated")
            return jsonOk(SwonalJson.encodeToString(created), Status.CREATED)
        }
        val uid = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Missing user id")
        if (segs.size == 4 && segs[3] == "demo-pip") {
            return if (m == Method.GET) {
                jsonOk("""{"enabled":${engine.getDemoPip(uid)}}""")
            } else if (m == Method.PATCH || m == Method.POST) {
                val b = parseJsonBody(session)
                val enabled = b["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                engine.setDemoPip(uid, enabled)
                repo.setShowDemoPip(enabled)
                repo.notifyDirtySync()
                jsonOk("""{"enabled":$enabled}""")
            } else {
                server.jsonError(Status.METHOD_NOT_ALLOWED, "Method not allowed")
            }
        }
        if (segs.size == 3 && (m == Method.PATCH || m == Method.PUT)) {
            val b = parseJsonBody(session)
            val updated = engine.updateUser(
                userId = uid,
                name = b["name"]?.jsonPrimitive?.contentOrNull,
                avatarColor = b["avatarColor"]?.jsonPrimitive?.contentOrNull,
                accentColor = b["accentColor"]?.jsonPrimitive?.contentOrNull,
                themeMode = b["themeMode"]?.jsonPrimitive?.contentOrNull,
                weightUnit = b["weightUnit"]?.jsonPrimitive?.contentOrNull,
                preferredMusicProvider = b["preferredMusicProvider"]?.jsonPrimitive?.contentOrNull
            ) ?: return server.jsonError(Status.NOT_FOUND, "User not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("users_updated")
            return jsonOk(SwonalJson.encodeToString(updated))
        }
        if (segs.size == 3 && m == Method.DELETE) {
            val deleted = engine.deleteUser(uid)
            if (!deleted) return server.jsonError(Status.NOT_FOUND, "User not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("users_updated")
            return jsonOk("""{"deleted":true}""")
        }
        return server.jsonError(Status.NOT_FOUND, "User route not found")
    }

    // --- Exercises ---

    private suspend fun handleExercises(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (segs.size == 2 && m == Method.GET) {
            val list = engine.listExercises(
                muscleGroup = queryParam(session, "muscleGroup"),
                accessory = queryParam(session, "accessory"),
                searchQuery = queryParam(session, "q")
            )
            return jsonOk(SwonalJson.encodeToString(ListSerializer(Exercise.serializer()), list))
        }
        if (segs.size == 2 && m == Method.POST) {
            val b = parseJsonBody(session)
            val unilateral = b["unilateral"]?.jsonPrimitive?.booleanOrNull
                ?: b["isUnilateral"]?.jsonPrimitive?.booleanOrNull
                ?: false
            val created = engine.createExercise(
                name = b["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                muscleGroup = b["muscleGroup"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                accessory = b["accessory"]?.jsonPrimitive?.contentOrNull ?: "HANDLES",
                unilateral = unilateral,
                recommendedHeight = b["recommendedHeight"]?.jsonPrimitive?.intOrNull ?: 4,
                recommendedRotation = b["recommendedRotation"]?.jsonPrimitive?.intOrNull ?: 2,
                recommendedAngle = b["recommendedAngle"]?.jsonPrimitive?.intOrNull ?: 1,
                notes = b["notes"]?.jsonPrimitive?.contentOrNull,
                mediaUrl = b["mediaUrl"]?.jsonPrimitive?.contentOrNull
            )
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("exercises_updated")
            return jsonOk(SwonalJson.encodeToString(Exercise.serializer(), created), Status.CREATED)
        }
        val exId = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Missing exercise id")
        if (segs.size == 4 && segs[3] == "media" && (m == Method.PATCH || m == Method.POST)) {
            val b = parseJsonBody(session)
            val mediaUrl = b["mediaUrl"]?.jsonPrimitive?.contentOrNull
            val updated = engine.updateExercise(exId, mediaUrl = mediaUrl, updateMediaUrl = true)
                ?: return server.jsonError(Status.NOT_FOUND, "Exercise not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("exercises_updated")
            return jsonOk(SwonalJson.encodeToString(Exercise.serializer(), updated))
        }
        if (segs.size == 3 && (m == Method.PATCH || m == Method.PUT)) {
            val b = parseJsonBody(session)
            val unilateral = b["unilateral"]?.jsonPrimitive?.booleanOrNull ?: b["isUnilateral"]?.jsonPrimitive?.booleanOrNull
            val updated = engine.updateExercise(
                exerciseId = exId,
                name = b["name"]?.jsonPrimitive?.contentOrNull,
                muscleGroup = b["muscleGroup"]?.jsonPrimitive?.contentOrNull,
                accessory = b["accessory"]?.jsonPrimitive?.contentOrNull,
                unilateral = unilateral,
                recommendedHeight = b["recommendedHeight"]?.jsonPrimitive?.intOrNull,
                recommendedRotation = b["recommendedRotation"]?.jsonPrimitive?.intOrNull,
                recommendedAngle = b["recommendedAngle"]?.jsonPrimitive?.intOrNull,
                notes = b["notes"]?.jsonPrimitive?.contentOrNull,
                mediaUrl = b["mediaUrl"]?.jsonPrimitive?.contentOrNull
            ) ?: return server.jsonError(Status.NOT_FOUND, "Exercise not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("exercises_updated")
            return jsonOk(SwonalJson.encodeToString(Exercise.serializer(), updated))
        }
        if (segs.size == 3 && m == Method.DELETE) {
            return engine.deleteExercise(exId).fold(
                onSuccess = {
                    repo.refreshAppState()
                    repo.notifyDirtySync()
                    server.broadcastWsEvent("exercises_updated")
                    jsonOk("""{"deleted":true}""")
                },
                onFailure = { err -> server.jsonError(Status.CONFLICT, err.message ?: "Cannot delete exercise") }
            )
        }
        return server.jsonError(Status.NOT_FOUND, "Exercise route not found")
    }

    // --- Templates & Routines ---

    private fun decodeBlocksField(b: JsonObject): List<WorkoutBlock>? {
        val el = b["blocks"] ?: return b["blocksJson"]?.jsonPrimitive?.contentOrNull?.let {
            SwonalJson.decodeFromString(ListSerializer(WorkoutBlock.serializer()), it)
        }
        return if (el is JsonArray) {
            SwonalJson.decodeFromJsonElement(ListSerializer(WorkoutBlock.serializer()), el)
        } else {
            SwonalJson.decodeFromString(ListSerializer(WorkoutBlock.serializer()), el.jsonPrimitive.content)
        }
    }

    private fun decodeScheduleDays(b: JsonObject): List<Int>? {
        val arr = b["scheduleDays"] as? JsonArray ?: return null
        return arr.mapNotNull { it.jsonPrimitive.intOrNull }.filter { it in 0..6 }.distinct().sorted()
    }

    private suspend fun handleTemplates(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (segs.size == 2 && m == Method.GET) {
            val list = engine.listTemplates(queryParam(session, "userId"))
            return jsonOk(SwonalJson.encodeToString(ListSerializer(WorkoutTemplate.serializer()), list))
        }
        if (segs.size == 2 && m == Method.POST) {
            val b = parseJsonBody(session)
            val userId = b["userId"]?.jsonPrimitive?.contentOrNull
                ?: repo.uiStateFlow.value.activeUser?.id
                ?: throw IllegalArgumentException("Missing userId")
            val blocks = decodeBlocksField(b) ?: emptyList()
            val tpl = engine.createTemplate(
                userId = userId,
                name = b["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = b["description"]?.jsonPrimitive?.contentOrNull,
                blocks = blocks,
                unit = b["unit"]?.jsonPrimitive?.contentOrNull,
                scheduleDays = decodeScheduleDays(b).orEmpty()
            )
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("templates_updated")
            return jsonOk(SwonalJson.encodeToString(WorkoutTemplate.serializer(), tpl), Status.CREATED)
        }
        val tplId = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Missing template id")
        if (segs.size == 4 && segs[3] == "queue" && m == Method.POST) {
            val b = runCatching { parseJsonBody(session) }.getOrNull()
            val queued = engine.queueTemplate(tplId, b?.get("userId")?.jsonPrimitive?.contentOrNull)
                ?: return server.jsonError(Status.NOT_FOUND, "Template not found")
            repo.refreshAppState()
            // If Tonal is idle (weight off and no active routine in progress), load immediately;
            // otherwise keep it safely queued in the Kiosk banner so an active set is never interrupted.
            val curUi = repo.uiStateFlow.value
            if (!repo.telemetryFlow.value.weightEnabled && curUi.routineSteps.isEmpty()) {
                repo.startQueuedWorkout()
            }
            repo.emitLocalBroadcast("workout_queued") {
                put(
                    "workout",
                    SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.QueuedWorkoutPayload.serializer(), queued)
                )
            }
            return jsonOk(SwonalJson.encodeToString(queued))
        }
        if (segs.size == 3 && (m == Method.PUT || m == Method.PATCH)) {
            val b = parseJsonBody(session)
            val updated = engine.updateTemplate(
                templateId = tplId,
                name = b["name"]?.jsonPrimitive?.contentOrNull,
                description = b["description"]?.jsonPrimitive?.contentOrNull,
                blocks = decodeBlocksField(b),
                unit = b["unit"]?.jsonPrimitive?.contentOrNull,
                scheduleDays = decodeScheduleDays(b)
            ) ?: return server.jsonError(Status.NOT_FOUND, "Template not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("templates_updated")
            return jsonOk(SwonalJson.encodeToString(WorkoutTemplate.serializer(), updated))
        }
        if (segs.size == 3 && m == Method.DELETE) {
            val ok = engine.deleteTemplate(tplId)
            if (!ok) return server.jsonError(Status.NOT_FOUND, "Template not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            server.broadcastWsEvent("templates_updated")
            return jsonOk("""{"deleted":true}""")
        }
        return server.jsonError(Status.NOT_FOUND, "Template route not found")
    }

    // --- Sessions, Sets, PRs & Overload ---

    private suspend fun handleSessions(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (segs.size == 2 && m == Method.GET) {
            val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 50
            val list = engine.listSessions(queryParam(session, "userId"), limit)
            return jsonOk(SwonalJson.encodeToString(ListSerializer(WorkoutSession.serializer()), list))
        }
        if (segs.size == 2 && m == Method.POST) {
            val b = parseJsonBody(session)
            val uid = b["userId"]?.jsonPrimitive?.contentOrNull
                ?: repo.uiStateFlow.value.activeUser?.id
                ?: throw IllegalArgumentException("Missing userId")
            val created = engine.createSession(uid, b["templateId"]?.jsonPrimitive?.contentOrNull)
            repo.refreshAppState()
            repo.notifyDirtySync()
            return jsonOk(SwonalJson.encodeToString(WorkoutSession.serializer(), created), Status.CREATED)
        }
        val sid = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Missing session id")
        if (segs.size == 4 && segs[3] == "sets" && m == Method.POST) {
            return logSetFromBody(sid, parseJsonBody(session))
        }
        if (segs.size == 4 && segs[3] == "complete" && m == Method.POST) {
            val completed = engine.completeSession(sid)
                ?: return server.jsonError(Status.NOT_FOUND, "Session not found")
            repo.refreshAppState()
            repo.notifyDirtySync()
            return jsonOk(SwonalJson.encodeToString(WorkoutSession.serializer(), completed))
        }
        return server.jsonError(Status.NOT_FOUND, "Session route not found")
    }

    private suspend fun handleStandaloneSet(session: IHTTPSession, m: Method): Response {
        if (m != Method.POST) return server.jsonError(Status.METHOD_NOT_ALLOWED, "Method not allowed")
        val b = parseJsonBody(session)
        val sid = b["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: engine.getSessionState().activeSessionId
            ?: throw IllegalArgumentException("Missing sessionId")
        return logSetFromBody(sid, b)
    }

    private suspend fun logSetFromBody(sessionId: String, b: JsonObject): Response {
        val exerciseId = b["exerciseId"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing exerciseId")
        val res = engine.logSet(
            sessionId = sessionId,
            userId = b["userId"]?.jsonPrimitive?.contentOrNull,
            exerciseId = exerciseId,
            setNumber = b["setNumber"]?.jsonPrimitive?.intOrNull ?: 1,
            mode = b["mode"]?.jsonPrimitive?.contentOrNull ?: "standard",
            modifierPct = b["modifierPct"]?.jsonPrimitive?.intOrNull ?: 0,
            weightLb = b["weightLb"]?.jsonPrimitive?.doubleOrNull
                ?: throw IllegalArgumentException("Missing weightLb"),
            repsCompleted = b["repsCompleted"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("Missing repsCompleted"),
            rpe = b["rpe"]?.jsonPrimitive?.doubleOrNull,
            restSeconds = b["restSeconds"]?.jsonPrimitive?.intOrNull ?: 60
        )
        repo.refreshAppState()
        repo.notifyDirtySync()
        return jsonOk(SwonalJson.encodeToString(res), Status.CREATED)
    }

    private suspend fun handlePrs(m: Method, segs: List<String>): Response {
        if (m != Method.GET || segs.size < 3) return server.jsonError(Status.NOT_FOUND, "Not found")
        val prs = engine.getPrs(segs[2])
        return jsonOk(SwonalJson.encodeToString(ListSerializer(PersonalRecord.serializer()), prs))
    }

    private suspend fun handleOverload(m: Method, segs: List<String>): Response {
        if (m != Method.GET || segs.size < 4) return server.jsonError(Status.NOT_FOUND, "Not found")
        val sug = engine.getOverloadSuggestion(segs[2], segs[3])
        return jsonOk(SwonalJson.encodeToString(sug))
    }

    // --- Exports & Backups ---

    private suspend fun handleExports(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (m != Method.GET || segs.size < 3) return server.jsonError(Status.NOT_FOUND, "Not found")
        val uid = queryParam(session, "userId")
        return when (segs[2]) {
            "sessions.csv", "workouts.csv" -> {
                val csv = engine.exportSessionsCsv(uid)
                server.textResponse(Status.OK, csv, "text/csv; charset=utf-8").apply {
                    addHeader("Content-Disposition", "attachment; filename=\"swonal-workouts.csv\"")
                }
            }
            "sessions.json", "workouts.json" -> {
                val json = engine.exportSessionsJson(uid)
                jsonOk(json).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"swonal-workouts.json\"")
                }
            }
            else -> server.jsonError(Status.NOT_FOUND, "Export format not found")
        }
    }

    private suspend fun handleAdmin(session: IHTTPSession, m: Method, segs: List<String>): Response {
        val action = segs.getOrNull(2)
        val sub = segs.getOrNull(3)
        if ((action == "backup" && (sub == null || sub == "db")) && m == Method.GET) {
            val tempDir = File(server.mediaDir.parentFile ?: server.mediaDir, "backups").apply { mkdirs() }
            val tempFile = File.createTempFile("swonal-backup-", ".db", tempDir)
            engine.exportSqliteBackup(tempFile)
            return server.fileAttachmentResponse(tempFile, "swonal-backup.db", "application/octet-stream")
        }
        if (action == "restore" && sub == "json") {
            if (m == Method.GET) {
                val bundle = repo.localDb.exportRestoreBundle()
                return jsonOk(SwonalJson.encodeToString(RestoreBundleDto.serializer(), bundle)).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"swonal-restore-bundle.json\"")
                }
            }
            if (m == Method.POST) {
                val raw = server.readRequestBody(session, EmbeddedSwonalServer.RESTORE_JSON_MAX_BYTES).trim()
                val bundle = runCatching { SwonalJson.decodeFromString(RestoreBundleDto.serializer(), raw) }
                    .getOrElse { throw IllegalArgumentException("Invalid JSON backup bundle: ${it.message}") }
                val pull = SyncPullResponse(
                    serverTimeMs = System.currentTimeMillis(),
                    users = bundle.users,
                    exercises = bundle.exercises,
                    templates = bundle.templates,
                    sessions = bundle.sessions,
                    setLogs = bundle.setLogs,
                    personalRecords = bundle.personalRecords,
                    friends = bundle.friends
                )
                repo.localDb.applyPulledSync(pull)
                repo.refreshAppState()
                repo.notifyDirtySync()
                server.broadcastWsEvent("users_updated")
                server.broadcastWsEvent("templates_updated")
                server.broadcastWsEvent("exercises_updated")
                return jsonOk(SwonalJson.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("restoredUsers", bundle.users.size)
                    put("restoredTemplates", bundle.templates.size)
                    put("restoredSessions", bundle.sessions.size)
                }))
            }
        }
        return server.jsonError(Status.NOT_FOUND, "Admin route not found")
    }

    // --- Local Loopback Daemon Forwarding ---

    private suspend fun handleDaemon(session: IHTTPSession, m: Method, segs: List<String>): Response {
        val route = segs.drop(2).joinToString("/")
        return when {
            m == Method.GET && route == "health" -> {
                val h = repo.localDaemon.fetchDirectHealth()
                    ?: return jsonOk("""{"status":"offline","mock_mode":false}""")
                jsonOk(SwonalJson.encodeToString(h))
            }
            m == Method.POST && route == "mode/stock" -> {
                if (repo.isWeightCurrentlyEngaged()) repo.disableWeightAndAwait()
                val ok = repo.localDaemon.switchToStockModeDirect()
                jsonOk("""{"ok":$ok,"mode":"STOCK"}""")
            }
            m == Method.POST && route == "mode/custom" -> {
                val ok = repo.localDaemon.switchToCustomModeDirect()
                jsonOk("""{"ok":$ok,"mode":"CUSTOM"}""")
            }
            m == Method.POST && route == "mock/ble-event" -> {
                val b = parseJsonBody(session)
                val action = b["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val ok = repo.localDaemon.triggerMockBleDirect(action)
                jsonOk("""{"ok":$ok}""")
            }
            m == Method.POST && route == "mock/bar-tilt" -> {
                val b = runCatching { parseJsonBody(session) }.getOrNull()
                val auto = b?.get("auto_enable")?.jsonPrimitive?.booleanOrNull ?: true
                val ok = repo.localDaemon.triggerMockBarTiltDirect(autoEnable = auto)
                jsonOk("""{"ok":$ok}""")
            }
            m == Method.POST && route == "mock/arm-positions" -> {
                val b = parseJsonBody(session)
                val (code, raw) = runCatching {
                    repo.localDaemon.postDirectJson("/api/mock/arm-positions", SwonalJson.encodeToString(b))
                }.getOrDefault(503 to """{"error":"daemon unreachable"}""")
                if (code in 200..299) {
                    jsonOk(raw.ifBlank { """{"ok":true}""" })
                } else {
                    server.jsonResponse(
                        Status.lookup(code) ?: Status.SERVICE_UNAVAILABLE,
                        raw.ifBlank { """{"error":"Daemon error HTTP $code"}""" }
                    )
                }
            }
            m == Method.GET && route == "calibration" -> {
                val cal = repo.localDaemon.getCalibrationDirect()
                    ?: return server.jsonError(Status.SERVICE_UNAVAILABLE, "Calibration unavailable")
                jsonOk(SwonalJson.encodeToString(cal))
            }
            m == Method.POST && route == "calibration/zero" -> {
                val cal = repo.localDaemon.zeroCalibrationDirect()
                    ?: return server.jsonError(Status.SERVICE_UNAVAILABLE, "Calibration failed")
                jsonOk(SwonalJson.encodeToString(cal))
            }
            else -> server.jsonError(Status.NOT_FOUND, "Daemon route not found")
        }
    }

    // --- Home Assistant ---

    private suspend fun handleIntegrations(session: IHTTPSession, m: Method, segs: List<String>): Response {
        if (segs.getOrNull(2) != "ha") return server.jsonError(Status.NOT_FOUND, "Not found")
        return when {
            m == Method.GET && segs.getOrNull(3) == "status" -> {
                jsonOk(SwonalJson.encodeToString(repo.localHaClient.getStatus()))
            }
            m == Method.POST && segs.getOrNull(3) == "config" -> {
                val b = parseJsonBody(session)
                val clear = b["clearTokens"]?.jsonPrimitive?.booleanOrNull == true
                val st = repo.localHaClient.updateConfig(
                    webhookUrl = b["webhookUrl"]?.jsonPrimitive?.contentOrNull,
                    webhookToken = if (clear) "" else b["webhookToken"]?.jsonPrimitive?.contentOrNull,
                    haUrl = b["haUrl"]?.jsonPrimitive?.contentOrNull,
                    haToken = if (clear) "" else b["haToken"]?.jsonPrimitive?.contentOrNull
                )
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(st))
            }
            m == Method.GET && segs.getOrNull(3) == "media-players" -> {
                val players = repo.localHaClient.listMediaPlayers()
                jsonOk(SwonalJson.encodeToString(JsonArray(players)))
            }
            m == Method.POST && segs.getOrNull(3) == "test" -> {
                val ev = repo.localHaClient.publishEvent(
                    "test",
                    buildJsonObject {
                        put("source", "companion_web")
                        put("message", "Swonal Home Assistant webhook test")
                    }
                )
                jsonOk(SwonalJson.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("event", ev)
                }))
            }
            else -> server.jsonError(Status.NOT_FOUND, "Integration route not found")
        }
    }

    // --- AI Coach ---

    private suspend fun handleCoach(session: IHTTPSession, m: Method, segs: List<String>): Response {
        return when {
            m == Method.GET && segs.getOrNull(2) == "config" -> {
                jsonOk(SwonalJson.encodeToString(repo.localAiCoach.getPublicConfig()))
            }
            m == Method.POST && segs.getOrNull(2) == "config" -> {
                val b = parseJsonBody(session)
                val cfg = repo.localAiCoach.setConfig(
                    provider = b["provider"]?.jsonPrimitive?.contentOrNull,
                    model = b["model"]?.jsonPrimitive?.contentOrNull,
                    apiKey = b["apiKey"]?.jsonPrimitive?.contentOrNull,
                    baseUrl = b["baseUrl"]?.jsonPrimitive?.contentOrNull,
                    clearApiKey = b["clearApiKey"]?.jsonPrimitive?.booleanOrNull == true
                )
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(cfg))
            }
            m == Method.POST && segs.getOrNull(2) == "test" -> {
                val res = repo.localAiCoach.testConnection()
                jsonOk(SwonalJson.encodeToString(res))
            }
            m == Method.POST && segs.getOrNull(2) == "ask" -> {
                val b = parseJsonBody(session)
                val uid = b["userId"]?.jsonPrimitive?.contentOrNull
                    ?: repo.uiStateFlow.value.activeUser?.id
                    ?: throw IllegalArgumentException("Missing userId")
                val prompt = b["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(prompt.isNotBlank()) { "Prompt is required" }
                val telemetry = repo.latestTelemetryFrame()
                val res = repo.localAiCoach.askFullJson(
                    userId = uid,
                    prompt = prompt,
                    exerciseName = b["exerciseName"]?.jsonPrimitive?.contentOrNull
                        ?: repo.uiStateFlow.value.activeExercise?.name,
                    currentWeightLb = b["currentWeightLb"]?.jsonPrimitive?.doubleOrNull ?: telemetry.targetWeightLb,
                    repCount = b["repCount"]?.jsonPrimitive?.intOrNull ?: telemetry.repCount,
                    meanVelocity = b["meanVelocity"]?.jsonPrimitive?.doubleOrNull,
                    velocityDropPct = b["velocityDropPct"]?.jsonPrimitive?.doubleOrNull
                )
                // Clamp / sanitize HTTP-returned Coach actions against sudden load spikes or remote motor engagement
                val rawAct = res["suggestedAction"] as? JsonObject
                val cleanAction: kotlinx.serialization.json.JsonElement = if (rawAct != null) {
                    val actType = rawAct["type"]?.jsonPrimitive?.contentOrNull
                    when (actType) {
                        "set_weight_delta" -> {
                            val d = (rawAct["deltaLb"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                                .coerceIn(-com.swonal.kiosk.net.SwonalRepository.MAX_COACH_DELTA_LB, com.swonal.kiosk.net.SwonalRepository.MAX_COACH_DELTA_LB)
                            if (d == 0.0) kotlinx.serialization.json.JsonNull
                            else buildJsonObject {
                                put("type", "set_weight_delta")
                                put("deltaLb", d)
                                put("label", rawAct["label"]?.jsonPrimitive?.contentOrNull ?: "Adjust Weight")
                            }
                        }
                        "set_weight_target" -> {
                            val t = (rawAct["targetLb"]?.jsonPrimitive?.doubleOrNull ?: telemetry.targetWeightLb)
                                .coerceIn(com.swonal.kiosk.model.Units.MIN_WEIGHT_LB, com.swonal.kiosk.model.Units.MAX_WEIGHT_LB)
                            buildJsonObject {
                                put("type", "set_weight_target")
                                put("targetLb", t)
                                put("label", rawAct["label"]?.jsonPrimitive?.contentOrNull ?: "Set Weight")
                            }
                        }
                        "set_weight_enabled" -> {
                            if (rawAct["enabled"]?.jsonPrimitive?.booleanOrNull == false) rawAct
                            else kotlinx.serialization.json.JsonNull
                        }
                        "toggle_weight" -> kotlinx.serialization.json.JsonNull
                        else -> rawAct
                    }
                } else {
                    kotlinx.serialization.json.JsonNull
                }
                val sanitized = buildJsonObject {
                    for ((k, v) in res) {
                        if (k == "suggestedAction") put("suggestedAction", cleanAction) else put(k, v)
                    }
                }
                jsonOk(SwonalJson.encodeToString(sanitized))
            }
            else -> server.jsonError(Status.NOT_FOUND, "Coach route not found")
        }
    }

    // --- Friends / Nostr Swocial ---

    private suspend fun handleFriends(session: IHTTPSession, m: Method, segs: List<String>): Response {
        val sub = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Not found")
        return when {
            sub == "identity" && segs.size == 4 -> {
                val uid = segs[3]
                if (m == Method.GET) {
                    jsonOk(SwonalJson.encodeToString(repo.localNostr.getOrCreateIdentity(uid)))
                } else if (m == Method.POST || m == Method.PATCH) {
                    val b = parseJsonBody(session)
                    val relays = (b["relays"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    val updated = repo.localNostr.updateIdentitySettings(
                        userId = uid,
                        shareWorkouts = b["shareWorkouts"]?.jsonPrimitive?.booleanOrNull,
                        relays = relays
                    )
                    repo.notifyDirtySync()
                    jsonOk(SwonalJson.encodeToString(updated))
                } else {
                    server.jsonError(Status.METHOD_NOT_ALLOWED, "Method not allowed")
                }
            }
            sub == "feed" && segs.size == 4 && m == Method.GET -> {
                val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                jsonOk(SwonalJson.encodeToString(repo.localNostr.getFeed(segs[3], limit)))
            }
            sub == "sync" && segs.size == 4 && m == Method.POST -> {
                val count = repo.localNostr.syncFeed(segs[3])
                repo.notifyDirtySync()
                jsonOk("""{"synced":$count}""")
            }
            sub == "share-routine" && segs.size == 5 && m == Method.POST -> {
                val tpl = repo.localDb.getTemplateById(segs[4])
                    ?: return server.jsonError(Status.NOT_FOUND, "Template not found")
                val ev = repo.localNostr.publishRoutine(segs[3], tpl)
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(ev))
            }
            sub == "import-routine" && segs.size == 5 && m == Method.POST -> {
                val imported = repo.localNostr.importRoutine(segs[3], segs[4])
                repo.refreshAppState()
                repo.notifyDirtySync()
                server.broadcastWsEvent("templates_updated")
                jsonOk(SwonalJson.encodeToString(WorkoutTemplate.serializer(), imported), Status.CREATED)
            }
            sub == "react" && segs.size == 4 && m == Method.POST -> {
                val b = parseJsonBody(session)
                val targetId = b["targetEventId"]?.jsonPrimitive?.contentOrNull
                    ?: b["activityId"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing targetEventId")
                val targetPubkey = b["targetPubkey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: repo.localDb.getFriendActivityByEventId(targetId)?.friendPubkeyHex
                    ?: repo.localNostr.getOrCreateIdentity(segs[3]).pubkeyHex
                val emoji = b["emoji"]?.jsonPrimitive?.contentOrNull ?: "💪"
                val ev = repo.localNostr.publishReaction(segs[3], targetId, targetPubkey, emoji)
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(ev))
            }
            segs.size == 3 && m == Method.GET -> {
                jsonOk(SwonalJson.encodeToString(repo.localNostr.listFriends(sub)))
            }
            segs.size == 3 && m == Method.POST -> {
                val b = parseJsonBody(session)
                val npubOrHex = b["npubOrHex"]?.jsonPrimitive?.contentOrNull
                    ?: b["npub"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing npubOrHex")
                val displayName = b["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: b["nickname"]?.jsonPrimitive?.contentOrNull
                val created = repo.localNostr.addFriend(sub, npubOrHex, displayName)
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(created), Status.CREATED)
            }
            segs.size == 4 && m == Method.DELETE -> {
                val ok = repo.localNostr.removeFriend(sub, segs[3])
                repo.notifyDirtySync()
                jsonOk("""{"deleted":$ok}""")
            }
            else -> server.jsonError(Status.NOT_FOUND, "Friends route not found")
        }
    }

    // --- Spotify & Music Hub ---

    private suspend fun handleSpotify(
        session: IHTTPSession,
        m: Method,
        segs: List<String>,
        auth: EmbeddedSwonalServer.RequestAuthContext
    ): Response {
        val uid = queryParam(session, "userId") ?: repo.uiStateFlow.value.activeUser?.id ?: ""
        val route = segs.drop(2).joinToString("/")
        return when {
            m == Method.GET && route == "status" -> {
                val st = repo.localSpotify.getPlaybackState(uid)
                jsonOk(SwonalJson.encodeToString(buildJsonObject {
                    put("connected", st.isConnected)
                    put("playback", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.MusicPlaybackState.serializer(), st))
                }))
            }
            m == Method.GET && route == "token" -> {
                if (!auth.authenticated) return server.jsonError(Status.UNAUTHORIZED, "Unauthorized")
                jsonOk("""{"status":"on_device_pkce","connected":${repo.uiStateFlow.value.musicState.isConnected}}""")
            }
            route == "reset-rate-limit" && (m == Method.GET || m == Method.POST) -> {
                repo.localSpotify.resetRateLimit()
                jsonOk("""{"ok":true,"rateLimitedUntilMs":0}""")
            }
            m == Method.GET && route == "speaker-status" -> {
                jsonOk(SwonalJson.encodeToString(repo.localSpotify.getTonalSpeakerStatus()))
            }
            m == Method.GET && route == "speaker-auth/url" -> {
                val url = repo.localSpotify.buildSpeakerPkceUrl(uid)
                jsonOk(SwonalJson.encodeToString(buildJsonObject { put("url", url) }))
            }
            m == Method.GET && route == "speaker-auth/start" -> {
                val url = repo.localSpotify.buildSpeakerPkceUrl(uid)
                server.textResponse(Status.REDIRECT, "Redirecting to Spotify...").apply {
                    addHeader("Location", url)
                }
            }
            m == Method.POST && route == "speaker-auth/complete" -> {
                val b = parseJsonBody(session)
                val codeOrUrl = b["code"]?.jsonPrimitive?.contentOrNull
                    ?: b["codeOrUrl"]?.jsonPrimitive?.contentOrNull
                    ?: b["redirectUrl"]?.jsonPrimitive?.contentOrNull
                    ?: b["url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing Spotify code or redirectUrl")
                val targetUid = b["userId"]?.jsonPrimitive?.contentOrNull ?: uid
                val res = repo.localSpotify.exchangeKeymasterPkceCode(targetUid, codeOrUrl)
                repo.notifyDirtySync()
                repo.refreshMusicNow()
                jsonOk(SwonalJson.encodeToString(res))
            }
            else -> server.jsonError(Status.NOT_FOUND, "Spotify route not found")
        }
    }

    private suspend fun handleMusic(session: IHTTPSession, m: Method, segs: List<String>): Response {
        val sub = segs.getOrNull(2) ?: return server.jsonError(Status.NOT_FOUND, "Not found")
        val uid = queryParam(session, "userId") ?: repo.uiStateFlow.value.activeUser?.id ?: ""
        return when {
            sub == "providers" && m == Method.GET -> {
                val list = listOf(repo.localSpotify.getProviderInfo()) + repo.localLanMusic.getSecondaryProviders()
                jsonOk(SwonalJson.encodeToString(list))
            }
            sub == "config" && m == Method.GET -> {
                val cfg = repo.localLanMusic.loadConfig()
                val out = buildJsonObject {
                    put("plexamp", buildJsonObject {
                        put("plexampUrl", cfg.plexampUrl)
                        put("hasToken", !cfg.plexTokenEnc.isNullOrBlank())
                    })
                    put("youtube_music", buildJsonObject {
                        put("apiUrl", cfg.ytmApiUrl)
                        put("hasToken", !cfg.ytmAuthTokenEnc.isNullOrBlank())
                    })
                    put("apple_music", buildJsonObject { put("haEntityId", cfg.appleHaEntityId) })
                    put("amazon_music", buildJsonObject { put("haEntityId", cfg.amazonHaEntityId) })
                }
                jsonOk(SwonalJson.encodeToString(out))
            }
            sub == "favorites" && m == Method.GET -> {
                val ids = repo.localSpotify.getFavoritePlaylists(uid)
                jsonOk(SwonalJson.encodeToString(buildJsonObject {
                    put("playlistIds", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }))
            }
            sub == "favorites" && segs.getOrNull(3) == "toggle" && m == Method.POST -> {
                val b = parseJsonBody(session)
                val targetUid = b["userId"]?.jsonPrimitive?.contentOrNull ?: uid
                val plId = b["playlistId"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing playlistId")
                val ids = repo.localSpotify.toggleFavoritePlaylist(targetUid, plId)
                repo.notifyDirtySync()
                jsonOk(SwonalJson.encodeToString(buildJsonObject {
                    put("playlistIds", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }))
            }
            segs.size >= 4 -> {
                val provider = sub
                val action = segs[3]
                when {
                    action == "config" && m == Method.POST -> {
                        val b = parseJsonBody(session)
                        repo.localLanMusic.updateProviderConfig(provider, b)
                        repo.notifyDirtySync()
                        jsonOk("""{"ok":true}""")
                    }
                    action == "test" && m == Method.POST -> {
                        val st = if (provider == "spotify") repo.localSpotify.getPlaybackState(uid)
                        else repo.localLanMusic.controlAction(provider, "state")
                        jsonOk(SwonalJson.encodeToString(buildJsonObject {
                            put("ok", st.isConnected)
                            put("state", SwonalJson.encodeToJsonElement(com.swonal.kiosk.model.MusicPlaybackState.serializer(), st))
                        }))
                    }
                    action == "state" && m == Method.GET -> {
                        val st = if (provider == "spotify") repo.localSpotify.getPlaybackState(uid)
                        else repo.localLanMusic.controlAction(provider, "state")
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "play" && m == Method.POST -> {
                        val b = runCatching { parseJsonBody(session) }.getOrNull()
                        val targetUid = b?.get("userId")?.jsonPrimitive?.contentOrNull ?: uid
                        val st = if (provider == "spotify") {
                            repo.localSpotify.play(
                                targetUid,
                                contextUri = b?.get("contextUri")?.jsonPrimitive?.contentOrNull,
                                deviceId = b?.get("deviceId")?.jsonPrimitive?.contentOrNull
                            )
                        } else {
                            repo.localLanMusic.controlAction(provider, "play")
                        }
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "pause" && m == Method.POST -> {
                        val b = runCatching { parseJsonBody(session) }.getOrNull()
                        val targetUid = b?.get("userId")?.jsonPrimitive?.contentOrNull ?: uid
                        val st = if (provider == "spotify") repo.localSpotify.pause(targetUid)
                        else repo.localLanMusic.controlAction(provider, "pause")
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "next" && m == Method.POST -> {
                        val b = runCatching { parseJsonBody(session) }.getOrNull()
                        val targetUid = b?.get("userId")?.jsonPrimitive?.contentOrNull ?: uid
                        val st = if (provider == "spotify") repo.localSpotify.nextTrack(targetUid)
                        else repo.localLanMusic.controlAction(provider, "next")
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "previous" && m == Method.POST -> {
                        val b = runCatching { parseJsonBody(session) }.getOrNull()
                        val targetUid = b?.get("userId")?.jsonPrimitive?.contentOrNull ?: uid
                        val st = if (provider == "spotify") repo.localSpotify.previousTrack(targetUid)
                        else repo.localLanMusic.controlAction(provider, "previous")
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "volume" && m == Method.POST -> {
                        val b = parseJsonBody(session)
                        val targetUid = b["userId"]?.jsonPrimitive?.contentOrNull ?: uid
                        val vol = b["volumePct"]?.jsonPrimitive?.intOrNull ?: 70
                        val st = if (provider == "spotify") repo.localSpotify.setVolume(targetUid, vol)
                        else repo.localLanMusic.controlAction(provider, "volume", vol)
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "duck" && m == Method.POST -> {
                        val b = runCatching { parseJsonBody(session) }.getOrNull()
                        val targetUid = b?.get("userId")?.jsonPrimitive?.contentOrNull ?: uid
                        val targetPct = b?.get("targetPct")?.jsonPrimitive?.intOrNull ?: 25
                        val durationMs = b?.get("durationMs")?.jsonPrimitive?.intOrNull ?: 1200
                        val st = if (provider == "spotify") repo.localSpotify.duckVolume(targetUid, targetPct, durationMs)
                        else com.swonal.kiosk.model.MusicPlaybackState(isConnected = false)
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    action == "playlists" && segs.size == 4 && m == Method.GET -> {
                        val pls = if (provider == "spotify") repo.localSpotify.getPlaylists(uid) else emptyList()
                        jsonOk(SwonalJson.encodeToString(pls))
                    }
                    action == "playlists" && segs.size == 6 && segs[5] == "tracks" && m == Method.GET -> {
                        val tracks = if (provider == "spotify") repo.localSpotify.getPlaylistTracks(segs[4], uid) else emptyList()
                        jsonOk(SwonalJson.encodeToString(tracks))
                    }
                    action == "devices" && m == Method.GET -> {
                        val devs = if (provider == "spotify") repo.localSpotify.getDevices(uid) else emptyList()
                        jsonOk(SwonalJson.encodeToString(devs))
                    }
                    action == "transfer" && m == Method.POST -> {
                        val b = parseJsonBody(session)
                        val targetUid = b["userId"]?.jsonPrimitive?.contentOrNull ?: uid
                        val devId = b["deviceId"]?.jsonPrimitive?.contentOrNull
                            ?: throw IllegalArgumentException("Missing deviceId")
                        val st = if (provider == "spotify") repo.localSpotify.transferPlayback(targetUid, devId)
                        else com.swonal.kiosk.model.MusicPlaybackState(isConnected = false)
                        jsonOk(SwonalJson.encodeToString(st))
                    }
                    else -> server.jsonError(Status.NOT_FOUND, "Music action not found")
                }
            }
            else -> server.jsonError(Status.NOT_FOUND, "Music route not found")
        }
    }
}
