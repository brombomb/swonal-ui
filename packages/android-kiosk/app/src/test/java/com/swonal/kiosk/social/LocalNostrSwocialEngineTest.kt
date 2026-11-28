package com.swonal.kiosk.social

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.upsertFriendActivity
import com.swonal.kiosk.engine.LocalWorkoutEngine
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.ExerciseName
import com.swonal.kiosk.model.MissingExercise
import com.swonal.kiosk.model.SharedRoutineExercises
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.UnknownExerciseException
import com.swonal.kiosk.model.User
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutBlockItem
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** Shared routines (Nostr kind 30102) resolve exercises by name across installs; same rules as the server app. */
class LocalNostrSwocialEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: SwonalDatabase
    private lateinit var social: LocalNostrSwocialEngine
    private lateinit var alex: User
    private lateinit var exA: Exercise
    private lateinit var exB: Exercise

    @BeforeEach
    fun setUp() = runBlocking<Unit> {
        db = SwonalDatabase(tempDir.resolve("swocial_test.db").toFile().absolutePath)
        val engine = LocalWorkoutEngine(db = db, daemonSetWeight = { _, _, _ -> }, daemonResetReps = { })
        engine.initialize(File("src/main/assets/seed/seed_catalog.json").readText(), includeDemoUsersAndRoutines = true)
        social = LocalNostrSwocialEngine(db)
        alex = engine.listUsers()[0]
        // Keep publishRoutine off the internet: a closed local port fails at once.
        social.updateIdentitySettings(alex.id, relays = listOf("ws://127.0.0.1:1"))
        val sorted = db.listExercises().sortedBy { it.name }
        exA = sorted[0]
        exB = sorted[1]
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private fun block(vararg ids: String) = WorkoutBlock(
        blockType = "STRAIGHT_SET",
        restSeconds = 60,
        items = ids.map { WorkoutBlockItem(exerciseId = it, sets = 3, targetReps = 10, targetWeightLb = 30.0) }
    )

    private fun blocksJson(vararg blocks: WorkoutBlock) =
        SwonalJson.encodeToJsonElement(ListSerializer(WorkoutBlock.serializer()), blocks.toList())

    private fun ref(id: String, name: String) = buildJsonObject { put("id", id); put("name", name) }

    private suspend fun cacheShared(content: JsonObject): String {
        val eventId = UUID.randomUUID().toString().replace("-", "")
        val withType = JsonObject(mapOf("type" to JsonPrimitive("routine_share"), "name" to JsonPrimitive("Remote Push")) + content)
        db.upsertFriendActivity("cd".repeat(32), "Remote Friend", eventId, 30102, IsoTime.nowIso(), SwonalJson.encodeToString(JsonObject.serializer(), withType))
        return eventId
    }

    private fun stepIds(blocks: List<WorkoutBlock>) = blocks.flatMap { b -> b.items.map { it.exerciseId } }

    @Test
    fun `publishRoutine includes name, muscle group and accessory for each exercise, keeping the ids`() = runBlocking<Unit> {
        val tpl = db.createTemplate(alex.id, "Shared with names", null, listOf(block(exA.id, exB.id), block(exA.id)))
        val ev = social.publishRoutine(alex.id, tpl)
        val payload = SwonalJson.parseToJsonElement(ev.content).jsonObject
        val ids = payload["blocks"]!!.jsonArray.flatMap { b -> b.jsonObject["items"]!!.jsonArray.map { it.jsonObject["exerciseId"]!!.jsonPrimitive.content } }
        assertEquals(listOf(exA.id, exB.id, exA.id), ids)
        val expected = buildJsonArray {
            for (e in listOf(exA, exB)) add(buildJsonObject {
                put("id", e.id); put("name", e.name); put("muscleGroup", e.muscleGroup); put("accessory", e.accessory)
            })
        }
        assertEquals(expected, payload["exercises"])
    }

    @Test
    fun `imports a routine shared from another install by matching names, rewriting the ids`() = runBlocking<Unit> {
        val tpl = db.createTemplate(alex.id, "Round trip", null, listOf(block(exA.id, exB.id)))
        var content = social.publishRoutine(alex.id, tpl).content
        // Make it look like it came from an install whose seed gave other ids.
        content = content.replace(exA.id, UUID.randomUUID().toString()).replace(exB.id, UUID.randomUUID().toString())
        val payload = SwonalJson.parseToJsonElement(content).jsonObject
        val exercises = payload["exercises"]!!.jsonArray
        // Names differ only by case and surrounding spaces on the other install.
        val renamed = JsonArray(listOf(
            JsonObject(exercises[0].jsonObject + ("name" to JsonPrimitive("  ${exA.name.uppercase()} "))),
            exercises[1]
        ))
        val eventId = cacheShared(JsonObject(payload + ("exercises" to renamed)))

        val imported = social.importRoutine(alex.id, eventId)
        assertEquals(listOf(exA.id, exB.id), stepIds(imported.blocks))
        assertEquals(block(exB.id).items[0], imported.blocks[0].items[1])
        assertEquals(listOf(exA.id, exB.id), stepIds(db.getTemplateById(imported.id)!!.blocks))
    }

    @Test
    fun `keeps an id that exists here even when the payload names another exercise (same install)`() = runBlocking<Unit> {
        val eventId = cacheShared(buildJsonObject {
            put("blocks", blocksJson(block(exA.id)))
            put("exercises", buildJsonArray { add(ref(exA.id, exB.name)) })
        })
        assertEquals(listOf(exA.id), stepIds(social.importRoutine(alex.id, eventId).blocks))
    }

    @Test
    fun `imports an old payload without names by id, and refuses unknown ids like the server`() = runBlocking<Unit> {
        val ok = social.importRoutine(alex.id, cacheShared(buildJsonObject { put("blocks", blocksJson(block(exA.id))) }))
        assertEquals(listOf(exA.id), stepIds(ok.blocks))

        val bogus = UUID.randomUUID().toString()
        val before = db.listTemplates(alex.id).size
        val eventId = cacheShared(buildJsonObject { put("blocks", blocksJson(block(exA.id, bogus))) })
        val err = assertThrows(UnknownExerciseException::class.java) {
            runBlocking { social.importRoutine(alex.id, eventId) }
        }
        assertEquals("${UnknownExerciseException.IMPORT_PREFIX}: $bogus", err.message)
        assertEquals(before, db.listTemplates(alex.id).size)
    }

    @Test
    fun `refuses the whole import, listing missing exercises by name and id, and stores nothing`() = runBlocking<Unit> {
        val remoteA = UUID.randomUUID().toString()
        val remoteMissing = UUID.randomUUID().toString()
        val unnamed = UUID.randomUUID().toString()
        val before = db.listTemplates(alex.id).size
        val eventId = cacheShared(buildJsonObject {
            put("blocks", blocksJson(block(remoteA, remoteMissing, unnamed)))
            put("exercises", buildJsonArray { add(ref(remoteA, exA.name)); add(ref(remoteMissing, "Zercher Moonwalk")) })
        })
        val err = assertThrows(UnknownExerciseException::class.java) {
            runBlocking { social.importRoutine(alex.id, eventId) }
        }
        assertEquals(listOf(MissingExercise(remoteMissing, "Zercher Moonwalk"), MissingExercise(unnamed)), err.missing)
        assertTrue(err.message!!.contains("Zercher Moonwalk ($remoteMissing)"))
        assertEquals(before, db.listTemplates(alex.id).size)

        val body = err.toJson()
        assertEquals(buildJsonArray { add(JsonPrimitive(remoteMissing)); add(JsonPrimitive(unnamed)) }, body["unknownExerciseIds"])
        assertEquals("Zercher Moonwalk", body["missingExercises"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(body["missingExercises"]!!.jsonArray[1].jsonObject["name"])
    }

    @Test
    fun `never matches a deleted exercise by name, but falls back to the name when the shared id was deleted here`() = runBlocking<Unit> {
        val retired = db.createExercise(name = "Retired move", muscleGroup = "Test")
        assertTrue(db.deleteExerciseIfUnused(retired.id).isSuccess)

        val remote = UUID.randomUUID().toString()
        val refused = cacheShared(buildJsonObject {
            put("blocks", blocksJson(block(remote)))
            put("exercises", buildJsonArray { add(ref(remote, "Retired move")) })
        })
        val err = assertThrows(UnknownExerciseException::class.java) {
            runBlocking { social.importRoutine(alex.id, refused) }
        }
        assertEquals(listOf(MissingExercise(remote, "Retired move")), err.missing)

        val fallback = cacheShared(buildJsonObject {
            put("blocks", blocksJson(block(retired.id)))
            put("exercises", buildJsonArray { add(ref(retired.id, exB.name)) })
        })
        assertEquals(listOf(exB.id), stepIds(social.importRoutine(alex.id, fallback).blocks))
    }

    @Test
    fun `refuses a payload whose blocks can't be read instead of importing an empty routine`() = runBlocking<Unit> {
        val before = db.listTemplates(alex.id).size
        val eventId = cacheShared(buildJsonObject {
            put("blocks", buildJsonArray { add(buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("sets", 3) }) }) }) })
        })
        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { social.importRoutine(alex.id, eventId) }
        }
        assertEquals("Corrupt routine payload in activity record", err.message)
        assertEquals(before, db.listTemplates(alex.id).size)
    }

    @Test
    fun `pickByName matches trimmed and case-insensitively, preferring an exact match, then name order, then id`() {
        val lib = listOf(
            ExerciseName("b", "bench press"),
            ExerciseName("a", "Bench Press "),
            ExerciseName("c", "BENCH PRESS"),
            ExerciseName("d", "Squat")
        )
        assertEquals("a", SharedRoutineExercises.pickByName(lib, "Bench Press")?.id)
        assertEquals("b", SharedRoutineExercises.pickByName(lib, " bench press")?.id)
        assertEquals("c", SharedRoutineExercises.pickByName(lib, "Bench press")?.id)
        assertNull(SharedRoutineExercises.pickByName(lib, "Deadlift"))
        assertEquals("x", SharedRoutineExercises.pickByName(listOf(ExerciseName("y", "X"), ExerciseName("x", "X")), "x")?.id)
    }
}
