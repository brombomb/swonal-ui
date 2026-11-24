package com.swonal.kiosk.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Android 6.0 (API 23) + Host JVM compatible utilities.
 * Never uses `java.util.Base64` (API 26+) or `java.time.*` (API 26+).
 */
object Base64Compat {
    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in STD_ALPHABET.indices) this[STD_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun encodeUrlNoPadding(data: ByteArray): String = encode(data, URL_ALPHABET, pad = false)

    fun encodeStd(data: ByteArray): String = encode(data, STD_ALPHABET, pad = true)

    fun decode(input: String): ByteArray {
        val cleaned = input.trim().filter { !it.isWhitespace() && it != '=' }
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ByteArray((cleaned.length * 3) / 4)
        var outIdx = 0
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val code = ch.code
            val v = if (code < 256) DECODE_TABLE[code] else -1
            if (v < 0) throw IllegalArgumentException("Invalid Base64 character: $ch")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun encode(data: ByteArray, alphabet: String, pad: Boolean): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val hasB1 = i < data.size
            val b1 = if (hasB1) data[i++].toInt() and 0xFF else 0
            val hasB2 = i < data.size
            val b2 = if (hasB2) data[i++].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            if (hasB1) sb.append(alphabet[(triple ushr 6) and 0x3F]) else if (pad) sb.append('=')
            if (hasB2) sb.append(alphabet[triple and 0x3F]) else if (pad) sb.append('=')
        }
        return sb.toString()
    }
}

object IsoTime {
    private val UTC_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = true
            }
    }

    private val UTC_NO_MILLIS_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = true
            }
    }

    private val DATE_ONLY_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    fun nowIso(): String = formatUtc(System.currentTimeMillis())

    fun formatUtc(epochMs: Long): String = UTC_FORMAT.get()!!.format(Date(epochMs))

    fun todayUtcDate(epochMs: Long = System.currentTimeMillis()): String =
        DATE_ONLY_FORMAT.get()!!.format(Date(epochMs))

    fun parseUtcMs(iso: String?, fallbackMs: Long = 0L): Long {
        val raw = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return fallbackMs
        return runCatching { UTC_FORMAT.get()!!.parse(raw)?.time }.getOrNull()
            ?: runCatching { UTC_NO_MILLIS_FORMAT.get()!!.parse(raw)?.time }.getOrNull()
            ?: fallbackMs
    }
}

interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(stored: String): String?
}

object PlaintextTestCipher : SecretCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(stored: String): String = stored
}

object CryptoCompat {
    private val rng = SecureRandom()

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { "%02x".format(it) }

    fun randomBytes(len: Int): ByteArray = ByteArray(len).also { rng.nextBytes(it) }

    fun randomHex(byteLen: Int): String = randomBytes(byteLen).joinToString("") { "%02x".format(it) }

    fun hashPasscode(passcode: String): String {
        val salt = randomHex(16)
        val derived = derivePbkdf2(passcode, salt)
        return "pbkdf2:$salt:$derived"
    }

    fun verifyPasscode(passcode: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        val parts = storedHash.split(":")
        if (parts.size != 3 || parts[0] != "pbkdf2") return false
        val salt = parts[1]
        val expectedHex = parts[2]
        val actualHex = derivePbkdf2(passcode, salt)
        return MessageDigest.isEqual(
            expectedHex.toByteArray(Charsets.UTF_8),
            actualHex.toByteArray(Charsets.UTF_8)
        )
    }

    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    private fun derivePbkdf2(passcode: String, salt: String): String {
        val spec = PBEKeySpec(passcode.toCharArray(), salt.toByteArray(Charsets.UTF_8), 65_536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Validates external integration URLs against SSRF into loopback daemon/services or link-local metadata endpoints.
 * Private LAN hosts (192.168.x.x, 10.x.x.x, 172.16-31.x.x, *.local) remain allowed for Home Assistant & LAN music.
 */
object UrlSecurity {
    fun sanitizeExternalUrl(raw: String, allowLoopback: Boolean = false): String {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching { URL(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("URL must be a valid http:// or https:// URL")
        val proto = parsed.protocol.lowercase(Locale.US)
        require(proto == "http" || proto == "https") {
            "URL must use http:// or https://"
        }
        require(parsed.userInfo.isNullOrEmpty()) {
            "URL must not contain embedded credentials"
        }
        val host = parsed.host.orEmpty().lowercase(Locale.US).removePrefix("[").removeSuffix("]")
        require(host.isNotEmpty()) { "URL host is required" }
        require(host != "metadata.google.internal" && host != "0.0.0.0") {
            "Blocked internal metadata or wildcard address"
        }
        if (host.startsWith("169.254.") || host.startsWith("fe80:") || host.startsWith("fec0:")) {
            throw IllegalArgumentException("Blocked link-local metadata IP address")
        }
        if (!allowLoopback) {
            require(!isLoopbackHostOrIp(host)) {
                "Loopback addresses (localhost / 127.0.0.0/8 / ::1) are not allowed for this integration"
            }
        }
        return trimmed
    }

    fun isLoopbackHostOrIp(rawHost: String): Boolean {
        val host = rawHost.lowercase(Locale.US).removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (host.startsWith("127.")) return true
        // Reject hex/octal multi-radix IPv4 loopback notation (e.g., 0x7f.0.0.1 or 0177.0.0.1)
        if (host.split('.').any { it.startsWith("0x") || (it.length > 1 && it.startsWith("0") && it.all(Char::isDigit)) }) {
            return true
        }
        // Handle decimal single-integer IPv4 loopback form
        if (host.all { it.isDigit() }) {
            val asLong = host.toLongOrNull() ?: return false
            if ((asLong ushr 24) == 127L) return true
        }
        val addr = runCatching {
            if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
                InetAddress.getByName(host)
            } else null
        }.getOrNull()
        return addr != null && (addr.isLoopbackAddress || (addr is Inet4Address && (addr.address.firstOrNull()?.toInt()?.and(0xFF) == 127)))
    }
}
