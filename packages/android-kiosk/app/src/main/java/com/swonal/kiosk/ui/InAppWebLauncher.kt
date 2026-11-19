package com.swonal.kiosk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.swonal.kiosk.theme.SwonalPalette

/**
 * Opens Swonal web screens (/history, /settings, /admin, /companion) inside our embedded
 * in-app [SwonalWebActivity] (backed by modern Mozilla GeckoView 140 ESR on arm64-v8a).
 * Never launches an external browser.
 */
object InAppWebLauncher {

    fun open(context: Context, url: String, title: String, palette: SwonalPalette) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https")) {
            Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show()
            return
        }

        val webIntent = Intent(context, SwonalWebActivity::class.java).apply {
            putExtra(SwonalWebActivity.EXTRA_URL, url)
            putExtra(SwonalWebActivity.EXTRA_TITLE, title)
        }
        context.startActivity(webIntent)
    }
}
