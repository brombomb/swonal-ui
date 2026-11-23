package com.swonal.kiosk.net

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class TelemetryDisplayMode(val wireValue: String, val label: String) {
    ALWAYS("always", "On"),
    OFF("off", "Off");

    fun next(): TelemetryDisplayMode = when (this) {
        ALWAYS -> OFF
        OFF -> ALWAYS
    }

    companion object {
        fun fromWire(value: String?): TelemetryDisplayMode =
            if (value == "off") OFF else ALWAYS
    }
}

/**
 * Preferences store using Android Keystore AES-256-GCM (natively supported from API 23) to encrypt
 * sensitive credentials (`apiToken`, `sessionCookie`) at rest.
 */
class SwonalPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("swonal_kiosk_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = normalizeBaseUrl(
            prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            DEFAULT_SERVER_URL
        )
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, normalizeBaseUrl(value, DEFAULT_SERVER_URL)).apply()
        }

    var localDaemonUrl: String
        get() = normalizeLoopbackDaemonUrl(
            prefs.getString(KEY_DAEMON_URL, DEFAULT_DAEMON_URL) ?: DEFAULT_DAEMON_URL
        )
        set(value) {
            prefs.edit().putString(KEY_DAEMON_URL, normalizeLoopbackDaemonUrl(value)).apply()
        }

    var apiToken: String?
        get() = readSecret(KEY_API_TOKEN)
        set(value) = writeSecret(KEY_API_TOKEN, value)

    var sessionCookie: String?
        get() = readSecret(KEY_SESSION_COOKIE)
        set(value) = writeSecret(KEY_SESSION_COOKIE, value)

    var lastKnownAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTH_ENABLED, value).apply()
        }

    var telemetryDisplayMode: TelemetryDisplayMode
        get() = TelemetryDisplayMode.fromWire(prefs.getString(KEY_TELEMETRY_MODE, TelemetryDisplayMode.ALWAYS.wireValue))
        set(value) {
            prefs.edit().putString(KEY_TELEMETRY_MODE, value.wireValue).apply()
        }

    var soundMuted: Boolean
        get() = prefs.getBoolean(KEY_SOUND_MUTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SOUND_MUTED, value).apply()
        }

    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEV_MODE, value).apply()
        }

    var cachedUsersJson: String?
        get() = prefs.getString(KEY_CACHED_USERS, null)
        set(value) {
            prefs.edit().putString(KEY_CACHED_USERS, value).apply()
        }

    var cachedExercisesJson: String?
        get() = prefs.getString(KEY_CACHED_EXERCISES, null)
        set(value) {
            prefs.edit().putString(KEY_CACHED_EXERCISES, value).apply()
        }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_API_TOKEN)
            .remove(KEY_SESSION_COOKIE)
            .apply()
    }

    private fun readSecret(key: String): String? {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!raw.startsWith(ENC_PREFIX)) {
            // Migrate legacy plaintext value transparently into encrypted storage
            writeSecret(key, raw)
            return raw
        }
        return runCatching { decryptFromPref(raw.removePrefix(ENC_PREFIX)) }.getOrNull()
    }

    private fun writeSecret(key: String, value: String?) {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encoded = runCatching { ENC_PREFIX + encryptForPref(trimmed) }.getOrNull()
        if (encoded != null) {
            prefs.edit().putString(key, encoded).apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encryptForPref(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptFromPref(b64: String): String {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        if (combined.size <= 12) throw IllegalArgumentException("Corrupt secret blob")
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.1.100:3000"
        const val DEFAULT_DAEMON_URL = "http://127.0.0.1:8765"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "swonal_kiosk_secret_key_v1"
        private const val ENC_PREFIX = "enc_v1:"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DAEMON_URL = "local_daemon_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_AUTH_ENABLED = "last_known_auth_enabled"
        private const val KEY_TELEMETRY_MODE = "telemetry_display_mode"
        private const val KEY_SOUND_MUTED = "sound_muted"
        private const val KEY_DEV_MODE = "developer_mode"
        private const val KEY_CACHED_USERS = "cached_users_json"
        private const val KEY_CACHED_EXERCISES = "cached_exercises_json"

        fun normalizeBaseUrl(raw: String, fallback: String = DEFAULT_SERVER_URL): String {
            val trimmed = raw.trim().removeSuffix("/")
            if (trimmed.isEmpty()) return fallback
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                val hostPart = trimmed.substringBefore("/").substringBefore(":").lowercase()
                if (isLoopbackOrPrivateHost(hostPart)) "http://$trimmed" else "https://$trimmed"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: return fallback
            return parsed.toString().removeSuffix("/")
        }

        /** Enforces loopback-only host for the Phase 3 fail-safe daemon endpoint. */
        fun normalizeLoopbackDaemonUrl(raw: String): String {
            val normalized = normalizeBaseUrl(raw, DEFAULT_DAEMON_URL)
            val parsed = normalized.toHttpUrlOrNull() ?: return DEFAULT_DAEMON_URL
            return if (isLoopbackHost(parsed.host)) normalized else DEFAULT_DAEMON_URL
        }

        fun isLoopbackHost(host: String): Boolean {
            val h = host.lowercase().removePrefix("[").removeSuffix("]")
            return h == "127.0.0.1" || h == "localhost" || h == "::1"
        }

        fun isLoopbackOrPrivateHost(host: String): Boolean {
            val h = host.lowercase().removePrefix("[").removeSuffix("]")
            if (isLoopbackHost(h)) return true
            if (h.startsWith("10.") || h.startsWith("192.168.")) return true
            if (h.startsWith("172.")) {
                val second = h.split(".").getOrNull(1)?.toIntOrNull()
                if (second != null && second in 16..31) return true
            }
            return false
        }

        fun sameOrigin(urlA: String, urlB: String): Boolean {
            val a = urlA.toHttpUrlOrNull() ?: return false
            val b = urlB.toHttpUrlOrNull() ?: return false
            return a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                a.port == b.port
        }

        /** Authenticates WebSocket upgrades via headers only (never leaks apiToken in query strings). */
        fun toWebSocketUrl(httpBaseUrl: String): String {
            val normalized = normalizeBaseUrl(httpBaseUrl, DEFAULT_SERVER_URL)
            val wsBase = when {
                normalized.startsWith("https://", ignoreCase = true) ->
                    "wss://${normalized.substring(8)}"
                normalized.startsWith("http://", ignoreCase = true) ->
                    "ws://${normalized.substring(7)}"
                else -> "wss://$normalized"
            }
            return "$wsBase/ws"
        }
    }
}
