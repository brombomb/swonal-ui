package com.swonal.kiosk.engine

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.util.PlaintextTestCipher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAiCoachEngineTest {

    @Test
    fun `voice command parser extracts numeric and spoken strength adjustments`() {
        val addTen = LocalAiCoachEngine.detectVoiceCommand("add ten pounds")
        assertNotNull(addTen)
        assertEquals("set_weight_delta", addTen?.type)
        assertEquals(10.0, addTen?.deltaLb)

        val dropDecimal = LocalAiCoachEngine.detectVoiceCommand("drop 2.5 kg")
        assertNotNull(dropDecimal)
        assertEquals("set_weight_delta", dropDecimal?.type)
        assertEquals(-2.5, dropDecimal?.deltaLb)

        val target = LocalAiCoachEngine.detectVoiceCommand("set weight to forty five lb")
        assertNotNull(target)
        assertEquals("set_weight_target", target?.type)
        assertEquals(45.0, target?.targetLb)

        val chains = LocalAiCoachEngine.detectVoiceCommand("switch to chains mode")
        assertEquals("set_mode", chains?.type)
        assertEquals("CHAINS", chains?.mode)

        val skip = LocalAiCoachEngine.detectVoiceCommand("skip rest timer please")
        assertEquals("skip_rest", skip?.type)

        val kill = LocalAiCoachEngine.detectVoiceCommand("weight off!")
        assertEquals("set_weight_enabled", kill?.type)
        assertEquals(false, kill?.enabled)

        assertNull(LocalAiCoachEngine.detectVoiceCommand("how many calories did i burn"))
    }

    @Test
    fun `local heuristic coach answers PR and VBT prompts offline`() = runBlocking {
        val db = SwonalDatabase(":memory:", PlaintextTestCipher)
        db.seedIfEmpty(
            """
            {
              "users": [
                { "id": "u-1", "name": "Alex", "avatarColor": "#3B82F6", "weightUnit": "LB" }
              ],
              "exercises": [
                { "id": "ex-1", "name": "Bench Press", "muscleGroup": "Chest", "equipment": "Handles", "defaultWeight": 60, "unilateral": false, "armAngle": "LOW", "armRotation": "OUT", "railHeight": "BENCH" }
              ]
            }
            """.trimIndent(),
            includeDemoUsersAndRoutines = true
        )
        val workout = LocalWorkoutEngine(db)
        val session = workout.createSession("u-1")
        workout.logSet(
            sessionId = session.id,
            userId = "u-1",
            exerciseId = "ex-1",
            setNumber = 1,
            mode = "STANDARD",
            weightLb = 100.0,
            repsCompleted = 5
        )

        val coach = LocalAiCoachEngine(db, PlaintextTestCipher)
        val prResp = coach.ask("u-1", "What is my best PR right now?")
        assertEquals("local", prResp.provider)
        assertTrue(prResp.reply.contains("Bench Press"))
        assertTrue(prResp.reply.contains("1RM"))

        val vbtResp = coach.ask(
            userId = "u-1",
            prompt = "How does my bar speed look?",
            exerciseName = "Bench Press",
            currentWeightLb = 100.0,
            repCount = 6,
            meanVelocity = 34.2,
            velocityDropPct = 28.0
        )
        assertEquals("local", vbtResp.provider)
        assertTrue(vbtResp.reply.contains("28%"))
        assertTrue(vbtResp.reply.contains("dropping load") || vbtResp.reply.contains("Velocity"))
    }

    @Test
    fun `encrypts BYOK api key and calls OpenAI client when configured`() = runBlocking {
        val db = SwonalDatabase(":memory:", PlaintextTestCipher)
        db.seedIfEmpty(
            """{ "users": [ { "id": "u-1", "name": "Alex", "weightUnit": "LB" } ] }""",
            includeDemoUsersAndRoutines = true
        )

        var seenAuthHeader: String? = null
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            seenAuthHeader = req.header("Authorization")
            val json = """
                {
                  "choices": [
                    { "message": { "content": "Solid bar speed—stay smooth on the eccentric." } }
                  ]
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
        val mockHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val coach = LocalAiCoachEngine(db, PlaintextTestCipher, httpClientOverride = mockHttp)

        val saved = coach.setConfig(provider = "openai", model = "gpt-4o-mini", apiKey = "sk-test-secret-6789")
        assertEquals("openai", saved.provider)
        assertTrue(saved.hasApiKey)
        assertEquals("••••6789", saved.keyHint)

        val resp = coach.ask("u-1", "Give me a form cue for bench press")
        assertEquals("openai", resp.provider)
        assertEquals("Bearer sk-test-secret-6789", seenAuthHeader)
        assertTrue(resp.reply.contains("Solid bar speed"))
    }
}
