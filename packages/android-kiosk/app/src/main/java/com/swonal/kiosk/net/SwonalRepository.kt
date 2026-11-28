package com.swonal.kiosk.net

import com.swonal.kiosk.audio.SwonalSoundEngine
import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.countDirtySyncRows
import com.swonal.kiosk.engine.LocalAiCoachEngine
import com.swonal.kiosk.engine.LocalWorkoutEngine
import com.swonal.kiosk.integrations.LocalHomeAssistantClient
import com.swonal.kiosk.model.*
import com.swonal.kiosk.music.LocalLanMusicProviders
import com.swonal.kiosk.music.LocalSpotifyController
import com.swonal.kiosk.social.LocalNostrSwocialEngine
import com.swonal.kiosk.util.PlaintextTestCipher
import com.swonal.kiosk.util.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.round

data class KioskUiState(
    val syncMode: SyncMode = SyncMode.STANDALONE,
    val syncServerUrl: String = "",
    val syncConnected: Boolean = false,
    val pendingSyncCount: Int = 0,
    val lastSyncedAtMs: Long? = null,
    val syncError: String? = null,
    val localWebServerUrl: String = SwonalPreferences.DEFAULT_SERVER_URL,
    val serverUrl: String = SwonalPreferences.DEFAULT_SERVER_URL,
    val localDaemonUrl: String = SwonalPreferences.DEFAULT_DAEMON_URL,
    val wsConnected: Boolean = false,
    val daemonConnected: Boolean = false,
    val localFailSafeConnected: Boolean = false,
    val isMockMode: Boolean = false,
    val authRequired: Boolean = false,
    val authChecking: Boolean = false,
    val authError: String? = null,
    val users: List<User> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val templates: List<WorkoutTemplate> = emptyList(),
    val sessionState: SharedSessionState = SharedSessionState(),
    val activeExerciseId: String? = null,
    val activeTemplateName: String? = null,
    val routineSteps: List<RoutineStep> = emptyList(),
    val currentStepIdx: Int = 0,
    val prs: List<PersonalRecord> = emptyList(),
    val overloadSuggestion: OverloadSuggestion? = null,
    val lastPrBanner: String? = null,
    val sessionPrHits: List<PrHit> = emptyList(),
    val safetyEvent: SafetyEvent? = null,
    val restSecondsRemaining: Int = 0,
    val showDemoPip: Boolean = true,
    val telemetryDisplayMode: TelemetryDisplayMode = TelemetryDisplayMode.ALWAYS,
    val soundMuted: Boolean = false,
    val developerMode: Boolean = false,
    /** Active lifter's "Auto-Adjust Weights" preference: rep-goal driven ±5/10% between routine sets. */
    val autoAdjustWeights: Boolean = false,
    /** Accessory the lifter last used per exercise (overrides the catalog default in the attachment prompt). */
    val rememberedAccessoryByExercise: Map<String, String> = emptyMap(),
    val musicProviders: List<MusicProviderInfo> = emptyList(),
    val musicState: MusicPlaybackState = MusicPlaybackState(),
    val musicDevices: List<MusicDevice> = emptyList(),
    val musicPlaylists: List<MusicPlaylist> = emptyList(),
    val favoritePlaylistIds: Set<String> = emptySet(),
    val musicError: String? = null,
    val coachConfig: CoachConfig? = null,
    val coachReply: String? = null,
    val coachAction: CoachSuggestedAction? = null,
    val coachLoading: Boolean = false,
    val networkInfo: NetworkInfo? = null,
    val calibrationStatus: CalibrationStatus? = null,
    val haStatus: HomeAssistantStatus? = null,
    val recentSessions: List<WorkoutSession> = emptyList(),
    val statusToast: String? = null
) {
    val activeUser: User?
        get() = users.firstOrNull { it.id == sessionState.activeUserId } ?: users.firstOrNull()

    val partnerUser: User?
        get() {
            val activeId = activeUser?.id ?: return null
            val partnerId = sessionState.partnerUserIds.firstOrNull { it != activeId }
            return users.firstOrNull { it.id == partnerId } ?: users.firstOrNull { it.id != activeId }
        }

    val activeUnit: String
        get() = activeUser?.weightUnit ?: "LB"

    val activeThemeMode: String
        get() = activeUser?.themeMode ?: "dark"

    val activeAccentHex: String
        get() = activeUser?.accentColor ?: "#00F0FF"

    val preferredMusicProvider: String
        get() = activeUser?.preferredMusicProvider?.ifBlank { "spotify" } ?: "spotify"

    val currentStep: RoutineStep?
        get() = routineSteps.getOrNull(currentStepIdx)

    val nextStep: RoutineStep?
        get() = routineSteps.getOrNull(currentStepIdx + 1)

    val activeExercise: Exercise?
        get() {
            val stepExId = currentStep?.exerciseId
            val targetId = stepExId ?: activeExerciseId
            return exercises.firstOrNull { it.id == targetId } ?: exercises.firstOrNull()
        }

    val nextExercise: Exercise?
        get() = nextStep?.let { step -> exercises.firstOrNull { it.id == step.exerciseId } }

    val activeExercisePr: PersonalRecord?
        get() = activeExercise?.let { ex -> prs.firstOrNull { it.exerciseId == ex.id } }

    val musicConfigured: Boolean
        get() = musicProviders.any { it.configured } || musicState.isConnected

    val isStandaloneMode: Boolean
        get() = syncMode == SyncMode.STANDALONE

    val isUsingLocalFailSafe: Boolean
        get() = syncMode == SyncMode.SERVER_SYNC && (!wsConnected || !daemonConnected) && localFailSafeConnected

    val hardwareConnected: Boolean
        get() = localFailSafeConnected || (wsConnected && daemonConnected)
}

sealed interface KioskEffect {
    data class PlayCue(val cue: SwonalSoundEngine.Cue) : KioskEffect
    data class ShowWorkoutSummary(val session: WorkoutSession, val prHits: List<PrHit>) : KioskEffect
    data class ShowMessage(val message: String) : KioskEffect
}

class SwonalRepository(
    val prefs: SwonalPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val tlsConfig: SwonalTlsConfig? = null,
    apiClientOverride: SwonalApiClient? = null,
    localDaemonOverride: LocalDaemonFailSafeClient? = null,
    localDbPath: String? = null,
    private val seedCatalogJson: String? = null,
    secretCipherOverride: SecretCipher? = null
) {
    private enum class TelemetrySource { NONE, SERVER, LOCAL }

    private val secretCipher: SecretCipher = secretCipherOverride ?: runCatching { prefs.secretCipher }.getOrElse { PlaintextTestCipher }

    val localDb: SwonalDatabase = SwonalDatabase(
        dbPath = localDbPath ?: ":memory:",
        cipher = secretCipher
    )

    val localAiCoach: LocalAiCoachEngine = LocalAiCoachEngine(localDb, secretCipher, tlsConfig)
    val localHaClient: LocalHomeAssistantClient = LocalHomeAssistantClient(localDb, secretCipher, tlsConfig)
    val localNostr: LocalNostrSwocialEngine = LocalNostrSwocialEngine(localDb, tlsConfig)

    private val _wsBroadcastFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val wsBroadcastFlow: SharedFlow<String> = _wsBroadcastFlow.asSharedFlow()

    @Volatile
    var syncPokeListener: (() -> Unit)? = null

    @Volatile
    var syncNowHandler: (suspend () -> Result<Unit>)? = null

    @Volatile
    var restoreFromServerHandler: (suspend () -> Result<Unit>)? = null

    suspend fun triggerSyncNow(): Result<Unit> {
        val handler = syncNowHandler
            ?: return Result.failure(IllegalStateException("Server sync is only active in Home/Cloud Server Sync mode"))
        return handler()
    }

    suspend fun restoreFromSyncServer(): Result<Unit> {
        val handler = restoreFromServerHandler
            ?: return Result.failure(IllegalStateException("Configure Home/Cloud Server Sync URL first"))
        return handler()
    }

    @Volatile
    var sessionCompletedListener: ((WorkoutSession) -> Unit)? = null

    @Volatile
    var sessionEventListener: ((String, JsonObject) -> Unit)? = null

    val localEngine: LocalWorkoutEngine = LocalWorkoutEngine(
        db = localDb,
        daemonSetWeight = { w, p, m -> setWeight(w, p, m, allowWhileEngaged = false) },
        daemonResetReps = { resetReps() },
        onSessionEvent = { evType, payload ->
            handleLocalSessionEvent(evType, payload)
            sessionEventListener?.invoke(evType, payload)
        },
        onSessionCompletedHook = { completed ->
            handleLocalSessionCompleted(completed)
            sessionCompletedListener?.invoke(completed)
            notifyDirtySync()
        }
    )

    private fun handleLocalSessionEvent(evType: String, payload: JsonObject) {
        cloudSyncManager.forwardSessionEvent(evType, payload)
        scope.launch {
            runCatching { localHaClient.publishEvent(evType, payload) }
            runCatching { localHaClient.getStatus() }.onSuccess { ha ->
                _uiStateFlow.update { it.copy(haStatus = ha) }
            }
        }
    }

    private fun handleLocalSessionCompleted(completed: WorkoutSession) {
        val prLabels = _uiStateFlow.value.sessionPrHits.map {
            "${it.exerciseName}: ${round(it.oneRepMaxEst).toInt()} lb 1RM"
        }
        scope.launch {
            runCatching { localNostr.publishWorkout(completed, prLabels) }
            runCatching {
                localHaClient.publishEvent(
                    "workout_completed",
                    buildJsonObject {
                        put("sessionId", completed.id)
                        put("userId", completed.userId)
                        put("routineName", completed.template?.name ?: "Free Lift")
                        put("totalVolumeLb", round(completed.totalVolume).toInt())
                        put("setsCount", completed.setLogs.size)
                        put("prCount", prLabels.size)
                    }
                )
            }
            runCatching { localHaClient.getStatus() }.onSuccess { ha ->
                _uiStateFlow.update { it.copy(haStatus = ha) }
            }
        }
    }

    fun emitLocalBroadcast(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val obj = buildJsonObject {
            put("type", type)
            extra()
        }
        _wsBroadcastFlow.tryEmit(SwonalJson.encodeToString(obj))
    }

    fun emitRawLocalBroadcast(jsonText: String) {
        _wsBroadcastFlow.tryEmit(jsonText)
    }

    fun notifyDirtySync() {
        scope.launch {
            val dirty = runCatching { localDb.countDirtySyncRows() }.getOrDefault(0)
            _uiStateFlow.update { it.copy(pendingSyncCount = dirty) }
            syncPokeListener?.invoke()
        }
    }

    private val lastUiHeartbeatMs = AtomicLong(System.currentTimeMillis())
    private val uiActive = AtomicBoolean(true)

    fun isUiAliveForWatchdog(): Boolean {
        if (!uiActive.get() || _uiStateFlow.value.authRequired) return false
        return (System.currentTimeMillis() - lastUiHeartbeatMs.get()) < 1600L
    }

    fun markUiHeartbeat(isForeground: Boolean) {
        uiActive.set(isForeground)
        if (isForeground) {
            lastUiHeartbeatMs.set(System.currentTimeMillis())
        }
    }

    private val hasApiClientOverride = apiClientOverride != null

    val api: SwonalApiClient = apiClientOverride ?: SwonalApiClient(
        baseUrlProvider = { prefs.serverUrl },
        tokenProvider = { prefs.apiToken },
        sessionCookieStore = object : SwonalApiClient.SessionCookieStore {
            override fun getCookie(): String? = prefs.sessionCookie
            override fun saveCookie(cookieValue: String?) {
                prefs.sessionCookie = cookieValue
            }
        },
        tlsConfig = tlsConfig
    )

    val localDaemon: LocalDaemonFailSafeClient = localDaemonOverride ?: LocalDaemonFailSafeClient(
        daemonBaseUrlProvider = { prefs.localDaemonUrl },
        scope = scope,
        uiAliveCheck = { isUiAliveForWatchdog() }
    )

    val localSpotify: LocalSpotifyController = LocalSpotifyController(localDb, localDaemon, scope, tlsConfig)
    val localLanMusic: LocalLanMusicProviders = LocalLanMusicProviders(localDb, secretCipher, localHaClient, tlsConfig)

    val cloudSyncManager: com.swonal.kiosk.sync.CloudSyncManager = com.swonal.kiosk.sync.CloudSyncManager(
        db = localDb,
        engine = localEngine,
        prefs = prefs,
        scope = scope,
        tlsConfig = tlsConfig,
        onStatusUpdate = { connected, pending, lastMs, err ->
            _uiStateFlow.update {
                it.copy(
                    syncConnected = connected,
                    pendingSyncCount = pending,
                    lastSyncedAtMs = lastMs,
                    syncError = err
                )
            }
        },
        onDataPulled = { refreshAppState() },
        onWorkoutQueued = { payload -> deliverSyncedQueuedWorkout(payload) }
    )

    init {
        syncPokeListener = { cloudSyncManager.requestDebouncedSync() }
        syncNowHandler = { cloudSyncManager.syncNow() }
        restoreFromServerHandler = { cloudSyncManager.restoreFromServer() }
    }

    suspend fun deliverSyncedQueuedWorkout(payload: QueuedWorkoutPayload) {
        localEngine.queueWorkout(payload)
        cloudSyncManager.requestDebouncedSync()
        emitLocalBroadcast("workout_queued") {
            put("workout", SwonalJson.encodeToJsonElement(QueuedWorkoutPayload.serializer(), payload))
        }
        _effectsFlow.tryEmit(KioskEffect.ShowMessage("Workout queued from Home Server: ${payload.templateName}"))
    }

    fun buildSpeakerPairingUrl(userId: String): String = localSpotify.buildSpeakerPkceUrl(userId)

    @Volatile
    var embeddedServerPort: Int = 53013
        private set

    fun latestTelemetryFrame(): TelemetryFrame = _telemetryFlow.value

    fun attachEmbeddedServer(port: Int, lanIps: List<String>) {
        embeddedServerPort = port
        val localUrl = "http://127.0.0.1:$port"
        _uiStateFlow.update { cur ->
            val primary = lanIps.firstOrNull()
            val curPublicOverride = cur.networkInfo?.publicUrlOverride
            val effectivePublic = curPublicOverride?.takeIf { it.isNotBlank() } ?: cur.networkInfo?.publicUrl
            cur.copy(
                localWebServerUrl = localUrl,
                networkInfo = NetworkInfo(
                    primaryIp = primary,
                    ips = lanIps,
                    port = port,
                    publicUrl = effectivePublic,
                    publicUrlOverride = curPublicOverride,
                    companionBaseUrl = effectivePublic?.trimEnd('/') ?: primary?.let { "http://$it:$port" }
                )
            )
        }
    }

    suspend fun getEffectiveNetworkInfo(): NetworkInfo {
        val ips = com.swonal.kiosk.server.EmbeddedSwonalServer.discoverLanIpv4s()
        val publicOverride = runCatching { localDb.getSetting("public_url_override") }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val primary = ips.firstOrNull()
        val port = embeddedServerPort
        val info = NetworkInfo(
            primaryIp = primary,
            ips = ips,
            port = port,
            publicUrl = publicOverride,
            publicUrlOverride = publicOverride,
            companionBaseUrl = publicOverride?.trimEnd('/') ?: primary?.let { "http://$it:$port" }
        )
        _uiStateFlow.update { it.copy(networkInfo = info) }
        return info
    }

    private val _telemetryFlow = MutableStateFlow(TelemetryFrame())
    val telemetryFlow: StateFlow<TelemetryFrame> = _telemetryFlow.asStateFlow()

    private val _vbtFlow = MutableStateFlow(VbtMetrics())
    val vbtFlow: StateFlow<VbtMetrics> = _vbtFlow.asStateFlow()

    private val initialCachedUsers: List<User> = runCatching {
        prefs.cachedUsersJson?.let { SwonalJson.decodeFromString<List<User>>(it) }
    }.getOrNull().orEmpty()

    private val initialCachedExercises: List<Exercise> = runCatching {
        prefs.cachedExercisesJson?.let { SwonalJson.decodeFromString<List<Exercise>>(it) }
    }.getOrNull().orEmpty()

    private val _uiStateFlow = MutableStateFlow(
        KioskUiState(
            syncMode = prefs.syncMode,
            syncServerUrl = prefs.syncServerUrl,
            serverUrl = prefs.serverUrl,
            localDaemonUrl = prefs.localDaemonUrl,
            authRequired = false,
            telemetryDisplayMode = prefs.telemetryDisplayMode,
            soundMuted = prefs.soundMuted,
            developerMode = prefs.developerMode,
            users = initialCachedUsers,
            exercises = initialCachedExercises,
            activeExerciseId = initialCachedExercises.firstOrNull()?.id
        )
    )
    val uiStateFlow: StateFlow<KioskUiState> = _uiStateFlow.asStateFlow()

    private val _effectsFlow = MutableSharedFlow<KioskEffect>(extraBufferCapacity = 32)
    val effectsFlow: SharedFlow<KioskEffect> = _effectsFlow.asSharedFlow()

    private val telemetryLock = Any()
    private val vbtTracker = VbtTracker()
    private var prevWeightEnabled = false
    private var prevRepCount = 0
    private var activeTelemetrySource = TelemetrySource.NONE
    private var lastAutoLoggedStepKey: String? = null

    /** Held weight while engaged + which lifter the current set belongs to. */
    private val heldWeight = HeldWeightTracker()

    /** Serializes attachment sends so a lifter's own pick and a held attachment can never interleave. */
    private val accessoryLock = Any()

    /**
     * Bumped whenever what's on screen changes (exercise pick, step change, routine start, profile switch,
     * Work-In rotation). A settings lookup that finishes after a newer change is dropped instead of
     * overwriting the newer choice.
     */
    private val selectionGeneration = AtomicLong(0L)

    /** When the weight last engaged, and when a safety cutout was last reported (cutout verdict per set). */
    @Volatile
    private var engagedAtMs = 0L

    @Volatile
    private var lastSafetyEventAtMs = 0L

    /** A real motor telemetry frame has arrived (before that, the default frame's "weight off" means nothing). */
    @Volatile
    private var motorFrameSeen = false

    /** Throttles re-sending held settings when the motor didn't take them. */
    @Volatile
    private var lastHeldApplyAttemptMs = 0L

    private suspend fun prFor(userId: String, exerciseId: String): PersonalRecord? =
        runCatching { localEngine.getPrs(userId) }.getOrNull()?.firstOrNull { it.exerciseId == exerciseId }

    /**
     * Settings for a routine step: the step's own weight, mode and modifier, except that an AUTO step for a
     * lifter with Auto-Adjust ON uses their remembered (adjusted) weight at this rep goal. The attachment is
     * the one the lifter last used on the exercise, never anything but the BAR on a BAR exercise.
     */
    private suspend fun resolveRoutineStepSettings(
        userId: String?,
        step: RoutineStep,
        exercise: Exercise?,
        workInPartner: Boolean = false
    ): ExerciseSettings {
        if (userId == null) return ExerciseSettingsPolicy.forRoutineStep(step, exercise, null, false, null)
        awaitPendingRemember()
        val autoAdjust = autoAdjustFor(userId)
        val autoStep = step.autoWeight && autoAdjust
        val pref = if (autoStep) {
            runCatching {
                localEngine.getExercisePref(userId, step.exerciseId, ExercisePref.routineScope(step.targetReps))
            }.getOrNull()
        } else null
        val lastAccessory = pref?.accessory
            ?: runCatching { localEngine.getMostRecentExercisePref(userId, step.exerciseId) }.getOrNull()?.accessory
        val pr = if (pref == null && (workInPartner || autoStep)) prFor(userId, step.exerciseId) else null
        return ExerciseSettingsPolicy.forRoutineStep(
            step, exercise, pref, autoAdjust, pr?.maxWeightLb, workInPartner,
            prOneRepMaxLb = pr?.oneRepMaxEst,
            lastAccessory = lastAccessory
        )
    }

    /** Free Lift: restore exactly what the lifter last used on this exercise in Free Lift (never adjusted). */
    private suspend fun resolveFreeLiftSettings(userId: String?, exercise: Exercise, fallbackWeightLb: Double?): ExerciseSettings {
        awaitPendingRemember()
        val pref = userId?.let { uid -> runCatching { localEngine.getExercisePref(uid, exercise.id) }.getOrNull() }
        return ExerciseSettingsPolicy.forFreeLift(exercise, pref, fallbackWeightLb, _telemetryFlow.value.profileMode)
    }

    /**
     * Loads settings onto the motor. Nothing is changed while engaged: it is held and applied the moment the
     * weight is released, and kept until it was actually sent. Returns true when the weight was deferred
     * that way. [generation] (from [selectionGeneration]) drops the call if the selection changed since.
     */
    private fun applyExerciseSettings(exerciseId: String, settings: ExerciseSettings, generation: Long? = null): Boolean {
        if (_uiStateFlow.value.authRequired) return false
        if (generation != null && generation != selectionGeneration.get()) return false
        val target = HeldWeightTracker.Held(
            settings.weightLb, settings.mode, settings.modifierPct, settings.accessory,
            exerciseId = exerciseId,
            rememberedAccessory = settings.accessoryRemembered && settings.accessory != null
        )
        if (target.isEmpty) return false
        if (isWeightCurrentlyEngaged()) {
            // Never change weight, mode or attachment mid-lift (the BAR attachment drives the bar-tilt cutout)
            heldWeight.hold(target)
            return settings.weightLb != null
        }
        heldWeight.clear()
        if (!applyHeldSettings(target, fromHeld = false)) {
            // Engaged in the meantime, or the motor couldn't be reached: load it on a later frame
            heldWeight.hold(target)
            return settings.weightLb != null && isWeightCurrentlyEngaged()
        }
        return false
    }

    /**
     * Sends target settings to the motor; false if the weight is (still) engaged or nothing could be sent.
     * A mode/modifier without a weight (e.g. the Free Lift chains/eccentric reset) is sent at the current
     * weight.
     */
    private fun applyHeldSettings(target: HeldWeightTracker.Held, fromHeld: Boolean): Boolean {
        if (isWeightCurrentlyEngaged()) return false
        // The attachment prompt follows what is actually loaded, not what is still waiting for release
        target.exerciseId?.let { exId ->
            _uiStateFlow.update {
                val map = if (target.rememberedAccessory && target.accessory != null) {
                    it.rememberedAccessoryByExercise + (exId to target.accessory)
                } else {
                    it.rememberedAccessoryByExercise - exId
                }
                it.copy(rememberedAccessoryByExercise = map)
            }
        }
        var ok = true
        target.accessory?.let { acc ->
            synchronized(accessoryLock) {
                // A held attachment the lifter replaced with their own pick meanwhile is not sent
                val stillWanted = !fromHeld || heldWeight.currentHeld === target
                if (stillWanted && !sendAccessory(acc)) ok = false
            }
        }
        if (target.weightLb != null || target.mode != null || target.modifierPct != null) {
            if (isWeightCurrentlyEngaged()) return false
            val weightLb = target.weightLb ?: _telemetryFlow.value.targetWeightLb
            if (!sendWeight(weightLb, target.mode, target.modifierPct)) ok = false
        }
        return ok
    }

    /** Loads held settings once a frame shows the weight off; kept for later frames until they were sent. */
    private fun applyHeldWeightIfReleased(weightEnabled: Boolean) {
        val held = heldWeight.heldToApply(weightEnabled) ?: return
        val now = System.currentTimeMillis()
        if (now - lastHeldApplyAttemptMs < HELD_RETRY_MS) return
        lastHeldApplyAttemptMs = now
        if (applyHeldSettings(held, fromHeld = true)) {
            heldWeight.confirmApplied(held)
            lastHeldApplyAttemptMs = 0L
        }
    }

    /** The weight the motor will actually take for [settings] (dynamic modes can lower the ceiling). */
    private fun loadedWeightLb(settings: ExerciseSettings): Double? {
        val w = settings.weightLb ?: return null
        val current = _telemetryFlow.value
        val mode = settings.mode ?: current.profileMode
        return Units.clampWeightForDynamicMode(w, mode, Units.effectiveModifierPct(mode, settings.modifierPct ?: current.modifierPct))
    }

    private fun showMessage(text: String?) {
        if (!text.isNullOrBlank()) _effectsFlow.tryEmit(KioskEffect.ShowMessage(text))
    }

    private val launchSettingsRestored = AtomicBoolean(false)

    /** Set once bootstrap finished and the kiosk is unlocked, so launch restore never races the auth check. */
    @Volatile
    private var launchRestoreReady = false

    /**
     * On app launch (once a motor frame confirms the weight is off and the kiosk is unlocked), reopen Free
     * Lift on the exercise the active lifter used last and restore its remembered Free Lift settings.
     * Skipped once the lifter picks an exercise, starts a routine, or switches profile themselves.
     */
    private suspend fun maybeRestoreSettingsOnLaunch() {
        val ui = _uiStateFlow.value
        if (!launchRestoreReady || launchSettingsRestored.get() || ui.authRequired) return
        if (ui.routineSteps.isNotEmpty()) return
        // Before the first real frame the default frame says "weight off" whatever the motor is doing
        if (!motorFrameSeen || isWeightCurrentlyEngaged()) return
        if (!localDaemon.connected.value && !canSendMotorViaServer()) return
        val uid = ui.activeUser?.id ?: return
        if (!launchSettingsRestored.compareAndSet(false, true)) return
        val gen = selectionGeneration.incrementAndGet()
        val latest = runCatching { localEngine.getLatestExercisePref(uid) }.getOrNull()
        val exercise = latest?.let { p -> ui.exercises.firstOrNull { it.id == p.exerciseId } }
            ?: ui.activeExercise
            ?: return
        if (gen != selectionGeneration.get()) return
        _uiStateFlow.update { it.copy(activeExerciseId = exercise.id) }
        val settings = resolveFreeLiftSettings(uid, exercise, fallbackWeightLb = null)
        if (settings.remembered) applyExerciseSettings(exercise.id, settings, gen)
        refreshOverload(uid, exercise.id)
    }

    /**
     * Remembers a finished set's settings (adjusted weight on AUTO steps with Auto-Adjust ON). Returns the
     * weight change, or null if nothing changed or the save didn't complete.
     */
    private suspend fun rememberFinishedSet(lifter: User, set: HeldWeightTracker.FinishedSet): WeightChange? {
        val autoAdjust = autoAdjustFor(lifter.id)
        val outcome = ExerciseSettingsPolicy.afterSet(
            userId = lifter.id,
            exerciseId = set.exerciseId,
            weightLb = set.weightLb,
            mode = set.mode,
            modifierPct = set.modifierPct,
            accessory = set.accessory,
            reps = set.reps,
            routineTargetReps = set.routineTargetReps,
            autoAdjustOn = autoAdjust,
            unit = lifter.weightUnit,
            safetyCutout = set.safetyCutout || set.appReleased,
            autoStep = set.autoStep
        ) ?: return null
        val saved = runCatching { localEngine.saveExercisePref(outcome.pref) }.isSuccess
        return if (saved) outcome.change else null
    }

    /** Saving the last released set; loading settings waits for it so Next right after release sees it. */
    @Volatile
    private var rememberJob: Deferred<HeldWeightTracker.FinishedSet?>? = null

    private suspend fun awaitPendingRemember() {
        rememberJob?.join()
    }

    /** True when the next set loads this same remembered weight (next step is this exercise's AUTO step). */
    private fun nextSetUsesSameWeight(ui: KioskUiState, exerciseId: String, routineTargetReps: Int?): Boolean {
        if (ui.routineSteps.isEmpty()) return true
        val next = ui.nextStep ?: return false
        return next.exerciseId == exerciseId && next.autoWeight && next.targetReps == routineTargetReps
    }

    /**
     * The weight was released with reps: the set is finished. Remember exactly what was lifted, for whoever
     * engaged the weight, before any held settings load. Doesn't log the set or move the routine on; when
     * [autoLogStepKey] is set it auto-logs this captured set once the cutout grace period is over.
     */
    private fun onSetFinished(frame: TelemetryFrame, autoLogStepKey: String?, autoLogReps: Int?): Boolean {
        val ui = _uiStateFlow.value
        // The set belongs to the exercise / rep goal on screen when the weight was engaged: switching
        // exercise or step disengages *after* the switch, so the current screen may already be the next one.
        val ctx = heldWeight.currentContext
        // Logged mid-lift already: the weight in hand belonged to that step, not to the one now on screen
        if (ctx?.carriedOver == true) return true
        val exercise = ctx?.exerciseId?.let { id -> ui.exercises.firstOrNull { it.id == id } }
            ?: ui.activeExercise
            ?: return false
        val step = ui.currentStep?.takeIf { it.exerciseId == exercise.id }
        val routineTargetReps = if (ctx?.exerciseId != null) ctx.routineTargetReps else step?.targetReps
        val autoStep = if (ctx?.exerciseId != null) ctx.autoStep else step?.autoWeight == true
        val lifterId = heldWeight.lifterForSet(ui.activeUser?.id) ?: return false
        val lifter = ui.users.firstOrNull { it.id == lifterId } ?: return false
        val set = HeldWeightTracker.FinishedSet(
            lifterId = lifterId,
            exerciseId = exercise.id,
            weightLb = frame.targetWeightLb,
            mode = frame.profileMode,
            modifierPct = frame.modifierPct,
            // What the motor reports as active (what the daemon's bar-tilt check uses), not what was last sent
            accessory = frame.accessory,
            reps = frame.repCount,
            routineTargetReps = routineTargetReps,
            appReleased = heldWeight.takeAppRelease(),
            autoStep = autoStep,
            releaseId = heldWeight.nextReleaseId()
        )
        heldWeight.onReleased(set)
        val setEngagedAtMs = engagedAtMs
        val job = scope.async {
            // A safety cutout disengages the motor and reports why; give that report a moment to arrive. The
            // verdict belongs to this set: a cutout reported after it was engaged (up to the end of the grace).
            delay(SAFETY_EVENT_GRACE_MS)
            val cutout = setEngagedAtMs > 0L && lastSafetyEventAtMs >= setEngagedAtMs
            val finalSet = set.copy(safetyCutout = cutout)
            heldWeight.replaceFinished(set, finalSet)
            val change = rememberFinishedSet(lifter, finalSet)
            // An auto-logged set shows its message when it's logged (with what loads next)
            if (change != null && autoLogStepKey == null) {
                val nowUi = _uiStateFlow.value
                showMessage(
                    ExerciseSettingsPolicy.changeMessage(
                        change, lifter.weightUnit, exercise.name,
                        exercise.id == nowUi.activeExercise?.id && nextSetUsesSameWeight(nowUi, exercise.id, routineTargetReps)
                    )
                )
            }
            finalSet
        }
        rememberJob = job
        if (autoLogStepKey != null && autoLogReps != null) {
            scope.launch {
                val finalSet = job.await()
                // Skip if the set was logged by hand (Complete Set) or the step changed during the wait
                val nowUi = _uiStateFlow.value
                val keyNow = "${nowUi.sessionState.activeSessionId}:${nowUi.currentStepIdx}:${nowUi.currentStep?.exerciseId}"
                if (keyNow == autoLogStepKey && !heldWeight.isLogged(finalSet.releaseId)) {
                    logCompletedSetInternal(autoLogReps, autoLogged = true, captured = finalSet)
                }
            }
        }
        return true
    }

    /** Per-lifter Auto-Adjust: the lifter's latest toggle if it is still being saved, else the stored value. */
    private suspend fun autoAdjustFor(userId: String): Boolean =
        autoAdjustDesired[userId] ?: runCatching { localEngine.getAutoAdjustWeights(userId) }.getOrDefault(false)

    /** Latest Auto-Adjust toggle per lifter, written in order by [autoAdjustWriteMutex]. */
    private val autoAdjustDesired = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val autoAdjustWriteMutex = Mutex()

    private val started = AtomicBoolean(false)
    private val logSetInFlight = AtomicBoolean(false)
    private val lastEnableTapMs = AtomicLong(0L)
    private val autoSelectedLibrespot = AtomicBoolean(false)

    @Volatile
    private var serverWs: WebSocket? = null
    @Volatile
    private var lastServerFrameAtMs: Long = 0L
    @Volatile
    private var lastServerTelemetryAtMs: Long = 0L
    @Volatile
    private var wsReconnectJob: Job? = null
    @Volatile
    private var wsPingJob: Job? = null
    @Volatile
    private var restTimerJob: Job? = null
    @Volatile
    private var musicPollJob: Job? = null
    @Volatile
    private var prBannerJob: Job? = null
    @Volatile
    private var prevRestCountdown: Int = 0

    private val managedJobs = mutableListOf<Job>()

    fun start() {
        if (!started.compareAndSet(false, true)) return

        localDaemon.start()

        synchronized(managedJobs) {
            managedJobs += scope.launch {
                localDaemon.connected.collect { connected ->
                    _uiStateFlow.update {
                        it.copy(
                            localFailSafeConnected = connected,
                            daemonConnected = if (it.syncMode == SyncMode.STANDALONE) connected else it.daemonConnected
                        )
                    }
                    if (connected) {
                        val health = localDaemon.fetchDirectHealth()
                        if (health != null) {
                            _uiStateFlow.update { it.copy(isMockMode = health.mockMode) }
                        }
                        maybeRestoreSettingsOnLaunch()
                    }
                }
            }

            managedJobs += scope.launch {
                localDaemon.localTelemetry.collect { frame ->
                    emitRawLocalBroadcast(
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
                                put("left_cable", SwonalJson.encodeToJsonElement(CableData.serializer(), frame.leftCable))
                                put("right_cable", SwonalJson.encodeToJsonElement(CableData.serializer(), frame.rightCable))
                                put("arm_positions", SwonalJson.encodeToJsonElement(ArmPositions.serializer(), frame.armPositions))
                            }
                        )
                    )
                    val ui = _uiStateFlow.value
                    val serverStalled = (System.currentTimeMillis() - lastServerTelemetryAtMs) > 500L
                    if (ui.syncMode == SyncMode.STANDALONE || !ui.wsConnected || !ui.daemonConnected || serverStalled) {
                        handleTelemetryFrame(frame, TelemetrySource.LOCAL)
                    }
                    cloudSyncManager.forwardTelemetry(frame)
                }
            }

            managedJobs += scope.launch {
                localDaemon.localSafety.collect { event ->
                    handleSafetyEvent(event)
                    emitLocalBroadcast("safety_event") {
                        put("reason", event.reason)
                        event.delta?.let { put("delta", it) }
                        put("timestamp", event.timestamp)
                    }
                }
            }

            managedJobs += scope.launch {
                localDaemon.localHandleEvents.collect { ev ->
                    emitLocalBroadcast("handle_event") {
                        put("action", ev.action)
                        put("source", ev.source)
                    }
                    when (ev.action) {
                        "spotify_next" -> refreshMusicNow()
                        "emergency_stop" -> disableWeight()
                    }
                }
            }

            managedJobs += scope.launch {
                localDaemon.localErrors.collect { errMsg ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(errMsg))
                }
            }

            managedJobs += scope.launch {
                // Per-lifter preferences follow the active lifter however it changes (profile switch,
                // Work-In rotation, Companion, server sync)
                _uiStateFlow.map { it.activeUser?.id }.distinctUntilChanged().collect { uid ->
                    if (uid != null) {
                        val enabled = autoAdjustFor(uid)
                        if (_uiStateFlow.value.activeUser?.id == uid) {
                            _uiStateFlow.update { it.copy(autoAdjustWeights = enabled) }
                        }
                    }
                }
            }

            managedJobs += scope.launch {
                localEngine.sessionStateFlow.collect { sState ->
                    _uiStateFlow.update { it.copy(sessionState = sState) }
                    emitRawLocalBroadcast(
                        SwonalJson.encodeToString(
                            buildJsonObject {
                                put("type", "session_state")
                                put("state", SwonalJson.encodeToJsonElement(SharedSessionState.serializer(), sState))
                            }
                        )
                    )
                }
            }
        }

        startRestTimerLoop()
        startMusicPollLoop()
        scope.launch { bootstrapAndConnect() }
    }

    fun stop() {
        started.set(false)
        synchronized(managedJobs) {
            managedJobs.forEach { it.cancel() }
            managedJobs.clear()
        }
        wsReconnectJob?.cancel()
        wsPingJob?.cancel()
        restTimerJob?.cancel()
        musicPollJob?.cancel()
        prBannerJob?.cancel()
        wsReconnectJob = null
        wsPingJob = null
        restTimerJob = null
        musicPollJob = null
        prBannerJob = null
        serverWs?.close(1000, "App stopping")
        serverWs = null
        cloudSyncManager.stop()
        localDaemon.stop()
        localDb.close()
    }

    @Volatile
    private var localDbInitialized = false

    fun updateSyncConfiguration(
        newSyncMode: SyncMode,
        newSyncServerUrl: String,
        newLocalDaemonUrl: String,
        newApiToken: String? = null,
        clearToken: Boolean = false
    ) {
        val oldSyncUrl = prefs.syncServerUrl
        val normalizedSync = newSyncServerUrl.trim().let {
            if (it.isEmpty()) "" else SwonalPreferences.normalizeBaseUrl(it, "")
        }
        val originChanged = !SwonalPreferences.sameOrigin(oldSyncUrl, normalizedSync)

        if (originChanged) {
            prefs.clearCredentials()
            api.clearCookies()
            prefs.lastKnownAuthEnabled = false
        }

        prefs.syncMode = newSyncMode
        prefs.syncServerUrl = normalizedSync
        prefs.localDaemonUrl = SwonalPreferences.normalizeLoopbackDaemonUrl(newLocalDaemonUrl)

        if (clearToken) {
            prefs.apiToken = null
        } else if (!newApiToken.isNullOrBlank()) {
            prefs.apiToken = newApiToken.trim()
        }

        _uiStateFlow.update {
            it.copy(
                syncMode = prefs.syncMode,
                syncServerUrl = prefs.syncServerUrl,
                serverUrl = prefs.serverUrl,
                localDaemonUrl = prefs.localDaemonUrl,
                wsConnected = false,
                syncConnected = false,
                authRequired = false,
                authError = null,
                syncError = null
            )
        }
        serverWs?.cancel()
        serverWs = null
        wsReconnectJob?.cancel()
        localDaemon.reconnectNow()
        scope.launch { bootstrapAndConnect() }
    }

    fun updateConnectionEndpoints(
        newServerUrl: String,
        newLocalDaemonUrl: String,
        newApiToken: String? = null,
        clearToken: Boolean = false
    ) {
        val trimmed = newServerUrl.trim()
        val parsed = runCatching {
            SwonalPreferences.normalizeBaseUrl(trimmed, "")
        }.getOrDefault("")
        val useServerSync = parsed.isNotBlank() && !SwonalPreferences.isLoopbackHost(
            parsed.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
        )
        updateSyncConfiguration(
            newSyncMode = if (useServerSync) SyncMode.SERVER_SYNC else SyncMode.STANDALONE,
            newSyncServerUrl = if (useServerSync) parsed else "",
            newLocalDaemonUrl = newLocalDaemonUrl,
            newApiToken = newApiToken,
            clearToken = clearToken
        )
    }

    private suspend fun ensureLocalDatabaseReady() {
        if (localDbInitialized) return
        val seedJson = seedCatalogJson ?: "{}"
        localEngine.initialize(seedJson, includeDemoUsersAndRoutines = true)
        localDbInitialized = true
    }

    suspend fun bootstrapAndConnect() {
        _uiStateFlow.update { it.copy(authChecking = true) }

        // 1. Initialize & hydrate from local on-device SQLite immediately (<30ms, works 100% offline)
        runCatching { ensureLocalDatabaseReady() }
        val localAuth = runCatching {
            localEngine.getAuthStatus(isLoopbackClient = true, isSessionAuthenticated = false)
        }.getOrDefault(AuthStatus(enabled = false, authenticated = true))

        refreshAppState()
        refreshDaemonHealth()

        if (localAuth.enabled && !localAuth.authenticated) {
            _uiStateFlow.update { it.copy(authChecking = false, authRequired = true, authError = null) }
            return
        }

        _uiStateFlow.update {
            it.copy(
                authChecking = false,
                authRequired = false,
                authError = null,
                syncMode = prefs.syncMode,
                syncServerUrl = prefs.syncServerUrl,
                serverUrl = prefs.serverUrl
            )
        }

        refreshAuxiliaryState()
        launchRestoreReady = true
        maybeRestoreSettingsOnLaunch()

        // 2. Start or stop optional background Home / Cloud Server sync without blocking offline lifting
        cloudSyncManager.startOrReconfigure()
        val externalSyncConfigured = prefs.syncMode == SyncMode.SERVER_SYNC && prefs.syncServerUrl.isNotBlank()
        if (externalSyncConfigured && hasApiClientOverride) {
            startServerWsLoop()
        }
    }

    fun submitPasscode(passcode: String) {
        scope.launch {
            _uiStateFlow.update { it.copy(authChecking = true, authError = null) }
            val localAuth = localEngine.getAuthStatus(isLoopbackClient = false, isSessionAuthenticated = false)
            if (localAuth.enabled) {
                localEngine.verifyPasscode(passcode, "kiosk")
                    .onSuccess { ok ->
                        if (ok) {
                            _uiStateFlow.update { it.copy(authChecking = false, authRequired = false, authError = null) }
                            refreshAppState()
                            launchRestoreReady = true
                            maybeRestoreSettingsOnLaunch()
                        } else {
                            _uiStateFlow.update {
                                it.copy(authChecking = false, authRequired = true, authError = "Invalid passcode")
                            }
                        }
                    }
                    .onFailure { err ->
                        _uiStateFlow.update {
                            it.copy(authChecking = false, authRequired = true, authError = err.message ?: "Too many attempts")
                        }
                    }
                return@launch
            }

            // Fallback to remote server auth if synced server requested passcode unlock
            runCatching { api.login(passcode) }
                .onSuccess {
                    _uiStateFlow.update { it.copy(authChecking = false, authRequired = false, authError = null) }
                    bootstrapAndConnect()
                }
                .onFailure { err ->
                    _uiStateFlow.update {
                        it.copy(
                            authChecking = false,
                            authRequired = true,
                            authError = err.message ?: "Invalid passcode"
                        )
                    }
                }
        }
    }

    suspend fun refreshAppState() {
        runCatching {
            ensureLocalDatabaseReady()
            val daemonOk = localDaemon.connected.value || _uiStateFlow.value.daemonConnected
            val state = localEngine.getAppState(daemonConnected = daemonOk)
            val current = _uiStateFlow.value
            val nextExId = current.activeExerciseId
                ?.takeIf { id -> state.exercises.any { it.id == id } }
                ?: state.sessionState.activeExerciseId
                ?: state.exercises.firstOrNull()?.id
            val dirty = runCatching { localDb.countDirtySyncRows() }.getOrDefault(0)
            _uiStateFlow.update {
                it.copy(
                    daemonConnected = daemonOk,
                    users = state.users,
                    exercises = state.exercises,
                    templates = state.templates,
                    sessionState = state.sessionState,
                    activeExerciseId = nextExId,
                    pendingSyncCount = dirty,
                    authRequired = false
                )
            }
            val activeUid = _uiStateFlow.value.activeUser?.id
            if (activeUid != null) {
                refreshUserScopedData(activeUid)
            }
            runCatching {
                if (state.users.isNotEmpty()) {
                    prefs.cachedUsersJson = SwonalJson.encodeToString(state.users)
                }
                if (state.exercises.isNotEmpty()) {
                    prefs.cachedExercisesJson = SwonalJson.encodeToString(state.exercises)
                }
            }
        }
    }

    private suspend fun refreshDaemonHealth() {
        val health = localDaemon.fetchDirectHealth()
            ?: runCatching {
                if (prefs.syncMode == SyncMode.SERVER_SYNC && prefs.syncServerUrl.isNotBlank()) api.getDaemonHealth() else null
            }.getOrNull()
        if (health != null) {
            _uiStateFlow.update { it.copy(isMockMode = health.mockMode) }
        }
    }

    private fun hasRemoteSync(): Boolean =
        prefs.syncMode == SyncMode.SERVER_SYNC && prefs.syncServerUrl.isNotBlank()

    private suspend fun fetchMusicStateForProvider(provider: String, userId: String): MusicPlaybackState {
        if (provider == "spotify") {
            return localSpotify.getPlaybackState(userId)
        }
        val lanState = localLanMusic.controlAction(provider, "state")
        if (lanState.isConnected) return lanState
        if (hasRemoteSync()) {
            return runCatching { api.getMusicState(provider, userId) }.getOrDefault(lanState)
        }
        return lanState
    }

    private suspend fun refreshAuxiliaryState() {
        val spotifyProvider = localSpotify.getProviderInfo()
        val lanSecondary = localLanMusic.getSecondaryProviders()
        var mergedProviders = listOf(spotifyProvider) + lanSecondary

        if (hasRemoteSync()) {
            runCatching { api.getMusicProviders() }.onSuccess { remoteProviders ->
                val remoteById = remoteProviders.associateBy { it.id }
                mergedProviders = mergedProviders.map { localInfo ->
                    val r = remoteById[localInfo.id]
                    if (r != null) {
                        localInfo.copy(
                            configured = localInfo.configured || r.configured,
                            capabilities = MusicProviderCapabilities(
                                supportsConnectDevices = localInfo.capabilities.supportsConnectDevices || r.capabilities.supportsConnectDevices,
                                supportsVolumeDucking = localInfo.capabilities.supportsVolumeDucking || r.capabilities.supportsVolumeDucking,
                                supportsPlaylists = localInfo.capabilities.supportsPlaylists || r.capabilities.supportsPlaylists
                            )
                        )
                    } else {
                        localInfo
                    }
                }
            }
            runCatching { api.getNetworkInfo() }.onSuccess { info ->
                _uiStateFlow.update { it.copy(networkInfo = info) }
            }
        }
        _uiStateFlow.update { it.copy(musicProviders = mergedProviders) }

        runCatching { localAiCoach.getPublicConfig() }.onSuccess { cfg ->
            _uiStateFlow.update { it.copy(coachConfig = cfg) }
        }
        runCatching { localHaClient.getStatus() }.onSuccess { ha ->
            _uiStateFlow.update { it.copy(haStatus = ha) }
        }
        runCatching { getEffectiveNetworkInfo() }

        val uid = _uiStateFlow.value.activeUser?.id
        if (uid != null) {
            refreshMusicAll(uid)
        }
    }

    private suspend fun refreshUserScopedData(userId: String) {
        val autoAdjust = autoAdjustFor(userId)
        if (_uiStateFlow.value.activeUser?.id == userId) {
            _uiStateFlow.update { it.copy(autoAdjustWeights = autoAdjust) }
        }
        runCatching { localEngine.getPrs(userId) }.onSuccess { prs ->
            if (_uiStateFlow.value.activeUser?.id == userId) {
                _uiStateFlow.update { it.copy(prs = prs) }
            }
        }
        runCatching { localEngine.getDemoPip(userId) }.onSuccess { enabled ->
            if (_uiStateFlow.value.activeUser?.id == userId) {
                _uiStateFlow.update { it.copy(showDemoPip = enabled) }
            }
        }
        runCatching { localEngine.listSessions(userId) }.onSuccess { sessions ->
            if (_uiStateFlow.value.activeUser?.id == userId) {
                _uiStateFlow.update { it.copy(recentSessions = sessions) }
            }
        }
        val exId = _uiStateFlow.value.activeExercise?.id
        if (exId != null) {
            refreshOverload(userId, exId)
        }
        refreshMusicAll(userId)
    }

    private suspend fun refreshOverload(userId: String, exerciseId: String) {
        runCatching { localEngine.getOverloadSuggestion(userId, exerciseId) }
            .onSuccess { sug ->
                if (_uiStateFlow.value.activeUser?.id == userId) {
                    _uiStateFlow.update { it.copy(overloadSuggestion = sug) }
                }
            }
            .onFailure {
                if (_uiStateFlow.value.activeUser?.id == userId) {
                    _uiStateFlow.update { it.copy(overloadSuggestion = null) }
                }
            }
    }

    fun refreshMusicNow() {
        scope.launch {
            val uid = _uiStateFlow.value.activeUser?.id ?: return@launch
            refreshMusicAll(uid)
        }
    }

    private suspend fun refreshMusicAll(userId: String) {
        val provider = _uiStateFlow.value.preferredMusicProvider
        runCatching { fetchMusicStateForProvider(provider, userId) }
            .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st, musicError = null) } }
            .onFailure { err ->
                if (err is ApiHttpException && err.statusCode == 401) {
                    _uiStateFlow.update { it.copy(musicState = it.musicState.copy(isConnected = false)) }
                }
            }

        if (provider == "spotify") {
            runCatching { localSpotify.getDevices(userId) }
                .onSuccess { devs ->
                    _uiStateFlow.update { it.copy(musicDevices = devs) }
                    val hasActive = devs.any { it.isActive } || !_uiStateFlow.value.musicState.activeDeviceId.isNullOrBlank()
                    val librespot = findLibrespotDevice(devs)
                    if (!hasActive && librespot != null && librespot.id != "tonal-gym-librespot" && autoSelectedLibrespot.compareAndSet(false, true)) {
                        runCatching { localSpotify.transferPlayback(userId, librespot.id) }
                            .onSuccess { st ->
                                _uiStateFlow.update {
                                    it.copy(
                                        musicState = st.copy(activeDeviceId = st.activeDeviceId ?: librespot.id),
                                        musicDevices = devs.map { d -> d.copy(isActive = d.id == librespot.id) }
                                    )
                                }
                            }
                    }
                }
            runCatching { localSpotify.getPlaylists(userId) }
                .onSuccess { pls -> _uiStateFlow.update { it.copy(musicPlaylists = pls) } }
        } else if (hasRemoteSync()) {
            runCatching { api.getMusicDevices(provider, userId) }
                .onSuccess { devs -> _uiStateFlow.update { it.copy(musicDevices = devs) } }
            runCatching { api.getMusicPlaylists(provider, userId) }
                .onSuccess { pls -> _uiStateFlow.update { it.copy(musicPlaylists = pls) } }
        } else {
            _uiStateFlow.update { it.copy(musicDevices = emptyList(), musicPlaylists = emptyList()) }
        }

        runCatching { localSpotify.getFavoritePlaylists(userId) }
            .onSuccess { favs -> _uiStateFlow.update { it.copy(favoritePlaylistIds = favs.toSet()) } }
    }

    fun refreshDiagnostics() {
        scope.launch {
            refreshDaemonHealth()
            val cal = localDaemon.getCalibrationDirect()
                ?: runCatching {
                    if (hasRemoteSync()) api.getCalibration() else null
                }.getOrNull()
            if (cal != null) {
                _uiStateFlow.update { it.copy(calibrationStatus = cal) }
            }
            runCatching { localHaClient.getStatus() }
                .onSuccess { ha -> _uiStateFlow.update { it.copy(haStatus = ha) } }
            runCatching { localAiCoach.getPublicConfig() }
                .onSuccess { cfg -> _uiStateFlow.update { it.copy(coachConfig = cfg) } }
            if (hasRemoteSync()) {
                runCatching { api.getHomeAssistantStatus() }
                    .onSuccess { remoteHa ->
                        _uiStateFlow.update { cur ->
                            val localHa = cur.haStatus
                            cur.copy(
                                haStatus = remoteHa.copy(
                                    webhookConfigured = remoteHa.webhookConfigured || (localHa?.webhookConfigured == true),
                                    recentEvents = (remoteHa.recentEvents + (localHa?.recentEvents.orEmpty())).take(15)
                                )
                            )
                        }
                    }
                runCatching { api.getNetworkInfo() }
                    .onSuccess { net -> _uiStateFlow.update { it.copy(networkInfo = net) } }
            }
            val uid = _uiStateFlow.value.activeUser?.id
            if (uid != null) {
                runCatching { localEngine.listSessions(uid) }
                    .onSuccess { sessions -> _uiStateFlow.update { it.copy(recentSessions = sessions) } }
            }
        }
    }

    // --- WebSocket Connection to server-app ---

    private fun startServerWsLoop() {
        wsReconnectJob?.cancel()
        wsReconnectJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 1000L
            while (isActive && started.get()) {
                if (!_uiStateFlow.value.wsConnected && serverWs == null && !_uiStateFlow.value.authRequired) {
                    openServerSocket()
                    backoffMs = (backoffMs * 2).coerceAtMost(8000L)
                } else {
                    backoffMs = 1000L
                }
                delay(backoffMs)
            }
        }
    }

    private fun openServerSocket() {
        val wsUrl = SwonalPreferences.toWebSocketUrl(prefs.serverUrl)
        val reqBuilder = Request.Builder().url(wsUrl)
        api.buildAuthHeaders(reqBuilder, prefs.serverUrl)
        serverWs = api.okHttpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                lastServerFrameAtMs = System.currentTimeMillis()
                _uiStateFlow.update { it.copy(wsConnected = true) }
                startServerPingLoop()
                scope.launch {
                    refreshAppState()
                    refreshDaemonHealth()
                    maybeRestoreSettingsOnLaunch()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                lastServerFrameAtMs = System.currentTimeMillis()
                val msg = WsMessageParser.parse(text) ?: return
                handleServerWsMessage(msg)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (serverWs === ws) {
                    serverWs = null
                    wsPingJob?.cancel()
                    if (code == 4401) prefs.lastKnownAuthEnabled = true
                    _uiStateFlow.update {
                        it.copy(
                            wsConnected = false,
                            authRequired = if (code == 4401) true else it.authRequired
                        )
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("SwonalRepo", "WebSocket failure on ${prefs.serverUrl}: ${t.javaClass.name}: ${t.message}", t)
                if (serverWs === ws) {
                    serverWs = null
                    wsPingJob?.cancel()
                    _uiStateFlow.update { it.copy(wsConnected = false) }
                }
            }
        })
    }

    private fun startServerPingLoop() {
        wsPingJob?.cancel()
        wsPingJob = scope.launch(Dispatchers.IO) {
            while (isActive && _uiStateFlow.value.wsConnected) {
                val now = System.currentTimeMillis()
                // Cancel half-open server socket within 2.5s so local fail-safe takes over immediately
                if (lastServerFrameAtMs > 0L && now - lastServerFrameAtMs > 2500L) {
                    serverWs?.cancel()
                    serverWs = null
                    _uiStateFlow.update { it.copy(wsConnected = false) }
                    break
                }
                if (isUiAliveForWatchdog()) {
                    sendWs(buildJsonObject { put("type", "ping") })
                }
                delay(1500L)
            }
        }
    }

    private fun sendWs(payload: JsonObject): Boolean {
        if (!_uiStateFlow.value.wsConnected) return false
        val ws = serverWs ?: return false
        return ws.send(SwonalJson.encodeToString(payload))
    }

    private fun canSendMotorViaServer(): Boolean =
        _uiStateFlow.value.wsConnected && _uiStateFlow.value.daemonConnected && serverWs != null

    private fun handleServerWsMessage(msg: WsInboundMessage) {
        when (msg) {
            WsInboundMessage.Pong -> Unit
            is WsInboundMessage.DaemonStatus -> {
                _uiStateFlow.update { it.copy(daemonConnected = msg.connected) }
                if (msg.connected) {
                    scope.launch { refreshDaemonHealth() }
                }
            }
            is WsInboundMessage.Telemetry -> {
                lastServerTelemetryAtMs = System.currentTimeMillis()
                handleTelemetryFrame(msg.frame, TelemetrySource.SERVER)
            }
            is WsInboundMessage.SessionStateUpdate -> {
                val prevUser = _uiStateFlow.value.sessionState.activeUserId
                _uiStateFlow.update { st ->
                    val syncedEx = if (st.routineSteps.isEmpty() && !msg.state.activeExerciseId.isNullOrBlank()) {
                        msg.state.activeExerciseId
                    } else {
                        st.activeExerciseId
                    }
                    st.copy(sessionState = msg.state, activeExerciseId = syncedEx)
                }
                if (msg.state.activeUserId.isNotBlank() && msg.state.activeUserId != prevUser) {
                    scope.launch { refreshUserScopedData(msg.state.activeUserId) }
                }
            }
            is WsInboundMessage.WorkoutQueued -> {
                _uiStateFlow.update {
                    it.copy(sessionState = it.sessionState.copy(queuedWorkout = msg.workout))
                }
            }
            is WsInboundMessage.Safety -> handleSafetyEvent(msg.event)
            is WsInboundMessage.Handle -> {
                if (msg.event.action == "spotify_next") {
                    refreshMusicNow()
                }
            }
            is WsInboundMessage.ServerError -> {
                _effectsFlow.tryEmit(KioskEffect.ShowMessage(msg.message))
            }
            is WsInboundMessage.SetLogged -> {
                val uid = _uiStateFlow.value.activeUser?.id
                if (uid != null) {
                    scope.launch {
                        runCatching { api.getPrs(uid) }.onSuccess { prs ->
                            if (_uiStateFlow.value.activeUser?.id == uid) {
                                _uiStateFlow.update { it.copy(prs = prs) }
                            }
                        }
                    }
                }
            }
            is WsInboundMessage.PartnerRotated -> {
                scope.launch { refreshAppState() }
            }
            is WsInboundMessage.MusicUpdated -> refreshMusicNow()
            WsInboundMessage.UsersUpdated,
            WsInboundMessage.TemplatesUpdated,
            WsInboundMessage.ExercisesUpdated -> {
                scope.launch { refreshAppState() }
            }
            is WsInboundMessage.UserUpdated -> {
                _uiStateFlow.update { state ->
                    val updated = state.users.map { if (it.id == msg.user.id) msg.user else it }
                    state.copy(users = updated)
                }
            }
            is WsInboundMessage.DemoPipUpdated -> {
                if (msg.userId == _uiStateFlow.value.activeUser?.id) {
                    _uiStateFlow.update { it.copy(showDemoPip = msg.enabled) }
                }
            }
            is WsInboundMessage.Unknown -> Unit
        }
    }

    private fun handleTelemetryFrame(frame: TelemetryFrame, source: TelemetrySource) {
        var shouldAutoLogReps: Int? = null
        var autoLogStepKey: String? = null
        var setFinished = false
        val firstFrame = !motorFrameSeen
        motorFrameSeen = true

        synchronized(telemetryLock) {
            val sourceSwitched = (activeTelemetrySource != TelemetrySource.NONE && activeTelemetrySource != source)
            activeTelemetrySource = source

            val wasEnabled = prevWeightEnabled
            val oldReps = prevRepCount

            _telemetryFlow.value = frame
            _vbtFlow.value = vbtTracker.update(frame)

            // Suppress edge-triggered cues & auto-log when transitioning between server WS & local fail-safe
            // so a stale cached frame on reconnect can never jump resistance mid-lift.
            if (!sourceSwitched) {
                if (!wasEnabled && frame.weightEnabled) {
                    val engagedUi = _uiStateFlow.value
                    val engagedExercise = engagedUi.activeExercise
                    val engagedStep = engagedUi.currentStep?.takeIf { it.exerciseId == engagedExercise?.id }
                    engagedAtMs = System.currentTimeMillis()
                    heldWeight.onEngaged(
                        engagedUi.activeUser?.id,
                        engagedExercise?.id,
                        engagedStep?.targetReps,
                        autoStep = engagedStep?.autoWeight == true
                    )
                    _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.WEIGHT_ENGAGE))
                } else if (wasEnabled && !frame.weightEnabled) {
                    _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.WEIGHT_DISENGAGE))
                    // Already logged mid-lift (Complete Set): the reps since belong to that logged step's
                    // weight, so they neither auto-log nor count as a set of the step now on screen
                    val carriedOver = heldWeight.currentContext?.carriedOver == true
                    val ui = _uiStateFlow.value
                    val step = ui.currentStep
                    val stepKey = "${ui.sessionState.activeSessionId}:${ui.currentStepIdx}:${step?.exerciseId}"
                    if (!carriedOver && step != null && frame.repCount >= step.targetReps && stepKey != lastAutoLoggedStepKey) {
                        lastAutoLoggedStepKey = stepKey
                        autoLogStepKey = stepKey
                        shouldAutoLogReps = frame.repCount
                    }
                    if (frame.repCount > 0) setFinished = true
                }
                if (frame.repCount > oldReps) {
                    _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.REP_COMPLETE))
                }
            } else if (wasEnabled && !frame.weightEnabled && frame.repCount > 0) {
                // The release arrived on the other telemetry source: no cue or auto-log (a cached frame could
                // be stale), but still capture the finished set so its result isn't lost
                setFinished = true
            }

            prevWeightEnabled = frame.weightEnabled
            prevRepCount = frame.repCount
        }

        val repsToLog = shouldAutoLogReps
        // Capture the finished set before any held settings load below (it auto-logs from that capture)
        val captured = setFinished && onSetFinished(frame, autoLogStepKey, repsToLog)
        if (repsToLog != null && !captured) {
            // Nothing to capture it from (exercise or lifter unknown): log from live values as before
            scope.launch { logCompletedSetInternal(repsToLog, autoLogged = true) }
        }

        if (firstFrame) scope.launch { maybeRestoreSettingsOnLaunch() }

        // Load any weight that was held back while engaged, on any frame showing the weight off (not just
        // the release edge, which is suppressed on telemetry source switches)
        applyHeldWeightIfReleased(frame.weightEnabled || isWeightCurrentlyEngaged())
    }

    private fun handleSafetyEvent(event: SafetyEvent) {
        lastSafetyEventAtMs = System.currentTimeMillis()
        _uiStateFlow.update { it.copy(safetyEvent = event) }
        _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.SAFETY_CUTOUT))
        duckMusicForCue(1800)
    }

    private fun startRestTimerLoop() {
        restTimerJob?.cancel()
        restTimerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val endsAt = _uiStateFlow.value.sessionState.restTimerEndsAt
                val remaining = if (endsAt != null) {
                    val diff = endsAt - System.currentTimeMillis()
                    if (diff > 0L) ceil(diff / 1000.0).toInt() else 0
                } else {
                    0
                }
                if (endsAt == null) {
                    prevRestCountdown = 0
                }
                if (remaining != _uiStateFlow.value.restSecondsRemaining) {
                    _uiStateFlow.update { it.copy(restSecondsRemaining = remaining) }
                    if (remaining in 1..3 && remaining < prevRestCountdown) {
                        _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.COUNTDOWN_TICK))
                    } else if (remaining == 0 && prevRestCountdown > 0 && endsAt != null) {
                        _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.COUNTDOWN_FINAL))
                        localEngine.setRestTimer(null)
                        sendWs(buildJsonObject { put("type", "skip_rest_timer") })
                    }
                    prevRestCountdown = remaining
                }
                delay(250L)
            }
        }
    }

    private fun startMusicPollLoop() {
        musicPollJob?.cancel()
        musicPollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
                val ui = _uiStateFlow.value
                val uid = ui.activeUser?.id
                if (uiActive.get() && !ui.authRequired && uid != null && ui.musicState.isPlaying) {
                    runCatching { fetchMusicStateForProvider(ui.preferredMusicProvider, uid) }
                        .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st) } }
                }
            }
        }
    }

    // --- Safety-Critical Motor Commands (with Phase 3 Local Fail-Safe Fast Path) ---

    fun isWeightCurrentlyEngaged(): Boolean {
        return _telemetryFlow.value.weightEnabled ||
            (localDaemon.lastLocalTelemetry.value?.weightEnabled == true)
    }

    /**
     * Explicit idempotent engage/disengage:
     * - If either server or local telemetry reports engaged -> ALWAYS disengage immediately (zero debounce)
     * - If idle -> requires unlocked auth, acknowledged safetyEvent, and 350ms enable debounce, then sends `enable_weight`
     */
    fun toggleWeight() {
        if (isWeightCurrentlyEngaged()) {
            disableWeight(byApp = false)
            return
        }

        val ui = _uiStateFlow.value
        if (ui.authRequired) {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Unlock Swonal before engaging weight"))
            return
        }
        if (ui.safetyEvent != null) {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Acknowledge safety cutout banner before re-engaging"))
            return
        }

        val now = System.currentTimeMillis()
        val prevTap = lastEnableTapMs.get()
        if (now - prevTap < 350L) return
        lastEnableTapMs.set(now)

        val localOk = if (localDaemon.connected.value) localDaemon.enableWeightDirect() else false
        if (!localOk) {
            val sent = if (canSendMotorViaServer()) {
                sendWs(buildJsonObject { put("type", "enable_weight") })
            } else {
                false
            }
            if (!sent) {
                _effectsFlow.tryEmit(KioskEffect.ShowMessage("Unable to reach local daemon or server"))
            }
        }
    }

    /** Always allowed (even while locked or backgrounded): disengages via local fail-safe + server WS in parallel. */
    /**
     * @param byApp true when Swonal itself turns the weight off (leaving the screen, switching exercise
     *   mid-lift, watchdog, dialogs); false for the lifter's own toggle. App releases are remembered but
     *   never auto-adjusted.
     */
    fun disableWeight(byApp: Boolean = true) {
        if (byApp && isWeightCurrentlyEngaged()) heldWeight.markAppRelease()
        scope.launch(Dispatchers.IO) {
            localDaemon.emergencyDisableWeight()
        }
        sendWs(buildJsonObject { put("type", "disable_weight") })
    }

    fun setWeightEnabled(enabled: Boolean) {
        if (!enabled) {
            disableWeight(byApp = false)
            return
        }
        if (!isUiAliveForWatchdog()) {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Cannot engage motor while Kiosk UI is inactive or locked"))
            return
        }
        if (!isWeightCurrentlyEngaged()) {
            toggleWeight()
        }
    }

    suspend fun disableWeightAndAwait(byApp: Boolean = true): Boolean {
        if (byApp && isWeightCurrentlyEngaged()) heldWeight.markAppRelease()
        sendWs(buildJsonObject { put("type", "disable_weight") })
        val localOk = localDaemon.emergencyDisableWeight()
        // Wait briefly for telemetry to confirm weight is off
        repeat(6) {
            if (!isWeightCurrentlyEngaged()) return true
            delay(50L)
        }
        return localOk || !isWeightCurrentlyEngaged()
    }

    /**
     * Sets weight / mode on the motor. Returns true only when the command was actually sent (local daemon or
     * server), so callers never treat an unreachable motor as "loaded".
     */
    fun setWeight(
        weightLb: Double,
        profileMode: String? = null,
        modifierPct: Int? = null,
        allowWhileEngaged: Boolean = true
    ): Boolean {
        if (_uiStateFlow.value.authRequired) return false
        val engaged = isWeightCurrentlyEngaged()
        if (!allowWhileEngaged && engaged) return false
        val sent = sendWeight(weightLb, profileMode, modifierPct)
        // A change made with the weight off wins over a weight still waiting to load. While engaged it only
        // adjusts the lift in hand (e.g. -5 on the bench after a mid-lift Complete Set), so the next step's
        // held weight still loads on release. A held attachment is never dropped by a weight change.
        if (sent && !engaged) heldWeight.clearHeldWeight()
        return sent
    }

    private fun sendWeight(weightLb: Double, profileMode: String?, modifierPct: Int?): Boolean {
        val current = _telemetryFlow.value
        val mode = profileMode ?: current.profileMode
        val rawMod = modifierPct ?: current.modifierPct
        val effMod = Units.effectiveModifierPct(mode, rawMod)
        val safeLb = Units.clampWeightForDynamicMode(weightLb, mode, effMod)

        val sentLocal = if (localDaemon.connected.value) localDaemon.setWeightDirect(safeLb, mode, effMod) else false
        val sent = sentLocal || (
            canSendMotorViaServer() && sendWs(
                buildJsonObject {
                    put("type", "set_weight")
                    put("weight_lb", safeLb)
                    put("profile", mode)
                    put("modifier_pct", effMod)
                }
            )
        )
        if (sent) {
            // Optimistic target update so rapid +/-5 taps accumulate without server RTT loss
            _telemetryFlow.update {
                it.copy(
                    targetWeightLb = safeLb,
                    profileMode = mode,
                    modifierPct = effMod
                )
            }
        }
        return sent
    }

    fun stepWeight(deltaDisplay: Double) {
        if (_uiStateFlow.value.authRequired) return
        val current = _telemetryFlow.value
        val unit = _uiStateFlow.value.activeUnit
        val nextLb = Units.stepWeightLb(current.targetWeightLb, deltaDisplay, unit)
        setWeight(nextLb, current.profileMode, current.modifierPct, allowWhileEngaged = true)
    }

    fun setDynamicMode(mode: String) {
        if (_uiStateFlow.value.authRequired) return
        val defaultPct = Units.dynamicModeDefaultPct(mode)
        setWeight(_telemetryFlow.value.targetWeightLb, mode, defaultPct, allowWhileEngaged = true)
    }

    /**
     * @param byLifter true for the lifter's own pick (attachment buttons, Companion): cancels a held attachment.
     * @return true when the command was actually sent.
     */
    fun setAccessory(accessory: String, byLifter: Boolean = true): Boolean {
        if (_uiStateFlow.value.authRequired) return false
        // Under the same lock as a held attachment being applied, so the lifter's pick always lands last
        synchronized(accessoryLock) {
            if (byLifter) heldWeight.clearHeldAccessory()
            return sendAccessory(accessory)
        }
    }

    private fun sendAccessory(accessory: String): Boolean {
        val sentLocal = if (localDaemon.connected.value) localDaemon.setAccessoryDirect(accessory) else false
        return sentLocal || (
            canSendMotorViaServer() && sendWs(
                buildJsonObject {
                    put("type", "set_accessory")
                    put("accessory", accessory)
                }
            )
        )
    }

    fun resetReps() {
        if (_uiStateFlow.value.authRequired) return
        val sentLocal = if (localDaemon.connected.value) localDaemon.resetRepsDirect() else false
        if (!sentLocal && canSendMotorViaServer()) {
            sendWs(buildJsonObject { put("type", "reset_reps") })
        }
    }

    fun syncMockArmsToActiveExercise() {
        val ui = _uiStateFlow.value
        if (ui.authRequired || !ui.isMockMode) return
        val ex = ui.activeExercise ?: return
        scope.launch {
            val ok = localDaemon.setMockArmPositionsDirect(ex)
            if (!ok && prefs.syncMode == SyncMode.SERVER_SYNC && prefs.syncServerUrl.isNotBlank()) {
                runCatching { api.syncMockArmPositions(ex) }
            }
        }
    }

    // --- User, Partner, Theme, Unit, Preferences ---

    fun switchUser(userId: String) {
        if (_uiStateFlow.value.authRequired) return
        // Tapping the profile that's already active must not undo the lifter's unlogged changes
        if (_uiStateFlow.value.sessionState.activeUserId == userId) return
        launchSettingsRestored.set(true)
        val gen = selectionGeneration.incrementAndGet()
        scope.launch {
            val nextState = localEngine.setActiveUser(userId)
            _uiStateFlow.update { it.copy(sessionState = nextState) }
            if (canSendMotorViaServer()) {
                sendWs(
                    buildJsonObject {
                        put("type", "set_active_user")
                        put("userId", userId)
                    }
                )
            }
            refreshUserScopedData(userId)
            val ui = _uiStateFlow.value
            val pr = ui.prs.firstOrNull { it.exerciseId == ui.activeExercise?.id }
            val suggested = if (pr != null && pr.maxWeightLb > 0) {
                ExerciseSettingsPolicy.prBasedStartLb(pr.maxWeightLb)
            } else null
            applySettingsForLifter(userId, freeLiftFallbackLb = suggested, workInPartner = false, generation = gen)
        }
    }

    /**
     * Loads [userId]'s settings for what's on screen (current routine step, else the Free Lift exercise),
     * holding them until release if the weight is on, with a message naming the lifter.
     */
    private suspend fun applySettingsForLifter(
        userId: String,
        freeLiftFallbackLb: Double?,
        workInPartner: Boolean,
        generation: Long
    ) {
        val ui = _uiStateFlow.value
        val exercise = ui.activeExercise
        val step = ui.currentStep
        val settings = when {
            step != null -> resolveRoutineStepSettings(userId, step, exercise, workInPartner)
            exercise != null -> resolveFreeLiftSettings(userId, exercise, freeLiftFallbackLb)
            else -> null
        } ?: return
        // Dropped if someone else became active, or the exercise / step changed, while it was looked up
        val now = _uiStateFlow.value
        if (now.activeUser?.id != userId || now.activeExercise?.id != exercise?.id || now.currentStepIdx != ui.currentStepIdx) return
        val deferred = applyExerciseSettings(step?.exerciseId ?: exercise!!.id, settings, generation)
        val shownLb = loadedWeightLb(settings)
        if (deferred && shownLb != null) {
            val lifter = ui.users.firstOrNull { it.id == userId }
            showMessage(ExerciseSettingsPolicy.deferredMessage(shownLb, lifter?.weightUnit ?: ui.activeUnit, lifter?.name))
        }
    }

    /**
     * A set was logged outside the kiosk's own flow (Companion / API). The release already remembered it;
     * start fresh for the next set and, in Work-In, load the next partner's remembered settings.
     */
    fun onExternalSetLogged(resp: SetLogResponse) {
        // Only the set it actually logged: a partner's phone log must not wipe the set on the kiosk
        heldWeight.onExternalSetLogged(resp.setLog.userId, resp.setLog.exerciseId)
        val rotation = resp.partnerRotation ?: return
        val gen = selectionGeneration.incrementAndGet()
        scope.launch {
            applySettingsForLifter(
                rotation.rotatedToUserId, freeLiftFallbackLb = rotation.targetWeightLb, workInPartner = true, generation = gen
            )
        }
    }

    fun togglePartnerMode() {
        val ui = _uiStateFlow.value
        if (ui.authRequired || ui.users.size < 2) return
        val nextEnabled = !ui.sessionState.partnerModeEnabled
        val ids = listOf(ui.users[0].id, ui.users[1].id)
        val nextState = localEngine.setPartnerMode(nextEnabled, ids)
        _uiStateFlow.update { it.copy(sessionState = nextState) }
        if (canSendMotorViaServer()) {
            sendWs(
                buildJsonObject {
                    put("type", "set_partner_mode")
                    put("enabled", nextEnabled)
                    putJsonArray("partnerUserIds") {
                        ids.forEach { add(JsonPrimitive(it)) }
                    }
                }
            )
        }
    }

    fun toggleUnit() {
        if (_uiStateFlow.value.authRequired) return
        val user = _uiStateFlow.value.activeUser ?: return
        val nextUnit = if (Units.isKg(user.weightUnit)) "LB" else "KG"
        scope.launch {
            localEngine.updateUser(user.id, weightUnit = nextUnit)?.let { updated ->
                updateUserInState(updated)
                emitLocalBroadcast("user_updated") {
                    put("user", SwonalJson.encodeToJsonElement(User.serializer(), updated))
                }
                notifyDirtySync()
            }
        }
    }

    fun toggleTheme() {
        if (_uiStateFlow.value.authRequired) return
        val user = _uiStateFlow.value.activeUser ?: return
        val nextTheme = if (user.themeMode == "light") "dark" else "light"
        scope.launch {
            localEngine.updateUser(user.id, themeMode = nextTheme)?.let { updated ->
                updateUserInState(updated)
                emitLocalBroadcast("user_updated") {
                    put("user", SwonalJson.encodeToJsonElement(User.serializer(), updated))
                }
                notifyDirtySync()
            }
        }
    }

    fun updateUserAccent(accentHex: String) {
        if (_uiStateFlow.value.authRequired) return
        val user = _uiStateFlow.value.activeUser ?: return
        scope.launch {
            localEngine.updateUser(user.id, accentColor = accentHex, avatarColor = accentHex)?.let { updated ->
                updateUserInState(updated)
                emitLocalBroadcast("user_updated") {
                    put("user", SwonalJson.encodeToJsonElement(User.serializer(), updated))
                }
                notifyDirtySync()
            }
        }
    }

    fun createHouseholdUser(name: String, accentHex: String) {
        if (_uiStateFlow.value.authRequired) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            runCatching { localEngine.createUser(trimmed, avatarColor = accentHex, accentColor = accentHex) }
                .onSuccess { created ->
                    refreshAppState()
                    switchUser(created.id)
                    emitLocalBroadcast("users_updated")
                    notifyDirtySync()
                }
                .onFailure { err ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Failed to create profile"))
                }
        }
    }

    fun setShowDemoPip(enabled: Boolean) {
        if (_uiStateFlow.value.authRequired) return
        _uiStateFlow.update { it.copy(showDemoPip = enabled) }
        val uid = _uiStateFlow.value.activeUser?.id ?: return
        scope.launch {
            localEngine.setDemoPip(uid, enabled)
            emitLocalBroadcast("demo_pip_updated") {
                put("userId", uid)
                put("enabled", enabled)
            }
            notifyDirtySync()
        }
    }

    fun cycleTelemetryDisplayMode() {
        val next = _uiStateFlow.value.telemetryDisplayMode.next()
        prefs.telemetryDisplayMode = next
        _uiStateFlow.update { it.copy(telemetryDisplayMode = next) }
    }

    fun toggleSoundMuted() {
        val next = !_uiStateFlow.value.soundMuted
        prefs.soundMuted = next
        _uiStateFlow.update { it.copy(soundMuted = next) }
    }

    fun setAutoAdjustWeights(enabled: Boolean) {
        if (_uiStateFlow.value.authRequired) return
        val uid = _uiStateFlow.value.activeUser?.id ?: return
        autoAdjustDesired[uid] = enabled
        _uiStateFlow.update { it.copy(autoAdjustWeights = enabled) }
        scope.launch {
            // Writes happen one at a time and always store the latest toggle, so rapid taps can't land
            // out of order
            autoAdjustWriteMutex.withLock {
                val latest = autoAdjustDesired[uid] ?: return@withLock
                if (runCatching { localEngine.setAutoAdjustWeights(uid, latest) }.isSuccess) {
                    autoAdjustDesired.remove(uid, latest)
                }
            }
        }
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.developerMode = enabled
        _uiStateFlow.update { it.copy(developerMode = enabled) }
    }

    private fun updateUserInState(updated: User) {
        _uiStateFlow.update { st ->
            st.copy(users = st.users.map { if (it.id == updated.id) updated else it })
        }
    }

    // --- Workouts, Routines, Sets & Rest Timer ---

    fun startWorkoutTemplate(template: WorkoutTemplate) {
        if (_uiStateFlow.value.authRequired) return
        val blocks = template.blocks.ifEmpty {
            runCatching { SwonalJson.decodeFromString<List<WorkoutBlock>>(template.blocksJson) }
                .getOrDefault(emptyList())
        }
        startWorkoutFromBlocks(
            templateId = template.id.takeIf { it.isNotBlank() },
            templateName = template.name,
            blocks = blocks,
            preferredUserId = template.userId.takeIf { it.isNotBlank() }
        )
    }

    fun updateTemplateSchedule(template: WorkoutTemplate, scheduleDays: List<Int>) {
        if (_uiStateFlow.value.authRequired) return
        val normalized = scheduleDays.filter { it in 0..6 }.distinct().sorted()
        _uiStateFlow.update { st ->
            st.copy(
                templates = st.templates.map { t ->
                    if (t.id == template.id) t.copy(scheduleDays = normalized) else t
                }
            )
        }
        scope.launch {
            runCatching { localEngine.updateTemplate(template.id, scheduleDays = normalized) }
                .onSuccess { updated ->
                    if (updated != null) {
                        _uiStateFlow.update { st ->
                            st.copy(
                                templates = st.templates.map { t ->
                                    if (t.id == updated.id) updated else t
                                }
                            )
                        }
                        emitLocalBroadcast("templates_updated")
                        notifyDirtySync()
                    }
                }
                .onFailure { err ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Failed to save schedule"))
                }
        }
    }

    fun startQueuedWorkout() {
        if (_uiStateFlow.value.authRequired) return
        val queued = _uiStateFlow.value.sessionState.queuedWorkout ?: return
        clearQueuedWorkout()
        startWorkoutFromBlocks(
            templateId = queued.templateId.takeIf { it.isNotBlank() },
            templateName = queued.templateName,
            blocks = queued.blocks,
            preferredUserId = queued.userId.takeIf { it.isNotBlank() }
        )
    }

    fun clearQueuedWorkout() {
        localEngine.clearQueuedWorkout()
        sendWs(buildJsonObject { put("type", "clear_queued_workout") })
        _uiStateFlow.update {
            it.copy(sessionState = it.sessionState.copy(queuedWorkout = null))
        }
    }

    private fun startWorkoutFromBlocks(
        templateId: String?,
        templateName: String,
        blocks: List<WorkoutBlock>,
        preferredUserId: String?
    ) {
        val steps = Units.flattenTemplateBlocks(blocks)
        if (steps.isEmpty()) {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Routine has no exercise steps"))
            return
        }
        val uid = preferredUserId ?: _uiStateFlow.value.activeUser?.id ?: return
        launchSettingsRestored.set(true)
        heldWeight.clear()
        heldWeight.forgetFinishedSet()
        val gen = selectionGeneration.incrementAndGet()
        scope.launch {
            if (isWeightCurrentlyEngaged()) {
                disableWeightAndAwait()
            }
            val session = runCatching { localEngine.createSession(uid, templateId) }.getOrNull()
            val step0 = steps[0]
            val ex0 = _uiStateFlow.value.exercises.firstOrNull { it.id == step0.exerciseId }
            lastAutoLoggedStepKey = null
            _uiStateFlow.update {
                it.copy(
                    activeTemplateName = templateName,
                    routineSteps = steps,
                    currentStepIdx = 0,
                    activeExerciseId = step0.exerciseId,
                    sessionPrHits = emptyList(),
                    sessionState = if (session != null) {
                        it.sessionState.copy(activeUserId = uid, activeSessionId = session.id)
                    } else it.sessionState.copy(activeUserId = uid)
                )
            }
            applyExerciseSettings(step0.exerciseId, resolveRoutineStepSettings(uid, step0, ex0), gen)
            resetReps()
            refreshOverload(uid, step0.exerciseId)
            notifyDirtySync()
        }
    }

    fun selectFreeExercise(exercise: Exercise) {
        if (_uiStateFlow.value.authRequired) return
        launchSettingsRestored.set(true)
        heldWeight.clear()
        heldWeight.forgetFinishedSet()
        lastAutoLoggedStepKey = null
        val gen = selectionGeneration.incrementAndGet()
        val wasEngaged = isWeightCurrentlyEngaged()
        _uiStateFlow.update {
            it.copy(
                activeTemplateName = null,
                routineSteps = emptyList(),
                currentStepIdx = 0,
                activeExerciseId = exercise.id
            )
        }
        resetReps()
        val ui = _uiStateFlow.value
        val pr = ui.prs.firstOrNull { it.exerciseId == exercise.id }
        val suggested = if (pr != null && pr.maxWeightLb > 0) {
            (round((pr.maxWeightLb * 0.85) / 5.0) * 5.0)
                .coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
        } else null
        scope.launch {
            if (wasEngaged) {
                disableWeightAndAwait()
            }
            // Dropped if another exercise was picked while this one's settings were looked up
            applyExerciseSettings(exercise.id, resolveFreeLiftSettings(ui.activeUser?.id, exercise, suggested), gen)
            ui.activeUser?.id?.let { uid ->
                refreshOverload(uid, exercise.id)
            }
        }
    }

    fun stepRoutine(delta: Int) {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        if (ui.routineSteps.isNotEmpty()) {
            val nextIdx = (ui.currentStepIdx + delta).coerceIn(0, ui.routineSteps.lastIndex)
            if (nextIdx == ui.currentStepIdx) return
            heldWeight.clear()
            heldWeight.forgetFinishedSet()
            val gen = selectionGeneration.incrementAndGet()
            val wasEngaged = isWeightCurrentlyEngaged()
            val step = ui.routineSteps[nextIdx]
            val ex = ui.exercises.firstOrNull { it.id == step.exerciseId }
            _uiStateFlow.update {
                it.copy(currentStepIdx = nextIdx, activeExerciseId = step.exerciseId)
            }
            resetReps()
            scope.launch {
                if (wasEngaged) {
                    disableWeightAndAwait()
                }
                applyExerciseSettings(step.exerciseId, resolveRoutineStepSettings(ui.activeUser?.id, step, ex), gen)
                ui.activeUser?.id?.let { uid ->
                    refreshOverload(uid, step.exerciseId)
                }
            }
        } else if (ui.exercises.isNotEmpty()) {
            val curIdx = ui.exercises.indexOfFirst { it.id == ui.activeExercise?.id }.coerceAtLeast(0)
            val nextIdx = (curIdx + delta + ui.exercises.size) % ui.exercises.size
            selectFreeExercise(ui.exercises[nextIdx])
        }
    }

    fun logCompletedSet(repsOverride: Int? = null) {
        if (_uiStateFlow.value.authRequired) return
        scope.launch { logCompletedSetInternal(repsOverride) }
    }

    /**
     * @param captured the release to log (auto-log), instead of looking it up; logged at most once
     */
    private suspend fun logCompletedSetInternal(
        repsOverride: Int? = null,
        autoLogged: Boolean = false,
        captured: HeldWeightTracker.FinishedSet? = null
    ) {
        if (!logSetInFlight.compareAndSet(false, true)) return
        try {
            // Let a release's own save (and its safety-cutout verdict) finish first
            if (captured == null && !isWeightCurrentlyEngaged()) awaitPendingRemember()
            val ui = _uiStateFlow.value
            val activeUser = ui.activeUser ?: return
            val exercise = captured?.let { c -> ui.exercises.firstOrNull { it.id == c.exerciseId } }
                ?: ui.activeExercise
                ?: return
            val step = ui.currentStep
            // What was actually lifted: the captured release, live values while still engaged, else the set
            // captured on release (a held weight for the next lifter/step may already have loaded since)
            val engaged = isWeightCurrentlyEngaged()
            val finishedSet = captured ?: if (engaged) null else heldWeight.finishedSetFor(exercise.id)
            if (finishedSet != null && heldWeight.isLogged(finishedSet.releaseId)) return
            // Still holding the weight after an earlier mid-lift Complete Set: that weight belongs to the step
            // already logged, so this set is logged but not remembered for the step now on screen
            val carriedOver = finishedSet == null && engaged && heldWeight.currentContext?.carriedOver == true
            val live = _telemetryFlow.value
            // The set belongs to whoever engaged the weight, even if someone switched profile since
            val user = (finishedSet?.lifterId ?: heldWeight.lifterForSet(activeUser.id))
                ?.let { id -> ui.users.firstOrNull { it.id == id } }
                ?: activeUser
            val liftedLb = finishedSet?.weightLb ?: live.targetWeightLb
            val liftedMode = finishedSet?.mode ?: live.profileMode
            val liftedModifierPct = finishedSet?.modifierPct ?: live.modifierPct
            val countedReps = finishedSet?.reps ?: live.repCount
            val routineTargetReps = finishedSet?.routineTargetReps
                ?: step?.takeIf { it.exerciseId == exercise.id }?.targetReps
            val nextIsSameWeight = nextSetUsesSameWeight(ui, exercise.id, routineTargetReps)

            var sessionId = ui.sessionState.activeSessionId
            if (sessionId == null) {
                val created = runCatching { localEngine.createSession(user.id, null) }.getOrNull() ?: return
                sessionId = created.id
                _uiStateFlow.update {
                    it.copy(sessionState = it.sessionState.copy(activeSessionId = sessionId))
                }
            }

            val reps = repsOverride ?: if (countedReps > 0) countedReps else (step?.targetReps ?: 10)
            val restSec = step?.restSeconds ?: 60
            val setNumber = step?.setNumber ?: 1

            val resp = runCatching {
                localEngine.logSet(
                    sessionId = sessionId,
                    userId = user.id,
                    exerciseId = exercise.id,
                    setNumber = setNumber,
                    mode = liftedMode,
                    modifierPct = liftedModifierPct,
                    weightLb = liftedLb,
                    repsCompleted = reps,
                    restSeconds = restSec
                )
            }.getOrElse { err ->
                _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Failed to log set"))
                return
            }

            // Remember what was lifted. Released sets were already remembered on release (re-saved here with
            // the logged rep count; the safety-cutout verdict made at release stays); a set logged without
            // any detected lift (no motor data) isn't remembered.
            val change: WeightChange? = when {
                finishedSet != null -> rememberFinishedSet(user, finishedSet.copy(reps = reps))
                engaged && !carriedOver -> rememberFinishedSet(
                    user,
                    HeldWeightTracker.FinishedSet(
                        lifterId = user.id,
                        exerciseId = exercise.id,
                        weightLb = liftedLb,
                        mode = liftedMode,
                        modifierPct = liftedModifierPct,
                        accessory = live.accessory,
                        reps = reps,
                        routineTargetReps = routineTargetReps,
                        autoStep = step?.takeIf { it.exerciseId == exercise.id }?.autoWeight == true
                    )
                )
                else -> null
            }
            // A manual Complete Set after release already announced the change on release
            val announceChange = engaged || autoLogged || finishedSet == null
            var weightDeferred = false

            emitLocalBroadcast("set_logged") {
                put("setLog", SwonalJson.encodeToJsonElement(SetLog.serializer(), resp.setLog))
                put("newPr", resp.newPr)
                put("totalVolume", resp.totalVolume)
            }
            resp.partnerRotation?.let { prRot ->
                emitLocalBroadcast("partner_rotated") {
                    put("userId", prRot.rotatedToUserId)
                    put("targetWeightLb", prRot.targetWeightLb)
                    put("exerciseId", exercise.id)
                }
            }
            notifyDirtySync()

            if (resp.newPr) {
                _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.PERSONAL_RECORD))
                duckMusicForCue(1800)
                val est1Rm = Units.estimateEpley1Rm(liftedLb, reps)
                val hit = PrHit(exercise.name, est1Rm, liftedLb, reps)
                _uiStateFlow.update {
                    it.copy(
                        lastPrBanner = exercise.name,
                        sessionPrHits = it.sessionPrHits + hit
                    )
                }
                prBannerJob?.cancel()
                prBannerJob = scope.launch {
                    delay(5000L)
                    _uiStateFlow.update { it.copy(lastPrBanner = null) }
                }
            } else {
                _effectsFlow.tryEmit(KioskEffect.PlayCue(SwonalSoundEngine.Cue.SET_COMPLETE))
                duckMusicForCue(1200)
            }

            heldWeight.onSetLogged(finishedSet?.releaseId ?: 0L)
            // Next set: the rotated Work-In partner, else whoever is active now
            val effectiveUid = resp.partnerRotation?.rotatedToUserId ?: activeUser.id
            runCatching { localEngine.getPrs(effectiveUid) }.onSuccess { prs ->
                if (_uiStateFlow.value.activeUser?.id == effectiveUid) {
                    _uiStateFlow.update { it.copy(prs = prs) }
                }
            }
            runCatching { localEngine.listSessions(effectiveUid) }.onSuccess { sessions ->
                if (_uiStateFlow.value.activeUser?.id == effectiveUid) {
                    _uiStateFlow.update { it.copy(recentSessions = sessions) }
                }
            }

            // Advance routine step if active
            val updatedUi = _uiStateFlow.value
            val rotation = resp.partnerRotation
            var nextWeightLb: Double? = null
            if (updatedUi.routineSteps.isNotEmpty()) {
                val nextIdx = updatedUi.currentStepIdx + 1
                if (nextIdx < updatedUi.routineSteps.size) {
                    val nextStep = updatedUi.routineSteps[nextIdx]
                    val nextEx = updatedUi.exercises.firstOrNull { it.id == nextStep.exerciseId }
                    val gen = selectionGeneration.incrementAndGet()
                    _uiStateFlow.update {
                        it.copy(currentStepIdx = nextIdx, activeExerciseId = nextStep.exerciseId)
                    }
                    // Next lifter (same one, or the rotated Work-In partner) gets their settings for this step
                    val settings = resolveRoutineStepSettings(effectiveUid, nextStep, nextEx, workInPartner = rotation != null)
                    nextWeightLb = loadedWeightLb(settings)
                    weightDeferred = applyExerciseSettings(nextStep.exerciseId, settings, gen)
                    refreshOverload(effectiveUid, nextStep.exerciseId)
                } else {
                    // Last set logged while the lifter may still be holding the weight: finish the workout
                    // but let them release it themselves instead of cutting resistance under load
                    finishWorkoutInternal(disengage = false)
                }
            } else if (rotation != null) {
                // Free Lift Work-In: partner's remembered weight for this exercise, else the PR-based target
                val gen = selectionGeneration.incrementAndGet()
                val settings = resolveFreeLiftSettings(effectiveUid, exercise, rotation.targetWeightLb)
                nextWeightLb = loadedWeightLb(settings)
                weightDeferred = applyExerciseSettings(exercise.id, settings, gen)
            }

            val nextUser = updatedUi.users.firstOrNull { it.id == effectiveUid }
            val message = ExerciseSettingsPolicy.postSetMessage(
                changeText = change?.takeIf { announceChange }?.let {
                    ExerciseSettingsPolicy.changeMessage(it, user.weightUnit, exercise.name, nextIsSameWeight)
                },
                lifterName = user.name,
                someoneElseNext = effectiveUid != user.id,
                deferredWeightLb = nextWeightLb?.takeIf { weightDeferred },
                deferredUnit = nextUser?.weightUnit ?: _uiStateFlow.value.activeUnit,
                nextLifterName = nextUser?.name
            )
            showMessage(message)

            // Logged while still engaged (Complete Set mid-lift): what is lifted until the release is carried over
            // at the logged step's weight. It still belongs to whoever is holding the weight (not a partner
            // switched in meanwhile), and is not remembered or auto-logged as a set of the new step.
            if (finishedSet == null && engaged && isWeightCurrentlyEngaged()) {
                heldWeight.onLoggedWhileEngaged(user.id, exercise.id)
            }
        } finally {
            logSetInFlight.set(false)
        }
    }

    fun finishWorkout() {
        scope.launch { finishWorkoutInternal() }
    }

    private suspend fun finishWorkoutInternal(disengage: Boolean = true) {
        val sessionId = _uiStateFlow.value.sessionState.activeSessionId
        val prHits = _uiStateFlow.value.sessionPrHits
        heldWeight.clear()
        heldWeight.forgetFinishedSet()
        if (disengage) disableWeight()
        skipRestTimer()
        val completed = if (sessionId != null) {
            runCatching { localEngine.completeSession(sessionId) }.getOrNull()
        } else {
            null
        }
        lastAutoLoggedStepKey = null
        _uiStateFlow.update {
            it.copy(
                activeTemplateName = null,
                routineSteps = emptyList(),
                currentStepIdx = 0,
                sessionPrHits = emptyList(),
                sessionState = it.sessionState.copy(activeSessionId = null, restTimerEndsAt = null)
            )
        }
        if (completed != null) {
            _uiStateFlow.value.activeUser?.id?.let { uid ->
                runCatching { localEngine.listSessions(uid) }.onSuccess { sessions ->
                    if (_uiStateFlow.value.activeUser?.id == uid) {
                        _uiStateFlow.update { it.copy(recentSessions = sessions) }
                    }
                }
            }
            _effectsFlow.tryEmit(KioskEffect.ShowWorkoutSummary(completed, prHits))
        }
    }

    fun addRestSeconds(extraSec: Int) {
        if (_uiStateFlow.value.authRequired) return
        val total = (_uiStateFlow.value.restSecondsRemaining + extraSec).coerceAtLeast(1)
        val nextState = localEngine.startRestTimerSeconds(total)
        _uiStateFlow.update { it.copy(sessionState = nextState) }
        sendWs(
            buildJsonObject {
                put("type", "start_rest_timer")
                put("seconds", total)
            }
        )
    }

    fun skipRestTimer() {
        prevRestCountdown = 0
        val nextState = localEngine.setRestTimer(null)
        sendWs(buildJsonObject { put("type", "skip_rest_timer") })
        _uiStateFlow.update {
            it.copy(
                restSecondsRemaining = 0,
                sessionState = nextState
            )
        }
    }

    fun acknowledgeSafetyEvent() {
        _uiStateFlow.update { it.copy(safetyEvent = null) }
    }

    // --- Music Actions ---

    private suspend fun executeSecondaryMusicAction(
        provider: String,
        userId: String,
        action: String,
        volumePct: Int? = null
    ): MusicPlaybackState {
        val local = localLanMusic.controlAction(provider, action, volumePct)
        if (local.isConnected) return local
        if (hasRemoteSync()) {
            return when (action) {
                "play" -> api.playMusic(provider, userId)
                "pause" -> api.pauseMusic(provider, userId)
                "next" -> api.nextTrack(provider, userId)
                "previous" -> api.previousTrack(provider, userId)
                "volume" -> api.setMusicVolume(provider, userId, volumePct ?: 70)
                else -> local
            }
        }
        return local
    }

    fun toggleMusicPlayPause() {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        val defaultDeviceId = resolvePreferredMusicDeviceId(ui)
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    if (ui.musicState.isPlaying) {
                        localSpotify.pause(uid)
                    } else {
                        localSpotify.play(uid, deviceId = defaultDeviceId)
                    }
                } else {
                    executeSecondaryMusicAction(provider, uid, if (ui.musicState.isPlaying) "pause" else "play")
                }
            }.onSuccess { st ->
                _uiStateFlow.update { it.copy(musicState = st, musicError = null) }
            }.onFailure { err ->
                _uiStateFlow.update { it.copy(musicError = err.message ?: "Music command failed") }
            }
        }
    }

    fun musicPrevious() {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    localSpotify.previousTrack(uid)
                } else {
                    executeSecondaryMusicAction(provider, uid, "previous")
                }
            }
                .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st, musicError = null) } }
                .onFailure { err -> _uiStateFlow.update { it.copy(musicError = err.message) } }
        }
    }

    fun musicNext() {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    localSpotify.nextTrack(uid)
                } else {
                    executeSecondaryMusicAction(provider, uid, "next")
                }
            }
                .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st, musicError = null) } }
                .onFailure { err -> _uiStateFlow.update { it.copy(musicError = err.message) } }
        }
    }

    fun setMusicVolume(volumePct: Int) {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        _uiStateFlow.update { it.copy(musicState = it.musicState.copy(volumePct = volumePct)) }
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    localSpotify.setVolume(uid, volumePct)
                } else {
                    executeSecondaryMusicAction(provider, uid, "volume", volumePct)
                }
            }
                .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st, musicError = null) } }
                .onFailure { err -> _uiStateFlow.update { it.copy(musicError = err.message) } }
        }
    }

    fun playMusicUri(uri: String) {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        val defaultDeviceId = resolvePreferredMusicDeviceId(ui)
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    localSpotify.play(uid, contextUri = uri, deviceId = defaultDeviceId)
                } else if (hasRemoteSync()) {
                    api.playMusic(provider, uid, contextUri = uri, deviceId = defaultDeviceId)
                } else {
                    localLanMusic.controlAction(provider, "play")
                }
            }
                .onSuccess { st -> _uiStateFlow.update { it.copy(musicState = st, musicError = null) } }
                .onFailure { err -> _uiStateFlow.update { it.copy(musicError = err.message) } }
        }
    }

    private fun resolvePreferredMusicDeviceId(ui: KioskUiState): String? {
        val librespot = findLibrespotDevice(ui.musicDevices)
        if (librespot != null && librespot.id != "tonal-gym-librespot") {
            return librespot.id
        }
        val currentActive = ui.musicDevices.firstOrNull { it.isActive }?.id
            ?: ui.musicState.activeDeviceId?.takeIf { it.isNotBlank() }
        if (currentActive != null) return currentActive
        return librespot?.id ?: ui.musicDevices.firstOrNull()?.id
    }

    fun completeSpotifySpeakerAuth(codeOrUrl: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val uid = _uiStateFlow.value.activeUser?.id
        val fallbackUid = uid ?: _uiStateFlow.value.users.firstOrNull()?.id ?: "11111111-1111-4111-8111-111111111111"
        scope.launch {
            runCatching { localSpotify.exchangeKeymasterPkceCode(fallbackUid, codeOrUrl) }
                .onSuccess {
                    notifyDirtySync()
                    if (uid != null) {
                        delay(1500L)
                        refreshMusicAll(uid)
                    }
                    onDone(true, "Tonal Speakers paired with Spotify!")
                }
                .onFailure { err ->
                    onDone(false, err.message ?: "Speaker pairing failed")
                }
        }
    }

    fun transferMusicDevice(deviceId: String) {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val uid = ui.activeUser?.id ?: return
        val provider = ui.preferredMusicProvider
        scope.launch {
            runCatching {
                if (provider == "spotify") {
                    localSpotify.transferPlayback(uid, deviceId)
                } else if (hasRemoteSync()) {
                    api.transferMusicDevice(provider, uid, deviceId)
                } else {
                    MusicPlaybackState(isConnected = false)
                }
            }
                .onSuccess { st ->
                    _uiStateFlow.update { it.copy(musicState = st, musicError = null) }
                    refreshMusicAll(uid)
                }
                .onFailure { err -> _uiStateFlow.update { it.copy(musicError = err.message) } }
        }
    }

    fun toggleFavoritePlaylist(playlistId: String) {
        if (_uiStateFlow.value.authRequired) return
        val uid = _uiStateFlow.value.activeUser?.id ?: return
        scope.launch {
            runCatching { localSpotify.toggleFavoritePlaylist(uid, playlistId) }
                .onSuccess { favIds ->
                    _uiStateFlow.update { it.copy(favoritePlaylistIds = favIds.toSet()) }
                    notifyDirtySync()
                }
        }
    }

    fun switchMusicProvider(providerId: String) {
        if (_uiStateFlow.value.authRequired) return
        val user = _uiStateFlow.value.activeUser ?: return
        scope.launch {
            runCatching { localEngine.updateUser(user.id, preferredMusicProvider = providerId) }
                .onSuccess { updated ->
                    if (updated != null) {
                        updateUserInState(updated)
                        notifyDirtySync()
                        refreshMusicAll(updated.id)
                    }
                }
        }
    }

    suspend fun loadPlaylistTracks(playlistId: String): Result<List<MusicTrack>> {
        val ui = _uiStateFlow.value
        val uid = ui.activeUser?.id ?: return Result.failure(IllegalStateException("No active user"))
        return runCatching {
            if (ui.preferredMusicProvider == "spotify") {
                localSpotify.getPlaylistTracks(playlistId, uid)
            } else if (hasRemoteSync()) {
                api.getPlaylistTracks(ui.preferredMusicProvider, playlistId, uid)
            } else {
                emptyList()
            }
        }
    }

    fun clearMusicError() {
        _uiStateFlow.update { it.copy(musicError = null) }
    }

    private fun duckMusicForCue(durationMs: Int) {
        val ui = _uiStateFlow.value
        if (!ui.musicState.isPlaying || !ui.musicState.isConnected) return
        val uid = ui.activeUser?.id ?: return
        scope.launch {
            runCatching {
                if (ui.preferredMusicProvider == "spotify") {
                    localSpotify.duckVolume(uid, targetPct = 25, durationMs = durationMs)
                } else if (hasRemoteSync()) {
                    api.duckMusic(ui.preferredMusicProvider, uid, targetPct = 25, durationMs = durationMs)
                }
            }
        }
    }

    // --- AI Coach & Diagnostics ---

    fun askCoach(prompt: String) {
        val ui = _uiStateFlow.value
        if (ui.authRequired) return
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        val uid = ui.activeUser?.id ?: return
        val telemetry = _telemetryFlow.value
        val vbt = _vbtFlow.value
        _uiStateFlow.update { it.copy(coachLoading = true) }
        scope.launch {
            runCatching {
                localAiCoach.ask(
                    userId = uid,
                    prompt = trimmed,
                    exerciseName = ui.activeExercise?.name,
                    currentWeightLb = telemetry.targetWeightLb,
                    repCount = telemetry.repCount,
                    meanVelocity = vbt.currentVelocityCmSec,
                    velocityDropPct = vbt.velocityDropPct
                )
            }.onSuccess { resp ->
                _uiStateFlow.update {
                    it.copy(
                        coachLoading = false,
                        coachReply = resp.reply,
                        coachAction = sanitizeCoachAction(resp.suggestedAction)
                    )
                }
            }.onFailure { err ->
                _uiStateFlow.update {
                    it.copy(
                        coachLoading = false,
                        coachReply = "Coach unavailable: ${err.message}",
                        coachAction = null
                    )
                }
            }
        }
    }

    fun executeCoachAction(action: CoachSuggestedAction) {
        if (_uiStateFlow.value.authRequired) return
        val safe = sanitizeCoachAction(action) ?: run {
            _uiStateFlow.update { it.copy(coachAction = null) }
            return
        }
        when (safe.type) {
            "set_weight_delta" -> {
                if (isWeightCurrentlyEngaged()) {
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage("Disengage weight before applying Coach load change"))
                } else {
                    val delta = safe.deltaLb ?: 5.0
                    setWeight(_telemetryFlow.value.targetWeightLb + delta, allowWhileEngaged = false)
                }
            }
            "skip_rest" -> skipRestTimer()
        }
        _uiStateFlow.update { it.copy(coachAction = null) }
    }

    fun saveCoachConfig(provider: String, model: String, apiKey: String?, baseUrl: String?) {
        if (_uiStateFlow.value.authRequired) return
        scope.launch {
            runCatching { localAiCoach.setConfig(provider = provider, model = model, apiKey = apiKey, baseUrl = baseUrl) }
                .onSuccess { cfg ->
                    _uiStateFlow.update { it.copy(coachConfig = cfg) }
                    notifyDirtySync()
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage("AI Coach settings saved"))
                }
                .onFailure { err ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Save failed"))
                }
        }
    }

    fun triggerMockBle(action: String) {
        val ui = _uiStateFlow.value
        if (ui.authRequired || !ui.isMockMode) return
        scope.launch {
            val ok = localDaemon.triggerMockBleDirect(action)
            if (!ok) {
                runCatching { api.triggerMockBle(action) }
                    .onFailure { err -> _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "BLE sim failed")) }
            }
        }
    }

    fun triggerMockBarTilt() {
        val ui = _uiStateFlow.value
        if (ui.authRequired || !ui.isMockMode) return
        scope.launch {
            val ok = localDaemon.triggerMockBarTiltDirect(autoEnable = true)
            if (!ok) {
                runCatching { api.triggerMockBarTilt(autoEnable = true) }
                    .onFailure { err -> _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Bar tilt failed")) }
            }
        }
    }

    fun zeroCalibration() {
        if (_uiStateFlow.value.authRequired) return
        scope.launch {
            val direct = localDaemon.zeroCalibrationDirect()
            if (direct != null) {
                _uiStateFlow.update { it.copy(calibrationStatus = direct) }
                _effectsFlow.tryEmit(KioskEffect.ShowMessage("Cable strain gauges zeroed"))
                return@launch
            }
            runCatching { api.zeroCalibration() }
                .onSuccess { cal ->
                    _uiStateFlow.update { it.copy(calibrationStatus = cal) }
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage("Cable strain gauges zeroed"))
                }
                .onFailure { err ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Calibration failed"))
                }
        }
    }

    fun testHomeAssistant() {
        if (_uiStateFlow.value.authRequired) return
        scope.launch {
            runCatching {
                localHaClient.publishEvent(
                    "test",
                    buildJsonObject {
                        put("source", "kiosk")
                        put("message", "Swonal Home Assistant webhook test")
                    }
                )
                if (hasRemoteSync()) {
                    runCatching { api.testHomeAssistantEvent() }
                }
            }
                .onSuccess {
                    refreshDiagnostics()
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage("Home Assistant test event published"))
                }
                .onFailure { err ->
                    _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "HA test failed"))
                }
        }
    }

    suspend fun switchToStockMode(): Boolean {
        if (_uiStateFlow.value.authRequired) return false
        val ok = localDaemon.switchToStockModeDirect() ||
            runCatching { api.switchToStockMode(); true }.getOrDefault(false)
        if (ok) {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Handed motor daemon to Stock Tonal mode"))
        } else {
            _effectsFlow.tryEmit(KioskEffect.ShowMessage("Failed to switch daemon to Stock mode"))
        }
        return ok
    }

    fun switchToCustomMode() {
        if (_uiStateFlow.value.authRequired) return
        scope.launch {
            val ok = localDaemon.switchToCustomModeDirect()
            if (ok) {
                _effectsFlow.tryEmit(KioskEffect.ShowMessage("Switched daemon to Custom mode"))
                return@launch
            }
            runCatching { api.switchToCustomMode() }
                .onSuccess { _effectsFlow.tryEmit(KioskEffect.ShowMessage("Switched daemon to Custom mode")) }
                .onFailure { err -> _effectsFlow.tryEmit(KioskEffect.ShowMessage(err.message ?: "Mode switch failed")) }
        }
    }

    companion object {
        /** How long to wait after a release for a safety-cutout report that may arrive just after it. */
        private const val SAFETY_EVENT_GRACE_MS = 500L

        /** Minimum gap between attempts to send held settings the motor didn't take. */
        private const val HELD_RETRY_MS = 250L

        const val MAX_COACH_DELTA_LB = 20.0

        fun findLibrespotDevice(devices: List<MusicDevice>): MusicDevice? {
            return devices.firstOrNull {
                it.name.contains("Tonal Speakers", ignoreCase = true) ||
                    it.name.contains("Tonal Gym", ignoreCase = true) ||
                    it.name.contains("librespot", ignoreCase = true)
            }
        }

        /**
         * Validates & clamps LLM-suggested actions and ignores untrusted LLM label text
         * so a prompt-injected or hallucinated response can never spoof a button label or spike load.
         */
        fun sanitizeCoachAction(raw: CoachSuggestedAction?): CoachSuggestedAction? {
            if (raw == null) return null
            return when (raw.type) {
                "set_weight_delta" -> {
                    val delta = (raw.deltaLb ?: return null).coerceIn(-MAX_COACH_DELTA_LB, MAX_COACH_DELTA_LB)
                    if (delta == 0.0) return null
                    val sign = if (delta > 0) "+" else ""
                    val formatted = String.format(Locale.US, "%s%.1f LB", sign, delta).replace(".0 ", " ")
                    CoachSuggestedAction(type = "set_weight_delta", deltaLb = delta, label = "Adjust $formatted")
                }
                "skip_rest" -> CoachSuggestedAction(type = "skip_rest", deltaLb = null, label = "Skip Rest Timer")
                // Never allow LLM responses to engage/toggle physical motor resistance
                else -> null
            }
        }
    }
}
