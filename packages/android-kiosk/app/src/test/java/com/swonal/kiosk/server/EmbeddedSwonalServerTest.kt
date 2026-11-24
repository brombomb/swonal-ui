package com.swonal.kiosk.server

import android.content.Context
import android.content.SharedPreferences
import com.swonal.kiosk.db.RestoreBundleDto
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.LocalDaemonFailSafeClient
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.net.SwonalRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EmbeddedSwonalServerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }

    private fun testContext(dir: File): Context {
        val prefs = InMemorySharedPreferences()
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun `REST CRUD, PATCH, CSV export, restore bundle, path traversal guard, auth lock, and WebSocket work cleanly`() {
        val tempDir = Files.createTempDirectory("swonal-embedded-test-").toFile()
        val mediaDir = File(tempDir, "media").apply { mkdirs() }
        val prefs = SwonalPreferences(testContext(tempDir))
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val disableCalls = AtomicInteger(0)
        val mockDaemon = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/api/motor/disable") {
                    disableCalls.incrementAndGet()
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"status":"ok","mock_mode":true}""")
            }
        }
        mockDaemon.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        prefs.localDaemonUrl = "http://127.0.0.1:${mockDaemon.listeningPort}"

        val seedJson = """
            {
              "users": [
                { "id": "u-alex", "name": "Alex", "avatarColor": "#3B82F6", "weightUnit": "LB" }
              ],
              "exercises": [
                { "id": "ex-bench", "name": "Bench Press", "muscleGroup": "Chest", "accessory": "HANDLES", "defaultWeight": 45, "unilateral": false, "armAngle": "LOW", "armRotation": "OUT", "railHeight": "BENCH" }
              ]
            }
        """.trimIndent()

        val repo = SwonalRepository(
            prefs = prefs,
            scope = testScope,
            localDaemonOverride = LocalDaemonFailSafeClient(
                daemonBaseUrlProvider = { "http://127.0.0.1:${mockDaemon.listeningPort}" },
                scope = testScope,
                uiAliveCheck = { true }
            ),
            localDbPath = ":memory:",
            seedCatalogJson = seedJson
        )

        runBlocking {
            repo.localEngine.initialize(seedJson, includeDemoUsersAndRoutines = true)
            repo.refreshAppState()
        }

        val server = EmbeddedSwonalServer(
            repo = repo,
            port = 0,
            mediaDir = mediaDir,
            webAssetOpener = { rel ->
                if (rel == "index.html") ByteArrayInputStream("<html><body>Swonal SPA</body></html>".toByteArray()) else null
            },
            mediaAssetOpener = { name ->
                if (name == "bench.svg") ByteArrayInputStream("""<svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray()) else null
            },
            allowTestRemoteIpHeader = true
        )
        server.startServer()

        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val baseUrl = "http://127.0.0.1:${server.listeningPort}"
        val jsonType = "application/json; charset=utf-8".toMediaType()

        try {
            // 1. SPA HTML + bundled SVG asset
            http.newCall(Request.Builder().url("$baseUrl/companion").get().build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body?.string().orEmpty().contains("Swonal SPA"))
            }
            http.newCall(Request.Builder().url("$baseUrl/media/bench.svg").get().build()).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.header("Content-Type").orEmpty().contains("image/svg+xml"))
            }

            // 2. Strict path traversal rejection
            http.newCall(Request.Builder().url("$baseUrl/media/..%2f..%2fetc%2fpasswd").get().build()).execute().use { r ->
                assertEquals(403, r.code)
            }
            http.newCall(Request.Builder().url("$baseUrl/media/../secret.txt").get().build()).execute().use { r ->
                assertTrue(r.code in listOf(400, 403, 404))
            }

            // 3. GET /api/state, POST /api/users, and PATCH /api/users/:id
            http.newCall(Request.Builder().url("$baseUrl/api/state").get().build()).execute().use { r ->
                assertEquals(200, r.code)
                val obj = SwonalJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                assertTrue(obj["users"]?.jsonArray?.isNotEmpty() == true)
            }
            val createdUserId: String
            http.newCall(
                Request.Builder().url("$baseUrl/api/users")
                    .post("""{"name":"Jordan","weightUnit":"KG"}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(201, r.code)
                val u = SwonalJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                createdUserId = u["id"]!!.jsonPrimitive.content
                assertEquals("Jordan", u["name"]!!.jsonPrimitive.content)
            }
            http.newCall(
                Request.Builder().url("$baseUrl/api/users/$createdUserId")
                    .patch("""{"name":"Jordan Updated","accentColor":"#FF5500"}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(200, r.code)
                val u = SwonalJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                assertEquals("Jordan Updated", u["name"]!!.jsonPrimitive.content)
                assertEquals("#FF5500", u["accentColor"]!!.jsonPrimitive.content)
            }

            // 4. Create session, log set, export CSV
            val sessId: String
            http.newCall(
                Request.Builder().url("$baseUrl/api/sessions")
                    .post("""{"userId":"u-alex"}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(201, r.code)
                sessId = SwonalJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["id"]!!.jsonPrimitive.content
            }
            http.newCall(
                Request.Builder().url("$baseUrl/api/sessions/$sessId/sets")
                    .post("""{"exerciseId":"ex-bench","setNumber":1,"weightLb":135,"repsCompleted":8}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(201, r.code)
            }
            http.newCall(Request.Builder().url("$baseUrl/api/export/sessions.csv?userId=u-alex").get().build()).execute().use { r ->
                assertEquals(200, r.code)
                val csv = r.body?.string().orEmpty()
                assertTrue(csv.contains("SessionId,Routine,StartedAt"))
                assertTrue(csv.contains("Bench Press"))
                assertTrue(csv.contains("135"))
            }

            // 5. GET + POST /api/admin/restore/json roundtrip
            val bundleRaw: String
            http.newCall(Request.Builder().url("$baseUrl/api/admin/restore/json").get().build()).execute().use { r ->
                assertEquals(200, r.code)
                bundleRaw = r.body?.string().orEmpty()
                val bundle = SwonalJson.decodeFromString(RestoreBundleDto.serializer(), bundleRaw)
                assertTrue(bundle.sessions.any { it.id == sessId })
            }
            http.newCall(
                Request.Builder().url("$baseUrl/api/admin/restore/json")
                    .post(bundleRaw.toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(200, r.code)
                assertTrue(r.body?.string().orEmpty().contains(""""ok":true"""))
            }

            // 6. Real OkHttp WebSocket on /ws + ping/pong + safe close while Kiosk UI alive
            repo.markUiHeartbeat(true)
            val wsMessages = CopyOnWriteArrayList<String>()
            val initLatch = CountDownLatch(2)
            val pongLatch = CountDownLatch(1)
            val closedLatch = CountDownLatch(1)
            val ws = http.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:${server.listeningPort}/ws").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ping"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        wsMessages += text
                        initLatch.countDown()
                        if (text.contains(""""type":"pong"""")) {
                            pongLatch.countDown()
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedLatch.countDown()
                    }
                }
            )
            assertTrue(initLatch.await(5, TimeUnit.SECONDS))
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS))
            assertTrue(wsMessages.any { it.contains(""""type":"session_state"""") })
            assertTrue(wsMessages.any { it.contains(""""type":"telemetry"""") })
            ws.close(1000, "phone locked")
            closedLatch.await(3, TimeUnit.SECONDS)
            Thread.sleep(150)
            // Kiosk UI heartbeat is alive -> phone WS disconnect must NEVER disable Kiosk weight!
            assertEquals(0, disableCalls.get())

            // 7. Passcode lock blocks non-loopback requests until /api/auth/login succeeds
            http.newCall(
                Request.Builder().url("$baseUrl/api/auth/configure")
                    .post("""{"enabled":true,"passcode":"2468","trustLocalhost":true}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(200, r.code)
            }
            http.newCall(
                Request.Builder().url("$baseUrl/api/users")
                    .header("x-swonal-test-remote-ip", "192.168.1.42")
                    .get()
                    .build()
            ).execute().use { r ->
                assertEquals(401, r.code)
            }
            val sessionCookie: String
            http.newCall(
                Request.Builder().url("$baseUrl/api/auth/login")
                    .header("x-swonal-test-remote-ip", "192.168.1.42")
                    .post("""{"passcode":"2468"}""".toRequestBody(jsonType))
                    .build()
            ).execute().use { r ->
                assertEquals(200, r.code)
                val setCookie = r.header("Set-Cookie")
                assertNotNull(setCookie)
                sessionCookie = setCookie!!.substringBefore(';')
            }
            http.newCall(
                Request.Builder().url("$baseUrl/api/users")
                    .header("x-swonal-test-remote-ip", "192.168.1.42")
                    .header("Cookie", sessionCookie)
                    .get()
                    .build()
            ).execute().use { r ->
                assertEquals(200, r.code)
                assertFalse(r.body?.string().isNullOrBlank())
            }
        } finally {
            server.stopServer()
            mockDaemon.stop()
            testScope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
