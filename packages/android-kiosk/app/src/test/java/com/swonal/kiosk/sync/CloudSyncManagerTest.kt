package com.swonal.kiosk.sync

import android.content.Context
import android.content.SharedPreferences
import com.swonal.kiosk.db.StoredExerciseSyncRecord
import com.swonal.kiosk.db.StoredPrSyncRecord
import com.swonal.kiosk.db.StoredSessionSyncRecord
import com.swonal.kiosk.db.StoredSetLogSyncRecord
import com.swonal.kiosk.db.StoredUserSyncRecord
import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.SyncPullResponse
import com.swonal.kiosk.db.applyPulledSync
import com.swonal.kiosk.db.countDirtySyncRows
import com.swonal.kiosk.engine.LocalWorkoutEngine
import com.swonal.kiosk.model.QueuedWorkoutPayload
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.net.SyncMode
import com.swonal.kiosk.util.PlaintextTestCipher
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class CloudSyncManagerTest {

    private class MemoryPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(k: String?, d: String?): String? = map[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = d
        override fun getInt(k: String?, d: Int): Int = map[k] as? Int ?: d
        override fun getLong(k: String?, d: Long): Long = map[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float): Float = map[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean): Boolean = map[k] as? Boolean ?: d
        override fun contains(k: String?): Boolean = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(k: String?, v: String?) = apply { if (k != null) pending[k] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { }
            override fun putInt(k: String?, v: Int) = apply { if (k != null) pending[k] = v }
            override fun putLong(k: String?, v: Long) = apply { if (k != null) pending[k] = v }
            override fun putFloat(k: String?, v: Float) = apply { if (k != null) pending[k] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) pending[k] = v }
            override fun remove(k: String?) = apply { if (k != null) pending[k] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    @Test
    fun `offline dirty workout pushes cleanly, clears dirty count, and receives MCP queued workout`() {
        val dir = Files.createTempDirectory("swonal-sync-mgr-test-").toFile()
        val memPrefs = MemoryPrefs()
        val ctx = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = memPrefs
            override fun getApplicationContext(): Context = this
        }
        val prefs = SwonalPreferences(ctx)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val acked = AtomicBoolean(false)
        val pushedSeen = AtomicBoolean(false)
        val ackQueuedAtSeen = AtomicBoolean(false)
        val mockServer = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                return when (session.uri) {
                    "/api/sync/push" -> {
                        val files = HashMap<String, String>()
                        runCatching { session.parseBody(files) }
                        val body = files["postData"].orEmpty()
                        if (body.contains("ex-1") && body.contains("145.0")) {
                            pushedSeen.set(true)
                        }
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"ok":true,"serverTimeMs":2000000,"exerciseIdRemap":{},"acceptedCounts":{"sessions":1,"setLogs":1}}"""
                        )
                    }
                    "/api/sync/pull" -> {
                        val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
                        val queuedJson = if (since > 0L && !acked.get()) {
                            """, "pendingQueuedWorkout": { "userId": "u-1", "templateId": "tpl-mcp-1", "templateName": "AI Hypertrophy Push", "blocks": [], "queuedAt": "2026-11-24T09:00:00Z" }"""
                        } else {
                            ""
                        }
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"serverTimeMs":2000500,"users":[],"exercises":[],"templates":[],"sessions":[],"setLogs":[],"personalRecords":[],"friends":[]$queuedJson}"""
                        )
                    }
                    "/api/sync/ack-queued-workout" -> {
                        val files = HashMap<String, String>()
                        runCatching { session.parseBody(files) }
                        if (files["postData"].orEmpty().contains("2026-11-24T09:00:00Z")) {
                            ackQueuedAtSeen.set(true)
                        }
                        acked.set(true)
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
                }
            }
        }
        mockServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        try {
            prefs.syncMode = SyncMode.SERVER_SYNC
            prefs.syncServerUrl = "http://127.0.0.1:${mockServer.listeningPort}"

            val db = SwonalDatabase(":memory:", PlaintextTestCipher)
            val engine = LocalWorkoutEngine(db)
            var deliveredQueue: QueuedWorkoutPayload? = null

            val syncManager = CloudSyncManager(
                db = db,
                engine = engine,
                prefs = prefs,
                scope = scope,
                onWorkoutQueued = { q -> deliveredQueue = q }
            )

            runBlocking {
                db.seedIfEmpty(
                    """
                    {
                      "users": [ { "id": "u-1", "name": "Alex", "weightUnit": "LB" } ],
                      "exercises": [ { "id": "ex-1", "name": "Bench Press", "muscleGroup": "Chest", "accessory": "HANDLES", "defaultWeight": 45, "unilateral": false, "armAngle": "LOW", "armRotation": "OUT", "railHeight": "BENCH" } ]
                    }
                    """.trimIndent(),
                    includeDemoUsersAndRoutines = true
                )
                val sess = engine.createSession("u-1")
                engine.logSet(sess.id, "u-1", "ex-1", 1, "STANDARD", 0, 145.0, 8)
                engine.completeSession(sess.id)
                assertTrue(db.countDirtySyncRows() > 0)

                val res = syncManager.syncNow()
                assertTrue(res.isSuccess)
                assertTrue(pushedSeen.get())
                assertEquals(0, db.countDirtySyncRows())
                assertNotNull(deliveredQueue)
                assertEquals("AI Hypertrophy Push", deliveredQueue?.templateName)
                assertTrue(acked.get())
                assertTrue(ackQueuedAtSeen.get())
            }
            syncManager.stop()
        } finally {
            scope.cancel()
            mockServer.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `applyPulledSync merges PR max on exercise remap, handles tombstoned FKs, and missing templateId falls back safely`() = runBlocking {
        val db = SwonalDatabase(":memory:", PlaintextTestCipher)
        val engine = LocalWorkoutEngine(db)
        db.seedIfEmpty(
            """
            {
              "users": [ { "id": "u-1", "name": "Alex", "weightUnit": "LB" } ],
              "exercises": [
                { "id": "ex-local", "name": "Incline Press", "muscleGroup": "Chest", "accessory": "HANDLES", "defaultWeight": 45, "unilateral": false, "armAngle": "LOW", "armRotation": "OUT", "railHeight": "BENCH" }
              ]
            }
            """.trimIndent(),
            includeDemoUsersAndRoutines = true
        )
        // Log high PR on local ID + lower PR already on canonical server ID
        val s1 = engine.createSession("u-1")
        engine.logSet(s1.id, "u-1", "ex-local", 1, "STANDARD", 0, 180.0, 5) // 1RM = 210.0

        val now = System.currentTimeMillis()
        db.applyPulledSync(
            SyncPullResponse(
                serverTimeMs = now,
                users = listOf(
                    StoredUserSyncRecord(
                        id = "u-deleted",
                        name = "Former User",
                        avatarColor = "#3B82F6",
                        preferredMusicProvider = "spotify",
                        themeMode = "dark",
                        accentColor = "#00F0FF",
                        weightUnit = "LB",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-02T00:00:00Z",
                        updatedAtMs = now,
                        deletedAtMs = now
                    )
                ),
                exercises = listOf(
                    StoredExerciseSyncRecord(
                        id = "ex-canonical-srv",
                        name = "Incline Press",
                        muscleGroup = "Chest",
                        accessory = "HANDLES",
                        updatedAtMs = now + 100
                    )
                ),
                personalRecords = listOf(
                    StoredPrSyncRecord(
                        id = "pr-srv",
                        userId = "u-1",
                        exerciseId = "ex-canonical-srv",
                        oneRepMaxEst = 120.0,
                        maxWeightLb = 100.0,
                        updatedAt = "2026-01-02T00:00:00Z",
                        updatedAtMs = now
                    )
                ),
                sessions = listOf(
                    StoredSessionSyncRecord(
                        id = "sess-hist-deleted-user",
                        userId = "u-deleted",
                        templateId = null,
                        startedAt = "2026-01-01T10:00:00Z",
                        completedAt = "2026-01-01T10:30:00Z",
                        totalVolume = 500.0,
                        updatedAtMs = now
                    )
                ),
                setLogs = listOf(
                    StoredSetLogSyncRecord(
                        id = "sl-hist-1",
                        sessionId = "sess-hist-deleted-user",
                        exerciseId = "ex-canonical-srv",
                        setNumber = 1,
                        mode = "standard",
                        weightLb = 50.0,
                        repsCompleted = 10,
                        volumeLb = 500.0,
                        timestamp = "2026-01-01T10:15:00Z",
                        updatedAtMs = now
                    )
                )
            )
        )

        val prs = db.listPrs("u-1")
        val mergedPr = prs.firstOrNull { it.exerciseId == "ex-canonical-srv" }
        assertNotNull(mergedPr)
        assertEquals(180.0, mergedPr?.maxWeightLb)
        assertTrue((mergedPr?.oneRepMaxEst ?: 0.0) >= 210.0)

        // Creating a session with a templateId not yet present locally must not throw FK exception
        val fallbackSession = engine.createSession("u-1", "template-not-yet-synced")
        assertNull(fallbackSession.templateId)
    }
}
