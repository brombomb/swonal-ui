package com.swonal.kiosk.music

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.getMusicIntegration
import com.swonal.kiosk.net.LocalDaemonFailSafeClient
import com.swonal.kiosk.util.PlaintextTestCipher
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class LocalSpotifyControllerTest {

    @Test
    fun `PKCE flow stores tokens, provisions local daemon, handles 401 refresh and 429 Retry-After`() = runBlocking {
        val db = SwonalDatabase(":memory:", PlaintextTestCipher)
        db.seedIfEmpty(
            """{ "users": [ { "id": "u-1", "name": "Alex", "weightUnit": "LB" } ] }""",
            includeDemoUsersAndRoutines = true
        )

        var provisionedToken: String? = null
        val daemonServer = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val uri = session.uri
                return when (uri) {
                    "/api/spotify/auth" -> {
                        val files = HashMap<String, String>()
                        runCatching { session.parseBody(files) }
                        val body = files["postData"].orEmpty()
                        if (body.contains("access-initial-123")) {
                            provisionedToken = "access-initial-123"
                        }
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"running":true}""")
                    }
                    "/api/spotify/status" -> {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"running":true,"has_cached_credentials":true,"device_name":"Tonal Speakers"}"""
                        )
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
                }
            }
        }
        daemonServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val localDaemon = LocalDaemonFailSafeClient(
            daemonBaseUrlProvider = { "http://127.0.0.1:${daemonServer.listeningPort}" },
            scope = testScope,
            uiAliveCheck = { true }
        )

        val playerCalls = AtomicInteger(0)
        var trigger429Next = false
        val mockHttp = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            when {
                req.url.host == "accounts.spotify.com" && path == "/api/token" -> {
                    val bodyStr = okio.Buffer().also { req.body?.writeTo(it) }.readUtf8()
                    val json = if (bodyStr.contains("authorization_code")) {
                        """{"access_token":"access-initial-123","refresh_token":"refresh-xyz","expires_in":3600}"""
                    } else {
                        """{"access_token":"access-refreshed-456","expires_in":3600}"""
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                req.url.host == "api.spotify.com" && path == "/v1/me/player" -> {
                    val callIdx = playerCalls.incrementAndGet()
                    if (trigger429Next) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(429)
                            .header("Retry-After", "12")
                            .message("Too Many Requests")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else if (callIdx == 1) {
                        // Force 401 -> automatic refresh_token flow -> retry succeeds!
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        val json = """
                            {
                              "is_playing": true,
                              "progress_ms": 12000,
                              "device": { "id": "dev-tonal", "volume_percent": 80 },
                              "item": {
                                "name": "Til I Collapse",
                                "duration_ms": 297000,
                                "artists": [{ "name": "Eminem" }],
                                "album": { "name": "The Eminem Show", "images": [] }
                              }
                            }
                        """.trimIndent()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
                else -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"devices":[],"items":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }).build()

        try {
            val spotify = LocalSpotifyController(
                db = db,
                localDaemon = localDaemon,
                scope = testScope,
                httpClientOverride = mockHttp
            )

            // Disconnected playlists fall back cleanly to built-in workout playlists
            val fallbackPls = spotify.getPlaylists("u-1")
            assertFalse(fallbackPls.isEmpty())

            // PKCE auth URL + exchange
            val authUrl = spotify.buildSpeakerPkceUrl("u-1")
            assertTrue(authUrl.contains("code_challenge_method=S256"))
            assertTrue(authUrl.contains("client_id=${LocalSpotifyController.KEYMASTER_CLIENT_ID}"))

            spotify.exchangeKeymasterPkceCode("u-1", "http://127.0.0.1:8898/login?code=pkce-code-abc&state=u-1")
            val stored = db.getMusicIntegration("u-1", "spotify")
            assertNotNull(stored)
            assertEquals("access-initial-123", stored?.accessToken)
            assertEquals("access-initial-123", provisionedToken)

            // 401 auto-refresh on GET /v1/me/player -> updates token to access-refreshed-456 & returns track!
            val playback = spotify.getPlaybackState("u-1")
            assertTrue(playback.isPlaying)
            assertEquals("Til I Collapse", playback.trackName)
            assertEquals("Eminem", playback.artistName)
            assertEquals("access-refreshed-456", db.getMusicIntegration("u-1", "spotify")?.accessToken)

            // 429 rate-limit triggers backoff
            trigger429Next = true
            spotify.play("u-1")
            assertTrue(spotify.rateLimitedUntilMs > System.currentTimeMillis())
            var threwRateLimit = false
            try {
                spotify.pause("u-1")
            } catch (e: IOException) {
                threwRateLimit = e.message?.contains("rate-limited") == true
            }
            assertTrue(threwRateLimit)
            spotify.resetRateLimit()
            assertEquals(0L, spotify.rateLimitedUntilMs)
        } finally {
            testScope.cancel()
            daemonServer.stop()
        }
    }
}
