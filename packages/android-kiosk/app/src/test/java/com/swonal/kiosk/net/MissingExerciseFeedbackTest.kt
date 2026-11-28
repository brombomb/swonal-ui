package com.swonal.kiosk.net

import android.content.Context
import android.content.SharedPreferences
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.RoutineStep
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutBlockItem
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.util.PlaintextTestCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** A routine step whose exercise is missing must say so instead of silently doing nothing. */
class MissingExerciseFeedbackTest {

    private class MemoryPrefs : SharedPreferences {
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
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }

    private fun testContext(dir: File): Context {
        val prefs = MemoryPrefs()
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
    }

    private fun exercise(id: String) = Exercise(id = id, name = id, muscleGroup = "Test")

    private fun step(exerciseId: String) = RoutineStep(
        blockIndex = 0, blockType = "STRAIGHT_SET", itemIndex = 0, exerciseId = exerciseId,
        setNumber = 1, totalSetsInItem = 1, targetReps = 10, mode = "standard",
        modifierPct = 0, targetWeightLb = 30.0, restSeconds = 60
    )

    @Test
    fun `missingExerciseId names the step's exercise only when the library lacks it`() {
        val lib = listOf(exercise("ex-a"), exercise("ex-b"))
        assertNull(KioskUiState(exercises = lib, routineSteps = listOf(step("ex-b"))).missingExerciseId)
        assertEquals("ex-gone", KioskUiState(exercises = lib, routineSteps = listOf(step("ex-gone"))).missingExerciseId)
        assertEquals("ex-gone", KioskUiState(exercises = lib, activeExerciseId = "ex-gone").missingExerciseId)
        // Nothing selected, or library still loading: not "missing".
        assertNull(KioskUiState(exercises = lib).missingExerciseId)
        assertNull(KioskUiState(exercises = emptyList(), routineSteps = listOf(step("ex-gone"))).missingExerciseId)
    }

    @Test
    fun `Complete and Log Set on a step with a missing exercise shows a message and logs nothing`() {
        val tempDir = Files.createTempDirectory("swonal-missing-ex-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seedJson = """
            {
              "users": [ { "id": "u-alex", "name": "Alex", "avatarColor": "#3B82F6", "weightUnit": "LB" } ],
              "exercises": [
                { "id": "ex-bench", "name": "Bench Press", "muscleGroup": "Chest", "accessory": "HANDLES", "defaultWeight": 45, "unilateral": false, "armAngle": "LOW", "armRotation": "OUT", "railHeight": "BENCH" }
              ]
            }
        """.trimIndent()
        val repo = SwonalRepository(
            prefs = SwonalPreferences(testContext(tempDir)),
            scope = scope,
            localDaemonOverride = LocalDaemonFailSafeClient(
                daemonBaseUrlProvider = { "http://127.0.0.1:9" },
                scope = scope,
                uiAliveCheck = { true }
            ),
            localDbPath = ":memory:",
            seedCatalogJson = seedJson,
            secretCipherOverride = PlaintextTestCipher
        )
        val effects = CopyOnWriteArrayList<KioskEffect>()
        val collector = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            repo.effectsFlow.collect { effects += it }
        }
        try {
            runBlocking {
                repo.localEngine.initialize(seedJson, includeDemoUsersAndRoutines = false)
                repo.refreshAppState()
                val alexId = repo.uiStateFlow.value.users.first { it.name == "Alex" }.id

                // A routine whose only step points at an exercise this kiosk doesn't have.
                repo.startWorkoutTemplate(
                    WorkoutTemplate(
                        id = "",
                        userId = alexId,
                        name = "Stale routine",
                        blocks = listOf(
                            WorkoutBlock(
                                blockType = "STRAIGHT_SET",
                                restSeconds = 60,
                                items = listOf(WorkoutBlockItem(exerciseId = "ex-gone", sets = 1, targetReps = 10, targetWeightLb = 30.0))
                            )
                        )
                    )
                )
                withTimeout(5_000) {
                    while (repo.uiStateFlow.value.sessionState.activeSessionId == null) delay(20)
                }
                val ui = repo.uiStateFlow.value
                assertNull(ui.activeExercise)
                assertEquals("ex-gone", ui.missingExerciseId)

                repo.logCompletedSet(10)
                withTimeout(5_000) {
                    while (effects.none { it is KioskEffect.ShowMessage }) delay(20)
                }
                val shown = effects.filterIsInstance<KioskEffect.ShowMessage>().map { it.message }
                assertTrue(MISSING_EXERCISE_NOT_LOGGED in shown, "expected the missing-exercise message, got $shown")

                val sessionId = ui.sessionState.activeSessionId
                val session = repo.localEngine.listSessions(alexId).first { it.id == sessionId }
                assertTrue(session.setLogs.isEmpty(), "no set may be logged against a substitute exercise")
            }
        } finally {
            collector.cancel()
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
