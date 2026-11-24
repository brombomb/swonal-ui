package com.swonal.kiosk.social

import com.swonal.kiosk.db.StoredFriendRecord
import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.db.deleteFriend
import com.swonal.kiosk.db.getFriendActivityByEventId
import com.swonal.kiosk.db.getNostrCredentials
import com.swonal.kiosk.db.listFriendActivities
import com.swonal.kiosk.db.listFriends
import com.swonal.kiosk.db.saveNostrIdentity
import com.swonal.kiosk.db.updateFriendLastSeen
import com.swonal.kiosk.db.upsertFriend
import com.swonal.kiosk.db.upsertFriendActivity
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.round

@Serializable
data class LocalNostrIdentity(
    val userId: String,
    val userName: String,
    val npub: String,
    val pubkeyHex: String,
    val shareWorkouts: Boolean,
    val relays: List<String>
)

@Serializable
data class LocalFeedReaction(
    val id: String,
    val authorPubkey: String,
    val authorName: String,
    val emoji: String,
    val createdAt: String
)

@Serializable
data class LocalFeedItem(
    val id: String,
    val kind: Int,
    val authorPubkey: String,
    val authorName: String,
    val createdAt: String,
    val payload: JsonObject,
    val reactions: List<LocalFeedReaction> = emptyList()
)

/**
 * Serverless decentralized P2P Swocial engine backed by [SwonalDatabase] and OkHttp WebSockets.
 * Zero central server required — signs/verifies BIP-340 Schnorr events directly on the Tonal.
 */
class LocalNostrSwocialEngine(
    private val db: SwonalDatabase,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null
) {
    private val wsClient: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(4, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
        writeTimeout(4, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    suspend fun getOrCreateIdentity(userId: String): LocalNostrIdentity {
        val creds = db.getNostrCredentials(userId)
            ?: throw IllegalArgumentException("User not found: $userId")
        val relays = creds.relays.ifEmpty { DEFAULT_RELAYS }
        if (!creds.privKeyHex.isNullOrBlank() && !creds.pubKeyHex.isNullOrBlank() && !creds.npub.isNullOrBlank()) {
            return LocalNostrIdentity(
                userId = creds.userId,
                userName = creds.userName,
                npub = creds.npub,
                pubkeyHex = creds.pubKeyHex,
                shareWorkouts = creds.shareWorkouts,
                relays = relays
            )
        }
        val privHex = NostrCrypto.generateSecretKeyHex()
        val pubHex = NostrCrypto.getPublicKeyHex(privHex)
        val npub = NostrCrypto.npubEncode(pubHex)
        db.saveNostrIdentity(userId, privHex, pubHex, npub, relays, null)
        return LocalNostrIdentity(userId, creds.userName, npub, pubHex, creds.shareWorkouts, relays)
    }

    suspend fun updateIdentitySettings(
        userId: String,
        shareWorkouts: Boolean? = null,
        relays: List<String>? = null
    ): LocalNostrIdentity {
        getOrCreateIdentity(userId)
        val cleanRelays = relays
            ?.map { it.trim() }
            ?.filter { it.startsWith("wss://") || it.startsWith("ws://") }
            ?.takeIf { it.isNotEmpty() }
            ?: getOrCreateIdentity(userId).relays
        db.saveNostrIdentity(userId, null, null, null, cleanRelays, shareWorkouts)
        return getOrCreateIdentity(userId)
    }

    suspend fun listFriends(userId: String): List<StoredFriendRecord> = db.listFriends(userId)

    suspend fun addFriend(userId: String, npubOrHex: String, displayName: String?): StoredFriendRecord {
        val (pubHex, npub) = NostrCrypto.normalizePubkey(npubOrHex)
        val ident = getOrCreateIdentity(userId)
        require(!ident.pubkeyHex.equals(pubHex, ignoreCase = true)) {
            "Cannot add yourself as a friend"
        }
        val finalName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Lifter-${pubHex.take(6)}"
        return db.upsertFriend(userId, npub, pubHex, finalName, SwonalJson.encodeToString(DEFAULT_RELAYS))
    }

    suspend fun removeFriend(userId: String, friendId: String): Boolean = db.deleteFriend(userId, friendId)

    suspend fun publishWorkout(session: WorkoutSession, prs: List<String> = emptyList()): NostrSignedEvent? {
        val ident = getOrCreateIdentity(session.userId)
        if (!ident.shareWorkouts) return null
        val creds = db.getNostrCredentials(session.userId) ?: return null
        val privHex = creds.privKeyHex ?: return null

        val setsCount = session.setLogs.size
        val repsCount = session.setLogs.sumOf { it.repsCompleted }
        val startedMs = IsoTime.parseUtcMs(session.startedAt, System.currentTimeMillis())
        val completedMs = session.completedAt?.let { IsoTime.parseUtcMs(it, System.currentTimeMillis()) } ?: System.currentTimeMillis()
        val durationSec = ((completedMs - startedMs) / 1000L).coerceAtLeast(0L)
        val routineName = session.template?.name ?: "Free Lift"

        val payload = buildJsonObject {
            put("type", "workout_completion")
            put("sessionId", session.id)
            put("userName", ident.userName)
            put("routineName", routineName)
            put("templateName", routineName)
            put("totalVolumeLb", round(session.totalVolume).toInt())
            put("totalSets", setsCount)
            put("setsCount", setsCount)
            put("repsCount", repsCount)
            put("prCount", prs.size)
            put("durationSec", durationSec)
            put("prs", buildJsonArray { prs.forEach { add(JsonPrimitive(it)) } })
            put("summaryText", "Completed $routineName: ${round(session.totalVolume).toInt()} lb across $setsCount sets! 💪")
            put("completedAt", IsoTime.formatUtc(completedMs))
        }
        val tags = listOf(
            listOf("d", "swonal-session-${session.id}"),
            listOf("t", "swonal"),
            listOf("t", "workout"),
            listOf("app", "swonal"),
            listOf("title", routineName)
        )
        val ev = NostrCrypto.signEvent(privHex, KIND_WORKOUT, tags, SwonalJson.encodeToString(payload))
        db.upsertFriendActivity(
            friendPubkeyHex = ident.pubkeyHex,
            friendDisplayName = ident.userName,
            eventId = ev.id,
            kind = ev.kind,
            createdAtIso = IsoTime.formatUtc(ev.created_at * 1000L),
            contentJson = ev.content
        )
        publishToRelays(ident.relays, ev)
        return ev
    }

    suspend fun publishRoutine(userId: String, template: WorkoutTemplate): NostrSignedEvent {
        val ident = getOrCreateIdentity(userId)
        val privHex = db.getNostrCredentials(userId)?.privKeyHex ?: error("Missing Nostr private key")
        val payload = buildJsonObject {
            put("type", "routine_share")
            put("templateId", template.id)
            put("name", template.name)
            put("description", template.description.orEmpty())
            put("authorName", ident.userName)
            put("blocks", SwonalJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(WorkoutBlock.serializer()), template.blocks))
            put("sharedAt", IsoTime.nowIso())
        }
        val tags = listOf(
            listOf("d", "swonal-routine-${template.id}"),
            listOf("t", "swonal"),
            listOf("t", "routine"),
            listOf("app", "swonal"),
            listOf("title", template.name)
        )
        val ev = NostrCrypto.signEvent(privHex, KIND_ROUTINE, tags, SwonalJson.encodeToString(payload))
        db.upsertFriendActivity(
            friendPubkeyHex = ident.pubkeyHex,
            friendDisplayName = ident.userName,
            eventId = ev.id,
            kind = ev.kind,
            createdAtIso = IsoTime.formatUtc(ev.created_at * 1000L),
            contentJson = ev.content
        )
        publishToRelays(ident.relays, ev)
        return ev
    }

    suspend fun importRoutine(userId: String, eventId: String): WorkoutTemplate {
        val activity = db.getFriendActivityByEventId(eventId)
            ?: throw IllegalArgumentException("Shared routine not found in activity cache")
        require(activity.kind == KIND_ROUTINE) { "Event is not a shared routine" }
        val parsed = runCatching { SwonalJson.parseToJsonElement(activity.contentJson).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Corrupt routine payload in activity record")
        val friendAuthor = activity.friendDisplayName?.takeIf { it.isNotBlank() } ?: "Friend"
        val rName = parsed["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = if (rName != null) "$rName (from $friendAuthor)" else "Shared Routine (from $friendAuthor)"
        val desc = parsed["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "Shared peer-to-peer via Nostr by $friendAuthor"
        val blocksEl = parsed["blocks"]
        val blocks = when {
            blocksEl is JsonArray -> runCatching { SwonalJson.decodeFromJsonElement<List<WorkoutBlock>>(blocksEl) }.getOrDefault(emptyList())
            blocksEl != null -> runCatching { SwonalJson.decodeFromString<List<WorkoutBlock>>(blocksEl.jsonPrimitive.content) }.getOrDefault(emptyList())
            else -> emptyList()
        }
        return db.createTemplate(userId, name, desc, blocks)
    }

    suspend fun publishReaction(
        userId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String
    ): NostrSignedEvent {
        val ident = getOrCreateIdentity(userId)
        val privHex = db.getNostrCredentials(userId)?.privKeyHex ?: error("Missing Nostr private key")
        val cleanEmoji = emoji.trim().ifBlank { "💪" }
        val contentJson = SwonalJson.encodeToString(
            buildJsonObject {
                put("emoji", cleanEmoji)
                put("targetEventId", targetEventId)
                put("targetPubkey", targetPubkey)
            }
        )
        val ev = NostrCrypto.signEvent(
            privKeyHex = privHex,
            kind = KIND_REACTION,
            tags = listOf(listOf("e", targetEventId), listOf("p", targetPubkey), listOf("k", KIND_WORKOUT.toString())),
            content = contentJson
        )
        db.upsertFriendActivity(
            friendPubkeyHex = ident.pubkeyHex,
            friendDisplayName = ident.userName,
            eventId = ev.id,
            kind = ev.kind,
            createdAtIso = IsoTime.formatUtc(ev.created_at * 1000L),
            contentJson = contentJson
        )
        publishToRelays(ident.relays, ev)
        return ev
    }

    suspend fun syncFeed(userId: String): Int {
        val ident = getOrCreateIdentity(userId)
        val friends = db.listFriends(userId)
        val friendMap = friends.associateBy { it.pubkeyHex.lowercase(Locale.US) }
        val watched = (friends.map { it.pubkeyHex.lowercase(Locale.US) } + ident.pubkeyHex.lowercase(Locale.US)).distinct()
        if (watched.isEmpty()) return 0

        val postFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_WORKOUT)); add(JsonPrimitive(KIND_ROUTINE)); add(JsonPrimitive(KIND_REACTION)) })
            put("authors", buildJsonArray { watched.forEach { add(JsonPrimitive(it)) } })
            put("limit", 100)
        }
        val reactionFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_REACTION)) })
            put("#p", buildJsonArray { watched.forEach { add(JsonPrimitive(it)) } })
            put("limit", 100)
        }

        val events = queryRelays(ident.relays, listOf(postFilter, reactionFilter), timeoutMs = 4500L)
        var count = 0
        for (ev in events) {
            if (!NostrCrypto.verifyEvent(ev)) continue
            val authorLower = ev.pubkey.lowercase(Locale.US)
            val friend = friendMap[authorLower]
            val isSelf = authorLower.equals(ident.pubkeyHex, ignoreCase = true)
            val displayName = friend?.displayName ?: if (isSelf) "${ident.userName} (You)" else null
            val createdIso = IsoTime.formatUtc(ev.created_at * 1000L)
            db.upsertFriendActivity(authorLower, displayName, ev.id, ev.kind, createdIso, ev.content)
            if (friend != null) {
                val prevMs = IsoTime.parseUtcMs(friend.lastSeenAt, 0L)
                if (ev.created_at * 1000L > prevMs) {
                    db.updateFriendLastSeen(userId, authorLower, createdIso)
                }
            }
            count++
        }
        return count
    }

    suspend fun getFeed(userId: String, limit: Int = 50): List<LocalFeedItem> {
        val ident = getOrCreateIdentity(userId)
        val friends = db.listFriends(userId)
        val friendMap = mutableMapOf<String, String>()
        friends.forEach { friendMap[it.pubkeyHex.lowercase(Locale.US)] = it.displayName }
        friendMap[ident.pubkeyHex.lowercase(Locale.US)] = "${ident.userName} (You)"

        val allowed = friendMap.keys.toList()
        if (allowed.isEmpty()) return emptyList()
        val allRows = db.listFriendActivities(allowed, limit = 200)
        val reactionsByTarget = mutableMapOf<String, MutableList<LocalFeedReaction>>()

        for (r in allRows.filter { it.kind == KIND_REACTION }.sortedBy { it.createdAt }) {
            val parsed = runCatching { SwonalJson.parseToJsonElement(r.contentJson).jsonObject }.getOrNull()
            val targetId = parsed?.get("targetEventId")?.jsonPrimitive?.contentOrNull ?: continue
            val emoji = parsed["emoji"]?.jsonPrimitive?.contentOrNull ?: "💪"
            val authorLower = r.friendPubkeyHex.lowercase(Locale.US)
            val list = reactionsByTarget.getOrPut(targetId) { mutableListOf() }
            list += LocalFeedReaction(
                id = r.eventId,
                authorPubkey = authorLower,
                authorName = friendMap[authorLower] ?: r.friendDisplayName ?: "Friend",
                emoji = emoji,
                createdAt = r.createdAt
            )
        }

        return allRows
            .filter { it.kind == KIND_WORKOUT || it.kind == KIND_ROUTINE }
            .take(limit)
            .map { act ->
                val authorLower = act.friendPubkeyHex.lowercase(Locale.US)
                val payload = runCatching { SwonalJson.parseToJsonElement(act.contentJson).jsonObject }
                    .getOrElse { buildJsonObject { put("raw", act.contentJson) } }
                LocalFeedItem(
                    id = act.eventId,
                    kind = act.kind,
                    authorPubkey = authorLower,
                    authorName = friendMap[authorLower] ?: act.friendDisplayName ?: "Friend",
                    createdAt = act.createdAt,
                    payload = payload,
                    reactions = reactionsByTarget[act.eventId].orEmpty()
                )
            }
    }

    private suspend fun publishToRelays(relays: List<String>, event: NostrSignedEvent) = withContext(Dispatchers.IO) {
        val frame = SwonalJson.encodeToString(
            buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(SwonalJson.encodeToJsonElement(NostrSignedEvent.serializer(), event))
            }
        )
        coroutineScope {
            relays.map { relayUrl ->
                async {
                    withTimeoutOrNull(3500L) {
                        val done = CompletableDeferred<Unit>()
                        val req = Request.Builder().url(relayUrl).build()
                        val ws = wsClient.newWebSocket(req, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(frame)
                                done.complete(Unit)
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                done.complete(Unit)
                            }
                        })
                        done.await()
                        runCatching { ws.close(1000, null) }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun queryRelays(
        relays: List<String>,
        filters: List<JsonObject>,
        timeoutMs: Long
    ): List<NostrSignedEvent> = withContext(Dispatchers.IO) {
        val byId = java.util.concurrent.ConcurrentHashMap<String, NostrSignedEvent>()
        coroutineScope {
            relays.map { relayUrl ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        val subId = "sw_${UUID.randomUUID().toString().take(8)}"
                        val reqFrame = SwonalJson.encodeToString(
                            buildJsonArray {
                                add(JsonPrimitive("REQ"))
                                add(JsonPrimitive(subId))
                                filters.forEach { add(it) }
                            }
                        )
                        val done = CompletableDeferred<Unit>()
                        val req = Request.Builder().url(relayUrl).build()
                        val ws = wsClient.newWebSocket(req, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(reqFrame)
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val arr = runCatching { SwonalJson.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                                val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                                when (msgType) {
                                    "EVENT" -> {
                                        val evEl: JsonElement = arr.getOrNull(2) ?: return
                                        runCatching {
                                            val ev = SwonalJson.decodeFromJsonElement(NostrSignedEvent.serializer(), evEl)
                                            byId[ev.id] = ev
                                        }
                                    }
                                    "EOSE" -> {
                                        webSocket.close(1000, null)
                                        done.complete(Unit)
                                    }
                                }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                done.complete(Unit)
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                done.complete(Unit)
                            }
                        })
                        done.await()
                        runCatching { ws.close(1000, null) }
                    }
                }
            }.awaitAll()
        }
        byId.values.toList()
    }

    companion object {
        const val KIND_WORKOUT = 30101
        const val KIND_ROUTINE = 30102
        const val KIND_REACTION = 7
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.snort.social",
            "wss://relay.primal.net"
        )
    }
}
