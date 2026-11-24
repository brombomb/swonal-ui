package com.swonal.kiosk.db

import androidx.sqlite.SQLiteConnection
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.util.IsoTime
import kotlinx.serialization.encodeToString
import java.util.UUID

// --- Music Integrations ---

suspend fun SwonalDatabase.getMusicIntegration(userId: String, provider: String): StoredMusicIntegration? = withDb { c ->
    getMusicIntegrationUnlocked(c, userId, provider)
}

suspend fun SwonalDatabase.getAnyMusicIntegrationWithToken(provider: String): StoredMusicIntegration? = withDb { c ->
    c.prepare(
        """
        SELECT id, user_id, provider, access_token_enc, refresh_token_enc, token_expiry, preferred_device_id, metadata_json, created_at, updated_at
        FROM music_integrations
        WHERE provider = ? AND access_token_enc IS NOT NULL AND access_token_enc != ''
        ORDER BY updated_at_ms DESC LIMIT 1
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, provider)
        if (s.step()) readMusicIntegrationRow(s) else null
    }
}

private fun SwonalDatabase.getMusicIntegrationUnlocked(
    c: SQLiteConnection,
    userId: String,
    provider: String
): StoredMusicIntegration? {
    return c.prepare(
        """
        SELECT id, user_id, provider, access_token_enc, refresh_token_enc, token_expiry, preferred_device_id, metadata_json, created_at, updated_at
        FROM music_integrations WHERE user_id = ? AND provider = ?
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, userId)
        s.bindText(2, provider)
        if (s.step()) readMusicIntegrationRow(s) else null
    }
}

private fun SwonalDatabase.readMusicIntegrationRow(s: androidx.sqlite.SQLiteStatement): StoredMusicIntegration {
    val accessEnc = getNullableText(s, 3)
    val refreshEnc = getNullableText(s, 4)
    return StoredMusicIntegration(
        id = s.getText(0),
        userId = s.getText(1),
        provider = s.getText(2),
        accessToken = accessEnc?.let { cipher.decrypt(it) },
        refreshToken = refreshEnc?.let { cipher.decrypt(it) },
        tokenExpiry = if (s.isNull(5)) null else s.getInt(5),
        preferredDeviceId = getNullableText(s, 6),
        metadataJson = getNullableText(s, 7),
        createdAt = s.getText(8),
        updatedAt = s.getText(9)
    )
}

suspend fun SwonalDatabase.upsertMusicIntegration(
    userId: String,
    provider: String,
    accessToken: String? = null,
    refreshToken: String? = null,
    tokenExpiry: Int? = null,
    preferredDeviceId: String? = null,
    metadataJson: String? = null,
    preserveExistingTokensWhenNull: Boolean = true
): StoredMusicIntegration = withTx { c ->
    val existing = getMusicIntegrationUnlocked(c, userId, provider)
    val nowMs = System.currentTimeMillis()
    val nowIso = IsoTime.formatUtc(nowMs)
    val id = existing?.id ?: UUID.randomUUID().toString()
    val createdAt = existing?.createdAt ?: nowIso

    val finalAccess = accessToken ?: if (preserveExistingTokensWhenNull) existing?.accessToken else null
    val finalRefresh = refreshToken ?: if (preserveExistingTokensWhenNull) existing?.refreshToken else null
    val finalExpiry = tokenExpiry ?: if (preserveExistingTokensWhenNull) existing?.tokenExpiry else null
    val finalDevice = preferredDeviceId ?: existing?.preferredDeviceId
    val finalMeta = metadataJson ?: existing?.metadataJson

    val accessEnc = finalAccess?.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
    val refreshEnc = finalRefresh?.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }

    c.prepare(
        """
        INSERT INTO music_integrations(
            id, user_id, provider, access_token_enc, refresh_token_enc, token_expiry,
            preferred_device_id, metadata_json, created_at, updated_at, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, provider) DO UPDATE SET
            access_token_enc = excluded.access_token_enc,
            refresh_token_enc = excluded.refresh_token_enc,
            token_expiry = excluded.token_expiry,
            preferred_device_id = excluded.preferred_device_id,
            metadata_json = excluded.metadata_json,
            updated_at = excluded.updated_at,
            updated_at_ms = excluded.updated_at_ms
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, id)
        s.bindText(2, userId)
        s.bindText(3, provider)
        bindNullableText(s, 4, accessEnc)
        bindNullableText(s, 5, refreshEnc)
        if (finalExpiry != null) s.bindInt(6, finalExpiry) else s.bindNull(6)
        bindNullableText(s, 7, finalDevice)
        bindNullableText(s, 8, finalMeta)
        s.bindText(9, createdAt)
        s.bindText(10, nowIso)
        s.bindLong(11, nowMs)
        s.step()
    }
    StoredMusicIntegration(
        id, userId, provider, finalAccess, finalRefresh, finalExpiry, finalDevice, finalMeta, createdAt, nowIso
    )
}

// --- Nostr Swocial Credentials, Friends & Feed Cache ---

suspend fun SwonalDatabase.getNostrCredentials(userId: String): StoredNostrCredentials? = withDb { c ->
    c.prepare(
        """
        SELECT id, name, nostr_priv_key_enc, nostr_pub_key_hex, nostr_npub, nostr_relays_json, share_workouts
        FROM users WHERE id = ? AND deleted_at_ms IS NULL
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, userId)
        if (!s.step()) return@useStmt null
        val privEnc = getNullableText(s, 2)
        val relaysRaw = getNullableText(s, 5)
        val relays = relaysRaw?.let {
            runCatching { SwonalJson.decodeFromString<List<String>>(it) }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: emptyList()
        StoredNostrCredentials(
            userId = s.getText(0),
            userName = s.getText(1),
            privKeyHex = privEnc?.let { cipher.decrypt(it) },
            pubKeyHex = getNullableText(s, 3),
            npub = getNullableText(s, 4),
            relays = relays,
            shareWorkouts = s.getInt(6) != 0
        )
    }
}

suspend fun SwonalDatabase.saveNostrIdentity(
    userId: String,
    privKeyHex: String?,
    pubKeyHex: String?,
    npub: String?,
    relays: List<String>,
    shareWorkouts: Boolean? = null
) = withTx { c ->
    val nowMs = System.currentTimeMillis()
    val nowIso = IsoTime.formatUtc(nowMs)
    val privEnc = privKeyHex?.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
    val relaysJson = SwonalJson.encodeToString(relays)
    c.prepare(
        """
        UPDATE users SET
            nostr_priv_key_enc = COALESCE(?, nostr_priv_key_enc),
            nostr_pub_key_hex = COALESCE(?, nostr_pub_key_hex),
            nostr_npub = COALESCE(?, nostr_npub),
            nostr_relays_json = ?,
            share_workouts = COALESCE(?, share_workouts),
            updated_at = ?,
            updated_at_ms = ?,
            dirty_sync = 1
        WHERE id = ?
        """.trimIndent()
    ).useStmt { s ->
        bindNullableText(s, 1, privEnc)
        bindNullableText(s, 2, pubKeyHex)
        bindNullableText(s, 3, npub)
        s.bindText(4, relaysJson)
        if (shareWorkouts != null) s.bindInt(5, if (shareWorkouts) 1 else 0) else s.bindNull(5)
        s.bindText(6, nowIso)
        s.bindLong(7, nowMs)
        s.bindText(8, userId)
        s.step()
    }
}

suspend fun SwonalDatabase.listFriends(userId: String): List<StoredFriendRecord> = withDb { c ->
    val out = mutableListOf<StoredFriendRecord>()
    c.prepare(
        """
        SELECT id, user_id, npub, pubkey_hex, display_name, avatar_url, relays_json, last_seen_at, created_at, updated_at, updated_at_ms, deleted_at_ms
        FROM friends WHERE user_id = ? AND deleted_at_ms IS NULL ORDER BY display_name ASC
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, userId)
        while (s.step()) {
            out += StoredFriendRecord(
                id = s.getText(0),
                userId = s.getText(1),
                npub = s.getText(2),
                pubkeyHex = s.getText(3),
                displayName = s.getText(4),
                avatarUrl = getNullableText(s, 5),
                relaysJson = getNullableText(s, 6),
                lastSeenAt = getNullableText(s, 7),
                createdAt = s.getText(8),
                updatedAt = s.getText(9),
                updatedAtMs = s.getLong(10),
                deletedAtMs = getNullableLong(s, 11)
            )
        }
    }
    out
}

suspend fun SwonalDatabase.upsertFriend(
    userId: String,
    npub: String,
    pubkeyHex: String,
    displayName: String,
    relaysJson: String
): StoredFriendRecord = withTx { c ->
    val nowMs = System.currentTimeMillis()
    val nowIso = IsoTime.formatUtc(nowMs)
    val id = c.prepare("SELECT id, created_at FROM friends WHERE user_id = ? AND pubkey_hex = ?").useStmt { s ->
        s.bindText(1, userId)
        s.bindText(2, pubkeyHex)
        if (s.step()) s.getText(0) to s.getText(1) else (UUID.randomUUID().toString() to nowIso)
    }
    c.prepare(
        """
        INSERT INTO friends(
            id, user_id, npub, pubkey_hex, display_name, relays_json, created_at, updated_at, updated_at_ms, deleted_at_ms, dirty_sync
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1)
        ON CONFLICT(user_id, pubkey_hex) DO UPDATE SET
            npub = excluded.npub,
            display_name = excluded.display_name,
            relays_json = excluded.relays_json,
            updated_at = excluded.updated_at,
            updated_at_ms = excluded.updated_at_ms,
            deleted_at_ms = NULL,
            dirty_sync = 1
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, id.first)
        s.bindText(2, userId)
        s.bindText(3, npub)
        s.bindText(4, pubkeyHex)
        s.bindText(5, displayName)
        s.bindText(6, relaysJson)
        s.bindText(7, id.second)
        s.bindText(8, nowIso)
        s.bindLong(9, nowMs)
        s.step()
    }
    StoredFriendRecord(id.first, userId, npub, pubkeyHex, displayName, null, relaysJson, null, id.second, nowIso, nowMs, null)
}

suspend fun SwonalDatabase.deleteFriend(userId: String, friendId: String): Boolean = withTx { c ->
    val nowMs = System.currentTimeMillis()
    c.prepare("UPDATE friends SET deleted_at_ms = ?, updated_at_ms = ?, dirty_sync = 1 WHERE user_id = ? AND id = ?")
        .useStmt { s ->
            s.bindLong(1, nowMs)
            s.bindLong(2, nowMs)
            s.bindText(3, userId)
            s.bindText(4, friendId)
            s.step()
        }
    true
}

suspend fun SwonalDatabase.upsertFriendActivity(
    friendPubkeyHex: String,
    friendDisplayName: String?,
    eventId: String,
    kind: Int,
    createdAtIso: String,
    contentJson: String
) = withTx { c ->
    val nowIso = IsoTime.nowIso()
    val id = UUID.randomUUID().toString()
    c.prepare(
        """
        INSERT INTO friend_activities(id, friend_pubkey_hex, friend_display_name, event_id, kind, created_at, content_json, received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(event_id) DO UPDATE SET
            friend_display_name = COALESCE(excluded.friend_display_name, friend_activities.friend_display_name),
            content_json = excluded.content_json
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, id)
        s.bindText(2, friendPubkeyHex)
        bindNullableText(s, 3, friendDisplayName)
        s.bindText(4, eventId)
        s.bindInt(5, kind)
        s.bindText(6, createdAtIso)
        s.bindText(7, contentJson)
        s.bindText(8, nowIso)
        s.step()
    }
}

suspend fun SwonalDatabase.listFriendActivities(pubkeys: List<String>, limit: Int = 50): List<StoredFriendActivityRecord> = withDb { c ->
    if (pubkeys.isEmpty()) return@withDb emptyList()
    val placeholders = pubkeys.joinToString(",") { "?" }
    val sql = """
        SELECT id, friend_pubkey_hex, friend_display_name, event_id, kind, created_at, content_json, received_at
        FROM friend_activities
        WHERE friend_pubkey_hex IN ($placeholders)
        ORDER BY created_at DESC LIMIT ?
    """.trimIndent()
    val out = mutableListOf<StoredFriendActivityRecord>()
    c.prepare(sql).useStmt { s ->
        pubkeys.forEachIndexed { idx, pk -> s.bindText(idx + 1, pk) }
        s.bindInt(pubkeys.size + 1, limit.coerceIn(1, 200))
        while (s.step()) {
            out += StoredFriendActivityRecord(
                id = s.getText(0),
                friendPubkeyHex = s.getText(1),
                friendDisplayName = getNullableText(s, 2),
                eventId = s.getText(3),
                kind = s.getInt(4),
                createdAt = s.getText(5),
                contentJson = s.getText(6),
                receivedAt = s.getText(7)
            )
        }
    }
    out
}

suspend fun SwonalDatabase.getFriendActivityByEventId(eventId: String): StoredFriendActivityRecord? = withDb { c ->
    c.prepare(
        "SELECT id, friend_pubkey_hex, friend_display_name, event_id, kind, created_at, content_json, received_at FROM friend_activities WHERE event_id = ?"
    ).useStmt { s ->
        s.bindText(1, eventId)
        if (s.step()) {
            StoredFriendActivityRecord(
                id = s.getText(0),
                friendPubkeyHex = s.getText(1),
                friendDisplayName = getNullableText(s, 2),
                eventId = s.getText(3),
                kind = s.getInt(4),
                createdAt = s.getText(5),
                contentJson = s.getText(6),
                receivedAt = s.getText(7)
            )
        } else null
    }
}

suspend fun SwonalDatabase.updateFriendLastSeen(userId: String, pubkeyHex: String, iso: String) = withTx { c ->
    c.prepare("UPDATE friends SET last_seen_at = ? WHERE user_id = ? AND pubkey_hex = ?").useStmt { s ->
        s.bindText(1, iso)
        s.bindText(2, userId)
        s.bindText(3, pubkeyHex)
        s.step()
    }
}

// --- Sync Outbox Collection, Merge & Restore ---

suspend fun SwonalDatabase.countDirtySyncRows(): Int = withDb { c ->
    val tables = listOf("users", "exercises", "workout_templates", "workout_sessions", "set_logs", "personal_records", "friends")
    var total = 0L
    for (t in tables) {
        total += queryLongUnlocked(c, "SELECT COUNT(*) FROM $t WHERE dirty_sync = 1")
    }
    total.toInt()
}

suspend fun SwonalDatabase.collectSyncPushRequest(deviceId: String = "tonal-kiosk"): SyncPushRequest = withDb { c ->
    val users = mutableListOf<StoredUserSyncRecord>()
    c.prepare(
        "SELECT id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit, nostr_pub_key_hex, nostr_npub, nostr_relays_json, share_workouts, demo_pip_enabled, created_at, updated_at, updated_at_ms, deleted_at_ms FROM users WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            users += StoredUserSyncRecord(
                id = s.getText(0), name = s.getText(1), avatarColor = s.getText(2),
                preferredMusicProvider = s.getText(3), themeMode = s.getText(4), accentColor = s.getText(5),
                weightUnit = s.getText(6), nostrPubKeyHex = getNullableText(s, 7), nostrNpub = getNullableText(s, 8),
                nostrRelays = getNullableText(s, 9), shareWorkouts = s.getInt(10) != 0,
                demoPipEnabled = s.getInt(11) != 0, createdAt = s.getText(12), updatedAt = s.getText(13),
                updatedAtMs = s.getLong(14), deletedAtMs = getNullableLong(s, 15)
            )
        }
    }

    val exercises = mutableListOf<StoredExerciseSyncRecord>()
    c.prepare(
        "SELECT id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, updated_at_ms, deleted_at_ms FROM exercises WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            exercises += StoredExerciseSyncRecord(
                id = s.getText(0), name = s.getText(1), muscleGroup = s.getText(2),
                accessory = s.getText(3), recommendedHeight = s.getInt(4), recommendedRotation = s.getInt(5),
                recommendedAngle = s.getInt(6), unilateral = s.getInt(7) != 0,
                notes = getNullableText(s, 8), mediaUrl = getNullableText(s, 9),
                updatedAtMs = s.getLong(10), deletedAtMs = getNullableLong(s, 11)
            )
        }
    }

    val templates = mutableListOf<StoredTemplateSyncRecord>()
    c.prepare(
        "SELECT id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at, updated_at_ms, deleted_at_ms FROM workout_templates WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            val days = getNullableText(s, 5)?.let {
                runCatching { SwonalJson.decodeFromString<List<Int>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            templates += StoredTemplateSyncRecord(
                id = s.getText(0), userId = s.getText(1), name = s.getText(2),
                description = getNullableText(s, 3), blocksJson = s.getText(4), scheduleDays = days,
                createdAt = s.getText(6), updatedAt = s.getText(7),
                updatedAtMs = s.getLong(8), deletedAtMs = getNullableLong(s, 9)
            )
        }
    }

    val sessions = mutableListOf<StoredSessionSyncRecord>()
    c.prepare(
        "SELECT id, user_id, template_id, started_at, completed_at, total_volume, updated_at_ms FROM workout_sessions WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            sessions += StoredSessionSyncRecord(
                id = s.getText(0), userId = s.getText(1), templateId = getNullableText(s, 2),
                startedAt = s.getText(3), completedAt = getNullableText(s, 4),
                totalVolume = s.getDouble(5), updatedAtMs = s.getLong(6)
            )
        }
    }

    val setLogs = mutableListOf<StoredSetLogSyncRecord>()
    c.prepare(
        "SELECT id, session_id, exercise_id, set_number, mode, modifier_pct, weight_lb, reps_completed, volume_lb, rpe, timestamp, updated_at_ms FROM set_logs WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            setLogs += StoredSetLogSyncRecord(
                id = s.getText(0), sessionId = s.getText(1), exerciseId = s.getText(2),
                setNumber = s.getInt(3), mode = s.getText(4), modifierPct = s.getInt(5),
                weightLb = s.getDouble(6), repsCompleted = s.getInt(7), volumeLb = s.getDouble(8),
                rpe = getNullableDouble(s, 9), timestamp = s.getText(10), updatedAtMs = s.getLong(11)
            )
        }
    }

    val prs = mutableListOf<StoredPrSyncRecord>()
    c.prepare(
        "SELECT id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at, updated_at_ms FROM personal_records WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            prs += StoredPrSyncRecord(
                id = s.getText(0), userId = s.getText(1), exerciseId = s.getText(2),
                oneRepMaxEst = s.getDouble(3), maxWeightLb = s.getDouble(4),
                updatedAt = s.getText(5), updatedAtMs = s.getLong(6)
            )
        }
    }

    val friends = mutableListOf<StoredFriendRecord>()
    c.prepare(
        "SELECT id, user_id, npub, pubkey_hex, display_name, avatar_url, relays_json, last_seen_at, created_at, updated_at, updated_at_ms, deleted_at_ms FROM friends WHERE dirty_sync = 1"
    ).useStmt { s ->
        while (s.step()) {
            friends += StoredFriendRecord(
                id = s.getText(0), userId = s.getText(1), npub = s.getText(2), pubkeyHex = s.getText(3),
                displayName = s.getText(4), avatarUrl = getNullableText(s, 5), relaysJson = getNullableText(s, 6),
                lastSeenAt = getNullableText(s, 7), createdAt = s.getText(8), updatedAt = s.getText(9),
                updatedAtMs = s.getLong(10), deletedAtMs = getNullableLong(s, 11)
            )
        }
    }

    SyncPushRequest(
        deviceId = deviceId,
        pushedAtMs = System.currentTimeMillis(),
        users = users,
        exercises = exercises,
        templates = templates,
        sessions = sessions,
        setLogs = setLogs,
        personalRecords = prs,
        friends = friends
    )
}

suspend fun SwonalDatabase.markPushedClean(
    pushed: SyncPushRequest,
    exerciseIdRemap: Map<String, String> = emptyMap()
) = withTx { c ->
    // Remap any local exercise UUIDs that matched a pre-existing canonical server Exercise by name
    for ((oldId, newId) in exerciseIdRemap) {
        if (oldId != newId) {
            remapExerciseIdUnlocked(c, oldId, newId)
        }
    }
    fun clearDirty(table: String, ids: List<String>, updatedMsById: Map<String, Long>) {
        for (id in ids) {
            val ts = updatedMsById[id] ?: Long.MAX_VALUE
            c.prepare("UPDATE $table SET dirty_sync = 0 WHERE id = ? AND updated_at_ms <= ?").useStmt { s ->
                s.bindText(1, id)
                s.bindLong(2, ts)
                s.step()
            }
        }
    }
    clearDirty("users", pushed.users.map { it.id }, pushed.users.associate { it.id to it.updatedAtMs })
    clearDirty("exercises", pushed.exercises.map { exerciseIdRemap[it.id] ?: it.id }, pushed.exercises.associate { (exerciseIdRemap[it.id] ?: it.id) to it.updatedAtMs })
    clearDirty("workout_templates", pushed.templates.map { it.id }, pushed.templates.associate { it.id to it.updatedAtMs })
    clearDirty("workout_sessions", pushed.sessions.map { it.id }, pushed.sessions.associate { it.id to it.updatedAtMs })
    clearDirty("set_logs", pushed.setLogs.map { it.id }, pushed.setLogs.associate { it.id to it.updatedAtMs })
    clearDirty("personal_records", pushed.personalRecords.map { it.id }, pushed.personalRecords.associate { it.id to it.updatedAtMs })
    clearDirty("friends", pushed.friends.map { it.id }, pushed.friends.associate { it.id to it.updatedAtMs })
}

private fun SwonalDatabase.remapExerciseIdUnlocked(c: SQLiteConnection, oldId: String, newId: String) {
    val existingOld = c.prepare("SELECT name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, updated_at_ms FROM exercises WHERE id = ?").useStmt { s ->
        s.bindText(1, oldId)
        if (s.step()) {
            StoredExerciseSyncRecord(
                id = oldId, name = s.getText(0), muscleGroup = s.getText(1), accessory = s.getText(2),
                recommendedHeight = s.getInt(3), recommendedRotation = s.getInt(4), recommendedAngle = s.getInt(5),
                unilateral = s.getInt(6) != 0, notes = getNullableText(s, 7), mediaUrl = getNullableText(s, 8),
                updatedAtMs = s.getLong(9)
            )
        } else null
    } ?: return

    // Update blocks inside templates
    val templatesToFix = mutableListOf<Pair<String, String>>()
    c.prepare("SELECT id, blocks_json FROM workout_templates WHERE blocks_json LIKE ?").useStmt { s ->
        s.bindText(1, "%$oldId%")
        while (s.step()) {
            templatesToFix += s.getText(0) to s.getText(1)
        }
    }
    for ((tplId, blocksJson) in templatesToFix) {
        val replaced = blocksJson.replace(oldId, newId)
        c.prepare("UPDATE workout_templates SET blocks_json = ? WHERE id = ?").useStmt { s ->
            s.bindText(1, replaced)
            s.bindText(2, tplId)
            s.step()
        }
    }

    // Temporarily rename old row to avoid UNIQUE(name) conflict when inserting canonical server ID
    c.prepare("UPDATE exercises SET name = ? WHERE id = ?").useStmt { s ->
        s.bindText(1, "${existingOld.name}__legacy_$oldId")
        s.bindText(2, oldId)
        s.step()
    }
    c.prepare(
        """
        INSERT OR IGNORE INTO exercises(
            id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle,
            unilateral, notes, media_url, updated_at_ms, dirty_sync
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """.trimIndent()
    ).useStmt { s ->
        s.bindText(1, newId)
        s.bindText(2, existingOld.name)
        s.bindText(3, existingOld.muscleGroup)
        s.bindText(4, existingOld.accessory)
        s.bindInt(5, existingOld.recommendedHeight)
        s.bindInt(6, existingOld.recommendedRotation)
        s.bindInt(7, existingOld.recommendedAngle)
        s.bindInt(8, if (existingOld.unilateral) 1 else 0)
        bindNullableText(s, 9, existingOld.notes)
        bindNullableText(s, 10, existingOld.mediaUrl)
        s.bindLong(11, existingOld.updatedAtMs)
        s.step()
    }
    c.prepare("UPDATE set_logs SET exercise_id = ? WHERE exercise_id = ?").useStmt { s ->
        s.bindText(1, newId)
        s.bindText(2, oldId)
        s.step()
    }
    c.prepare("DELETE FROM personal_records WHERE exercise_id = ? AND user_id IN (SELECT user_id FROM personal_records WHERE exercise_id = ?)").useStmt { s ->
        s.bindText(1, oldId)
        s.bindText(2, newId)
        s.step()
    }
    c.prepare("UPDATE personal_records SET exercise_id = ? WHERE exercise_id = ?").useStmt { s ->
        s.bindText(1, newId)
        s.bindText(2, oldId)
        s.step()
    }
    c.prepare("DELETE FROM exercises WHERE id = ?").useStmt { s ->
        s.bindText(1, oldId)
        s.step()
    }
}

suspend fun SwonalDatabase.applyPulledSync(pull: SyncPullResponse) = withTx { c ->
    // 1. Exercises (match by name first so server canonical UUIDs win cleanly)
    for (ex in pull.exercises) {
        val existingIdByName = c.prepare("SELECT id FROM exercises WHERE name = ?").useStmt { s ->
            s.bindText(1, ex.name)
            if (s.step()) s.getText(0) else null
        }
        if (existingIdByName != null && existingIdByName != ex.id) {
            remapExerciseIdUnlocked(c, existingIdByName, ex.id)
        }
        c.prepare(
            """
            INSERT INTO exercises(
                id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle,
                unilateral, notes, media_url, updated_at_ms, deleted_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                name = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.name ELSE exercises.name END,
                muscle_group = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.muscle_group ELSE exercises.muscle_group END,
                accessory = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.accessory ELSE exercises.accessory END,
                recommended_height = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.recommended_height ELSE exercises.recommended_height END,
                recommended_rotation = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.recommended_rotation ELSE exercises.recommended_rotation END,
                recommended_angle = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.recommended_angle ELSE exercises.recommended_angle END,
                unilateral = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.unilateral ELSE exercises.unilateral END,
                notes = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.notes ELSE exercises.notes END,
                media_url = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.media_url ELSE exercises.media_url END,
                deleted_at_ms = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN excluded.deleted_at_ms ELSE exercises.deleted_at_ms END,
                updated_at_ms = MAX(exercises.updated_at_ms, excluded.updated_at_ms),
                dirty_sync = CASE WHEN excluded.updated_at_ms >= exercises.updated_at_ms THEN 0 ELSE exercises.dirty_sync END
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, ex.id)
            s.bindText(2, ex.name)
            s.bindText(3, ex.muscleGroup)
            s.bindText(4, ex.accessory)
            s.bindInt(5, ex.recommendedHeight)
            s.bindInt(6, ex.recommendedRotation)
            s.bindInt(7, ex.recommendedAngle)
            s.bindInt(8, if (ex.unilateral) 1 else 0)
            bindNullableText(s, 9, ex.notes)
            bindNullableText(s, 10, ex.mediaUrl)
            s.bindLong(11, ex.updatedAtMs)
            bindNullableLong(s, 12, ex.deletedAtMs)
            s.step()
        }
    }

    // 2. Users (LWW by updatedAtMs)
    for (u in pull.users) {
        c.prepare(
            """
            INSERT INTO users(
                id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit,
                nostr_pub_key_hex, nostr_npub, nostr_relays_json, share_workouts, demo_pip_enabled,
                created_at, updated_at, updated_at_ms, deleted_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                name = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.name ELSE users.name END,
                avatar_color = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.avatar_color ELSE users.avatar_color END,
                preferred_music_provider = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.preferred_music_provider ELSE users.preferred_music_provider END,
                theme_mode = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.theme_mode ELSE users.theme_mode END,
                accent_color = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.accent_color ELSE users.accent_color END,
                weight_unit = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.weight_unit ELSE users.weight_unit END,
                share_workouts = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.share_workouts ELSE users.share_workouts END,
                demo_pip_enabled = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.demo_pip_enabled ELSE users.demo_pip_enabled END,
                updated_at = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.updated_at ELSE users.updated_at END,
                deleted_at_ms = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN excluded.deleted_at_ms ELSE users.deleted_at_ms END,
                updated_at_ms = MAX(users.updated_at_ms, excluded.updated_at_ms),
                dirty_sync = CASE WHEN excluded.updated_at_ms >= users.updated_at_ms THEN 0 ELSE users.dirty_sync END
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, u.id); s.bindText(2, u.name); s.bindText(3, u.avatarColor)
            s.bindText(4, u.preferredMusicProvider); s.bindText(5, u.themeMode); s.bindText(6, u.accentColor)
            s.bindText(7, u.weightUnit); bindNullableText(s, 8, u.nostrPubKeyHex)
            bindNullableText(s, 9, u.nostrNpub); bindNullableText(s, 10, u.nostrRelays)
            s.bindInt(11, if (u.shareWorkouts) 1 else 0); s.bindInt(12, if (u.demoPipEnabled) 1 else 0)
            s.bindText(13, u.createdAt.ifBlank { IsoTime.nowIso() })
            s.bindText(14, u.updatedAt.ifBlank { IsoTime.nowIso() })
            s.bindLong(15, u.updatedAtMs); bindNullableLong(s, 16, u.deletedAtMs)
            s.step()
        }
    }

    // 3. Templates (LWW by updatedAtMs)
    for (t in pull.templates) {
        val daysJson = SwonalJson.encodeToString(t.scheduleDays.filter { it in 0..6 }.distinct().sorted())
        c.prepare(
            """
            INSERT INTO workout_templates(
                id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at, updated_at_ms, deleted_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                name = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.name ELSE workout_templates.name END,
                description = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.description ELSE workout_templates.description END,
                blocks_json = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.blocks_json ELSE workout_templates.blocks_json END,
                schedule_days_json = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.schedule_days_json ELSE workout_templates.schedule_days_json END,
                updated_at = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.updated_at ELSE workout_templates.updated_at END,
                deleted_at_ms = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN excluded.deleted_at_ms ELSE workout_templates.deleted_at_ms END,
                updated_at_ms = MAX(workout_templates.updated_at_ms, excluded.updated_at_ms),
                dirty_sync = CASE WHEN excluded.updated_at_ms >= workout_templates.updated_at_ms THEN 0 ELSE workout_templates.dirty_sync END
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, t.id); s.bindText(2, t.userId); s.bindText(3, t.name)
            bindNullableText(s, 4, t.description); s.bindText(5, t.blocksJson); s.bindText(6, daysJson)
            s.bindText(7, t.createdAt.ifBlank { IsoTime.nowIso() })
            s.bindText(8, t.updatedAt.ifBlank { IsoTime.nowIso() })
            s.bindLong(9, t.updatedAtMs); bindNullableLong(s, 10, t.deletedAtMs)
            s.step()
        }
    }

    // 4. Sessions & SetLogs (append-only union + monotonic total_volume recompute)
    for (sess in pull.sessions) {
        c.prepare(
            """
            INSERT INTO workout_sessions(id, user_id, template_id, started_at, completed_at, total_volume, updated_at_ms, dirty_sync)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                completed_at = COALESCE(workout_sessions.completed_at, excluded.completed_at),
                total_volume = MAX(workout_sessions.total_volume, excluded.total_volume),
                updated_at_ms = MAX(workout_sessions.updated_at_ms, excluded.updated_at_ms)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, sess.id); s.bindText(2, sess.userId); bindNullableText(s, 3, sess.templateId)
            s.bindText(4, sess.startedAt); bindNullableText(s, 5, sess.completedAt)
            s.bindDouble(6, sess.totalVolume); s.bindLong(7, sess.updatedAtMs)
            s.step()
        }
    }

    val touchedSessions = mutableSetOf<String>()
    for (sl in pull.setLogs) {
        c.prepare(
            """
            INSERT OR IGNORE INTO set_logs(
                id, session_id, exercise_id, set_number, mode, modifier_pct, weight_lb,
                reps_completed, volume_lb, rpe, timestamp, updated_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, sl.id); s.bindText(2, sl.sessionId); s.bindText(3, sl.exerciseId)
            s.bindInt(4, sl.setNumber); s.bindText(5, sl.mode); s.bindInt(6, sl.modifierPct)
            s.bindDouble(7, sl.weightLb); s.bindInt(8, sl.repsCompleted); s.bindDouble(9, sl.volumeLb)
            if (sl.rpe != null) s.bindDouble(10, sl.rpe) else s.bindNull(10)
            s.bindText(11, sl.timestamp); s.bindLong(12, sl.updatedAtMs)
            s.step()
        }
        touchedSessions += sl.sessionId
    }
    for (sid in touchedSessions) {
        c.prepare(
            "UPDATE workout_sessions SET total_volume = MAX(total_volume, COALESCE((SELECT SUM(volume_lb) FROM set_logs WHERE session_id = ?), 0.0)) WHERE id = ?"
        ).useStmt { s ->
            s.bindText(1, sid); s.bindText(2, sid); s.step()
        }
    }

    // 5. PersonalRecords (max-envelope merge)
    for (pr in pull.personalRecords) {
        c.prepare(
            """
            INSERT INTO personal_records(id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at, updated_at_ms, dirty_sync)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(user_id, exercise_id) DO UPDATE SET
                one_rep_max_est = MAX(personal_records.one_rep_max_est, excluded.one_rep_max_est),
                max_weight_lb = MAX(personal_records.max_weight_lb, excluded.max_weight_lb),
                updated_at = CASE WHEN excluded.updated_at_ms >= personal_records.updated_at_ms THEN excluded.updated_at ELSE personal_records.updated_at END,
                updated_at_ms = MAX(personal_records.updated_at_ms, excluded.updated_at_ms)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, pr.id); s.bindText(2, pr.userId); s.bindText(3, pr.exerciseId)
            s.bindDouble(4, pr.oneRepMaxEst); s.bindDouble(5, pr.maxWeightLb)
            s.bindText(6, pr.updatedAt.ifBlank { IsoTime.nowIso() }); s.bindLong(7, pr.updatedAtMs)
            s.step()
        }
    }

    // 6. Friends
    for (f in pull.friends) {
        c.prepare(
            """
            INSERT INTO friends(
                id, user_id, npub, pubkey_hex, display_name, avatar_url, relays_json, last_seen_at,
                created_at, updated_at, updated_at_ms, deleted_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(user_id, pubkey_hex) DO UPDATE SET
                display_name = CASE WHEN excluded.updated_at_ms >= friends.updated_at_ms THEN excluded.display_name ELSE friends.display_name END,
                deleted_at_ms = CASE WHEN excluded.updated_at_ms >= friends.updated_at_ms THEN excluded.deleted_at_ms ELSE friends.deleted_at_ms END,
                updated_at = CASE WHEN excluded.updated_at_ms >= friends.updated_at_ms THEN excluded.updated_at ELSE friends.updated_at END,
                updated_at_ms = MAX(friends.updated_at_ms, excluded.updated_at_ms),
                dirty_sync = CASE WHEN excluded.updated_at_ms >= friends.updated_at_ms THEN 0 ELSE friends.dirty_sync END
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, f.id); s.bindText(2, f.userId); s.bindText(3, f.npub); s.bindText(4, f.pubkeyHex)
            s.bindText(5, f.displayName); bindNullableText(s, 6, f.avatarUrl); bindNullableText(s, 7, f.relaysJson)
            bindNullableText(s, 8, f.lastSeenAt); s.bindText(9, f.createdAt.ifBlank { IsoTime.nowIso() })
            s.bindText(10, f.updatedAt.ifBlank { IsoTime.nowIso() })
            s.bindLong(11, f.updatedAtMs); bindNullableLong(s, 12, f.deletedAtMs)
            s.step()
        }
    }
}

suspend fun SwonalDatabase.exportRestoreBundle(): RestoreBundleDto = withDb { c ->
    // Collect all rows regardless of dirty_sync
    val users = mutableListOf<StoredUserSyncRecord>()
    c.prepare("SELECT id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit, nostr_pub_key_hex, nostr_npub, nostr_relays_json, share_workouts, demo_pip_enabled, created_at, updated_at, updated_at_ms, deleted_at_ms FROM users WHERE deleted_at_ms IS NULL").useStmt { s ->
        while (s.step()) {
            users += StoredUserSyncRecord(
                id = s.getText(0), name = s.getText(1), avatarColor = s.getText(2),
                preferredMusicProvider = s.getText(3), themeMode = s.getText(4), accentColor = s.getText(5),
                weightUnit = s.getText(6), nostrPubKeyHex = getNullableText(s, 7), nostrNpub = getNullableText(s, 8),
                nostrRelays = getNullableText(s, 9), shareWorkouts = s.getInt(10) != 0,
                demoPipEnabled = s.getInt(11) != 0, createdAt = s.getText(12), updatedAt = s.getText(13),
                updatedAtMs = s.getLong(14), deletedAtMs = getNullableLong(s, 15)
            )
        }
    }
    val exercises = mutableListOf<StoredExerciseSyncRecord>()
    c.prepare("SELECT id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, updated_at_ms, deleted_at_ms FROM exercises WHERE deleted_at_ms IS NULL").useStmt { s ->
        while (s.step()) {
            exercises += StoredExerciseSyncRecord(
                id = s.getText(0), name = s.getText(1), muscleGroup = s.getText(2), accessory = s.getText(3),
                recommendedHeight = s.getInt(4), recommendedRotation = s.getInt(5), recommendedAngle = s.getInt(6),
                unilateral = s.getInt(7) != 0, notes = getNullableText(s, 8), mediaUrl = getNullableText(s, 9),
                updatedAtMs = s.getLong(10), deletedAtMs = getNullableLong(s, 11)
            )
        }
    }
    val templates = mutableListOf<StoredTemplateSyncRecord>()
    c.prepare("SELECT id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at, updated_at_ms, deleted_at_ms FROM workout_templates WHERE deleted_at_ms IS NULL").useStmt { s ->
        while (s.step()) {
            val days = getNullableText(s, 5)?.let { runCatching { SwonalJson.decodeFromString<List<Int>>(it) }.getOrDefault(emptyList()) }.orEmpty()
            templates += StoredTemplateSyncRecord(
                id = s.getText(0), userId = s.getText(1), name = s.getText(2), description = getNullableText(s, 3),
                blocksJson = s.getText(4), scheduleDays = days, createdAt = s.getText(6), updatedAt = s.getText(7),
                updatedAtMs = s.getLong(8), deletedAtMs = getNullableLong(s, 9)
            )
        }
    }
    val sessions = mutableListOf<StoredSessionSyncRecord>()
    c.prepare("SELECT id, user_id, template_id, started_at, completed_at, total_volume, updated_at_ms FROM workout_sessions").useStmt { s ->
        while (s.step()) {
            sessions += StoredSessionSyncRecord(
                id = s.getText(0), userId = s.getText(1), templateId = getNullableText(s, 2),
                startedAt = s.getText(3), completedAt = getNullableText(s, 4), totalVolume = s.getDouble(5), updatedAtMs = s.getLong(6)
            )
        }
    }
    val setLogs = mutableListOf<StoredSetLogSyncRecord>()
    c.prepare("SELECT id, session_id, exercise_id, set_number, mode, modifier_pct, weight_lb, reps_completed, volume_lb, rpe, timestamp, updated_at_ms FROM set_logs").useStmt { s ->
        while (s.step()) {
            setLogs += StoredSetLogSyncRecord(
                id = s.getText(0), sessionId = s.getText(1), exerciseId = s.getText(2), setNumber = s.getInt(3),
                mode = s.getText(4), modifierPct = s.getInt(5), weightLb = s.getDouble(6), repsCompleted = s.getInt(7),
                volumeLb = s.getDouble(8), rpe = getNullableDouble(s, 9), timestamp = s.getText(10), updatedAtMs = s.getLong(11)
            )
        }
    }
    val prs = mutableListOf<StoredPrSyncRecord>()
    c.prepare("SELECT id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at, updated_at_ms FROM personal_records").useStmt { s ->
        while (s.step()) {
            prs += StoredPrSyncRecord(
                id = s.getText(0), userId = s.getText(1), exerciseId = s.getText(2),
                oneRepMaxEst = s.getDouble(3), maxWeightLb = s.getDouble(4),
                updatedAt = s.getText(5), updatedAtMs = s.getLong(6)
            )
        }
    }
    val friends = mutableListOf<StoredFriendRecord>()
    c.prepare("SELECT id, user_id, npub, pubkey_hex, display_name, avatar_url, relays_json, last_seen_at, created_at, updated_at, updated_at_ms, deleted_at_ms FROM friends WHERE deleted_at_ms IS NULL").useStmt { s ->
        while (s.step()) {
            friends += StoredFriendRecord(
                id = s.getText(0), userId = s.getText(1), npub = s.getText(2), pubkeyHex = s.getText(3),
                displayName = s.getText(4), avatarUrl = getNullableText(s, 5), relaysJson = getNullableText(s, 6),
                lastSeenAt = getNullableText(s, 7), createdAt = s.getText(8), updatedAt = s.getText(9),
                updatedAtMs = s.getLong(10), deletedAtMs = getNullableLong(s, 11)
            )
        }
    }
    RestoreBundleDto(
        version = 1,
        exportedAt = IsoTime.nowIso(),
        users = users,
        exercises = exercises,
        templates = templates,
        sessions = sessions,
        setLogs = setLogs,
        personalRecords = prs,
        friends = friends
    )
}
