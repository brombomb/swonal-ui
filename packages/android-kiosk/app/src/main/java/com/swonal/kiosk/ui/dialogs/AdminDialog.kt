package com.swonal.kiosk.ui.dialogs

/**
 * Full-page native Kiosk Hardware Admin & System screen.
 * Opens the unified full-screen [AppSettingsDialog] directly on the "Admin & System" tab
 * (embedded Latest Release check, Hardware Diagnostics, Cloud & Home Sync, Exercise Catalog, and Bug Report).
 */
class AdminDialog : AppSettingsDialog() {
    override val defaultInitialTab: Int = 3
}
