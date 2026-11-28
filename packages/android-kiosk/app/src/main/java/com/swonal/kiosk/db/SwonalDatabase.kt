package com.swonal.kiosk.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.swonal.kiosk.model.ArmPositionSets
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.ExerciseName
import com.swonal.kiosk.model.MissingExercise
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.SessionTemplateSummary
import com.swonal.kiosk.model.SetLog
import com.swonal.kiosk.model.SetLogExerciseSummary
import com.swonal.kiosk.model.SharedExerciseRef
import com.swonal.kiosk.model.SharedRoutineExercises
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.UnknownExerciseException
import com.swonal.kiosk.model.User
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.util.IsoTime
import com.swonal.kiosk.util.PlaintextTestCipher
import com.swonal.kiosk.util.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Thread-safe bundled SQLite (3.46+) persistence layer for Standalone-First Swonal Kiosk.
 * Works identically on Android 6.0 (API 23, arm64-v8a) and host JVM JUnit 5 tests.
 */
class SwonalDatabase(
    private val dbPath: String,
    internal val cipher: SecretCipher = PlaintextTestCipher
) : Closeable {
    private val driver = BundledSQLiteDriver()
    internal val dbMutex = Mutex()

    @Volatile
    private var conn: SQLiteConnection? = null

    internal suspend fun <T> withDb(block: (SQLiteConnection) -> T): T = dbMutex.withLock {
        withContext(Dispatchers.IO) {
            val c = ensureOpenUnlocked()
            block(c)
        }
    }

    internal suspend fun <T> withTx(block: (SQLiteConnection) -> T): T = dbMutex.withLock {
        withContext(Dispatchers.IO) {
            val c = ensureOpenUnlocked()
            inTxUnlocked(c) { block(c) }
        }
    }

    private fun ensureOpenUnlocked(): SQLiteConnection {
        conn?.let { return it }
        if (dbPath != ":memory:") {
            File(dbPath).parentFile?.mkdirs()
        }
        val opened = driver.open(dbPath)
        runCatching { opened.execSQL("PRAGMA journal_mode = WAL;") }
        opened.execSQL("PRAGMA synchronous = NORMAL;")
        opened.execSQL("PRAGMA foreign_keys = ON;")
        opened.execSQL("PRAGMA busy_timeout = 5000;")
        migrateSchemaUnlocked(opened)
        conn = opened
        return opened
    }

    private fun migrateSchemaUnlocked(c: SQLiteConnection) {
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                avatar_color TEXT NOT NULL DEFAULT '#3B82F6',
                preferred_music_provider TEXT NOT NULL DEFAULT 'spotify',
                theme_mode TEXT NOT NULL DEFAULT 'dark',
                accent_color TEXT NOT NULL DEFAULT '#00F0FF',
                weight_unit TEXT NOT NULL DEFAULT 'LB',
                nostr_priv_key_enc TEXT,
                nostr_pub_key_hex TEXT,
                nostr_npub TEXT,
                nostr_relays_json TEXT,
                share_workouts INTEGER NOT NULL DEFAULT 1,
                demo_pip_enabled INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                deleted_at_ms INTEGER,
                dirty_sync INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exercises (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                muscle_group TEXT NOT NULL,
                accessory TEXT NOT NULL DEFAULT 'HANDLES',
                recommended_height INTEGER NOT NULL DEFAULT 4,
                recommended_rotation INTEGER NOT NULL DEFAULT 2,
                recommended_angle INTEGER NOT NULL DEFAULT 1,
                unilateral INTEGER NOT NULL DEFAULT 0,
                notes TEXT,
                media_url TEXT,
                updated_at_ms INTEGER NOT NULL,
                deleted_at_ms INTEGER,
                dirty_sync INTEGER NOT NULL DEFAULT 0,
                allowed_heights TEXT NOT NULL DEFAULT '',
                allowed_rotations TEXT NOT NULL DEFAULT '',
                allowed_angles TEXT NOT NULL DEFAULT ''
            );
            """.trimIndent()
        )
        // Existing installs: add arm-position set columns (comma lists, '' = recommended value only).
        ensureColumnUnlocked(c, "exercises", "allowed_heights", "TEXT NOT NULL DEFAULT ''")
        ensureColumnUnlocked(c, "exercises", "allowed_rotations", "TEXT NOT NULL DEFAULT ''")
        ensureColumnUnlocked(c, "exercises", "allowed_angles", "TEXT NOT NULL DEFAULT ''")
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_templates (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                description TEXT,
                blocks_json TEXT NOT NULL,
                schedule_days_json TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                deleted_at_ms INTEGER,
                dirty_sync INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_sessions (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                template_id TEXT REFERENCES workout_templates(id) ON DELETE SET NULL,
                started_at TEXT NOT NULL,
                completed_at TEXT,
                total_volume REAL NOT NULL DEFAULT 0.0,
                updated_at_ms INTEGER NOT NULL,
                dirty_sync INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS set_logs (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
                exercise_id TEXT NOT NULL REFERENCES exercises(id),
                set_number INTEGER NOT NULL,
                mode TEXT NOT NULL DEFAULT 'standard',
                modifier_pct INTEGER NOT NULL DEFAULT 0,
                weight_lb REAL NOT NULL,
                reps_completed INTEGER NOT NULL,
                volume_lb REAL NOT NULL,
                rpe REAL,
                timestamp TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                dirty_sync INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS personal_records (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                exercise_id TEXT NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
                one_rep_max_est REAL NOT NULL,
                max_weight_lb REAL NOT NULL,
                updated_at TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                dirty_sync INTEGER NOT NULL DEFAULT 0,
                UNIQUE(user_id, exercise_id)
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS music_integrations (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                provider TEXT NOT NULL,
                access_token_enc TEXT,
                refresh_token_enc TEXT,
                token_expiry INTEGER,
                preferred_device_id TEXT,
                metadata_json TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                UNIQUE(user_id, provider)
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS friends (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                npub TEXT NOT NULL,
                pubkey_hex TEXT NOT NULL,
                display_name TEXT NOT NULL,
                avatar_url TEXT,
                relays_json TEXT,
                last_seen_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                deleted_at_ms INTEGER,
                dirty_sync INTEGER NOT NULL DEFAULT 0,
                UNIQUE(user_id, pubkey_hex)
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS friend_activities (
                id TEXT PRIMARY KEY,
                friend_pubkey_hex TEXT NOT NULL,
                friend_display_name TEXT,
                event_id TEXT NOT NULL UNIQUE,
                kind INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                content_json TEXT NOT NULL,
                received_at TEXT NOT NULL
            );
            """.trimIndent()
        )
        c.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                key TEXT PRIMARY KEY,
                value_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_templates_user ON workout_templates(user_id);")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_user_started ON workout_sessions(user_id, started_at DESC);")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_setlogs_session ON set_logs(session_id, timestamp ASC);")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_setlogs_exercise ON set_logs(exercise_id, timestamp DESC);")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_friends_user ON friends(user_id);")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_friend_act_created ON friend_activities(created_at DESC);")
    }

    private fun ensureColumnUnlocked(c: SQLiteConnection, table: String, column: String, decl: String) {
        val exists = c.prepare("PRAGMA table_info($table)").useStmt { s ->
            var found = false
            while (s.step()) {
                if (s.getText(1) == column) found = true
            }
            found
        }
        if (!exists) c.execSQL("ALTER TABLE $table ADD COLUMN $column $decl;")
    }

    internal inline fun <T> inTxUnlocked(c: SQLiteConnection, block: () -> T): T {
        c.execSQL("BEGIN IMMEDIATE;")
        try {
            val res = block()
            c.execSQL("COMMIT;")
            return res
        } catch (t: Throwable) {
            runCatching { c.execSQL("ROLLBACK;") }
            throw t
        }
    }

    suspend fun seedIfEmpty(seedJson: String, includeDemoUsersAndRoutines: Boolean = true) {
        val seed = SwonalJson.decodeFromString<SeedCatalogDto>(seedJson)
        withTx { c ->
            // Seed placeholder records with epoch+1ms (NOT wall-clock now) so any real server-app
            // profile, routine, exercise, or PR always has a newer updated_at_ms and wins LWW sync.
            val seedMs = 1L
            val seedIso = IsoTime.formatUtc(seedMs)

            val exCount = queryLongUnlocked(c, "SELECT COUNT(*) FROM exercises WHERE deleted_at_ms IS NULL")
            if (exCount == 0L) {
                for (ex in seed.exercises) {
                    insertOrIgnoreExerciseUnlocked(c, ex, seedMs)
                }
            }

            if (includeDemoUsersAndRoutines) {
                val userCount = queryLongUnlocked(c, "SELECT COUNT(*) FROM users WHERE deleted_at_ms IS NULL")
                if (userCount == 0L) {
                    for (u in seed.users) {
                        c.prepare(
                            """
                            INSERT OR IGNORE INTO users(
                                id, name, avatar_color, preferred_music_provider, theme_mode, accent_color,
                                weight_unit, share_workouts, demo_pip_enabled, created_at, updated_at, updated_at_ms, dirty_sync
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, 0)
                            """.trimIndent()
                        ).useStmt { s ->
                            s.bindText(1, u.id)
                            s.bindText(2, u.name)
                            s.bindText(3, u.avatarColor)
                            s.bindText(4, u.preferredMusicProvider)
                            s.bindText(5, u.themeMode)
                            s.bindText(6, u.accentColor)
                            s.bindText(7, u.weightUnit)
                            s.bindText(8, seedIso)
                            s.bindText(9, seedIso)
                            s.bindLong(10, seedMs)
                            s.step()
                        }
                    }

                    val tplCount = queryLongUnlocked(c, "SELECT COUNT(*) FROM workout_templates WHERE deleted_at_ms IS NULL")
                    if (tplCount == 0L) {
                        for (t in seed.templates) {
                            c.prepare(
                                """
                                INSERT OR IGNORE INTO workout_templates(
                                    id, user_id, name, description, blocks_json, schedule_days_json,
                                    created_at, updated_at, updated_at_ms, dirty_sync
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                                """.trimIndent()
                            ).useStmt { s ->
                                s.bindText(1, t.id)
                                s.bindText(2, t.userId)
                                s.bindText(3, t.name)
                                bindNullableText(s, 4, t.description)
                                s.bindText(5, SwonalJson.encodeToString(t.blocks))
                                s.bindText(6, SwonalJson.encodeToString(t.scheduleDays))
                                s.bindText(7, seedIso)
                                s.bindText(8, seedIso)
                                s.bindLong(9, seedMs)
                                s.step()
                            }
                        }
                    }

                    for (pr in seed.personalRecords) {
                        c.prepare(
                            """
                            INSERT OR IGNORE INTO personal_records(
                                id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at, updated_at_ms, dirty_sync
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                            """.trimIndent()
                        ).useStmt { s ->
                            s.bindText(1, pr.id)
                            s.bindText(2, pr.userId)
                            s.bindText(3, pr.exerciseId)
                            s.bindDouble(4, pr.oneRepMaxEst)
                            s.bindDouble(5, pr.maxWeightLb)
                            s.bindText(6, seedIso)
                            s.bindLong(7, seedMs)
                            s.step()
                        }
                    }
                }
            }
        }
    }

    /** Seeds only the 50 canonical exercises if missing (safe before server import). */
    suspend fun ensureSeedExercisesPresent(seedJson: String) {
        val seed = SwonalJson.decodeFromString<SeedCatalogDto>(seedJson)
        withTx { c ->
            val count = queryLongUnlocked(c, "SELECT COUNT(*) FROM exercises WHERE deleted_at_ms IS NULL")
            if (count == 0L) {
                for (ex in seed.exercises) {
                    insertOrIgnoreExerciseUnlocked(c, ex, 1L)
                }
            }
        }
    }

    private fun insertOrIgnoreExerciseUnlocked(c: SQLiteConnection, ex: Exercise, nowMs: Long) {
        c.prepare(
            """
            INSERT OR IGNORE INTO exercises(
                id, name, muscle_group, accessory, recommended_height, recommended_rotation,
                recommended_angle, unilateral, notes, media_url, updated_at_ms, dirty_sync,
                allowed_heights, allowed_rotations, allowed_angles
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
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
            s.bindLong(11, nowMs)
            bindAllowedSets(s, 12, ex)
            s.step()
        }
    }

    // --- App Settings ---

    suspend fun getSetting(key: String): String? = withDb { c ->
        c.prepare("SELECT value_json FROM app_settings WHERE key = ?").useStmt { s ->
            s.bindText(1, key)
            if (s.step()) s.getText(0) else null
        }
    }

    suspend fun setSetting(key: String, value: String) = withTx { c ->
        val nowMs = System.currentTimeMillis()
        c.prepare(
            """
            INSERT INTO app_settings(key, value_json, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at_ms = excluded.updated_at_ms
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, key)
            s.bindText(2, value)
            s.bindLong(3, nowMs)
            s.step()
        }
    }

    suspend fun deleteSetting(key: String) = withTx { c ->
        c.prepare("DELETE FROM app_settings WHERE key = ?").useStmt { s ->
            s.bindText(1, key)
            s.step()
        }
    }

    // --- Users ---

    suspend fun listUsers(): List<User> = withDb { c ->
        listUsersUnlocked(c)
    }

    internal fun listUsersUnlocked(c: SQLiteConnection): List<User> {
        val out = mutableListOf<User>()
        c.prepare(
            """
            SELECT id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit, created_at, updated_at
            FROM users WHERE deleted_at_ms IS NULL ORDER BY created_at ASC
            """.trimIndent()
        ).useStmt { s ->
            while (s.step()) {
                out += readUserFromRow(s)
            }
        }
        return out
    }

    suspend fun getUserById(userId: String): User? = withDb { c ->
        c.prepare(
            """
            SELECT id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit, created_at, updated_at
            FROM users WHERE id = ? AND deleted_at_ms IS NULL
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, userId)
            if (s.step()) readUserFromRow(s) else null
        }
    }

    suspend fun createUser(
        name: String,
        avatarColor: String = "#3B82F6",
        accentColor: String = "#00F0FF",
        themeMode: String = "dark",
        weightUnit: String = "LB",
        preferredMusicProvider: String = "spotify"
    ): User = withTx { c ->
        val id = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        c.prepare(
            """
            INSERT INTO users(
                id, name, avatar_color, preferred_music_provider, theme_mode, accent_color,
                weight_unit, share_workouts, demo_pip_enabled, created_at, updated_at, updated_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, 1)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, id)
            s.bindText(2, name)
            s.bindText(3, avatarColor)
            s.bindText(4, preferredMusicProvider)
            s.bindText(5, themeMode)
            s.bindText(6, accentColor)
            s.bindText(7, weightUnit)
            s.bindText(8, nowIso)
            s.bindText(9, nowIso)
            s.bindLong(10, nowMs)
            s.step()
        }
        User(id, name, avatarColor, preferredMusicProvider, themeMode, accentColor, weightUnit, nowIso, nowIso)
    }

    suspend fun updateUser(
        userId: String,
        name: String? = null,
        avatarColor: String? = null,
        accentColor: String? = null,
        themeMode: String? = null,
        weightUnit: String? = null,
        preferredMusicProvider: String? = null
    ): User? = withTx { c ->
        val existing = c.prepare(
            "SELECT id, name, avatar_color, preferred_music_provider, theme_mode, accent_color, weight_unit, created_at, updated_at FROM users WHERE id = ? AND deleted_at_ms IS NULL"
        ).useStmt { s ->
            s.bindText(1, userId)
            if (s.step()) readUserFromRow(s) else null
        } ?: return@withTx null

        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        val updated = existing.copy(
            name = name ?: existing.name,
            avatarColor = avatarColor ?: existing.avatarColor,
            preferredMusicProvider = preferredMusicProvider ?: existing.preferredMusicProvider,
            themeMode = themeMode ?: existing.themeMode,
            accentColor = accentColor ?: existing.accentColor,
            weightUnit = weightUnit ?: existing.weightUnit,
            updatedAt = nowIso
        )
        c.prepare(
            """
            UPDATE users SET
                name = ?, avatar_color = ?, preferred_music_provider = ?, theme_mode = ?,
                accent_color = ?, weight_unit = ?, updated_at = ?, updated_at_ms = ?, dirty_sync = 1
            WHERE id = ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, updated.name)
            s.bindText(2, updated.avatarColor)
            s.bindText(3, updated.preferredMusicProvider)
            s.bindText(4, updated.themeMode)
            s.bindText(5, updated.accentColor)
            s.bindText(6, updated.weightUnit)
            s.bindText(7, nowIso)
            s.bindLong(8, nowMs)
            s.bindText(9, userId)
            s.step()
        }
        updated
    }

    suspend fun deleteUser(userId: String): Boolean = withTx { c ->
        val count = queryLongUnlocked(c, "SELECT COUNT(*) FROM users WHERE deleted_at_ms IS NULL")
        if (count <= 1L) return@withTx false
        val nowMs = System.currentTimeMillis()
        c.prepare("UPDATE users SET deleted_at_ms = ?, updated_at_ms = ?, dirty_sync = 1 WHERE id = ? AND deleted_at_ms IS NULL")
            .useStmt { s ->
                s.bindLong(1, nowMs)
                s.bindLong(2, nowMs)
                s.bindText(3, userId)
                s.step()
            }
        true
    }

    suspend fun getDemoPip(userId: String): Boolean = withDb { c ->
        c.prepare("SELECT demo_pip_enabled FROM users WHERE id = ? AND deleted_at_ms IS NULL").useStmt { s ->
            s.bindText(1, userId)
            if (s.step()) s.getInt(0) != 0 else true
        }
    }

    suspend fun setDemoPip(userId: String, enabled: Boolean): Boolean = withTx { c ->
        val nowMs = System.currentTimeMillis()
        c.prepare("UPDATE users SET demo_pip_enabled = ?, updated_at_ms = ?, dirty_sync = 1 WHERE id = ?").useStmt { s ->
            s.bindInt(1, if (enabled) 1 else 0)
            s.bindLong(2, nowMs)
            s.bindText(3, userId)
            s.step()
        }
        enabled
    }

    // --- Exercises ---

    suspend fun listExercises(
        muscleGroup: String? = null,
        accessory: String? = null,
        unilateral: Boolean? = null,
        searchQuery: String? = null
    ): List<Exercise> = withDb { c ->
        val sb = StringBuilder(
            "SELECT id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, allowed_heights, allowed_rotations, allowed_angles FROM exercises WHERE deleted_at_ms IS NULL"
        )
        val args = mutableListOf<Any>()
        if (!muscleGroup.isNullOrBlank()) {
            sb.append(" AND muscle_group = ?")
            args += muscleGroup
        }
        if (!accessory.isNullOrBlank()) {
            sb.append(" AND accessory = ?")
            args += accessory
        }
        if (unilateral != null) {
            sb.append(" AND unilateral = ?")
            args += if (unilateral) 1 else 0
        }
        if (!searchQuery.isNullOrBlank()) {
            sb.append(" AND (name LIKE ? COLLATE NOCASE OR muscle_group LIKE ? COLLATE NOCASE)")
            val pattern = "%${searchQuery.trim()}%"
            args += pattern
            args += pattern
        }
        sb.append(" ORDER BY muscle_group ASC, name ASC")

        val out = mutableListOf<Exercise>()
        c.prepare(sb.toString()).useStmt { s ->
            args.forEachIndexed { idx, v ->
                when (v) {
                    is Int -> s.bindInt(idx + 1, v)
                    else -> s.bindText(idx + 1, v.toString())
                }
            }
            while (s.step()) {
                out += readExerciseFromRow(s)
            }
        }
        out
    }

    suspend fun getExerciseById(exerciseId: String): Exercise? = withDb { c ->
        c.prepare(
            "SELECT id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, allowed_heights, allowed_rotations, allowed_angles FROM exercises WHERE id = ? AND deleted_at_ms IS NULL"
        ).useStmt { s ->
            s.bindText(1, exerciseId)
            if (s.step()) readExerciseFromRow(s) else null
        }
    }

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
    ): Exercise = withTx { c ->
        val id = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val created = normalizeAllowedSets(
            Exercise(
                id, name, muscleGroup, accessory, recommendedHeight, recommendedRotation, recommendedAngle, unilateral, notes, mediaUrl,
                allowedHeights, allowedRotations, allowedAngles
            )
        )
        c.prepare(
            """
            INSERT INTO exercises(
                id, name, muscle_group, accessory, recommended_height, recommended_rotation,
                recommended_angle, unilateral, notes, media_url, updated_at_ms, dirty_sync,
                allowed_heights, allowed_rotations, allowed_angles
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, id)
            s.bindText(2, name)
            s.bindText(3, muscleGroup)
            s.bindText(4, accessory)
            s.bindInt(5, recommendedHeight)
            s.bindInt(6, recommendedRotation)
            s.bindInt(7, recommendedAngle)
            s.bindInt(8, if (unilateral) 1 else 0)
            bindNullableText(s, 9, notes)
            bindNullableText(s, 10, mediaUrl)
            s.bindLong(11, nowMs)
            bindAllowedSets(s, 12, created)
            s.step()
        }
        created
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
    ): Exercise? = withTx { c ->
        val existing = c.prepare(
            "SELECT id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, media_url, allowed_heights, allowed_rotations, allowed_angles FROM exercises WHERE id = ? AND deleted_at_ms IS NULL"
        ).useStmt { s ->
            s.bindText(1, exerciseId)
            if (s.step()) readExerciseFromRow(s) else null
        } ?: return@withTx null

        val next = normalizeAllowedSets(existing.copy(
            name = name ?: existing.name,
            muscleGroup = muscleGroup ?: existing.muscleGroup,
            accessory = accessory ?: existing.accessory,
            recommendedHeight = recommendedHeight ?: existing.recommendedHeight,
            recommendedRotation = recommendedRotation ?: existing.recommendedRotation,
            recommendedAngle = recommendedAngle ?: existing.recommendedAngle,
            unilateral = unilateral ?: existing.unilateral,
            notes = notes ?: existing.notes,
            mediaUrl = if (updateMediaUrl) mediaUrl else (mediaUrl ?: existing.mediaUrl),
            allowedHeights = allowedHeights ?: existing.allowedHeights,
            allowedRotations = allowedRotations ?: existing.allowedRotations,
            allowedAngles = allowedAngles ?: existing.allowedAngles
        ))
        val nowMs = System.currentTimeMillis()
        c.prepare(
            """
            UPDATE exercises SET
                name = ?, muscle_group = ?, accessory = ?, recommended_height = ?, recommended_rotation = ?,
                recommended_angle = ?, unilateral = ?, notes = ?, media_url = ?, updated_at_ms = ?, dirty_sync = 1,
                allowed_heights = ?, allowed_rotations = ?, allowed_angles = ?
            WHERE id = ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, next.name)
            s.bindText(2, next.muscleGroup)
            s.bindText(3, next.accessory)
            s.bindInt(4, next.recommendedHeight)
            s.bindInt(5, next.recommendedRotation)
            s.bindInt(6, next.recommendedAngle)
            s.bindInt(7, if (next.unilateral) 1 else 0)
            bindNullableText(s, 8, next.notes)
            bindNullableText(s, 9, next.mediaUrl)
            s.bindLong(10, nowMs)
            bindAllowedSets(s, 11, next)
            s.bindText(14, exerciseId)
            s.step()
        }
        next
    }

    suspend fun deleteExerciseIfUnused(exerciseId: String): Result<Unit> = withTx { c ->
        val usage = c.prepare("SELECT COUNT(*) FROM set_logs WHERE exercise_id = ?").useStmt { s ->
            s.bindText(1, exerciseId)
            if (s.step()) s.getLong(0) else 0L
        }
        if (usage > 0L) {
            return@withTx Result.failure(IllegalStateException("Cannot delete exercise with $usage logged sets."))
        }
        val nowMs = System.currentTimeMillis()
        c.prepare("UPDATE exercises SET deleted_at_ms = ?, updated_at_ms = ?, dirty_sync = 1 WHERE id = ?").useStmt { s ->
            s.bindLong(1, nowMs)
            s.bindLong(2, nowMs)
            s.bindText(3, exerciseId)
            s.step()
        }
        Result.success(Unit)
    }

    // --- Workout Templates ---

    suspend fun listTemplates(userId: String? = null): List<WorkoutTemplate> = withDb { c ->
        listTemplatesUnlocked(c, userId)
    }

    internal fun listTemplatesUnlocked(c: SQLiteConnection, userId: String? = null): List<WorkoutTemplate> {
        val sql = buildString {
            append("SELECT id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at FROM workout_templates WHERE deleted_at_ms IS NULL")
            if (!userId.isNullOrBlank()) append(" AND user_id = ?")
            append(" ORDER BY created_at DESC")
        }
        val out = mutableListOf<WorkoutTemplate>()
        c.prepare(sql).useStmt { s ->
            if (!userId.isNullOrBlank()) s.bindText(1, userId)
            while (s.step()) {
                out += readTemplateFromRow(s)
            }
        }
        return out
    }

    suspend fun getTemplateById(templateId: String): WorkoutTemplate? = withDb { c ->
        c.prepare(
            "SELECT id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at FROM workout_templates WHERE id = ? AND deleted_at_ms IS NULL"
        ).useStmt { s ->
            s.bindText(1, templateId)
            if (s.step()) readTemplateFromRow(s) else null
        }
    }

    suspend fun createTemplate(
        userId: String,
        name: String,
        description: String?,
        blocks: List<WorkoutBlock>,
        scheduleDays: List<Int> = emptyList(),
        idOverride: String? = null,
        requireKnownExercises: Boolean = false,
        sharedExercises: Map<String, SharedExerciseRef>? = null
    ): WorkoutTemplate = withTx { c ->
        // A shared routine: match its exercises to this library (or refuse) in the same transaction.
        val finalBlocks = if (sharedExercises != null) resolveSharedExercisesUnlocked(c, blocks, sharedExercises) else blocks
        if (requireKnownExercises) requireKnownExercisesUnlocked(c, finalBlocks)
        val id = idOverride ?: UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        val blocksJson = SwonalJson.encodeToString(finalBlocks)
        val cleanDays = scheduleDays.filter { it in 0..6 }.distinct().sorted()
        val daysJson = SwonalJson.encodeToString(cleanDays)
        c.prepare(
            """
            INSERT INTO workout_templates(
                id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at, updated_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, id)
            s.bindText(2, userId)
            s.bindText(3, name)
            bindNullableText(s, 4, description)
            s.bindText(5, blocksJson)
            s.bindText(6, daysJson)
            s.bindText(7, nowIso)
            s.bindText(8, nowIso)
            s.bindLong(9, nowMs)
            s.step()
        }
        WorkoutTemplate(id, userId, name, description, blocksJson, finalBlocks, cleanDays, nowIso, nowIso)
    }

    suspend fun updateTemplate(
        templateId: String,
        name: String? = null,
        description: String? = null,
        blocks: List<WorkoutBlock>? = null,
        scheduleDays: List<Int>? = null,
        requireKnownExercises: Boolean = false
    ): WorkoutTemplate? = withTx { c ->
        if (requireKnownExercises && blocks != null) requireKnownExercisesUnlocked(c, blocks)
        val existing = c.prepare(
            "SELECT id, user_id, name, description, blocks_json, schedule_days_json, created_at, updated_at FROM workout_templates WHERE id = ? AND deleted_at_ms IS NULL"
        ).useStmt { s ->
            s.bindText(1, templateId)
            if (s.step()) readTemplateFromRow(s) else null
        } ?: return@withTx null

        val nextBlocks = blocks ?: existing.blocks
        val nextBlocksJson = if (blocks != null) SwonalJson.encodeToString(blocks) else existing.blocksJson
        val nextDays = scheduleDays?.filter { it in 0..6 }?.distinct()?.sorted() ?: existing.scheduleDays
        val nextDaysJson = SwonalJson.encodeToString(nextDays)
        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)

        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            blocksJson = nextBlocksJson,
            blocks = nextBlocks,
            scheduleDays = nextDays,
            updatedAt = nowIso
        )
        c.prepare(
            """
            UPDATE workout_templates SET
                name = ?, description = ?, blocks_json = ?, schedule_days_json = ?, updated_at = ?, updated_at_ms = ?, dirty_sync = 1
            WHERE id = ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, updated.name)
            bindNullableText(s, 2, updated.description)
            s.bindText(3, updated.blocksJson)
            s.bindText(4, nextDaysJson)
            s.bindText(5, nowIso)
            s.bindLong(6, nowMs)
            s.bindText(7, templateId)
            s.step()
        }
        updated
    }

    /**
     * Throws [UnknownExerciseException] (an IllegalArgumentException) if any block item references
     * a missing or soft-deleted exercise. One `IN (...)` query, run inside the caller's write
     * transaction so an exercise can't be deleted between the check and the insert/update.
     */
    internal fun requireKnownExercisesUnlocked(
        c: SQLiteConnection,
        blocks: List<WorkoutBlock>,
        prefix: String = UnknownExerciseException.DEFAULT_PREFIX
    ) {
        val missing = unknownExerciseIdsUnlocked(c, SharedRoutineExercises.exerciseIds(blocks))
        if (missing.isNotEmpty()) throw UnknownExerciseException(missing.map { MissingExercise(it) }, prefix)
    }

    private fun unknownExerciseIdsUnlocked(c: SQLiteConnection, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val found = HashSet<String>()
        val placeholders = ids.joinToString(",") { "?" }
        c.prepare("SELECT id FROM exercises WHERE id IN ($placeholders) AND deleted_at_ms IS NULL").useStmt { s ->
            ids.forEachIndexed { idx, id -> s.bindText(idx + 1, id) }
            while (s.step()) found += s.getText(0)
        }
        return ids.filterNot { it in found }
    }

    /**
     * Resolves a shared routine's exercises against this library (rules on [SharedExerciseRef]):
     * known id, else name match, else [UnknownExerciseException] listing name and id. Returns the
     * blocks with matched ids rewritten.
     */
    internal fun resolveSharedExercisesUnlocked(
        c: SQLiteConnection,
        blocks: List<WorkoutBlock>,
        refs: Map<String, SharedExerciseRef>
    ): List<WorkoutBlock> {
        val unknown = unknownExerciseIdsUnlocked(c, SharedRoutineExercises.exerciseIds(blocks))
        if (unknown.isEmpty()) return blocks
        val library = if (unknown.any { it in refs }) {
            c.prepare("SELECT id, name FROM exercises WHERE deleted_at_ms IS NULL").useStmt { s ->
                val out = ArrayList<ExerciseName>()
                while (s.step()) out += ExerciseName(s.getText(0), s.getText(1))
                out
            }
        } else {
            emptyList()
        }
        val remap = HashMap<String, String>()
        val missing = ArrayList<MissingExercise>()
        for (id in unknown) {
            val ref = refs[id]
            val match = ref?.let { SharedRoutineExercises.pickByName(library, it.name) }
            if (match != null) remap[id] = match.id else missing += MissingExercise(id, ref?.name)
        }
        if (missing.isNotEmpty()) throw UnknownExerciseException(missing, UnknownExerciseException.IMPORT_PREFIX)
        return SharedRoutineExercises.rewriteIds(blocks, remap)
    }

    /** Share-payload entries for these ids, in the given order; deleted exercises included (names still help). */
    suspend fun getSharedExerciseRefs(ids: List<String>): List<SharedExerciseRef> = withDb { c ->
        if (ids.isEmpty()) return@withDb emptyList()
        val byId = HashMap<String, SharedExerciseRef>()
        val placeholders = ids.joinToString(",") { "?" }
        c.prepare("SELECT id, name, muscle_group, accessory FROM exercises WHERE id IN ($placeholders)").useStmt { s ->
            ids.forEachIndexed { idx, id -> s.bindText(idx + 1, id) }
            while (s.step()) {
                val ref = SharedExerciseRef(s.getText(0), s.getText(1), s.getText(2), s.getText(3))
                byId[ref.id] = ref
            }
        }
        ids.mapNotNull { byId[it] }
    }

    suspend fun deleteTemplate(templateId: String): Boolean = withTx { c ->
        val nowMs = System.currentTimeMillis()
        c.prepare("UPDATE workout_templates SET deleted_at_ms = ?, updated_at_ms = ?, dirty_sync = 1 WHERE id = ? AND deleted_at_ms IS NULL")
            .useStmt { s ->
                s.bindLong(1, nowMs)
                s.bindLong(2, nowMs)
                s.bindText(3, templateId)
                s.step()
            }
        true
    }

    // --- Sessions, Set Logs, PRs & Overload ---

    suspend fun createSession(userId: String, templateId: String?): WorkoutSession = withTx { c ->
        val id = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        val resolvedTemplateId = if (!templateId.isNullOrBlank()) {
            val found = c.prepare("SELECT name FROM workout_templates WHERE id = ? AND deleted_at_ms IS NULL").useStmt { s ->
                s.bindText(1, templateId)
                if (s.step()) s.getText(0) else null
            }
            if (found != null) templateId to found else null to null
        } else {
            null to null
        }
        val effectiveTemplateId = resolvedTemplateId.first
        val tplName = resolvedTemplateId.second
        c.prepare(
            """
            INSERT INTO workout_sessions(id, user_id, template_id, started_at, completed_at, total_volume, updated_at_ms, dirty_sync)
            VALUES (?, ?, ?, ?, NULL, 0.0, ?, 1)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, id)
            s.bindText(2, userId)
            bindNullableText(s, 3, effectiveTemplateId)
            s.bindText(4, nowIso)
            s.bindLong(5, nowMs)
            s.step()
        }
        WorkoutSession(
            id = id,
            userId = userId,
            templateId = effectiveTemplateId,
            startedAt = nowIso,
            completedAt = null,
            totalVolume = 0.0,
            template = tplName?.let { SessionTemplateSummary(it) },
            setLogs = emptyList()
        )
    }

    suspend fun listSessions(userId: String? = null, limit: Int = 25): List<WorkoutSession> = withDb { c ->
        val sql = buildString {
            append(
                """
                SELECT ws.id, ws.user_id, ws.template_id, ws.started_at, ws.completed_at, ws.total_volume, wt.name
                FROM workout_sessions ws
                LEFT JOIN workout_templates wt ON wt.id = ws.template_id
                """.trimIndent()
            )
            if (!userId.isNullOrBlank()) append(" WHERE ws.user_id = ?")
            append(" ORDER BY ws.started_at DESC LIMIT ?")
        }
        val rows = mutableListOf<WorkoutSession>()
        c.prepare(sql).useStmt { s ->
            var idx = 1
            if (!userId.isNullOrBlank()) s.bindText(idx++, userId)
            s.bindInt(idx, limit.coerceIn(1, 500))
            while (s.step()) {
                val sessId = s.getText(0)
                val uid = s.getText(1)
                val tplId = getNullableText(s, 2)
                val startedAt = s.getText(3)
                val completedAt = getNullableText(s, 4)
                val totalVol = s.getDouble(5)
                val tplName = getNullableText(s, 6)
                rows += WorkoutSession(
                    id = sessId,
                    userId = uid,
                    templateId = tplId,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    totalVolume = totalVol,
                    template = tplName?.let { SessionTemplateSummary(it) },
                    setLogs = emptyList()
                )
            }
        }
        rows.map { sess -> sess.copy(setLogs = listSetLogsForSessionUnlocked(c, sess.id)) }
    }

    suspend fun getSessionWithLogs(sessionId: String): WorkoutSession? = withDb { c ->
        val sess = c.prepare(
            """
            SELECT ws.id, ws.user_id, ws.template_id, ws.started_at, ws.completed_at, ws.total_volume, wt.name
            FROM workout_sessions ws
            LEFT JOIN workout_templates wt ON wt.id = ws.template_id
            WHERE ws.id = ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, sessionId)
            if (s.step()) {
                val tplName = getNullableText(s, 6)
                WorkoutSession(
                    id = s.getText(0),
                    userId = s.getText(1),
                    templateId = getNullableText(s, 2),
                    startedAt = s.getText(3),
                    completedAt = getNullableText(s, 4),
                    totalVolume = s.getDouble(5),
                    template = tplName?.let { SessionTemplateSummary(it) },
                    setLogs = emptyList()
                )
            } else {
                null
            }
        } ?: return@withDb null
        sess.copy(setLogs = listSetLogsForSessionUnlocked(c, sessionId))
    }

    internal fun listSetLogsForSessionUnlocked(c: SQLiteConnection, sessionId: String): List<SetLog> {
        val out = mutableListOf<SetLog>()
        c.prepare(
            """
            SELECT sl.id, sl.session_id, ws.user_id, sl.exercise_id, sl.set_number, sl.mode, sl.modifier_pct,
                   sl.weight_lb, sl.reps_completed, sl.volume_lb, sl.rpe, sl.timestamp,
                   ex.name, ex.muscle_group, ex.accessory
            FROM set_logs sl
            JOIN workout_sessions ws ON ws.id = sl.session_id
            LEFT JOIN exercises ex ON ex.id = sl.exercise_id
            WHERE sl.session_id = ?
            ORDER BY sl.timestamp ASC
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, sessionId)
            while (s.step()) {
                val exName = getNullableText(s, 12)
                val exMuscle = getNullableText(s, 13)
                val exAcc = getNullableText(s, 14)
                val ts = s.getText(11)
                out += SetLog(
                    id = s.getText(0),
                    sessionId = s.getText(1),
                    userId = s.getText(2),
                    exerciseId = s.getText(3),
                    setNumber = s.getInt(4),
                    mode = s.getText(5),
                    modifierPct = s.getInt(6),
                    weightLb = s.getDouble(7),
                    repsCompleted = s.getInt(8),
                    volumeLb = s.getDouble(9),
                    rpe = if (s.isNull(10)) null else s.getDouble(10),
                    timestamp = ts,
                    createdAt = ts,
                    exercise = exName?.let {
                        SetLogExerciseSummary(it, exMuscle ?: "", exAcc ?: "HANDLES")
                    }
                )
            }
        }
        return out
    }

    data class DbLoggedSetOutcome(
        val setLog: SetLog,
        val newPr: Boolean,
        val totalVolume: Double
    )

    suspend fun insertSetAndEvaluatePr(
        sessionId: String,
        effectiveUserId: String,
        exerciseId: String,
        setNumber: Int,
        mode: String,
        modifierPct: Int,
        weightLb: Double,
        repsCompleted: Int,
        volumeLb: Double,
        rpe: Double?,
        oneRepMaxEst: Double
    ): DbLoggedSetOutcome? = withTx { c ->
        val sessionExists = c.prepare("SELECT user_id, total_volume FROM workout_sessions WHERE id = ?").useStmt { s ->
            s.bindText(1, sessionId)
            if (s.step()) s.getText(0) else null
        } ?: return@withTx null

        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        val setId = UUID.randomUUID().toString()

        c.prepare(
            """
            INSERT INTO set_logs(
                id, session_id, exercise_id, set_number, mode, modifier_pct, weight_lb,
                reps_completed, volume_lb, rpe, timestamp, updated_at_ms, dirty_sync
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, setId)
            s.bindText(2, sessionId)
            s.bindText(3, exerciseId)
            s.bindInt(4, setNumber)
            s.bindText(5, mode)
            s.bindInt(6, modifierPct)
            s.bindDouble(7, weightLb)
            s.bindInt(8, repsCompleted)
            s.bindDouble(9, volumeLb)
            if (rpe != null) s.bindDouble(10, rpe) else s.bindNull(10)
            s.bindText(11, nowIso)
            s.bindLong(12, nowMs)
            s.step()
        }

        // Recompute authoritative session total_volume from set_logs to stay drift-free
        val newTotalVolume = c.prepare("SELECT COALESCE(SUM(volume_lb), 0.0) FROM set_logs WHERE session_id = ?").useStmt { s ->
            s.bindText(1, sessionId)
            if (s.step()) kotlin.math.round(s.getDouble(0) * 10.0) / 10.0 else volumeLb
        }

        c.prepare("UPDATE workout_sessions SET total_volume = ?, updated_at_ms = ?, dirty_sync = 1 WHERE id = ?").useStmt { s ->
            s.bindDouble(1, newTotalVolume)
            s.bindLong(2, nowMs)
            s.bindText(3, sessionId)
            s.step()
        }

        // Evaluate Personal Record
        val existingPr = getPrForExerciseUnlocked(c, effectiveUserId, exerciseId)
        val isNewPr = existingPr == null ||
            weightLb > existingPr.maxWeightLb ||
            oneRepMaxEst > existingPr.oneRepMaxEst

        if (isNewPr) {
            val nextMaxW = maxOf(weightLb, existingPr?.maxWeightLb ?: 0.0)
            val next1Rm = maxOf(oneRepMaxEst, existingPr?.oneRepMaxEst ?: 0.0)
            val prId = existingPr?.id ?: UUID.randomUUID().toString()
            c.prepare(
                """
                INSERT INTO personal_records(id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at, updated_at_ms, dirty_sync)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(user_id, exercise_id) DO UPDATE SET
                    one_rep_max_est = MAX(personal_records.one_rep_max_est, excluded.one_rep_max_est),
                    max_weight_lb = MAX(personal_records.max_weight_lb, excluded.max_weight_lb),
                    updated_at = excluded.updated_at,
                    updated_at_ms = excluded.updated_at_ms,
                    dirty_sync = 1
                """.trimIndent()
            ).useStmt { s ->
                s.bindText(1, prId)
                s.bindText(2, effectiveUserId)
                s.bindText(3, exerciseId)
                s.bindDouble(4, next1Rm)
                s.bindDouble(5, nextMaxW)
                s.bindText(6, nowIso)
                s.bindLong(7, nowMs)
                s.step()
            }
        }

        val setLog = SetLog(
            id = setId,
            sessionId = sessionId,
            userId = sessionExists,
            exerciseId = exerciseId,
            setNumber = setNumber,
            mode = mode,
            modifierPct = modifierPct,
            weightLb = weightLb,
            repsCompleted = repsCompleted,
            volumeLb = volumeLb,
            rpe = rpe,
            timestamp = nowIso,
            createdAt = nowIso
        )
        DbLoggedSetOutcome(setLog, isNewPr, newTotalVolume)
    }

    suspend fun markSessionCompleted(sessionId: String): WorkoutSession? = withTx { c ->
        val nowMs = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMs)
        c.prepare(
            "UPDATE workout_sessions SET completed_at = COALESCE(completed_at, ?), updated_at_ms = ?, dirty_sync = 1 WHERE id = ?"
        ).useStmt { s ->
            s.bindText(1, nowIso)
            s.bindLong(2, nowMs)
            s.bindText(3, sessionId)
            s.step()
        }
        val row = c.prepare(
            """
            SELECT ws.id, ws.user_id, ws.template_id, ws.started_at, ws.completed_at, ws.total_volume, wt.name
            FROM workout_sessions ws
            LEFT JOIN workout_templates wt ON wt.id = ws.template_id
            WHERE ws.id = ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, sessionId)
            if (s.step()) {
                val tplName = getNullableText(s, 6)
                WorkoutSession(
                    id = s.getText(0),
                    userId = s.getText(1),
                    templateId = getNullableText(s, 2),
                    startedAt = s.getText(3),
                    completedAt = getNullableText(s, 4),
                    totalVolume = s.getDouble(5),
                    template = tplName?.let { SessionTemplateSummary(it) },
                    setLogs = emptyList()
                )
            } else {
                null
            }
        } ?: return@withTx null
        row.copy(setLogs = listSetLogsForSessionUnlocked(c, sessionId))
    }

    suspend fun listPrs(userId: String): List<PersonalRecord> = withDb { c ->
        val out = mutableListOf<PersonalRecord>()
        c.prepare(
            """
            SELECT pr.id, pr.user_id, pr.exercise_id, pr.one_rep_max_est, pr.max_weight_lb, pr.updated_at,
                   ex.id, ex.name, ex.muscle_group, ex.accessory, ex.recommended_height, ex.recommended_rotation,
                   ex.recommended_angle, ex.unilateral, ex.notes, ex.media_url,
                   ex.allowed_heights, ex.allowed_rotations, ex.allowed_angles
            FROM personal_records pr
            LEFT JOIN exercises ex ON ex.id = pr.exercise_id
            WHERE pr.user_id = ?
            ORDER BY pr.updated_at DESC
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, userId)
            while (s.step()) {
                val exId = getNullableText(s, 6)
                val exercise = if (exId != null) {
                    Exercise(
                        id = exId,
                        name = getNullableText(s, 7) ?: "",
                        muscleGroup = getNullableText(s, 8) ?: "",
                        accessory = getNullableText(s, 9) ?: "HANDLES",
                        recommendedHeight = s.getInt(10),
                        recommendedRotation = s.getInt(11),
                        recommendedAngle = s.getInt(12),
                        unilateral = s.getInt(13) != 0,
                        notes = getNullableText(s, 14),
                        mediaUrl = getNullableText(s, 15),
                        allowedHeights = getAllowedList(s, 16, ArmPositionSets.HEIGHT_RANGE),
                        allowedRotations = getAllowedList(s, 17, ArmPositionSets.ROTATION_RANGE),
                        allowedAngles = getAllowedList(s, 18, ArmPositionSets.ANGLE_RANGE)
                    )
                } else null
                out += PersonalRecord(
                    id = s.getText(0),
                    userId = s.getText(1),
                    exerciseId = s.getText(2),
                    oneRepMaxEst = s.getDouble(3),
                    maxWeightLb = s.getDouble(4),
                    updatedAt = s.getText(5),
                    exercise = exercise
                )
            }
        }
        out
    }

    suspend fun getPrForExercise(userId: String, exerciseId: String): PersonalRecord? = withDb { c ->
        getPrForExerciseUnlocked(c, userId, exerciseId)
    }

    internal fun getPrForExerciseUnlocked(c: SQLiteConnection, userId: String, exerciseId: String): PersonalRecord? {
        return c.prepare(
            "SELECT id, user_id, exercise_id, one_rep_max_est, max_weight_lb, updated_at FROM personal_records WHERE user_id = ? AND exercise_id = ?"
        ).useStmt { s ->
            s.bindText(1, userId)
            s.bindText(2, exerciseId)
            if (s.step()) {
                PersonalRecord(
                    id = s.getText(0),
                    userId = s.getText(1),
                    exerciseId = s.getText(2),
                    oneRepMaxEst = s.getDouble(3),
                    maxWeightLb = s.getDouble(4),
                    updatedAt = s.getText(5)
                )
            } else {
                null
            }
        }
    }

    suspend fun getRecentSetLogsForExercise(userId: String, exerciseId: String, limit: Int = 4): List<SetLog> = withDb { c ->
        val out = mutableListOf<SetLog>()
        c.prepare(
            """
            SELECT sl.id, sl.session_id, ws.user_id, sl.exercise_id, sl.set_number, sl.mode, sl.modifier_pct,
                   sl.weight_lb, sl.reps_completed, sl.volume_lb, sl.rpe, sl.timestamp
            FROM set_logs sl
            JOIN workout_sessions ws ON ws.id = sl.session_id
            WHERE ws.user_id = ? AND sl.exercise_id = ?
            ORDER BY sl.timestamp DESC
            LIMIT ?
            """.trimIndent()
        ).useStmt { s ->
            s.bindText(1, userId)
            s.bindText(2, exerciseId)
            s.bindInt(3, limit)
            while (s.step()) {
                val ts = s.getText(11)
                out += SetLog(
                    id = s.getText(0),
                    sessionId = s.getText(1),
                    userId = s.getText(2),
                    exerciseId = s.getText(3),
                    setNumber = s.getInt(4),
                    mode = s.getText(5),
                    modifierPct = s.getInt(6),
                    weightLb = s.getDouble(7),
                    repsCompleted = s.getInt(8),
                    volumeLb = s.getDouble(9),
                    rpe = if (s.isNull(10)) null else s.getDouble(10),
                    timestamp = ts,
                    createdAt = ts
                )
            }
        }
        out
    }

    /** Creates an atomic SQLite snapshot backup via VACUUM INTO. */
    suspend fun exportBackupToFile(destFile: File): File = withDb { c ->
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()
        c.prepare("VACUUM INTO ?").useStmt { s ->
            s.bindText(1, destFile.absolutePath)
            s.step()
        }
        destFile
    }

    override fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    // --- Statement & Row Helpers ---

    internal fun queryLongUnlocked(c: SQLiteConnection, sql: String): Long =
        c.prepare(sql).useStmt { s -> if (s.step()) s.getLong(0) else 0L }

    internal fun bindNullableText(s: SQLiteStatement, idx: Int, value: String?) {
        if (value == null) s.bindNull(idx) else s.bindText(idx, value)
    }

    internal fun bindNullableLong(s: SQLiteStatement, idx: Int, value: Long?) {
        if (value == null) s.bindNull(idx) else s.bindLong(idx, value)
    }

    internal fun getNullableText(s: SQLiteStatement, idx: Int): String? =
        if (s.isNull(idx)) null else s.getText(idx)

    internal fun getNullableDouble(s: SQLiteStatement, idx: Int): Double? =
        if (s.isNull(idx)) null else s.getDouble(idx)

    internal fun getNullableLong(s: SQLiteStatement, idx: Int): Long? =
        if (s.isNull(idx)) null else s.getLong(idx)

    /** Reads a stored comma list ("4,5"); '' / NULL = empty (recommended value only). */
    internal fun getAllowedList(s: SQLiteStatement, idx: Int, range: IntRange): List<Int> =
        if (s.isNull(idx)) emptyList() else ArmPositionSets.parseCsv(s.getText(idx), range)

    internal fun normalizeAllowedSets(ex: Exercise): Exercise = ex.copy(
        allowedHeights = ArmPositionSets.normalize(ex.allowedHeights, ex.recommendedHeight, ArmPositionSets.HEIGHT_RANGE),
        allowedRotations = ArmPositionSets.normalize(ex.allowedRotations, ex.recommendedRotation, ArmPositionSets.ROTATION_RANGE),
        allowedAngles = ArmPositionSets.normalize(ex.allowedAngles, ex.recommendedAngle, ArmPositionSets.ANGLE_RANGE)
    )

    /** Binds normalised allowed_heights, allowed_rotations, allowed_angles at idx, idx + 1, idx + 2. */
    internal fun bindAllowedSets(s: SQLiteStatement, idx: Int, ex: Exercise) {
        val n = normalizeAllowedSets(ex)
        s.bindText(idx, ArmPositionSets.toCsv(n.allowedHeights))
        s.bindText(idx + 1, ArmPositionSets.toCsv(n.allowedRotations))
        s.bindText(idx + 2, ArmPositionSets.toCsv(n.allowedAngles))
    }

    private fun readUserFromRow(s: SQLiteStatement): User = User(
        id = s.getText(0),
        name = s.getText(1),
        avatarColor = s.getText(2),
        preferredMusicProvider = s.getText(3),
        themeMode = s.getText(4),
        accentColor = s.getText(5),
        weightUnit = s.getText(6),
        createdAt = s.getText(7),
        updatedAt = s.getText(8)
    )

    private fun readExerciseFromRow(s: SQLiteStatement): Exercise = Exercise(
        id = s.getText(0),
        name = s.getText(1),
        muscleGroup = s.getText(2),
        accessory = s.getText(3),
        recommendedHeight = s.getInt(4),
        recommendedRotation = s.getInt(5),
        recommendedAngle = s.getInt(6),
        unilateral = s.getInt(7) != 0,
        notes = getNullableText(s, 8),
        mediaUrl = getNullableText(s, 9),
        allowedHeights = getAllowedList(s, 10, ArmPositionSets.HEIGHT_RANGE),
        allowedRotations = getAllowedList(s, 11, ArmPositionSets.ROTATION_RANGE),
        allowedAngles = getAllowedList(s, 12, ArmPositionSets.ANGLE_RANGE)
    )

    private fun readTemplateFromRow(s: SQLiteStatement): WorkoutTemplate {
        val blocksJson = s.getText(4)
        val scheduleJson = getNullableText(s, 5)
        val blocks = runCatching { SwonalJson.decodeFromString<List<WorkoutBlock>>(blocksJson) }.getOrDefault(emptyList())
        val days = scheduleJson?.let {
            runCatching { SwonalJson.decodeFromString<List<Int>>(it) }.getOrDefault(emptyList())
                .filter { d -> d in 0..6 }
        }.orEmpty()
        return WorkoutTemplate(
            id = s.getText(0),
            userId = s.getText(1),
            name = s.getText(2),
            description = getNullableText(s, 3),
            blocksJson = blocksJson,
            blocks = blocks,
            scheduleDays = days,
            createdAt = s.getText(6),
            updatedAt = s.getText(7)
        )
    }
}

internal inline fun <R> SQLiteStatement.useStmt(block: (SQLiteStatement) -> R): R {
    try {
        return block(this)
    } finally {
        runCatching { close() }
    }
}
