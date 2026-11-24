package com.swonal.kiosk.sync

import android.content.Context
import android.content.SharedPreferences
import com.swonal.kiosk.db.SwonalDatabase
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
            }
            syncManager.stop()
        } finally {
            scope.cancel()
            mockServer.stop()
            dir.deleteRecursively()
        }
    }
}
