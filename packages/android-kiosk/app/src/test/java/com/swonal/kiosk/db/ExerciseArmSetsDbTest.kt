package com.swonal.kiosk.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.swonal.kiosk.model.SwonalJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExerciseArmSetsDbTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `existing install without allowed columns is upgraded in place without data loss`() = runBlocking {
        val path = tempDir.resolve("legacy.db").toFile().absolutePath
        // Pre-change exercises schema with one user-edited row.
        BundledSQLiteDriver().open(path).apply {
            execSQL(
                """
                CREATE TABLE exercises (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, muscle_group TEXT NOT NULL,
                    accessory TEXT NOT NULL DEFAULT 'HANDLES', recommended_height INTEGER NOT NULL DEFAULT 4,
                    recommended_rotation INTEGER NOT NULL DEFAULT 2, recommended_angle INTEGER NOT NULL DEFAULT 1,
                    unilateral INTEGER NOT NULL DEFAULT 0, notes TEXT, media_url TEXT,
                    updated_at_ms INTEGER NOT NULL, deleted_at_ms INTEGER, dirty_sync INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO exercises(id, name, muscle_group, accessory, recommended_height, recommended_rotation, recommended_angle, unilateral, notes, updated_at_ms, dirty_sync) " +
                    "VALUES ('ex-legacy', 'Legacy Row', 'Back', 'HANDLES', 9, 4, 4, 1, 'keep me', 42, 1)"
            )
            close()
        }

        val db = SwonalDatabase(path)
        try {
            val ex = db.getExerciseById("ex-legacy")
            assertNotNull(ex)
            assertEquals(9, ex!!.recommendedHeight)
            assertEquals(4, ex.recommendedRotation)
            assertEquals("keep me", ex.notes)
            assertTrue(ex.unilateral)
            assertEquals(emptyList<Int>(), ex.allowedHeights)
            assertEquals(emptyList<Int>(), ex.allowedRotations)
            assertEquals(emptyList<Int>(), ex.allowedAngles)

            val updated = db.updateExercise("ex-legacy", allowedRotations = listOf(5, 9))!!
            // Out-of-range 9 dropped; recommended 4 added.
            assertEquals(listOf(4, 5), updated.allowedRotations)
            assertEquals(listOf(4, 5), db.getExerciseById("ex-legacy")!!.allowedRotations)

            // Unrelated update keeps the stored set.
            db.updateExercise("ex-legacy", notes = "still here")
            assertEquals(listOf(4, 5), db.listExercises().single().allowedRotations)
        } finally {
            db.close()
        }

        // Re-opening an already-migrated DB is a no-op.
        val reopened = SwonalDatabase(path)
        try {
            assertEquals(listOf(4, 5), reopened.getExerciseById("ex-legacy")!!.allowedRotations)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `allowed sets round-trip through create, sync push, pull and restore export`() = runBlocking {
        val db = SwonalDatabase(":memory:")
        try {
            val created = db.createExercise(
                name = "One Arm Row",
                muscleGroup = "Back",
                recommendedHeight = 9,
                recommendedRotation = 4,
                recommendedAngle = 4,
                unilateral = true,
                allowedHeights = listOf(8, 9),
                allowedRotations = listOf(5),
                allowedAngles = emptyList()
            )
            assertEquals(listOf(8, 9), created.allowedHeights)
            assertEquals(listOf(4, 5), created.allowedRotations)
            assertEquals(emptyList<Int>(), created.allowedAngles)

            val push = db.collectSyncPushRequest()
            val pushed = push.exercises.single { it.id == created.id }
            assertEquals(listOf(4, 5), pushed.allowedRotations)
            val wire = SwonalJson.encodeToString(SyncPushRequest.serializer(), push)
            assertTrue(wire.contains("\"allowedRotations\":[4,5]"), wire)
            assertTrue(wire.contains("\"allowedAngles\":[]"), wire)

            // Older server: no allowed* keys at all -> null (absent), stored as '' for a brand-new row.
            val oldServerJson = """{"serverTimeMs":10,"exercises":[{"id":"srv-1","name":"Lat Pulldown","muscleGroup":"Back","recommendedHeight":9,"recommendedRotation":4,"recommendedAngle":5,"updatedAtMs":10}]}"""
            val oldPull = SwonalJson.decodeFromString(SyncPullResponse.serializer(), oldServerJson)
            assertNull(oldPull.exercises.single().allowedRotations)

            val newServerJson = """{"serverTimeMs":20,"exercises":[{"id":"srv-2","name":"Chest Fly","muscleGroup":"Chest","recommendedHeight":6,"recommendedRotation":5,"recommendedAngle":3,"allowedHeights":[5,6],"allowedRotations":[4,5,6],"allowedAngles":[],"updatedAtMs":20}]}"""
            db.applyPulledSync(oldPull)
            db.applyPulledSync(SwonalJson.decodeFromString(SyncPullResponse.serializer(), newServerJson))

            val lat = db.getExerciseById("srv-1")!!
            assertEquals(emptyList<Int>(), lat.allowedRotations)
            val fly = db.getExerciseById("srv-2")!!
            assertEquals(listOf(5, 6), fly.allowedHeights)
            assertEquals(listOf(4, 5, 6), fly.allowedRotations)
            assertEquals(emptyList<Int>(), fly.allowedAngles)

            // Existing row, newer server copy that omits allowed* -> local sets kept; explicit [] clears.
            val omitJson = """{"serverTimeMs":30,"exercises":[{"id":"${created.id}","name":"One Arm Row","muscleGroup":"Back","recommendedHeight":9,"recommendedRotation":4,"recommendedAngle":4,"unilateral":true,"notes":"server note","updatedAtMs":${Long.MAX_VALUE / 2}}]}"""
            db.applyPulledSync(SwonalJson.decodeFromString(SyncPullResponse.serializer(), omitJson))
            val kept = db.getExerciseById(created.id)!!
            assertEquals("server note", kept.notes)
            assertEquals(listOf(8, 9), kept.allowedHeights)
            assertEquals(listOf(4, 5), kept.allowedRotations)

            val clearJson = omitJson.replace("\"notes\"", "\"allowedRotations\":[],\"notes\"")
                .replace("${Long.MAX_VALUE / 2}", "${Long.MAX_VALUE / 2 + 1}")
            db.applyPulledSync(SwonalJson.decodeFromString(SyncPullResponse.serializer(), clearJson))
            val cleared = db.getExerciseById(created.id)!!
            assertEquals(emptyList<Int>(), cleared.allowedRotations)
            assertEquals(listOf(8, 9), cleared.allowedHeights)

            val bundle = db.exportRestoreBundle()
            assertEquals(listOf(4, 5, 6), bundle.exercises.single { it.id == "srv-2" }.allowedRotations)
            assertEquals(listOf(8, 9), bundle.exercises.single { it.id == created.id }.allowedHeights)
            // Records read from the DB always carry all three lists, so a push never omits them.
            assertEquals(emptyList<Int>(), bundle.exercises.single { it.id == created.id }.allowedAngles)
        } finally {
            db.close()
        }
    }
}
