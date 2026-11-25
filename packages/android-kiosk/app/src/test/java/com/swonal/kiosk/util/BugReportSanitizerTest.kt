package com.swonal.kiosk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BugReportSanitizerTest {

    @Test
    fun `strips tokens, webhooks, emails, MACs, non-loopback IPs, and user names while keeping loopback`() {
        val raw = """
            2026-11-24 20:00:01 Connected to http://127.0.0.1:8765/ws (daemon ok)
            2026-11-24 20:00:02 Sync server https://admin:supersecret@192.168.0.21:3000/api/sync
            2026-11-24 20:00:03 Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig
            2026-11-24 20:00:04 Cookie: swonal_session=abc123def456
            2026-11-24 20:00:05 X-Plex-Token: myplexsecret1234
            2026-11-24 20:00:06 OpenAI key sk-proj-1234567890abcdefghij and Anthropic sk-ant-api03-abcdefghijklm
            2026-11-24 20:00:07 Nostr nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            2026-11-24 20:00:08 Webhook https://discord.com/api/webhooks/1234567890/AbCdEfGhIjKlMnOpQrStUvWxYz
            2026-11-24 20:00:09 Lifter Rob (rob@example.com) on BLE AA:BB:CC:DD:EE:FF paired with Jeri
        """.trimIndent()

        val scrubbed = BugReportSanitizer.sanitize(raw, redactNames = listOf("Rob", "Jeri"))

        assertTrue(scrubbed.contains("http://127.0.0.1:8765/ws"))
        assertFalse(scrubbed.contains("192.168.0.21"))
        assertFalse(scrubbed.contains("supersecret"))
        assertFalse(scrubbed.contains("eyJhbGciOiJIUzI1Ni"))
        assertFalse(scrubbed.contains("abc123def456"))
        assertFalse(scrubbed.contains("myplexsecret1234"))
        assertFalse(scrubbed.contains("sk-proj-1234567890"))
        assertFalse(scrubbed.contains("sk-ant-api03"))
        assertFalse(scrubbed.contains("nsec1qqqq"))
        assertFalse(scrubbed.contains("AbCdEfGhIjKlMnOpQrStUvWxYz"))
        assertFalse(scrubbed.contains("rob@example.com"))
        assertFalse(scrubbed.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(scrubbed.contains("Lifter Rob"))
        assertFalse(scrubbed.contains("with Jeri"))
        assertTrue(scrubbed.contains("[REDACTED_IP]"))
        assertTrue(scrubbed.contains("[REDACTED_EMAIL]"))
        assertTrue(scrubbed.contains("[REDACTED_MAC]"))
        assertTrue(scrubbed.contains("[REDACTED_WEBHOOK]"))
        assertTrue(scrubbed.contains("[USER]"))
    }

    @Test
    fun `validates Discord webhook URLs strictly`() {
        assertTrue(
            BugReportSanitizer.isValidDiscordWebhookUrl(
                "https://discord.com/api/webhooks/123456789012345678/abcDEF_123-456"
            )
        )
        assertFalse(
            BugReportSanitizer.isValidDiscordWebhookUrl(
                "https://discord.com/api/webhooks/123456789012345678/abcDEF_123-456/github"
            )
        )
        assertFalse(BugReportSanitizer.isValidDiscordWebhookUrl("http://192.168.0.1/webhook"))
        assertFalse(BugReportSanitizer.isValidDiscordWebhookUrl(""))
    }

    @Test
    fun `truncates oversized logs cleanly keeping tail`() {
        val longLog = "a".repeat(200) + "TAIL_MARKER"
        val out = BugReportSanitizer.sanitize(longLog, maxChars = 50)
        assertTrue(out.endsWith("TAIL_MARKER"))
        assertTrue(out.startsWith("... [truncated"))
        assertEquals(true, out.length < longLog.length)
    }
}
