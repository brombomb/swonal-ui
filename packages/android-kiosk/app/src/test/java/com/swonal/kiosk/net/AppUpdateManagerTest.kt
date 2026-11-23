package com.swonal.kiosk.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `isNewerVersion compares semantic versions accurately`() {
        assertTrue(AppUpdateManager.isNewerVersion("1.2.0", "1.1.1"))
        assertTrue(AppUpdateManager.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.2", "1.1.1"))
        assertFalse(AppUpdateManager.isNewerVersion("1.1.1", "1.1.1"))
        assertFalse(AppUpdateManager.isNewerVersion("1.1.0", "1.1.1"))
        assertFalse(AppUpdateManager.isNewerVersion("0.9.5", "1.0.0"))
    }

    @Test
    fun `isAllowedDownloadUrl enforces strict HTTPS GitHub hosts`() {
        assertTrue(
            AppUpdateManager.isAllowedDownloadUrl(
                "https://github.com/brombomb/swonal-ui/releases/download/v1.2.0/swonal-kiosk-v1.2.0.apk"
            )
        )
        assertTrue(
            AppUpdateManager.isAllowedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/12345"
            )
        )
        assertFalse(AppUpdateManager.isAllowedDownloadUrl("http://github.com/brombomb/swonal-ui/a.apk"))
        assertFalse(AppUpdateManager.isAllowedDownloadUrl("https://evil.example.com/swonal.apk"))
        assertFalse(AppUpdateManager.isAllowedDownloadUrl("file:///data/local/tmp/a.apk"))
    }

    @Test
    fun `formatChangelogBullets extracts clean human-readable items`() {
        val raw = """
            ## What's Changed
            - **Routine Schedules**: Assign days of the week to saved routines.
            - **Free Lift Randomizer**: Tap the `🎲` button for a random exercise by @brombomb in https://github.com/brombomb/swonal-ui/pull/12
            **Full Changelog**: https://github.com/brombomb/swonal-ui/compare/v1.1.0...v1.2.0
        """.trimIndent()

        val bullets = AppUpdateManager.formatChangelogBullets(raw)
        assertEquals(2, bullets.size)
        assertEquals("Routine Schedules: Assign days of the week to saved routines.", bullets[0])
        assertEquals("Free Lift Randomizer: Tap the 🎲 button for a random exercise", bullets[1])
    }
}
