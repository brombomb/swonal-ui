package com.swonal.kiosk.net

import com.swonal.kiosk.model.CoachSuggestedAction
import com.swonal.kiosk.model.WsInboundMessage
import com.swonal.kiosk.model.WsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SwonalProtocolTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `WsMessageParser parses all server event types and handles malformed input gracefully`() {
        assertInstanceOf(WsInboundMessage.Pong::class.java, WsMessageParser.parse("""{"type":"pong"}"""))
        assertInstanceOf(WsInboundMessage.DaemonStatus::class.java, WsMessageParser.parse("""{"type":"daemon_status","connected":true}"""))
        assertInstanceOf(WsInboundMessage.SessionStateUpdate::class.java, WsMessageParser.parse("""{"type":"session_state","state":{"activeUserId":"u1"}}"""))
        assertInstanceOf(WsInboundMessage.Handle::class.java, WsMessageParser.parse("""{"type":"handle_event","action":"toggle_weight","source":"ble"}"""))
        assertInstanceOf(WsInboundMessage.ServerError::class.java, WsMessageParser.parse("""{"type":"error","message":"fault"}"""))
        assertInstanceOf(WsInboundMessage.SetLogged::class.java, WsMessageParser.parse("""{"type":"set_logged","setLog":{"id":"s1","weightLb":50,"repsCompleted":10},"newPr":true,"totalVolume":500}"""))
        assertInstanceOf(WsInboundMessage.PartnerRotated::class.java, WsMessageParser.parse("""{"type":"partner_rotated","userId":"u2","targetWeightLb":45,"exerciseId":"e1"}"""))
        assertInstanceOf(WsInboundMessage.MusicUpdated::class.java, WsMessageParser.parse("""{"type":"music_updated","userId":"u1","provider":"spotify"}"""))
        assertInstanceOf(WsInboundMessage.UsersUpdated::class.java, WsMessageParser.parse("""{"type":"users_updated"}"""))
        assertInstanceOf(WsInboundMessage.UserUpdated::class.java, WsMessageParser.parse("""{"type":"user_updated","user":{"id":"u1","name":"Jeri"}}"""))
        assertInstanceOf(WsInboundMessage.TemplatesUpdated::class.java, WsMessageParser.parse("""{"type":"templates_updated"}"""))
        assertInstanceOf(WsInboundMessage.ExercisesUpdated::class.java, WsMessageParser.parse("""{"type":"exercises_updated"}"""))
        assertInstanceOf(WsInboundMessage.DemoPipUpdated::class.java, WsMessageParser.parse("""{"type":"demo_pip_updated","userId":"u1","enabled":false}"""))
        assertInstanceOf(WsInboundMessage.Unknown::class.java, WsMessageParser.parse("""{"type":"future_event_xyz"}"""))
        assertNull(WsMessageParser.parse("{invalid json"))
        assertNull(WsMessageParser.parse("""{"no_type":1}"""))

        val telemetryJson = """
            {
              "type": "telemetry",
              "mode": "CUSTOM",
              "weight_enabled": true,
              "target_weight_lb": 65.0,
              "applied_resistance_lb": 65.0,
              "profile_mode": "eccentric",
              "modifier_pct": 25,
              "rep_count": 4,
              "accessory": "BAR",
              "left_cable": {"position": 112.5, "velocity": 50.8, "load_lb": 32.5},
              "right_cable": {"position": 110.0, "velocity": 49.0, "load_lb": 32.5},
              "arm_positions": {
                "height": {"left": 3, "right": 3},
                "rotation": {"left": 2, "right": 2, "left_unlocked": false, "right_unlocked": false},
                "angle": {"left": 1, "right": 1}
              }
            }
        """.trimIndent()
        val parsed = WsMessageParser.parse(telemetryJson) as WsInboundMessage.Telemetry
        assertTrue(parsed.frame.weightEnabled)
        assertEquals(65.0, parsed.frame.targetWeightLb, 0.001)
        assertEquals(3, parsed.frame.armPositions.height.left)
    }

    @Test
    fun `SwonalApiClient parses Python float bar_tilt_dwell_ms and encodes path segments properly`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"state":"IDLE_WEIGHT_OFF","mock_mode":true,"port":"/dev/ttyACM0","expected_board":"Tonal","expected_firmware":"1.0","zero_offsets":{"left":0.1,"right":0.2},"last_calibrated_at":null,"bar_tilt_threshold_delta":35.0,"bar_tilt_dwell_ms":250.0}"""
                )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"sess-1","userId":"u1"}"""))

        val client = SwonalApiClient(
            baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
            tokenProvider = { "secret-token-123" }
        )

        // Verifies float 250.0 deserialization (fixes C-1 crash!)
        val cal = client.getCalibration()
        assertEquals(250.0, cal.barTiltDwellMs, 0.001)
        val calReq = server.takeRequest()
        assertEquals("/api/daemon/calibration", calReq.path)
        assertEquals("Bearer secret-token-123", calReq.getHeader("Authorization"))

        // Verifies space in path segment encodes to %20 rather than + (fixes C-7!)
        client.getPlaylistTracks("plexamp", "My Pump Playlist", "u1")
        val tracksReq = server.takeRequest()
        assertEquals("/api/music/plexamp/playlists/My%20Pump%20Playlist/tracks?userId=u1", tracksReq.path)

        // Verifies blank templateId is omitted from createSession body (fixes C-10!)
        client.createSession("u1", "")
        val sessReq = server.takeRequest()
        assertFalse(sessReq.body.readUtf8().contains("templateId"))
    }

    @Test
    fun `CookieJar isolates swonal_session cookie strictly to configured server origin`() = runBlocking {
        val otherServer = MockWebServer()
        otherServer.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "swonal_session=abc.hmac123; Path=/; HttpOnly")
                    .setBody("""{"ok":true,"authenticated":true}""")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"enabled":true,"authenticated":true}"""))
            otherServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val client = SwonalApiClient(
                baseUrlProvider = { server.url("/").toString().removeSuffix("/") }
            )

            client.login("1234")
            server.takeRequest()

            // Request to configured server receives exactly ONE swonal_session cookie
            client.getAuthStatus()
            val authReq = server.takeRequest()
            val cookieHeaders = authReq.headers.values("Cookie")
            assertEquals(1, cookieHeaders.size)
            assertTrue(cookieHeaders.first().contains("swonal_session=abc.hmac123"))

            // Request to a third-party host via same client receives ZERO cookies
            val thirdPartyReq = Request.Builder().url(otherServer.url("/album.jpg")).get().build()
            client.okHttpClient.newCall(thirdPartyReq).execute().use { /* noop */ }
            val leakedReq = otherServer.takeRequest()
            assertNull(leakedReq.getHeader("Cookie"))
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun `SwonalPreferences enforces https default, loopback daemon URL, and header-only WS URL`() {
        assertEquals("https://swonal.example.com", SwonalPreferences.normalizeBaseUrl("swonal.example.com"))
        assertEquals("http://192.168.1.50:3000", SwonalPreferences.normalizeBaseUrl("192.168.1.50:3000"))
        assertEquals("http://127.0.0.1:8765", SwonalPreferences.normalizeLoopbackDaemonUrl(""))
        assertEquals("http://127.0.0.1:8765", SwonalPreferences.normalizeLoopbackDaemonUrl("https://evil.example:8765"))
        assertEquals("http://localhost:8765", SwonalPreferences.normalizeLoopbackDaemonUrl("http://localhost:8765"))

        val wsUrl = SwonalPreferences.toWebSocketUrl("https://swonal.example.com")
        assertEquals("wss://swonal.example.com/ws", wsUrl)
        assertFalse(wsUrl.contains("token="))
    }

    @Test
    fun `sanitizeCoachAction clamps LLM weight delta and rejects untrusted labels or motor engagement`() {
        val hugeSpike = SwonalRepository.sanitizeCoachAction(
            CoachSuggestedAction(type = "set_weight_delta", deltaLb = 180.0, label = "+5 lbs")
        )
        assertNotNull(hugeSpike)
        assertEquals(20.0, hugeSpike!!.deltaLb!!, 0.001)
        assertEquals("Adjust +20 LB", hugeSpike.label)

        val blockedEngage = SwonalRepository.sanitizeCoachAction(
            CoachSuggestedAction(type = "toggle_weight", deltaLb = null, label = "Skip Rest")
        )
        assertNull(blockedEngage)

        val skipRest = SwonalRepository.sanitizeCoachAction(
            CoachSuggestedAction(type = "skip_rest", deltaLb = null, label = "Spoofed")
        )
        assertEquals("Skip Rest Timer", skipRest?.label)
    }

    @Test
    fun `SwonalTlsFactory loads bundled modern Root CA PEM stream cleanly`() {
        val pemFile = File("src/main/res/raw/swonal_extra_roots.pem")
        assertTrue(pemFile.exists(), "Bundled modern root CA PEM must exist")
        val tls = pemFile.inputStream().use { SwonalTlsFactory.createFromPemStream(it) }
        assertNotNull(tls.sslSocketFactory)
        // Must contain platform issuers PLUS our 6 bundled roots (ISRG X1/X2, GTS R1/R4, DigiCert G2/G3)
        assertTrue(tls.trustManager.acceptedIssuers.size >= 6)
        assertTrue(
            tls.trustManager.acceptedIssuers.any { it.subjectX500Principal.name.contains("ISRG Root X1") },
            "Bundled trust manager must include Let's Encrypt ISRG Root X1"
        )
    }

    @Test
    fun `LocalDaemonFailSafeClient disengages via dedicated or fallback loopback HTTP endpoint`() = runBlocking {
        // First tries /api/motor/disable (404 on current daemon), then falls back to /api/mock/ble-event
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","event":{"type":"handle_event","action":"emergency_stop","source":"mock"}}""")
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failSafe = LocalDaemonFailSafeClient(
            daemonBaseUrlProvider = { server.url("/").toString().removeSuffix("/") },
            scope = scope
        )

        val ok = failSafe.emergencyDisableWeight()
        assertTrue(ok)
        assertEquals("/api/motor/disable", server.takeRequest().path)
        val fallbackReq = server.takeRequest()
        assertEquals("/api/mock/ble-event", fallbackReq.path)
        assertTrue(fallbackReq.body.readUtf8().contains("emergency_stop"))

        // Direct /api/motor/disable 200 succeeds in a single HTTP request (no fallback needed)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","weight_enabled":false,"state":"IDLE_WEIGHT_OFF"}"""))
        assertTrue(failSafe.emergencyDisableWeight())
        assertEquals("/api/motor/disable", server.takeRequest().path)
    }

    @Test
    fun `SwonalApiClient refuses to send passcode or BYOK API key over public cleartext HTTP`() = runBlocking {
        val publicCleartextClient = SwonalApiClient(
            baseUrlProvider = { "http://swonal.example.com" }
        )
        val loginErr = runCatching { publicCleartextClient.login("1234") }.exceptionOrNull()
        assertNotNull(loginErr)
        assertTrue(loginErr!!.message!!.contains("public cleartext HTTP"))

        val keyErr = runCatching {
            publicCleartextClient.saveCoachConfig("openai", "gpt-4o-mini", "sk-secret", null)
        }.exceptionOrNull()
        assertNotNull(keyErr)
        assertTrue(keyErr!!.message!!.contains("public cleartext HTTP"))
    }

    @Test
    fun `findLibrespotDevice prefers on-device Tonal Gym or librespot speaker`() {
        val livingRoom = com.swonal.kiosk.model.MusicDevice(
            id = "dev-living",
            name = "Scarlett's Speaker",
            type = "Speaker",
            isActive = true,
            volumePct = 60
        )
        val tonalGym = com.swonal.kiosk.model.MusicDevice(
            id = "dev-tonal",
            name = "Tonal Gym",
            type = "Speaker",
            isActive = false,
            volumePct = 80
        )
        val librespot = com.swonal.kiosk.model.MusicDevice(
            id = "dev-lib",
            name = "librespot-android",
            type = "Speaker",
            isActive = false,
            volumePct = 70
        )

        assertEquals("dev-tonal", SwonalRepository.findLibrespotDevice(listOf(livingRoom, tonalGym))?.id)
        assertEquals("dev-lib", SwonalRepository.findLibrespotDevice(listOf(livingRoom, librespot))?.id)
        assertNull(SwonalRepository.findLibrespotDevice(listOf(livingRoom)))
    }
}
