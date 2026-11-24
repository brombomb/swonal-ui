package com.swonal.kiosk.db

import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.QueuedWorkoutPayload
import com.swonal.kiosk.model.SetLog
import com.swonal.kiosk.model.User
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.model.WorkoutTemplate
import kotlinx.serialization.Serializable

@Serializable
data class SeedUserDto(
    val id: String,
    val name: String,
    val avatarColor: String = "#3B82F6",
    val accentColor: String = "#00F0FF",
    val preferredMusicProvider: String = "spotify",
    val themeMode: String = "dark",
    val weightUnit: String = "LB"
)

@Serializable
data class SeedTemplateDto(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val scheduleDays: List<Int> = emptyList(),
    val blocks: List<WorkoutBlock> = emptyList()
)

@Serializable
data class SeedPersonalRecordDto(
    val id: String,
    val userId: String,
    val exerciseId: String,
    val maxWeightLb: Double,
    val oneRepMaxEst: Double
)

@Serializable
data class SeedCatalogDto(
    val exercises: List<Exercise> = emptyList(),
    val users: List<SeedUserDto> = emptyList(),
    val templates: List<SeedTemplateDto> = emptyList(),
    val personalRecords: List<SeedPersonalRecordDto> = emptyList()
)

@Serializable
data class StoredUserSyncRecord(
    val id: String,
    val name: String,
    val avatarColor: String,
    val preferredMusicProvider: String,
    val themeMode: String,
    val accentColor: String,
    val weightUnit: String,
    val nostrPubKeyHex: String? = null,
    val nostrNpub: String? = null,
    val nostrRelays: String? = null,
    val shareWorkouts: Boolean = true,
    val demoPipEnabled: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null
) {
    fun toPublicUser(): User = User(
        id = id,
        name = name,
        avatarColor = avatarColor,
        preferredMusicProvider = preferredMusicProvider,
        themeMode = themeMode,
        accentColor = accentColor,
        weightUnit = weightUnit,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

@Serializable
data class StoredExerciseSyncRecord(
    val id: String,
    val name: String,
    val muscleGroup: String,
    val accessory: String = "HANDLES",
    val recommendedHeight: Int = 4,
    val recommendedRotation: Int = 2,
    val recommendedAngle: Int = 1,
    val unilateral: Boolean = false,
    val notes: String? = null,
    val mediaUrl: String? = null,
    val updatedAtMs: Long = 0L,
    val deletedAtMs: Long? = null
) {
    fun toPublicExercise(): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = muscleGroup,
        accessory = accessory,
        recommendedHeight = recommendedHeight,
        recommendedRotation = recommendedRotation,
        recommendedAngle = recommendedAngle,
        unilateral = unilateral,
        notes = notes,
        mediaUrl = mediaUrl
    )
}

@Serializable
data class StoredTemplateSyncRecord(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val blocksJson: String = "[]",
    val scheduleDays: List<Int> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val updatedAtMs: Long = 0L,
    val deletedAtMs: Long? = null
)

@Serializable
data class StoredSessionSyncRecord(
    val id: String,
    val userId: String,
    val templateId: String? = null,
    val startedAt: String = "",
    val completedAt: String? = null,
    val totalVolume: Double = 0.0,
    val updatedAtMs: Long = 0L
)

@Serializable
data class StoredSetLogSyncRecord(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val setNumber: Int = 1,
    val mode: String = "standard",
    val modifierPct: Int = 0,
    val weightLb: Double = 0.0,
    val repsCompleted: Int = 0,
    val volumeLb: Double = 0.0,
    val rpe: Double? = null,
    val timestamp: String = "",
    val updatedAtMs: Long = 0L
)

@Serializable
data class StoredPrSyncRecord(
    val id: String,
    val userId: String,
    val exerciseId: String,
    val oneRepMaxEst: Double = 0.0,
    val maxWeightLb: Double = 0.0,
    val updatedAt: String = "",
    val updatedAtMs: Long = 0L
)

@Serializable
data class StoredFriendRecord(
    val id: String,
    val userId: String,
    val npub: String,
    val pubkeyHex: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relaysJson: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val updatedAtMs: Long = 0L,
    val deletedAtMs: Long? = null
)

data class StoredNostrCredentials(
    val userId: String,
    val userName: String,
    val privKeyHex: String?,
    val pubKeyHex: String?,
    val npub: String?,
    val relays: List<String>,
    val shareWorkouts: Boolean
)

data class StoredMusicIntegration(
    val id: String,
    val userId: String,
    val provider: String,
    val accessToken: String?,
    val refreshToken: String?,
    val tokenExpiry: Int?,
    val preferredDeviceId: String?,
    val metadataJson: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StoredFriendActivityRecord(
    val id: String,
    val friendPubkeyHex: String,
    val friendDisplayName: String? = null,
    val eventId: String,
    val kind: Int,
    val createdAt: String,
    val contentJson: String,
    val receivedAt: String
)

@Serializable
data class SyncPushRequest(
    val deviceId: String = "tonal-kiosk",
    val pushedAtMs: Long = 0L,
    val users: List<StoredUserSyncRecord> = emptyList(),
    val exercises: List<StoredExerciseSyncRecord> = emptyList(),
    val templates: List<StoredTemplateSyncRecord> = emptyList(),
    val sessions: List<StoredSessionSyncRecord> = emptyList(),
    val setLogs: List<StoredSetLogSyncRecord> = emptyList(),
    val personalRecords: List<StoredPrSyncRecord> = emptyList(),
    val friends: List<StoredFriendRecord> = emptyList()
)

@Serializable
data class SyncPushResponse(
    val ok: Boolean = true,
    val serverTimeMs: Long = 0L,
    val exerciseIdRemap: Map<String, String> = emptyMap(),
    val acceptedCounts: Map<String, Int> = emptyMap()
)

@Serializable
data class SyncPullResponse(
    val serverTimeMs: Long = 0L,
    val users: List<StoredUserSyncRecord> = emptyList(),
    val exercises: List<StoredExerciseSyncRecord> = emptyList(),
    val templates: List<StoredTemplateSyncRecord> = emptyList(),
    val sessions: List<StoredSessionSyncRecord> = emptyList(),
    val setLogs: List<StoredSetLogSyncRecord> = emptyList(),
    val personalRecords: List<StoredPrSyncRecord> = emptyList(),
    val friends: List<StoredFriendRecord> = emptyList(),
    val pendingQueuedWorkout: QueuedWorkoutPayload? = null
)

@Serializable
data class RestoreBundleDto(
    val version: Int = 1,
    val exportedAt: String = "",
    val users: List<StoredUserSyncRecord> = emptyList(),
    val exercises: List<StoredExerciseSyncRecord> = emptyList(),
    val templates: List<StoredTemplateSyncRecord> = emptyList(),
    val sessions: List<StoredSessionSyncRecord> = emptyList(),
    val setLogs: List<StoredSetLogSyncRecord> = emptyList(),
    val personalRecords: List<StoredPrSyncRecord> = emptyList(),
    val friends: List<StoredFriendRecord> = emptyList()
)
