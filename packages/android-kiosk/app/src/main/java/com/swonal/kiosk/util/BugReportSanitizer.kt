package com.swonal.kiosk.util

/**
 * Scrubs PII, credentials, tokens, user names, emails, MACs, and non-loopback IP addresses
 * from diagnostic logs and bug report notes before sending anything off-device.
 */
object BugReportSanitizer {

    private val DISCORD_WEBHOOK_REGEX = Regex(
        """https://(?:ptb\.|canary\.)?(?:discord|discordapp)\.com/api/webhooks/[0-9]+/[A-Za-z0-9._-]+(?:/\S*)?""",
        RegexOption.IGNORE_CASE
    )
    private val URL_CREDENTIALS_REGEX = Regex("""(https?://)([^/\s:@]+):([^/\s@]+)@""", RegexOption.IGNORE_CASE)
    private val AUTH_HEADER_REGEX = Regex(
        """(?im)\b(Authorization|Cookie|Set-Cookie|X-Plex-Token|X-Api-Key|X-Swonal-Token)\s*[:=]\s*([^\r\n;]+)""",
    )
    private val BEARER_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val KEY_VALUE_SECRET_REGEX = Regex(
        """(?i)("?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passcode|secret|token|priv[_-]?key|nostrPrivKeyHex|webhook[_-]?url)"?\s*[:=]\s*"?)([^"\s,&}\]]{4,})("?)"""
    )
    private val PROVIDER_TOKEN_REGEX = Regex(
        """\b(?:sk-ant-[A-Za-z0-9_-]{12,}|sk-(?:proj-)?[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|nsec1[0-9a-z]{20,}|npub1[0-9a-z]{20,})\b"""
    )
    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val MAC_REGEX = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val IPV4_REGEX = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")

    fun isValidDiscordWebhookUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return DISCORD_WEBHOOK_REGEX.matches(trimmed) && !trimmed.endsWith("/github", ignoreCase = true)
    }

    fun sanitize(
        raw: String,
        redactNames: Collection<String> = emptyList(),
        maxChars: Int = 55_000
    ): String {
        if (raw.isEmpty()) return ""
        var out = raw

        // 1. Strip Discord webhook URLs and URL embedded credentials first
        out = DISCORD_WEBHOOK_REGEX.replace(out, "[REDACTED_WEBHOOK]")
        out = URL_CREDENTIALS_REGEX.replace(out, "$1[REDACTED]@")

        // 2. Strip auth headers, Bearer tokens, and secret key-value fields
        out = AUTH_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}: [REDACTED]" }
        out = BEARER_REGEX.replace(out, "Bearer [REDACTED]")
        out = KEY_VALUE_SECRET_REGEX.replace(out) { m ->
            "${m.groupValues[1]}[REDACTED]${m.groupValues[3]}"
        }
        out = PROVIDER_TOKEN_REGEX.replace(out, "[REDACTED_TOKEN]")

        // 3. Strip emails, MAC addresses, and non-loopback IPv4 addresses
        out = EMAIL_REGEX.replace(out, "[REDACTED_EMAIL]")
        out = MAC_REGEX.replace(out, "[REDACTED_MAC]")
        out = IPV4_REGEX.replace(out) { m ->
            val ip = m.value
            if (ip == "127.0.0.1" || ip == "0.0.0.0") ip else "[REDACTED_IP]"
        }

        // 4. Redact user profile names (whole-word, case-insensitive, skip 1-2 char names to avoid mangling logs)
        val cleanNames = redactNames
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
        for (name in cleanNames) {
            val pattern = Regex("""(?i)\b${Regex.escape(name)}\b""")
            out = pattern.replace(out, "[USER]")
        }

        return if (out.length > maxChars) {
            "... [truncated ${out.length - maxChars} chars] ...\n" + out.takeLast(maxChars)
        } else {
            out
        }
    }
}
