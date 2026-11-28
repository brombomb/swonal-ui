package com.swonal.kiosk.engine

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutBlockItem
import com.swonal.kiosk.util.Base64Compat
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class LocalWorkoutEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: SwonalDatabase
    private lateinit var engine: LocalWorkoutEngine
    private val daemonWeights = mutableListOf<Triple<Double, String, Int>>()
    private var repsResetCount = 0

    @BeforeEach
    fun setUp() {
        val dbFile = tempDir.resolve("swonal_test.db").toFile()
        db = SwonalDatabase(dbFile.absolutePath)
        daemonWeights.clear()
        repsResetCount = 0
        engine = LocalWorkoutEngine(
            db = db,
            daemonSetWeight = { w, p, m -> daemonWeights += Triple(w, p, m) },
            daemonResetReps = { repsResetCount++ }
        )
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private fun loadBundledSeed(): String {
        val file = File("src/main/assets/seed/seed_catalog.json")
        assertTrue(file.exists(), "seed_catalog.json must exist in assets/seed")
        return file.readText()
    }

    @Test
    fun `initializes cold from bundled seed catalog with 50 exercises, users, templates, and PRs`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        assertEquals(50, state.exercises.size)
        assertEquals(2, state.users.size)
        assertEquals(4, state.templates.size)
        assertEquals("Alex", state.users[0].name)
        assertEquals("Taylor", state.users[1].name)

        val alexPrs = engine.getPrs(state.users[0].id)
        assertEquals(12, alexPrs.size)
        assertTrue(state.exercises.all { !it.mediaUrl.isNullOrBlank() && it.mediaUrl!!.startsWith("/media/") })
    }

    @Test
    fun `logs sets, detects PRs, computes progressive overload, and rotates partner weight`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val taylor = state.users[1]
        val bench = state.exercises.first { it.name == "Bench Press" }

        val session = engine.createSession(alex.id, templateId = null)
        // Set 1: higher than Alex's seeded 65 lb PR -> new PR
        val res1 = engine.logSet(
            sessionId = session.id,
            userId = alex.id,
            exerciseId = bench.id,
            setNumber = 1,
            mode = "standard",
            weightLb = 90.0,
            repsCompleted = 10,
            restSeconds = 45
        )
        assertTrue(res1.newPr)
        assertEquals(900.0, res1.totalVolume, 0.01)
        assertNotNull(engine.getSessionState().restTimerEndsAt)
        assertEquals(1, repsResetCount)

        // Overload after 10 reps at 90 lb recommends +5 lb
        val sug = engine.getOverloadSuggestion(alex.id, bench.id)
        assertTrue(sug.ready)
        assertEquals(5.0, sug.recommendedBumpLb, 0.01)
        assertEquals(95.0, sug.recommendedWeightLb, 0.01)

        // Enable Partner "Work In" mode and log Alex's next set -> rotates to Taylor at 85% of Taylor's 135 lb Bench PR = 115 lb
        engine.setPartnerMode(true, listOf(alex.id, taylor.id))
        val res2 = engine.logSet(
            sessionId = session.id,
            userId = alex.id,
            exerciseId = bench.id,
            setNumber = 2,
            mode = "chains",
            modifierPct = 20,
            weightLb = 85.0,
            repsCompleted = 8,
            restSeconds = 60
        )
        assertNotNull(res2.partnerRotation)
        assertEquals(taylor.id, res2.partnerRotation!!.rotatedToUserId)
        assertEquals(115.0, res2.partnerRotation!!.targetWeightLb, 0.01)
        assertEquals(1, daemonWeights.size)
        assertEquals(115.0, daemonWeights[0].first, 0.01)
        assertEquals("chains", daemonWeights[0].second)

        val completed = engine.completeSession(session.id)
        assertNotNull(completed?.completedAt)
        assertNull(engine.getSessionState().activeSessionId)
        assertEquals(1580.0, completed!!.totalVolume, 0.01)
    }

    @Test
    fun `a set with 0 reps is logged but never becomes a PR`() = runBlocking<Unit> {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val withPr = engine.getPrs(alex.id).map { it.exerciseId }.toSet()
        val fresh = state.exercises.first { it.id !in withPr }
        val bench = state.exercises.first { it.name == "Bench Press" }
        val benchPrBefore = engine.getPrs(alex.id).first { it.exerciseId == bench.id }
        val session = engine.createSession(alex.id, templateId = null)

        // First-ever set for this exercise, 0 reps: no PR row is created
        val first = engine.logSet(
            sessionId = session.id, userId = alex.id, exerciseId = fresh.id,
            setNumber = 1, weightLb = 150.0, repsCompleted = 0, restSeconds = 0
        )
        assertFalse(first.newPr)
        assertTrue(engine.getPrs(alex.id).none { it.exerciseId == fresh.id })
        assertEquals(0, first.setLog.repsCompleted)
        assertEquals(0.0, first.totalVolume, 0.01)

        // 0 reps above the existing PR weight leaves the PR untouched
        val heavy = engine.logSet(
            sessionId = session.id, userId = alex.id, exerciseId = bench.id,
            setNumber = 1, weightLb = benchPrBefore.maxWeightLb + 50.0, repsCompleted = 0, restSeconds = 0
        )
        assertFalse(heavy.newPr)
        val benchPrAfter = engine.getPrs(alex.id).first { it.exerciseId == bench.id }
        assertEquals(benchPrBefore.maxWeightLb, benchPrAfter.maxWeightLb, 0.001)
        assertEquals(benchPrBefore.oneRepMaxEst, benchPrAfter.oneRepMaxEst, 0.001)

        // A real set on the fresh exercise still sets its first PR
        val real = engine.logSet(
            sessionId = session.id, userId = alex.id, exerciseId = fresh.id,
            setNumber = 2, weightLb = 40.0, repsCompleted = 8, restSeconds = 0
        )
        assertTrue(real.newPr)
        assertEquals(40.0, engine.getPrs(alex.id).first { it.exerciseId == fresh.id }.maxWeightLb, 0.001)
    }

    @Test
    fun `escapes CSV formulas, converts KG routine weights on create, and vacuums SQLite backup`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val alex = engine.listUsers()[0]
        val customEx = engine.createExercise(name = "=CMD|'/C calc'!A0", muscleGroup = "Chest", accessory = "HANDLES")

        val tpl = engine.createTemplate(
            userId = alex.id,
            name = "Metric Routine",
            description = "Test",
            unit = "KG",
            blocks = listOf(
                WorkoutBlock(
                    blockType = "STRAIGHT_SET",
                    restSeconds = 60,
                    items = listOf(WorkoutBlockItem(exerciseId = customEx.id, sets = 2, targetReps = 10, targetWeightLb = 20.0))
                )
            ),
            scheduleDays = listOf(1, 3, 5)
        )
        // 20 kg ~= 44.0 lb stored canonically
        assertTrue(tpl.blocks[0].items[0].targetWeightLb in 43.5..44.5)
        assertEquals(listOf(1, 3, 5), tpl.scheduleDays)

        val sess = engine.createSession(alex.id, tpl.id)
        engine.logSet(sess.id, alex.id, customEx.id, 1, "standard", 0, 45.0, 10, null, 0)

        val csv = engine.exportSessionsCsv(alex.id)
        assertTrue(csv.contains("\"'=CMD|'/C calc'!A0\""), "CSV formula cell must be apostrophe-escaped")

        val jsonExport = engine.exportSessionsJson(alex.id)
        assertTrue(jsonExport.contains("\"TraditionalStrengthTraining\""))

        val backupFile = tempDir.resolve("backup.db").toFile()
        engine.exportSqliteBackup(backupFile)
        assertTrue(backupFile.exists() && backupFile.length() > 4096)

        // Verify the backup SQLite file opens cleanly with BundledSQLiteDriver
        val reopened = SwonalDatabase(backupFile.absolutePath)
        try {
            assertEquals(51, reopened.listExercises().size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `local PBKDF2 passcode lock verifies hashes, locks out after 5 failures, and Base64Compat works`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val initial = engine.getAuthStatus(isLoopbackClient = false)
        assertFalse(initial.enabled)
        assertTrue(initial.authenticated)

        val enabled = engine.configureAuth(enabled = true, passcode = "8675", trustLocalhost = true)
        assertTrue(enabled.enabled)

        assertTrue(engine.verifyPasscode("8675", "192.168.1.55").getOrThrow())
        repeat(5) {
            assertFalse(engine.verifyPasscode("0000", "192.168.1.55").getOrThrow())
        }
        val lockedOut = engine.verifyPasscode("8675", "192.168.1.55")
        assertTrue(lockedOut.isFailure)

        val raw = "Swonal Standalone Sync Payload 123!".toByteArray(Charsets.UTF_8)
        val b64Url = Base64Compat.encodeUrlNoPadding(raw)
        assertFalse(b64Url.contains('='))
        assertEquals(String(raw, Charsets.UTF_8), String(Base64Compat.decode(b64Url), Charsets.UTF_8))

        val nowMs = 1763980000123L
        val iso = IsoTime.formatUtc(nowMs)
        assertEquals(nowMs, IsoTime.parseUtcMs(iso))
    }

    @Test
    fun `exercise prefs are stored per lifter and per exercise and upsert in place`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val taylor = state.users[1]
        val bench = state.exercises[0]
        val row = state.exercises[1]

        assertNull(engine.getExercisePref(alex.id, bench.id))

        engine.saveExercisePref(
            com.swonal.kiosk.model.ExercisePref(
                userId = alex.id, exerciseId = bench.id, weightLb = 60.0, mode = "CHAINS",
                modifierPct = 30, accessory = "bar", lastReps = 9, lastTargetReps = 10
            )
        )
        engine.saveExercisePref(
            com.swonal.kiosk.model.ExercisePref(
                userId = taylor.id, exerciseId = bench.id, weightLb = 35.0, lastReps = 12, lastTargetReps = null
            )
        )

        val alexBench = engine.getExercisePref(alex.id, bench.id)
        assertNotNull(alexBench)
        assertEquals(60.0, alexBench!!.weightLb)
        assertEquals("chains", alexBench.mode)
        assertEquals(30, alexBench.modifierPct)
        assertEquals("BAR", alexBench.accessory)
        assertEquals(9, alexBench.lastReps)
        assertEquals(10, alexBench.lastTargetReps)

        val taylorBench = engine.getExercisePref(taylor.id, bench.id)
        assertEquals(35.0, taylorBench!!.weightLb)
        assertNull(taylorBench.lastTargetReps)
        assertNull(engine.getExercisePref(alex.id, row.id))

        // Upsert replaces the row for the same lifter+exercise
        engine.saveExercisePref(alexBench.copy(weightLb = 57.0, mode = "standard", modifierPct = 0, lastTargetReps = null))
        val updated = engine.getExercisePref(alex.id, bench.id)!!
        assertEquals(57.0, updated.weightLb)
        assertEquals("standard", updated.mode)
        assertNull(updated.lastTargetReps)
    }

    @Test
    fun `latest exercise pref returns the lifter's most recently used exercise`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val taylor = state.users[1]
        assertNull(engine.getLatestExercisePref(alex.id))

        engine.saveExercisePref(com.swonal.kiosk.model.ExercisePref(alex.id, state.exercises[0].id, weightLb = 40.0))
        Thread.sleep(5)
        engine.saveExercisePref(com.swonal.kiosk.model.ExercisePref(alex.id, state.exercises[1].id, weightLb = 30.0))
        Thread.sleep(5)
        engine.saveExercisePref(com.swonal.kiosk.model.ExercisePref(taylor.id, state.exercises[2].id, weightLb = 25.0))

        assertEquals(state.exercises[1].id, engine.getLatestExercisePref(alex.id)?.exerciseId)
        assertEquals(state.exercises[2].id, engine.getLatestExercisePref(taylor.id)?.exerciseId)

        // Re-using an older exercise makes it the latest again
        Thread.sleep(5)
        engine.saveExercisePref(com.swonal.kiosk.model.ExercisePref(alex.id, state.exercises[0].id, weightLb = 42.0))
        val latest = engine.getLatestExercisePref(alex.id)
        assertEquals(state.exercises[0].id, latest?.exerciseId)
        assertEquals(42.0, latest?.weightLb)
    }

    @Test
    fun `auto-adjust weights preference is per lifter and defaults off`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val taylor = state.users[1]
        assertFalse(engine.getAutoAdjustWeights(alex.id))
        assertFalse(engine.getAutoAdjustWeights(taylor.id))

        engine.setAutoAdjustWeights(alex.id, true)
        assertTrue(engine.getAutoAdjustWeights(alex.id))
        assertFalse(engine.getAutoAdjustWeights(taylor.id))

        engine.setAutoAdjustWeights(alex.id, false)
        assertFalse(engine.getAutoAdjustWeights(alex.id))
    }

    @Test
    fun `Work-In rotates to the partner after whoever actually lifted`() = runBlocking {
        engine.initialize(loadBundledSeed(), includeDemoUsersAndRoutines = true)
        val state = engine.getAppState(daemonConnected = true)
        val alex = state.users[0]
        val taylor = state.users[1]
        val bench = state.exercises[0]
        engine.setPartnerMode(true, listOf(alex.id, taylor.id))
        engine.setActiveUser(alex.id)
        val sess = engine.createSession(alex.id)

        // Normal Work-In: Alex lifts and is active -> Taylor next
        val first = engine.logSet(sess.id, alex.id, bench.id, 1, "standard", 0, 50.0, 10)
        assertEquals(taylor.id, first.partnerRotation?.rotatedToUserId)
        assertEquals(taylor.id, engine.getSessionState().activeUserId)

        // Taylor's set -> Alex next
        val second = engine.logSet(sess.id, taylor.id, bench.id, 1, "standard", 0, 35.0, 10)
        assertEquals(alex.id, second.partnerRotation?.rotatedToUserId)

        // Taylor's profile was switched in while Alex was still lifting: Alex's set still hands over to Taylor
        engine.setActiveUser(taylor.id)
        val third = engine.logSet(sess.id, alex.id, bench.id, 2, "standard", 0, 50.0, 10)
        assertEquals(taylor.id, third.partnerRotation?.rotatedToUserId)
        assertEquals(taylor.id, engine.getSessionState().activeUserId)
    }
}
