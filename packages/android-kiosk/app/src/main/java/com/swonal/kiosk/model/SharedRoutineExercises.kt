package com.swonal.kiosk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * One exercise named in a shared routine (Nostr kind 30102, payload `type: "routine_share"`).
 *
 * Every install seeds its exercise library with its own random ids, so a routine shared from
 * another install points at ids that don't exist here. The share payload therefore carries,
 * next to the unchanged `blocks`:
 *
 *   "exercises": [{ "id": "<sharer's id>", "name": "Bench Press", "muscleGroup": "Chest", "accessory": "HANDLES" }]
 *
 * Same format and import rules as the server app (packages/server-app sharedRoutineExercises.ts):
 *   1. the id exists here and isn't deleted -> keep it (same-install sharing, paired devices);
 *   2. else the payload names it -> a non-deleted exercise with that name, compared trimmed and
 *      case-insensitively; if several match, an exact (case-sensitive) trimmed match wins, then
 *      the name that sorts first by UTF-16 code units, then the smaller id;
 *   3. else the whole import is refused with an [UnknownExerciseException] listing name and id.
 * Nothing is created; matched ids are rewritten in the imported blocks. Payloads without
 * `exercises` (older installs) only get step 1.
 */
@Serializable
data class SharedExerciseRef(
    val id: String,
    val name: String,
    val muscleGroup: String? = null,
    val accessory: String? = null
)

/** Just what name matching needs from the local library. */
data class ExerciseName(val id: String, val name: String)

/** An exercise a routine needs but this install doesn't have; [name] is known for shared routines. */
data class MissingExercise(val id: String, val name: String? = null)

/**
 * A routine references missing or soft-deleted exercises. Subclass of IllegalArgumentException so
 * every existing caller still maps it to HTTP 400; the embedded API adds `unknownExerciseIds` and
 * `missingExercises` to the body, matching the server app.
 */
class UnknownExerciseException(
    val missing: List<MissingExercise>,
    prefix: String = DEFAULT_PREFIX
) : IllegalArgumentException(
    "$prefix: " + missing.joinToString(", ") { m ->
        if (m.name != null) "${m.name} (${m.id.ifEmpty { "no id" }})" else m.id.ifEmpty { "(missing)" }
    }
) {
    val exerciseIds: List<String> get() = missing.map { it.id }

    fun toJson(): JsonObject = buildJsonObject {
        put("error", message.orEmpty())
        put("unknownExerciseIds", buildJsonArray { exerciseIds.forEach { add(JsonPrimitive(it)) } })
        put("missingExercises", buildJsonArray {
            missing.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    if (m.name != null) put("name", m.name)
                })
            }
        })
    }

    companion object {
        const val DEFAULT_PREFIX = "Unknown exercise id(s)"
        const val IMPORT_PREFIX = "Can't import this routine: it uses exercises that aren't in your library"
        const val QUEUE_PREFIX = "Can't queue this routine: edit it first, these exercises are missing or deleted"
    }
}

object SharedRoutineExercises {
    fun normalizeName(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Distinct exercise ids in step order. */
    fun exerciseIds(blocks: List<WorkoutBlock>): List<String> =
        blocks.flatMap { block -> block.items.map { it.exerciseId } }.distinct()

    /** Reads a received `exercises` list, ignoring malformed entries. First entry per id wins. */
    fun parseRefs(raw: JsonElement?): Map<String, SharedExerciseRef> {
        val refs = LinkedHashMap<String, SharedExerciseRef>()
        val arr = raw as? JsonArray ?: return refs
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            val id = obj.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: continue
            val name = obj.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (refs.containsKey(id)) continue
            refs[id] = SharedExerciseRef(id, name, obj.stringOrNull("muscleGroup"), obj.stringOrNull("accessory"))
        }
        return refs
    }

    /** The deterministic name match described on [SharedExerciseRef], or null. */
    fun pickByName(candidates: List<ExerciseName>, sharedName: String): ExerciseName? {
        val wanted = normalizeName(sharedName)
        val exact = sharedName.trim()
        return candidates
            .filter { normalizeName(it.name) == wanted }
            .sortedWith(
                compareBy<ExerciseName> { if (it.name.trim() == exact) 0 else 1 }
                    .thenBy { it.name }
                    .thenBy { it.id }
            )
            .firstOrNull()
    }

    fun rewriteIds(blocks: List<WorkoutBlock>, remap: Map<String, String>): List<WorkoutBlock> =
        if (remap.isEmpty()) blocks else blocks.map { block ->
            block.copy(items = block.items.map { item ->
                remap[item.exerciseId]?.let { item.copy(exerciseId = it) } ?: item
            })
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
