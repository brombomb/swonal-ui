package com.swonal.kiosk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder is JsonDecoder) {
            val el = decoder.decodeJsonElement()
            if (el is JsonPrimitive) {
                el.booleanOrNull?.let { return it }
                el.intOrNull?.let { return it != 0 }
                val str = el.content.trim().lowercase()
                if (str == "true" || str == "1") return true
                if (str == "false" || str == "0") return false
            }
            return false
        }
        return runCatching { decoder.decodeBoolean() }.getOrDefault(false)
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

val SwonalJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class CableData(
    val position: Double = 0.0,
    val velocity: Double = 0.0,
    @SerialName("load_lb") val loadLb: Double = 0.0
)

@Serializable
data class ArmAxis(
    val left: Int = 4,
    val right: Int = 4,
    @SerialName("left_unlocked")
    @Serializable(with = FlexibleBooleanSerializer::class)
    val leftUnlocked: Boolean = false,
    @SerialName("right_unlocked")
    @Serializable(with = FlexibleBooleanSerializer::class)
    val rightUnlocked: Boolean = false
)

@Serializable
data class ArmPositions(
    val height: ArmAxis = ArmAxis(4, 4),
    val rotation: ArmAxis = ArmAxis(2, 2),
    val angle: ArmAxis = ArmAxis(1, 1)
)

@Serializable
data class TelemetryFrame(
    val mode: String = "CUSTOM",
    @SerialName("weight_enabled") val weightEnabled: Boolean = false,
    @SerialName("target_weight_lb") val targetWeightLb: Double = 25.0,
    @SerialName("applied_resistance_lb") val appliedResistanceLb: Double = 0.0,
    @SerialName("profile_mode") val profileMode: String = "standard",
    @SerialName("modifier_pct") val modifierPct: Int = 0,
    @SerialName("rep_count") val repCount: Int = 0,
    val accessory: String = "HANDLES",
    @SerialName("left_cable") val leftCable: CableData = CableData(),
    @SerialName("right_cable") val rightCable: CableData = CableData(),
    @SerialName("arm_positions") val armPositions: ArmPositions = ArmPositions()
)

@Serializable
data class User(
    val id: String,
    val name: String,
    val avatarColor: String = "#3B82F6",
    val preferredMusicProvider: String = "spotify",
    val themeMode: String = "dark",
    val accentColor: String = "#00F0FF",
    val weightUnit: String = "LB",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val muscleGroup: String,
    val accessory: String = "HANDLES",
    val recommendedHeight: Int = 4,
    val recommendedRotation: Int = 2,
    val recommendedAngle: Int = 1,
    val unilateral: Boolean = false,
    val notes: String? = null,
    val mediaUrl: String? = null
)

@Serializable
data class WorkoutBlockItem(
    val exerciseId: String,
    val sets: Int = 3,
    val targetReps: Int = 10,
    val mode: String = "standard",
    val modifierPct: Int = 0,
    /** Starting weight for a lifter's first time on this exercise (remembered weight is used after). */
    val targetWeightLb: Double = 25.0,
    /** "Auto": Swonal picks the first-time starting weight for lifters with Auto-Adjust Weights ON. */
    val autoWeight: Boolean = false
)

@Serializable
data class WorkoutBlock(
    val blockType: String = "STRAIGHT_SET",
    val restSeconds: Int = 60,
    val items: List<WorkoutBlockItem> = emptyList()
)

@Serializable
data class WorkoutTemplate(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val blocksJson: String = "[]",
    val blocks: List<WorkoutBlock> = emptyList(),
    val scheduleDays: List<Int> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class RoutineStep(
    val blockIndex: Int,
    val blockType: String,
    val itemIndex: Int,
    val exerciseId: String,
    val setNumber: Int,
    val totalSetsInItem: Int,
    val targetReps: Int,
    val targetWeightLb: Double,
    val mode: String,
    val modifierPct: Int,
    val restSeconds: Int,
    val autoWeight: Boolean = false
)

@Serializable
data class QueuedWorkoutPayload(
    val userId: String = "",
    val templateId: String = "",
    val templateName: String = "",
    val description: String? = null,
    val blocks: List<WorkoutBlock> = emptyList(),
    val queuedAt: String = ""
)

@Serializable
data class SharedSessionState(
    val activeUserId: String = "",
    val partnerModeEnabled: Boolean = false,
    val partnerUserIds: List<String> = emptyList(),
    val activePartnerIndex: Int = 0,
    val activeSessionId: String? = null,
    val activeExerciseId: String? = null,
    val queuedWorkout: QueuedWorkoutPayload? = null,
    val restTimerEndsAt: Long? = null
)

@Serializable
data class AppStateResponse(
    val sessionState: SharedSessionState = SharedSessionState(),
    val daemonConnected: Boolean = false,
    val users: List<User> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val templates: List<WorkoutTemplate> = emptyList()
)

@Serializable
data class SetLogExerciseSummary(
    val name: String = "",
    val muscleGroup: String = "",
    val accessory: String = "HANDLES"
)

@Serializable
data class SetLog(
    val id: String = "",
    val sessionId: String = "",
    val userId: String = "",
    val exerciseId: String = "",
    val setNumber: Int = 1,
    val mode: String = "standard",
    val modifierPct: Int = 0,
    val weightLb: Double = 0.0,
    val repsCompleted: Int = 0,
    val volumeLb: Double? = null,
    val rpe: Double? = null,
    val timestamp: String = "",
    val createdAt: String = "",
    val exercise: SetLogExerciseSummary? = null
)

@Serializable
data class SessionTemplateSummary(
    val name: String = ""
)

@Serializable
data class WorkoutSession(
    val id: String,
    val userId: String,
    val templateId: String? = null,
    val startedAt: String = "",
    val completedAt: String? = null,
    val totalVolume: Double = 0.0,
    val template: SessionTemplateSummary? = null,
    val setLogs: List<SetLog> = emptyList()
)

@Serializable
data class PartnerRotation(
    val rotatedToUserId: String,
    val targetWeightLb: Double
)

@Serializable
data class SetLogResponse(
    val setLog: SetLog,
    val newPr: Boolean = false,
    val totalVolume: Double = 0.0,
    val partnerRotation: PartnerRotation? = null
)

/** Last-used settings for one lifter on one exercise (weight, mode, accessory, and rep outcome). */
@Serializable
data class ExercisePref(
    val userId: String,
    val exerciseId: String,
    val weightLb: Double,
    val mode: String = "standard",
    val modifierPct: Int = 0,
    val accessory: String = "HANDLES",
    val lastReps: Int? = null,
    /** Rep goal of the last set when it was done inside a routine; null for Free Lift sets. */
    val lastTargetReps: Int? = null,
    val updatedAt: String = ""
)

@Serializable
data class PersonalRecord(
    val id: String = "",
    val userId: String = "",
    val exerciseId: String = "",
    val oneRepMaxEst: Double = 0.0,
    val maxWeightLb: Double = 0.0,
    val updatedAt: String = "",
    val exercise: Exercise? = null
)

@Serializable
data class OverloadSuggestion(
    val ready: Boolean = false,
    val recommendedBumpLb: Double = 0.0,
    val recommendedWeightLb: Double = 0.0,
    val lastWeightLb: Double? = null,
    val lastReps: Int? = null,
    val reason: String = ""
)

@Serializable
data class DemoPipResponse(
    val enabled: Boolean = true
)

@Serializable
data class MusicPlaybackState(
    val isPlaying: Boolean = false,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val artworkUrl: String? = null,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeDeviceId: String? = null,
    val volumePct: Int? = 70,
    val isDucked: Boolean = false,
    val isConnected: Boolean = false
)

@Serializable
data class MusicProviderCapabilities(
    val supportsConnectDevices: Boolean = false,
    val supportsVolumeDucking: Boolean = false,
    val supportsPlaylists: Boolean = false
)

@Serializable
data class MusicProviderInfo(
    val id: String,
    val displayName: String,
    val configured: Boolean = false,
    val capabilities: MusicProviderCapabilities = MusicProviderCapabilities()
)

@Serializable
data class MusicPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val artworkUrl: String? = null,
    val trackCount: Int = 0
)

@Serializable
data class MusicTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artistName: String = "",
    val albumName: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null
)

@Serializable
data class MusicDevice(
    val id: String,
    val name: String,
    val type: String = "Speaker",
    val isActive: Boolean = false,
    val volumePct: Int = 70
)

@Serializable
data class MusicFavoritesResponse(
    val playlistIds: List<String> = emptyList()
)

@Serializable
data class DaemonHealth(
    val status: String = "unknown",
    val state: String = "DISCONNECTED",
    val mode: String = "CUSTOM",
    @SerialName("weight_enabled") val weightEnabled: Boolean = false,
    @SerialName("target_weight_lb") val targetWeightLb: Double = 25.0,
    @SerialName("profile_mode") val profileMode: String = "standard",
    @SerialName("modifier_pct") val modifierPct: Int = 0,
    val accessory: String = "HANDLES",
    @SerialName("rep_count") val repCount: Int = 0,
    @SerialName("mock_mode") val mockMode: Boolean = false
)

@Serializable
data class CalibrationOffsets(
    val left: Double = 0.0,
    val right: Double = 0.0
)

@Serializable
data class CalibrationStatus(
    val state: String = "UNKNOWN",
    @SerialName("mock_mode") val mockMode: Boolean = false,
    val port: String = "",
    @SerialName("expected_board") val expectedBoard: String = "",
    @SerialName("expected_firmware") val expectedFirmware: String = "",
    @SerialName("zero_offsets") val zeroOffsets: CalibrationOffsets = CalibrationOffsets(),
    @SerialName("last_calibrated_at") val lastCalibratedAt: Double? = null,
    @SerialName("bar_tilt_threshold_delta") val barTiltThresholdDelta: Double = 35.0,
    @SerialName("bar_tilt_dwell_ms") val barTiltDwellMs: Double = 250.0
)

@Serializable
data class HomeAssistantEvent(
    val topic: String = "",
    val timestamp: String = "",
    val summary: String = ""
)

@Serializable
data class HomeAssistantStatus(
    val mqttConfigured: Boolean = false,
    val mqttConnected: Boolean = false,
    val mqttTopicPrefix: String = "swonal",
    val lastMqttError: String? = null,
    val webhookConfigured: Boolean = false,
    val webhookUrl: String? = null,
    val haUrl: String? = null,
    val haRestConfigured: Boolean = false,
    val recentEvents: List<JsonObject> = emptyList()
)

@Serializable
data class NetworkInfo(
    val primaryIp: String? = null,
    val ips: List<String> = emptyList(),
    val port: Int = 3000,
    val publicUrl: String? = null,
    val publicUrlOverride: String? = null,
    val companionBaseUrl: String? = null
)

@Serializable
data class AuthStatus(
    val enabled: Boolean = false,
    val authenticated: Boolean = true,
    val trustLocalhost: Boolean = true,
    val isLocalhostBypass: Boolean = false,
    val envLocked: Boolean = false
)

@Serializable
data class CoachProviderOption(
    val id: String,
    val label: String
)

@Serializable
data class CoachConfig(
    val provider: String = "local",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val keyHint: String? = null,
    val baseUrl: String? = null,
    val supportedProviders: List<CoachProviderOption> = emptyList()
)

@Serializable
data class CoachSuggestedAction(
    val type: String,
    val deltaLb: Double? = null,
    val label: String = ""
)

@Serializable
data class CoachAskResponse(
    val provider: String = "local",
    val model: String = "",
    val reply: String = "",
    val suggestedAction: CoachSuggestedAction? = null
)

@Serializable
data class SafetyEvent(
    val reason: String = "SAFETY_CUTOUT",
    val delta: Double? = null,
    val timestamp: Double = 0.0
)

@Serializable
data class HandleEvent(
    val action: String = "",
    val source: String = "ble",
    val timestamp: Double? = null
)

@Serializable
data class PrHit(
    val exerciseName: String,
    val oneRepMaxEst: Double,
    val weightLb: Double,
    val reps: Int
)

sealed interface WsInboundMessage {
    data object Pong : WsInboundMessage
    data class DaemonStatus(val connected: Boolean) : WsInboundMessage
    data class Telemetry(val frame: TelemetryFrame) : WsInboundMessage
    data class SessionStateUpdate(val state: SharedSessionState) : WsInboundMessage
    data class WorkoutQueued(val workout: QueuedWorkoutPayload) : WsInboundMessage
    data class Safety(val event: SafetyEvent) : WsInboundMessage
    data class Handle(val event: HandleEvent) : WsInboundMessage
    data class ServerError(val message: String) : WsInboundMessage
    data class SetLogged(val setLog: SetLog, val newPr: Boolean, val totalVolume: Double) : WsInboundMessage
    data class PartnerRotated(val userId: String, val targetWeightLb: Double, val exerciseId: String) : WsInboundMessage
    data class MusicUpdated(val userId: String, val provider: String) : WsInboundMessage
    data object UsersUpdated : WsInboundMessage
    data class UserUpdated(val user: User) : WsInboundMessage
    data object TemplatesUpdated : WsInboundMessage
    data object ExercisesUpdated : WsInboundMessage
    data class DemoPipUpdated(val userId: String, val enabled: Boolean) : WsInboundMessage
    data class Unknown(val type: String) : WsInboundMessage
}

object WsMessageParser {
    fun parse(rawText: String): WsInboundMessage? {
        val obj = runCatching { SwonalJson.parseToJsonElement(rawText).jsonObject }.getOrNull()
            ?: return null
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return runCatching {
            when (type) {
                "pong" -> WsInboundMessage.Pong
                "daemon_status" -> WsInboundMessage.DaemonStatus(
                    connected = obj["connected"]?.jsonPrimitive?.booleanOrNull ?: false
                )
                "telemetry" -> WsInboundMessage.Telemetry(
                    frame = SwonalJson.decodeFromJsonElement<TelemetryFrame>(obj)
                )
                "session_state" -> {
                    val stateEl = obj["state"] ?: return null
                    WsInboundMessage.SessionStateUpdate(
                        state = SwonalJson.decodeFromJsonElement<SharedSessionState>(stateEl)
                    )
                }
                "workout_queued" -> {
                    val workoutEl = obj["workout"] ?: return null
                    WsInboundMessage.WorkoutQueued(
                        workout = SwonalJson.decodeFromJsonElement<QueuedWorkoutPayload>(workoutEl)
                    )
                }
                "safety_event" -> WsInboundMessage.Safety(
                    event = SafetyEvent(
                        reason = obj["reason"]?.jsonPrimitive?.content ?: "SAFETY_CUTOUT",
                        delta = obj["delta"]?.jsonPrimitive?.doubleOrNull,
                        timestamp = obj["timestamp"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                )
                "handle_event" -> WsInboundMessage.Handle(
                    event = HandleEvent(
                        action = obj["action"]?.jsonPrimitive?.content ?: "",
                        source = obj["source"]?.jsonPrimitive?.content ?: "ble",
                        timestamp = obj["timestamp"]?.jsonPrimitive?.doubleOrNull
                    )
                )
                "error" -> WsInboundMessage.ServerError(
                    message = obj["message"]?.jsonPrimitive?.content ?: "Unknown daemon error"
                )
                "set_logged" -> {
                    val setLogEl = obj["setLog"] ?: return null
                    WsInboundMessage.SetLogged(
                        setLog = SwonalJson.decodeFromJsonElement<SetLog>(setLogEl),
                        newPr = obj["newPr"]?.jsonPrimitive?.booleanOrNull ?: false,
                        totalVolume = obj["totalVolume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
                "partner_rotated" -> WsInboundMessage.PartnerRotated(
                    userId = obj["userId"]?.jsonPrimitive?.content ?: "",
                    targetWeightLb = obj["targetWeightLb"]?.jsonPrimitive?.doubleOrNull ?: 25.0,
                    exerciseId = obj["exerciseId"]?.jsonPrimitive?.content ?: ""
                )
                "music_updated" -> WsInboundMessage.MusicUpdated(
                    userId = obj["userId"]?.jsonPrimitive?.content ?: "",
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "spotify"
                )
                "users_updated" -> WsInboundMessage.UsersUpdated
                "user_updated" -> {
                    val userEl = obj["user"] ?: return WsInboundMessage.UsersUpdated
                    WsInboundMessage.UserUpdated(
                        user = SwonalJson.decodeFromJsonElement<User>(userEl)
                    )
                }
                "templates_updated" -> WsInboundMessage.TemplatesUpdated
                "exercises_updated" -> WsInboundMessage.ExercisesUpdated
                "demo_pip_updated" -> WsInboundMessage.DemoPipUpdated(
                    userId = obj["userId"]?.jsonPrimitive?.content ?: "",
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                else -> WsInboundMessage.Unknown(type)
            }
        }.getOrNull()
    }
}
