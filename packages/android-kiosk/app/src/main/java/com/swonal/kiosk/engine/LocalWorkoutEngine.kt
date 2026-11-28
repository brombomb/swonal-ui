package com.swonal.kiosk.engine

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.model.AppStateResponse
import com.swonal.kiosk.model.ArmPositionSets
import com.swonal.kiosk.model.AuthStatus
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.OverloadSuggestion
import com.swonal.kiosk.model.PartnerRotation
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.QueuedWorkoutPayload
import com.swonal.kiosk.model.SetLogResponse
import com.swonal.kiosk.model.SharedSessionState
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.UnknownExerciseException
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.User
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.util.CryptoCompat
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/**
 * Primary offline-first domain engine backed directly by [SwonalDatabase].
 * All workout sessions, sets, PRs, overload calculations, partner rotations,
 * rest timers, exports, and passcode locks run locally in <2ms without an external server.
 */
class LocalWorkoutEngine(
    val db: SwonalDatabase,
    private val daemonSetWeight: (weightLb: Double, profile: String, modifierPct: Int) -> Unit = { _, _, _ -> },
    private val daemonResetReps: () -> Unit = {},
    private val onSessionEvent: (eventType: String, payload: JsonObject) -> Unit = { _, _ -> },
    private val onSessionCompletedHook: (session: WorkoutSession) -> Unit = {}
) {
    private val stateLock = Any()

    @Volatile
    private var sessionState = SharedSessionState()

    private val _sessionStateFlow = MutableStateFlow(SharedSessionState())
    val sessionStateFlow: StateFlow<SharedSessionState> = _sessionStateFlow.asStateFlow()

    private data class LoginAttemptState(var failures: Int = 0, var lockedUntilMs: Long = 0L)
    private val loginAttempts = ConcurrentHashMap<String, LoginAttemptState>()

    suspend fun initialize(seedJson: String, includeDemoUsersAndRoutines: Boolean = true) {
        db.seedIfEmpty(seedJson, includeDemoUsersAndRoutines)
        val users = db.listUsers()
        val firstUserId = users.firstOrNull()?.id.orEmpty()
        val partnerIds = users.take(2).map { it.id }
        val savedActiveUser = db.getSetting(SETTING_ACTIVE_USER_ID)
            ?.takeIf { uid -> users.any { it.id == uid } }
            ?: firstUserId
        updateSessionState {
            copy(
                activeUserId = savedActiveUser,
                partnerUserIds = if (partnerUserIds.isNotEmpty()) partnerUserIds else listOfNotNull(savedActiveUser.takeIf { it.isNotBlank() })
            )
        }
    }

    fun getSessionState(): SharedSessionState = synchronized(stateLock) { sessionState }

    private fun updateSessionState(transform: SharedSessionState.() -> SharedSessionState): SharedSessionState {
        val next = synchronized(stateLock) {
            sessionState = sessionState.transform()
            sessionState
        }
        _sessionStateFlow.value = next
        return next
    }

    suspend fun getAppState(daemonConnected: Boolean): AppStateResponse {
        val users = db.listUsers()
        val exercises = db.listExercises()
        val templates = db.listTemplates()
        val state = getSessionState()
        val validActive = if (users.any { it.id == state.activeUserId }) {
            state
        } else {
            val fallback = users.firstOrNull()?.id.orEmpty()
            updateSessionState { copy(activeUserId = fallback) }
        }
        return AppStateResponse(
            sessionState = validActive,
            daemonConnected = daemonConnected,
            users = users,
            exercises = exercises,
            templates = templates
        )
    }

    // --- Session Coordination ---

    suspend fun setActiveUser(userId: String): SharedSessionState {
        val user = db.getUserById(userId) ?: return getSessionState()
        db.setSetting(SETTING_ACTIVE_USER_ID, user.id)
        return updateSessionState {
            val idx = partnerUserIds.indexOf(user.id).let { if (it >= 0) it else 0 }
            copy(activeUserId = user.id, activePartnerIndex = idx)
        }
    }

    fun setPartnerMode(enabled: Boolean, partnerUserIds: List<String>? = null): SharedSessionState {
        return updateSessionState {
            val ids = if (!partnerUserIds.isNullOrEmpty()) partnerUserIds else this.partnerUserIds
            copy(
                partnerModeEnabled = enabled && ids.size >= 2,
                partnerUserIds = ids
            )
        }
    }

    fun setRestTimer(endsAtMs: Long?): SharedSessionState {
        return updateSessionState { copy(restTimerEndsAt = endsAtMs) }
    }

    fun startRestTimerSeconds(seconds: Int): SharedSessionState {
        val clamped = seconds.coerceIn(0, 3600)
        return setRestTimer(if (clamped > 0) System.currentTimeMillis() + clamped * 1000L else null)
    }

    fun setActiveSession(sessionId: String?, exerciseId: String? = null): SharedSessionState {
        return updateSessionState {
            copy(
                activeSessionId = sessionId,
                activeExerciseId = exerciseId ?: this.activeExerciseId
            )
        }
    }

    fun queueWorkout(payload: QueuedWorkoutPayload): SharedSessionState {
        return updateSessionState { copy(queuedWorkout = payload) }
    }

    fun clearQueuedWorkout(): SharedSessionState {
        return updateSessionState { copy(queuedWorkout = null) }
    }

    // --- Users CRUD ---

    suspend fun listUsers(): List<User> = db.listUsers()

    suspend fun createUser(
        name: String,
        avatarColor: String = "#3B82F6",
        accentColor: String = "#00F0FF",
        themeMode: String = "dark",
        weightUnit: String = "LB",
        preferredMusicProvider: String = "spotify"
    ): User {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 40) { "Name must be 1..40 characters" }
        val user = db.createUser(trimmed, avatarColor, accentColor, themeMode, weightUnit, preferredMusicProvider)
        val users = db.listUsers()
        if (users.size >= 2) {
            updateSessionState {
                if (partnerUserIds.size < 2) copy(partnerUserIds = users.take(2).map { it.id }) else this
            }
        }
        return user
    }

    suspend fun updateUser(
        userId: String,
        name: String? = null,
        avatarColor: String? = null,
        accentColor: String? = null,
        themeMode: String? = null,
        weightUnit: String? = null,
        preferredMusicProvider: String? = null
    ): User? = db.updateUser(userId, name?.trim()?.takeIf { it.isNotEmpty() }, avatarColor, accentColor, themeMode, weightUnit, preferredMusicProvider)

    suspend fun deleteUser(userId: String): Boolean {
        val deleted = db.deleteUser(userId)
        if (deleted && getSessionState().activeUserId == userId) {
            val next = db.listUsers().firstOrNull()
            if (next != null) {
                setActiveUser(next.id)
            }
        }
        return deleted
    }

    suspend fun getDemoPip(userId: String): Boolean = db.getDemoPip(userId)
    suspend fun setDemoPip(userId: String, enabled: Boolean): Boolean = db.setDemoPip(userId, enabled)

    // --- Exercises CRUD ---

    suspend fun listExercises(
        muscleGroup: String? = null,
        accessory: String? = null,
        unilateral: Boolean? = null,
        searchQuery: String? = null
    ): List<Exercise> = db.listExercises(muscleGroup, accessory, unilateral, searchQuery)

    suspend fun createExercise(
        name: String,
        muscleGroup: String,
        accessory: String = "HANDLES",
        recommendedHeight: Int = 4,
        recommendedRotation: Int = 2,
        recommendedAngle: Int = 1,
        unilateral: Boolean = false,
        notes: String? = null,
        mediaUrl: String? = null,
        allowedHeights: List<Int> = emptyList(),
        allowedRotations: List<Int> = emptyList(),
        allowedAngles: List<Int> = emptyList()
    ): Exercise {
        val cleanMedia = sanitizeMediaUrl(mediaUrl)
        return db.createExercise(
            name = name.trim(),
            muscleGroup = muscleGroup.trim(),
            accessory = accessory.uppercase(Locale.US),
            recommendedHeight = recommendedHeight.coerceIn(ArmPositionSets.HEIGHT_RANGE),
            recommendedRotation = recommendedRotation.coerceIn(ArmPositionSets.ROTATION_RANGE),
            recommendedAngle = recommendedAngle.coerceIn(ArmPositionSets.ANGLE_RANGE),
            unilateral = unilateral,
            notes = notes?.trim(),
            mediaUrl = cleanMedia,
            allowedHeights = allowedHeights,
            allowedRotations = allowedRotations,
            allowedAngles = allowedAngles
        )
    }

    suspend fun updateExercise(
        exerciseId: String,
        name: String? = null,
        muscleGroup: String? = null,
        accessory: String? = null,
        recommendedHeight: Int? = null,
        recommendedRotation: Int? = null,
        recommendedAngle: Int? = null,
        unilateral: Boolean? = null,
        notes: String? = null,
        mediaUrl: String? = null,
        updateMediaUrl: Boolean = false,
        allowedHeights: List<Int>? = null,
        allowedRotations: List<Int>? = null,
        allowedAngles: List<Int>? = null
    ): Exercise? {
        val cleanMedia = if (updateMediaUrl) sanitizeMediaUrl(mediaUrl) else mediaUrl?.let { sanitizeMediaUrl(it) }
        return db.updateExercise(
            exerciseId = exerciseId,
            name = name?.trim(),
            muscleGroup = muscleGroup?.trim(),
            accessory = accessory?.uppercase(Locale.US),
            recommendedHeight = recommendedHeight?.coerceIn(ArmPositionSets.HEIGHT_RANGE),
            recommendedRotation = recommendedRotation?.coerceIn(ArmPositionSets.ROTATION_RANGE),
            recommendedAngle = recommendedAngle?.coerceIn(ArmPositionSets.ANGLE_RANGE),
            unilateral = unilateral,
            notes = notes?.trim(),
            mediaUrl = cleanMedia,
            updateMediaUrl = updateMediaUrl,
            allowedHeights = allowedHeights,
            allowedRotations = allowedRotations,
            allowedAngles = allowedAngles
        )
    }

    suspend fun deleteExercise(exerciseId: String): Result<Unit> = db.deleteExerciseIfUnused(exerciseId)

    // --- Templates CRUD & Queue ---

    suspend fun listTemplates(userId: String? = null): List<WorkoutTemplate> = db.listTemplates(userId)

    suspend fun createTemplate(
        userId: String,
        name: String,
        description: String?,
        blocks: List<WorkoutBlock>,
        unit: String? = null,
        scheduleDays: List<Int> = emptyList()
    ): WorkoutTemplate {
        require(blocks.isNotEmpty()) { "Workout routine must have at least one block" }
        val user = db.getUserById(userId) ?: throw IllegalArgumentException("User not found: $userId")
        val effectiveUnit = unit ?: user.weightUnit
        val normalizedBlocks = normalizeBlocksToCanonicalLb(blocks, effectiveUnit)
        // Rejects missing/deleted exercises (IllegalArgumentException -> HTTP 400 in the embedded
        // API). Without this the kiosk later ran another exercise and logged sets/PRs against it.
        return db.createTemplate(
            userId, name.trim(), description?.trim(), normalizedBlocks, scheduleDays,
            requireKnownExercises = true
        )
    }

    suspend fun updateTemplate(
        templateId: String,
        name: String? = null,
        description: String? = null,
        blocks: List<WorkoutBlock>? = null,
        unit: String? = null,
        scheduleDays: List<Int>? = null
    ): WorkoutTemplate? {
        val existing = db.getTemplateById(templateId) ?: return null
        val owner = db.getUserById(existing.userId)
        val effectiveUnit = unit ?: owner?.weightUnit ?: "LB"
        val normalizedBlocks = blocks?.let {
            require(it.isNotEmpty()) { "Workout routine must have at least one block" }
            normalizeBlocksToCanonicalLb(it, effectiveUnit)
        }
        return db.updateTemplate(
            templateId, name?.trim(), description?.trim(), normalizedBlocks, scheduleDays,
            requireKnownExercises = true
        )
    }

    suspend fun deleteTemplate(templateId: String): Boolean = db.deleteTemplate(templateId)

    suspend fun queueTemplate(templateId: String, userIdOverride: String? = null): QueuedWorkoutPayload? {
        val template = db.getTemplateById(templateId) ?: return null
        // The routine may predate validation, or one of its exercises was deleted since it was saved.
        // Refuse (UnknownExerciseException -> HTTP 400) before it reaches the Tonal, like the server.
        db.requireKnownExercises(template.blocks, UnknownExerciseException.QUEUE_PREFIX)
        val uid = userIdOverride?.takeIf { it.isNotBlank() } ?: template.userId
        val payload = QueuedWorkoutPayload(
            userId = uid,
            templateId = template.id,
            templateName = template.name,
            description = template.description,
            blocks = template.blocks,
            queuedAt = IsoTime.nowIso()
        )
        queueWorkout(payload)
        onSessionEvent(
            "workout_queued",
            buildJsonObject {
                put("userId", uid)
                put("templateId", template.id)
                put("templateName", template.name)
            }
        )
        return payload
    }

    // --- Sessions, Sets, PRs & Progressive Overload ---

    suspend fun listSessions(userId: String? = null, limit: Int = 25): List<WorkoutSession> =
        db.listSessions(userId, limit)

    suspend fun createSession(userId: String, templateId: String? = null): WorkoutSession {
        val sess = db.createSession(userId, templateId)
        setActiveUser(userId)
        setActiveSession(sess.id, null)
        return sess
    }

    suspend fun logSet(
        sessionId: String,
        userId: String? = null,
        exerciseId: String,
        setNumber: Int,
        mode: String = "standard",
        modifierPct: Int = 0,
        weightLb: Double,
        repsCompleted: Int,
        rpe: Double? = null,
        restSeconds: Int = 60
    ): SetLogResponse {
        val safeWeight = Units.clampWeightForDynamicMode(weightLb, mode, modifierPct)
        val safeReps = repsCompleted.coerceIn(0, 500)
        val volumeLb = round(safeWeight * safeReps * 10.0) / 10.0
        val oneRepMaxEst = Units.estimateEpley1Rm(safeWeight, safeReps)
        val state = getSessionState()
        val effectiveUserId = userId?.takeIf { it.isNotBlank() } ?: state.activeUserId

        val outcome = db.insertSetAndEvaluatePr(
            sessionId = sessionId,
            effectiveUserId = effectiveUserId,
            exerciseId = exerciseId,
            setNumber = setNumber.coerceAtLeast(1),
            mode = mode.lowercase(Locale.US),
            modifierPct = Units.effectiveModifierPct(mode, modifierPct),
            weightLb = safeWeight,
            repsCompleted = safeReps,
            volumeLb = volumeLb,
            rpe = rpe?.coerceIn(1.0, 10.0),
            oneRepMaxEst = oneRepMaxEst
        ) ?: throw IllegalArgumentException("Workout session not found: $sessionId")

        if (restSeconds > 0) {
            startRestTimerSeconds(restSeconds)
        }

        // Partner "Work In" rotation matching server-app/apiRoutes.ts:514-553
        var rotation: PartnerRotation? = null
        val curState = getSessionState()
        if (curState.partnerModeEnabled && curState.partnerUserIds.size >= 2) {
            val nextIdx = (curState.activePartnerIndex + 1) % curState.partnerUserIds.size
            val nextUserId = curState.partnerUserIds[nextIdx]
            updateSessionState { copy(activePartnerIndex = nextIdx, activeUserId = nextUserId) }
            db.setSetting(SETTING_ACTIVE_USER_ID, nextUserId)

            val partnerPr = db.getPrForExercise(nextUserId, exerciseId)
            val rawTarget = if (partnerPr != null && partnerPr.maxWeightLb > 0.0) {
                round((partnerPr.maxWeightLb * 0.85) / 5.0) * 5.0
            } else {
                safeWeight
            }
            val partnerTargetLb = Units.clampWeightForDynamicMode(rawTarget, mode, modifierPct)
            daemonSetWeight(partnerTargetLb, mode, modifierPct)
            daemonResetReps()
            rotation = PartnerRotation(nextUserId, partnerTargetLb)
        } else {
            daemonResetReps()
        }

        onSessionEvent(
            "set_logged",
            buildJsonObject {
                put("userId", effectiveUserId)
                put("sessionId", sessionId)
                put("exerciseId", exerciseId)
                put("weightLb", safeWeight)
                put("repsCompleted", safeReps)
                put("volumeLb", volumeLb)
                put("newPr", outcome.newPr)
            }
        )

        return SetLogResponse(
            setLog = outcome.setLog,
            newPr = outcome.newPr,
            totalVolume = outcome.totalVolume,
            partnerRotation = rotation
        )
    }

    suspend fun completeSession(sessionId: String): WorkoutSession? {
        val completed = db.markSessionCompleted(sessionId) ?: return null
        updateSessionState { copy(activeSessionId = null, activeExerciseId = null, restTimerEndsAt = null) }
        onSessionEvent(
            "workout_completed",
            buildJsonObject {
                put("userId", completed.userId)
                put("sessionId", completed.id)
                put("routineName", completed.template?.name ?: "Free Lift")
                put("totalVolumeLb", completed.totalVolume)
                put("setsCount", completed.setLogs.size)
            }
        )
        onSessionCompletedHook(completed)
        return completed
    }

    suspend fun getPrs(userId: String): List<PersonalRecord> = db.listPrs(userId)

    suspend fun getOverloadSuggestion(userId: String, exerciseId: String): OverloadSuggestion {
        val recentSets = db.getRecentSetLogsForExercise(userId, exerciseId, limit = 4)
        val pr = db.getPrForExercise(userId, exerciseId)
        val latest = recentSets.firstOrNull()
        val twoStrongSets = recentSets.size >= 2 &&
            recentSets[0].repsCompleted >= 8 &&
            recentSets[1].repsCompleted >= 8 &&
            recentSets[0].weightLb >= recentSets[1].weightLb
        val highRepLatest = latest != null && latest.repsCompleted >= 10
        val ready = latest != null && (twoStrongSets || highRepLatest) && latest.weightLb < Units.MAX_WEIGHT_LB
        val recommendedWeightLb = when {
            latest != null -> minOf(Units.MAX_WEIGHT_LB, latest.weightLb + if (ready) 5.0 else 0.0)
            pr != null -> (round((pr.maxWeightLb * 0.85) / 5.0) * 5.0).coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
            else -> 45.0
        }
        return OverloadSuggestion(
            ready = ready,
            recommendedBumpLb = if (ready) 5.0 else 0.0,
            recommendedWeightLb = recommendedWeightLb,
            lastWeightLb = latest?.weightLb,
            lastReps = latest?.repsCompleted,
            reason = if (ready) {
                "Strong rep consistency detected — ready for +5 lb progressive overload"
            } else {
                "Log 8+ reps across 2 sets (or 10+ reps) to unlock +5 lb overload suggestion"
            }
        )
    }

    // --- Exports & Backups ---

    suspend fun exportSessionsCsv(userId: String?): String {
        val uid = userId?.takeIf { it.isNotBlank() } ?: getSessionState().activeUserId
        val user = db.getUserById(uid)
        val unit = if (Units.isKg(user?.weightUnit ?: "LB")) "KG" else "LB"
        val sessions = db.listSessions(uid, limit = 500)

        fun escapeCsv(value: Any?): String {
            val str = value?.toString().orEmpty()
            val safe = if (str.isNotEmpty() && str[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
                "'$str"
            } else {
                str
            }
            return "\"${safe.replace("\"", "\"\"")}\""
        }

        val sb = StringBuilder()
        sb.append("SessionId,Routine,StartedAt,Exercise,MuscleGroup,Set,Mode,Weight($unit),Reps,Volume($unit),Timestamp\n")
        for (s in sessions) {
            val routine = s.template?.name ?: "Free Lift"
            for (log in s.setLogs) {
                sb.append(
                    listOf(
                        escapeCsv(s.id),
                        escapeCsv(routine),
                        escapeCsv(s.startedAt),
                        escapeCsv(log.exercise?.name ?: log.exerciseId),
                        escapeCsv(log.exercise?.muscleGroup ?: ""),
                        log.setNumber.toString(),
                        escapeCsv(log.mode),
                        Units.formatCompactWeight(log.weightLb, unit),
                        log.repsCompleted.toString(),
                        Units.formatCompactWeight(log.volumeLb ?: (log.weightLb * log.repsCompleted), unit, exact = true),
                        escapeCsv(log.timestamp)
                    ).joinToString(",")
                ).append('\n')
            }
        }
        return sb.toString()
    }

    suspend fun exportSessionsJson(userId: String?): String {
        val uid = userId?.takeIf { it.isNotBlank() } ?: getSessionState().activeUserId
        val user = db.getUserById(uid)
        val unit = if (Units.isKg(user?.weightUnit ?: "LB")) "KG" else "LB"
        val sessions = db.listSessions(uid, limit = 500)

        val json = buildJsonObject {
            put("exportedAt", IsoTime.nowIso())
            put("source", "swonal-ui")
            put("activityType", "TraditionalStrengthTraining")
            put("athlete", user?.name ?: "Athlete")
            put("unit", unit)
            put("sessions", buildJsonArray {
                for (s in sessions) {
                    add(buildJsonObject {
                        put("id", s.id)
                        put("routine", s.template?.name ?: "Free Lift")
                        put("startedAt", s.startedAt)
                        s.completedAt?.let { put("completedAt", it) }
                        put("totalVolume", Units.formatDisplayWeight(s.totalVolume, unit, exact = true))
                        put("unit", unit)
                        put("sets", buildJsonArray {
                            for (l in s.setLogs) {
                                add(buildJsonObject {
                                    put("exercise", l.exercise?.name ?: l.exerciseId)
                                    put("muscleGroup", l.exercise?.muscleGroup ?: "")
                                    put("accessory", l.exercise?.accessory ?: "HANDLES")
                                    put("setNumber", l.setNumber)
                                    put("mode", l.mode)
                                    put("weight", Units.formatDisplayWeight(l.weightLb, unit))
                                    put("reps", l.repsCompleted)
                                    put("volume", Units.formatDisplayWeight(l.volumeLb ?: (l.weightLb * l.repsCompleted), unit, exact = true))
                                    put("timestamp", l.timestamp)
                                })
                            }
                        })
                    })
                }
            })
        }
        return SwonalJson.encodeToString(json)
    }

    suspend fun exportSqliteBackup(destFile: File): File = db.exportBackupToFile(destFile)

    // --- Offline-Safe Local Passcode & API Token Auth ---

    suspend fun getAuthStatus(
        isLoopbackClient: Boolean = true,
        isSessionAuthenticated: Boolean = false
    ): AuthStatus {
        val hash = db.getSetting(SETTING_AUTH_HASH)
        val enabled = !hash.isNullOrBlank()
        val trustLocal = (db.getSetting(SETTING_AUTH_TRUST_LOCALHOST) ?: "true") != "false"
        val loopbackBypass = enabled && trustLocal && isLoopbackClient
        return AuthStatus(
            enabled = enabled,
            authenticated = !enabled || isSessionAuthenticated || loopbackBypass,
            trustLocalhost = trustLocal,
            isLocalhostBypass = loopbackBypass,
            envLocked = false
        )
    }

    suspend fun configureAuth(
        enabled: Boolean? = null,
        passcode: String? = null,
        trustLocalhost: Boolean? = null
    ): AuthStatus {
        if (trustLocalhost != null) {
            db.setSetting(SETTING_AUTH_TRUST_LOCALHOST, if (trustLocalhost) "true" else "false")
        }
        if (enabled == false) {
            db.deleteSetting(SETTING_AUTH_HASH)
        } else if (!passcode.isNullOrBlank()) {
            val clean = passcode.trim()
            require(clean.length in 4..200) { "Passcode must be 4..200 characters" }
            db.setSetting(SETTING_AUTH_HASH, CryptoCompat.hashPasscode(clean))
        } else if (enabled == true && db.getSetting(SETTING_AUTH_HASH).isNullOrBlank()) {
            throw IllegalArgumentException("Provide a 4+ character passcode to enable lock")
        }
        if (db.getSetting(SETTING_LOCAL_API_TOKEN).isNullOrBlank()) {
            db.setSetting(SETTING_LOCAL_API_TOKEN, CryptoCompat.randomHex(24))
        }
        return getAuthStatus(isLoopbackClient = true, isSessionAuthenticated = true)
    }

    suspend fun verifyPasscode(passcode: String, clientKey: String = "local"): Result<Boolean> {
        val hash = db.getSetting(SETTING_AUTH_HASH)
        if (hash.isNullOrBlank()) return Result.success(true)

        val now = System.currentTimeMillis()
        var state = loginAttempts[clientKey]
        if (state == null) {
            val fresh = LoginAttemptState()
            state = loginAttempts.putIfAbsent(clientKey, fresh) ?: fresh
        }
        synchronized(state) {
            if (state.lockedUntilMs > now) {
                val waitSec = ((state.lockedUntilMs - now) / 1000L + 1L).coerceAtLeast(1L)
                return Result.failure(IllegalStateException("Too many failed passcode attempts. Try again in ${waitSec}s."))
            }
        }
        val ok = CryptoCompat.verifyPasscode(passcode, hash)
        synchronized(state) {
            if (ok) {
                state.failures = 0
                state.lockedUntilMs = 0L
            } else {
                state.failures += 1
                if (state.failures >= 5) {
                    state.failures = 0
                    state.lockedUntilMs = now + 60_000L
                }
            }
        }
        return Result.success(ok)
    }

    suspend fun getOrCreateLocalApiToken(): String {
        val existing = db.getSetting(SETTING_LOCAL_API_TOKEN)
        if (!existing.isNullOrBlank()) return existing
        val created = CryptoCompat.randomHex(24)
        db.setSetting(SETTING_LOCAL_API_TOKEN, created)
        return created
    }

    suspend fun getOrCreateSessionSecret(): String {
        val existing = db.getSetting(SETTING_SESSION_SECRET)
        if (!existing.isNullOrBlank()) return existing
        val created = CryptoCompat.randomHex(32)
        db.setSetting(SETTING_SESSION_SECRET, created)
        return created
    }

    suspend fun verifyApiToken(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val stored = db.getSetting(SETTING_LOCAL_API_TOKEN) ?: return false
        return CryptoCompat.constantTimeEquals(candidate.trim(), stored)
    }

    // --- Helpers ---

    private fun normalizeBlocksToCanonicalLb(blocks: List<WorkoutBlock>, unit: String): List<WorkoutBlock> {
        if (!Units.isKg(unit)) return blocks
        return blocks.map { b ->
            b.copy(
                items = b.items.map { item ->
                    item.copy(
                        targetWeightLb = (round((item.targetWeightLb * Units.KG_TO_LB) * 2.0) / 2.0)
                            .coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
                    )
                }
            )
        }
    }

    private fun sanitizeMediaUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(
            trimmed.startsWith("/media/") ||
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "mediaUrl must be a /media/ path or http(s) URL"
        }
        return trimmed.take(500)
    }

    companion object {
        const val SETTING_ACTIVE_USER_ID = "active_user_id"
        const val SETTING_AUTH_HASH = "auth_passcode_hash"
        const val SETTING_AUTH_TRUST_LOCALHOST = "auth_trust_localhost"
        const val SETTING_LOCAL_API_TOKEN = "local_api_token"
        const val SETTING_SESSION_SECRET = "local_session_secret"
        const val SETTING_SYNC_CURSOR_MS = "last_sync_cursor_ms"
        const val SETTING_INITIAL_SERVER_IMPORT_DONE = "initial_server_import_done"
    }
}
