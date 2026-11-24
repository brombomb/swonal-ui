package com.swonal.kiosk.engine

import com.swonal.kiosk.db.SwonalDatabase
import com.swonal.kiosk.model.CoachAskResponse
import com.swonal.kiosk.model.CoachConfig
import com.swonal.kiosk.model.CoachProviderOption
import com.swonal.kiosk.model.CoachSuggestedAction
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.util.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Serializable
data class StoredAiCoachConfig(
    val provider: String = "local",
    val model: String = LocalAiCoachEngine.DEFAULT_LOCAL_MODEL,
    val apiKeyEnc: String? = null,
    val baseUrl: String? = null
)

@Serializable
data class CoachTestResult(
    val ok: Boolean,
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val reply: String? = null,
    val error: String? = null
)

@Serializable
data class ExtendedCoachSuggestedAction(
    val type: String,
    val deltaLb: Double? = null,
    val targetLb: Double? = null,
    val mode: String? = null,
    val exerciseQuery: String? = null,
    val enabled: Boolean? = null,
    val label: String = ""
)

/**
 * Serverless on-device AI Coach:
 * - Deterministic voice-command parser (weights, modes, next/prev exercise, rest, engage/disengage)
 * - Offline heuristic strength coach using local PRs, sessions, and VBT drop
 * - Direct BYOK HTTPS/LAN execution (OpenAI, Anthropic, Google Gemini, Ollama) with TLS root CAs for API 23
 */
class LocalAiCoachEngine(
    private val db: SwonalDatabase,
    private val cipher: SecretCipher,
    tlsConfig: SwonalTlsConfig? = null,
    httpClientOverride: OkHttpClient? = null
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = httpClientOverride ?: OkHttpClient.Builder().apply {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
        writeTimeout(10, TimeUnit.SECONDS)
        callTimeout(11, TimeUnit.SECONDS)
        if (tlsConfig != null) {
            sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
        }
    }.build()

    private suspend fun loadStored(): StoredAiCoachConfig {
        val raw = db.getSetting(SETTING_AI_COACH) ?: return StoredAiCoachConfig()
        return runCatching { SwonalJson.decodeFromString<StoredAiCoachConfig>(raw) }
            .getOrDefault(StoredAiCoachConfig())
    }

    private fun decryptKey(stored: StoredAiCoachConfig): String {
        val enc = stored.apiKeyEnc?.takeIf { it.isNotBlank() } ?: return ""
        return cipher.decrypt(enc).orEmpty()
    }

    suspend fun getPublicConfig(): CoachConfig {
        val st = loadStored()
        val key = decryptKey(st)
        val effBase = st.baseUrl?.takeIf { it.isNotBlank() }
            ?: if (st.provider == "ollama") "http://localhost:11434" else null
        return CoachConfig(
            provider = st.provider,
            model = st.model.ifBlank { defaultModelFor(st.provider) },
            hasApiKey = key.isNotBlank(),
            keyHint = if (key.isNotBlank()) "••••${key.takeLast(4)}" else null,
            baseUrl = effBase,
            supportedProviders = SUPPORTED_PROVIDERS
        )
    }

    suspend fun setConfig(
        provider: String? = null,
        model: String? = null,
        apiKey: String? = null,
        baseUrl: String? = null,
        clearApiKey: Boolean = false
    ): CoachConfig {
        val cur = loadStored()
        val nextProvider = provider?.trim()?.lowercase(Locale.US)
            ?.takeIf { it in setOf("local", "openai", "anthropic", "gemini", "ollama") }
            ?: cur.provider
        val providerChanged = nextProvider != cur.provider
        val nextModel = when {
            !model.isNullOrBlank() -> model.trim().take(120)
            providerChanged || cur.model.isBlank() -> defaultModelFor(nextProvider)
            else -> cur.model
        }
        val nextKeyEnc = when {
            clearApiKey -> null
            !apiKey.isNullOrBlank() -> cipher.encrypt(apiKey.trim())
            else -> cur.apiKeyEnc
        }
        val nextBase = if (baseUrl != null) sanitizeHttpBaseUrl(baseUrl) else cur.baseUrl
        val updated = StoredAiCoachConfig(nextProvider, nextModel, nextKeyEnc, nextBase?.takeIf { it.isNotBlank() })
        db.setSetting(SETTING_AI_COACH, SwonalJson.encodeToString(updated))
        return getPublicConfig()
    }

    suspend fun testConnection(): CoachTestResult {
        val st = loadStored()
        val start = System.currentTimeMillis()
        if (st.provider == "local") {
            return CoachTestResult(
                ok = true,
                provider = "local",
                model = st.model.ifBlank { DEFAULT_LOCAL_MODEL },
                latencyMs = System.currentTimeMillis() - start,
                reply = "Built-in Local Heuristic Coach is ready offline (PR lookups, VBT fatigue cues, and voice commands active)."
            )
        }
        val res = callExternalProvider(
            st,
            "You are Swonal Coach. Reply in one short sentence confirming connection.",
            "Connection self-test from Swonal Settings.",
            "Respond with \"Swonal Coach ready.\""
        )
        val latency = System.currentTimeMillis() - start
        return if (res.first != null) {
            CoachTestResult(ok = true, provider = st.provider, model = st.model, latencyMs = latency, reply = res.first)
        } else {
            CoachTestResult(ok = false, provider = st.provider, model = st.model, latencyMs = latency, error = res.second ?: "Unknown AI provider error")
        }
    }

    suspend fun ask(
        userId: String,
        prompt: String,
        exerciseName: String? = null,
        currentWeightLb: Double? = null,
        repCount: Int? = null,
        meanVelocity: Double? = null,
        velocityDropPct: Double? = null
    ): CoachAskResponse {
        val out = askDetailed(userId, prompt, exerciseName, currentWeightLb, repCount, meanVelocity, velocityDropPct)
        return CoachAskResponse(
            provider = out.provider,
            model = out.model,
            reply = out.reply,
            suggestedAction = out.suggestedAction?.let {
                CoachSuggestedAction(type = it.type, deltaLb = it.deltaLb, label = it.label)
            }
        )
    }

    suspend fun askFullJson(
        userId: String,
        prompt: String,
        exerciseName: String? = null,
        currentWeightLb: Double? = null,
        repCount: Int? = null,
        meanVelocity: Double? = null,
        velocityDropPct: Double? = null
    ): JsonObject {
        val out = askDetailed(userId, prompt, exerciseName, currentWeightLb, repCount, meanVelocity, velocityDropPct)
        return buildJsonObject {
            put("provider", out.provider)
            put("model", out.model)
            put("reply", out.reply)
            if (out.fallbackReason != null) put("fallbackReason", out.fallbackReason)
            if (out.suggestedAction != null) {
                val a = out.suggestedAction
                put("suggestedAction", buildJsonObject {
                    put("type", a.type)
                    a.deltaLb?.let { put("deltaLb", it) }
                    a.targetLb?.let { put("targetLb", it) }
                    a.mode?.let { put("mode", it) }
                    a.exerciseQuery?.let { put("exerciseQuery", it) }
                    a.enabled?.let { put("enabled", it) }
                    put("label", a.label)
                })
            } else {
                put("suggestedAction", kotlinx.serialization.json.JsonNull)
            }
        }
    }

    data class DetailedAskResult(
        val provider: String,
        val model: String,
        val reply: String,
        val fallbackReason: String?,
        val suggestedAction: ExtendedCoachSuggestedAction?
    )

    private suspend fun askDetailed(
        userId: String,
        prompt: String,
        exerciseName: String?,
        currentWeightLb: Double?,
        repCount: Int?,
        meanVelocity: Double?,
        velocityDropPct: Double?
    ): DetailedAskResult {
        val st = loadStored()
        val user = db.getUserById(userId)
        val unit = if (Units.isKg(user?.weightUnit ?: "LB")) "KG" else "LB"
        val prs = db.listPrs(userId).take(6)
        val recentSessions = db.listSessions(userId, limit = 3)
        val action = detectVoiceCommand(prompt)

        val contextSummary = listOfNotNull(
            "Lifter: ${user?.name ?: "Athlete"} ($unit).",
            exerciseName?.let { "Active exercise: $it." },
            currentWeightLb?.let { "Current target weight: ${Units.formatCompactWeight(it, unit)} $unit." },
            repCount?.let { "Current set reps: $it." },
            if (meanVelocity != null && meanVelocity > 0) "Velocity: ${String.format(Locale.US, "%.1f", meanVelocity)} cm/s." else null,
            if (velocityDropPct != null && velocityDropPct > 0) "Live velocity drop: ${String.format(Locale.US, "%.0f", velocityDropPct)}%." else null,
            if (prs.isNotEmpty()) {
                "Top PRs: " + prs.joinToString("; ") { p ->
                    "${p.exercise?.name ?: p.exerciseId}: ${Units.formatCompactWeight(p.oneRepMaxEst, unit)} $unit 1RM"
                } + "."
            } else "No PRs logged yet.",
            if (recentSessions.isNotEmpty()) {
                "Recent sessions: " + recentSessions.joinToString("; ") { s ->
                    "${s.template?.name ?: "Free Lift"} (${Units.formatCompactWeight(s.totalVolume, unit, exact = true)} $unit vol)"
                } + "."
            } else null
        ).joinToString(" ")

        val systemPrompt =
            "You are Swonal Coach, a concise, motivating strength coach on a smart cable gym screen. Keep replies under 3 short sentences so they sound great spoken aloud between sets. Always match the user unit."

        var fallbackReason: String? = null
        if (st.provider != "local") {
            val (extReply, extErr) = callExternalProvider(st, systemPrompt, contextSummary, prompt)
            if (extReply != null) {
                return DetailedAskResult(
                    provider = st.provider,
                    model = st.model.ifBlank { defaultModelFor(st.provider) },
                    reply = extReply,
                    fallbackReason = null,
                    suggestedAction = action
                )
            }
            fallbackReason = extErr ?: "${st.provider} did not return a response"
        }

        // Built-in Offline Local Heuristic Coach
        if (action != null) {
            val curW = currentWeightLb ?: 45.0
            val reply = when (action.type) {
                "set_weight_delta" -> {
                    val d = action.deltaLb ?: 0.0
                    val nextW = (curW + d).coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
                    val verb = if (d > 0) "Adding" else "Dropping"
                    "$verb ${abs(d).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} $unit. Target is now ${Units.formatCompactWeight(nextW, unit)} $unit."
                }
                "set_weight_target" -> "Setting target weight to ${Units.formatCompactWeight(action.targetLb ?: curW, unit)} $unit."
                "set_mode" -> "Switched to ${(action.mode ?: "standard").lowercase(Locale.US)} mode. Lock in your form!"
                "set_weight_enabled" -> if (action.enabled == true) "Weight engaged. Control the eccentric and drive hard!" else "Weight off. Great set, catch your breath."
                "next_exercise" -> "Moving to the next exercise."
                "prev_exercise" -> "Going back to the previous exercise."
                "select_exercise" -> "Switching to ${action.exerciseQuery}."
                "complete_set" -> "Set logged! Solid work, take your rest."
                "skip_rest" -> "Rest timer skipped. Let's get after this set!"
                else -> "Got it, ${user?.name ?: "athlete"} — ready to ${action.label.lowercase(Locale.US)}."
            }
            return DetailedAskResult("local", DEFAULT_LOCAL_MODEL, reply, fallbackReason, action)
        }

        val q = prompt.lowercase(Locale.US)
        if (q.contains("pr") || q.contains("record") || q.contains("best")) {
            val top = prs.firstOrNull()
            val msg = if (top != null) {
                "${user?.name ?: "Your"} top recorded PR is ${top.exercise?.name ?: "this lift"} at ${Units.formatCompactWeight(top.oneRepMaxEst, unit)} $unit estimated 1RM."
            } else {
                "You haven't logged a PR yet—complete a working set to set your baseline."
            }
            return DetailedAskResult("local", DEFAULT_LOCAL_MODEL, msg, fallbackReason, null)
        }

        if (velocityDropPct != null && velocityDropPct >= 25.0) {
            return DetailedAskResult(
                "local",
                DEFAULT_LOCAL_MODEL,
                "Velocity dropped ${String.format(Locale.US, "%.0f", velocityDropPct)}% on ${exerciseName ?: "this set"}—rack the weight or switch to Spotter mode to protect form.",
                fallbackReason,
                null
            )
        }

        val lastVol = recentSessions.firstOrNull()?.totalVolume ?: 0.0
        val defaultReply = "${user?.name ?: "Athlete"}, you're set at ${Units.formatCompactWeight(currentWeightLb ?: 45.0, unit)} $unit" +
            "${exerciseName?.let { " on $it" } ?: ""}. Last session volume was ${Units.formatCompactWeight(lastVol, unit, exact = true)} $unit—keep eccentric tempo smooth and brace your core."
        return DetailedAskResult("local", DEFAULT_LOCAL_MODEL, defaultReply, fallbackReason, null)
    }

    private suspend fun callExternalProvider(
        st: StoredAiCoachConfig,
        systemPrompt: String,
        contextSummary: String,
        userPrompt: String
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val key = decryptKey(st)
        val model = st.model.ifBlank { defaultModelFor(st.provider) }
        val base = st.baseUrl?.trim()?.removeSuffix("/") ?: ""
        runCatching {
            when (st.provider) {
                "openai" -> {
                    if (key.isBlank()) return@withContext null to "OpenAI API key is required"
                    val url = if (base.isNotBlank()) "$base/chat/completions" else "https://api.openai.com/v1/chat/completions"
                    val body = buildJsonObject {
                        put("model", model)
                        put("max_tokens", 180)
                        put("messages", buildJsonArray {
                            add(buildJsonObject { put("role", "system"); put("content", "$systemPrompt\nContext: $contextSummary") })
                            add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                        })
                    }
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $key")
                        .post(SwonalJson.encodeToString(body).toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@use null to "OpenAI HTTP ${resp.code}: ${raw.take(160)}"
                        val el = SwonalJson.parseToJsonElement(raw).jsonObject
                        val text = el["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
                        if (!text.isNullOrBlank()) text to null else null to "OpenAI returned an empty reply"
                    }
                }
                "anthropic" -> {
                    if (key.isBlank()) return@withContext null to "Anthropic API key is required"
                    val url = if (base.isNotBlank()) "$base/v1/messages" else "https://api.anthropic.com/v1/messages"
                    val body = buildJsonObject {
                        put("model", model)
                        put("max_tokens", 180)
                        put("system", "$systemPrompt\nContext: $contextSummary")
                        put("messages", buildJsonArray {
                            add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                        })
                    }
                    val req = Request.Builder()
                        .url(url)
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01")
                        .post(SwonalJson.encodeToString(body).toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@use null to "Anthropic HTTP ${resp.code}: ${raw.take(160)}"
                        val el = SwonalJson.parseToJsonElement(raw).jsonObject
                        val text = el["content"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
                        if (!text.isNullOrBlank()) text to null else null to "Anthropic returned an empty reply"
                    }
                }
                "gemini" -> {
                    if (key.isBlank()) return@withContext null to "Gemini API key is required"
                    val url = if (base.isNotBlank()) {
                        "$base/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent"
                    } else {
                        "https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent"
                    }
                    val body = buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject { put("text", "$systemPrompt\nContext: $contextSummary\nUser: $userPrompt") })
                                })
                            })
                        })
                    }
                    val req = Request.Builder()
                        .url(url)
                        .header("x-goog-api-key", key)
                        .post(SwonalJson.encodeToString(body).toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@use null to "Gemini HTTP ${resp.code}: ${raw.take(160)}"
                        val el = SwonalJson.parseToJsonElement(raw).jsonObject
                        val text = el["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
                        if (!text.isNullOrBlank()) text to null else null to "Gemini returned an empty reply"
                    }
                }
                "ollama" -> {
                    val url = (base.ifBlank { "http://localhost:11434" }) + "/api/chat"
                    val body = buildJsonObject {
                        put("model", model)
                        put("stream", false)
                        put("messages", buildJsonArray {
                            add(buildJsonObject { put("role", "system"); put("content", "$systemPrompt\nContext: $contextSummary") })
                            add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                        })
                    }
                    val req = Request.Builder()
                        .url(url)
                        .post(SwonalJson.encodeToString(body).toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@use null to "Ollama HTTP ${resp.code}: ${raw.take(160)}"
                        val el = SwonalJson.parseToJsonElement(raw).jsonObject
                        val text = el["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
                        if (!text.isNullOrBlank()) text to null else null to "Ollama returned an empty reply"
                    }
                }
                else -> null to null
            }
        }.getOrElse { err ->
            null to (err.message ?: err.javaClass.simpleName)
        }
    }

    private fun sanitizeHttpBaseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return ""
        val parsed = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Base URL must be a valid http:// or https:// URL")
        require(parsed.scheme == "http" || parsed.scheme == "https") {
            "Base URL must use http:// or https://"
        }
        return parsed.toString().removeSuffix("/")
    }

    companion object {
        const val SETTING_AI_COACH = "ai_coach_config_json"
        const val DEFAULT_LOCAL_MODEL = "swonal-local-coach-v1"

        private val NUMBER_WORDS = mapOf(
            "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
            "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
            "eleven" to 11.0, "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0,
            "twenty five" to 25.0, "twenty-five" to 25.0, "thirty" to 30.0,
            "thirty five" to 35.0, "thirty-five" to 35.0, "forty" to 40.0,
            "forty five" to 45.0, "forty-five" to 45.0, "fifty" to 50.0,
            "fifty five" to 55.0, "fifty-five" to 55.0, "sixty" to 60.0,
            "sixty five" to 65.0, "sixty-five" to 65.0, "seventy" to 70.0,
            "seventy five" to 75.0, "seventy-five" to 75.0, "eighty" to 80.0,
            "eighty five" to 85.0, "eighty-five" to 85.0, "ninety" to 90.0,
            "ninety five" to 95.0, "ninety-five" to 95.0, "hundred" to 100.0, "one hundred" to 100.0
        )

        private const val NUMBER_TOKEN =
            "(?:\\d+(?:\\.\\d+)?|twenty[-\\s]five|thirty[-\\s]five|forty[-\\s]five|fifty[-\\s]five|sixty[-\\s]five|seventy[-\\s]five|eighty[-\\s]five|ninety[-\\s]five|one\\s+hundred|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)"

        private val ADD_REGEX = Regex(
            "(?:add|increase(?:\\s+weight)?(?:\\s+by)?|plus|up(?:\\s+by)?|bump(?:\\s+up)?(?:\\s+by)?|heavier(?:\\s+by)?|raise(?:\\s+weight)?(?:\\s+by)?)\\s+($NUMBER_TOKEN)\\s*(?:lb|lbs|pound|pounds|kg|kilos)?",
            RegexOption.IGNORE_CASE
        )
        private val DROP_REGEX = Regex(
            "(?:drop|decrease(?:\\s+weight)?(?:\\s+by)?|lower(?:\\s+weight)?(?:\\s+by)?|reduce(?:\\s+weight)?(?:\\s+by)?|minus|subtract|down(?:\\s+by)?|lighter(?:\\s+by)?|remove)\\s+($NUMBER_TOKEN)\\s*(?:lb|lbs|pound|pounds|kg|kilos)?",
            RegexOption.IGNORE_CASE
        )
        private val TARGET_REGEX = Regex(
            "(?:set\\s+(?:the\\s+)?weight\\s+(?:to\\s+)?|change\\s+(?:the\\s+)?weight\\s+to\\s+|make\\s+(?:it|the\\s+weight)\\s+|weight\\s+to\\s+)($NUMBER_TOKEN)\\s*(?:lb|lbs|pound|pounds|kg|kilos)?",
            RegexOption.IGNORE_CASE
        )
        private val SELECT_EX_REGEX = Regex(
            "^(?:switch\\s+(?:exercise\\s+|workout\\s+|movement\\s+)?to|change\\s+(?:exercise\\s+|workout\\s+|movement\\s+)?to|select\\s+exercise|load\\s+exercise|start\\s+exercise|do)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )

        val SUPPORTED_PROVIDERS = listOf(
            CoachProviderOption("local", "Built-In Local Coach (No Key)"),
            CoachProviderOption("openai", "OpenAI (BYOK)"),
            CoachProviderOption("anthropic", "Anthropic Claude (BYOK)"),
            CoachProviderOption("gemini", "Google Gemini (BYOK)"),
            CoachProviderOption("ollama", "Ollama (Local LLM)")
        )

        fun defaultModelFor(provider: String): String = when (provider.lowercase(Locale.US)) {
            "openai" -> "gpt-4o-mini"
            "anthropic" -> "claude-3-5-haiku-latest"
            "gemini" -> "gemini-2.5-flash"
            "ollama" -> "llama3.2"
            else -> DEFAULT_LOCAL_MODEL
        }

        private fun parseSpokenNumber(token: String): Double? {
            val cleaned = token.trim().lowercase(Locale.US)
            if (cleaned.isEmpty()) return null
            NUMBER_WORDS[cleaned]?.let { return it }
            val num = cleaned.toDoubleOrNull() ?: return null
            return if (num > 0.0) num else null
        }

        fun detectVoiceCommand(prompt: String): ExtendedCoachSuggestedAction? {
            val q = prompt.trim().lowercase(Locale.US).replace(Regex("[.!?]+$"), "")
            ADD_REGEX.find(q)?.groupValues?.getOrNull(1)?.let { parseSpokenNumber(it) }?.let { delta ->
                val fmt = if (delta % 1.0 == 0.0) delta.toInt().toString() else delta.toString()
                return ExtendedCoachSuggestedAction("set_weight_delta", deltaLb = delta, label = "Add +$fmt lb")
            }
            DROP_REGEX.find(q)?.groupValues?.getOrNull(1)?.let { parseSpokenNumber(it) }?.let { delta ->
                val fmt = if (delta % 1.0 == 0.0) delta.toInt().toString() else delta.toString()
                return ExtendedCoachSuggestedAction("set_weight_delta", deltaLb = -delta, label = "Drop -$fmt lb")
            }
            TARGET_REGEX.find(q)?.groupValues?.getOrNull(1)?.let { parseSpokenNumber(it) }?.let { target ->
                val fmt = if (target % 1.0 == 0.0) target.toInt().toString() else target.toString()
                return ExtendedCoachSuggestedAction("set_weight_target", targetLb = target, label = "Set Weight to $fmt lb")
            }

            if (Regex("\\b(?:eccentric\\s+mode|switch\\s+to\\s+eccentric|turn\\s+on\\s+eccentric|enable\\s+eccentric)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_mode", mode = "ECCENTRIC", label = "Switch to Eccentric Mode")
            }
            if (Regex("\\b(?:chains?\\s+mode|switch\\s+to\\s+chains?|turn\\s+on\\s+chains?|enable\\s+chains?)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_mode", mode = "CHAINS", label = "Switch to Chains Mode")
            }
            if (Regex("\\b(?:spotter\\s+mode|switch\\s+to\\s+spotter|turn\\s+on\\s+spotter|enable\\s+spotter)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_mode", mode = "SPOTTER", label = "Switch to Spotter Mode")
            }
            if (Regex("\\b(?:burnout\\s+mode|switch\\s+to\\s+burnout|turn\\s+on\\s+burnout|enable\\s+burnout)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_mode", mode = "BURNOUT", label = "Switch to Burnout Mode")
            }
            if (Regex("\\b(?:standard\\s+mode|normal\\s+mode|switch\\s+to\\s+standard|switch\\s+to\\s+normal|turn\\s+on\\s+standard)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_mode", mode = "STANDARD", label = "Switch to Standard Mode")
            }
            if (Regex("\\b(?:next\\s+exercise|next\\s+workout|next\\s+movement|skip\\s+exercise|skip\\s+movement)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("next_exercise", label = "Next Exercise")
            }
            if (Regex("\\b(?:prev(?:ious)?\\s+exercise|prev(?:ious)?\\s+workout|prev(?:ious)?\\s+movement|last\\s+exercise|go\\s+back\\s+an?\\s+exercise)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("prev_exercise", label = "Previous Exercise")
            }

            SELECT_EX_REGEX.find(prompt.trim().replace(Regex("[.!?]+$"), ""))?.groupValues?.getOrNull(1)?.trim()?.let { cand ->
                val lower = cand.lowercase(Locale.US)
                val isModeOrWeight = Regex("\\b(?:standard|normal|eccentric|chains?|spotter|burnout)\\b").containsMatchIn(lower) ||
                    Regex("^\\d+\\s*(?:lb|lbs|pound|pounds|kg)?$").matches(lower)
                if (cand.length >= 2 && !isModeOrWeight) {
                    return ExtendedCoachSuggestedAction("select_exercise", exerciseQuery = cand, label = "Switch to $cand")
                }
            }

            if (q.contains("skip rest") || q.contains("end rest") || q.contains("start set now") || q.contains("next set")) {
                return ExtendedCoachSuggestedAction("skip_rest", label = "Skip Rest Timer")
            }
            if (Regex("\\b(?:complete\\s+set|log\\s+set|finish\\s+set|done\\s+with\\s+set|end\\s+set)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("complete_set", label = "Complete & Log Set")
            }
            if (Regex("\\btoggle\\s+weight\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("toggle_weight", label = "Toggle Weight")
            }
            if (Regex("\\b(?:weight\\s+off|turn\\s+weight\\s+off|turn\\s+off\\s+weight|stop\\s+weight|rack\\s+weight|rack\\s+it|kill\\s+weight|drop\\s+load|emergency\\s+stop|disable\\s+weight|load\\s+off)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_weight_enabled", enabled = false, label = "Turn Weight Off")
            }
            if (Regex("\\b(?:weight\\s+on|turn\\s+weight\\s+on|turn\\s+on\\s+weight|start\\s+weight|engage\\s+weight|enable\\s+weight|load\\s+on)\\b").containsMatchIn(q)) {
                return ExtendedCoachSuggestedAction("set_weight_enabled", enabled = true, label = "Turn Weight On")
            }
            return null
        }
    }
}
