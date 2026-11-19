package com.swonal.kiosk.ui

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalThemeTokens
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Embedded in-app WebView screen powered by Mozilla GeckoView 140 ESR (`arm64-v8a`),
 * so Swonal's modern React/Vite screens (/history, /settings, /admin, /companion) render
 * natively inside the kiosk on Android 6.0 (API 23) with zero external browser chrome.
 */
class SwonalWebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        fun warmup(appContext: Context) {
            if (sharedRuntime != null) return
            Thread {
                try {
                    getOrCreateRuntime(appContext.applicationContext, isDark = true)
                } catch (_: Exception) {}
            }.start()
        }

        private fun getOrCreateRuntime(appContext: Context, isDark: Boolean): GeckoRuntime {
            val existing = sharedRuntime
            if (existing != null) {
                existing.settings.preferredColorScheme = if (isDark) {
                    GeckoRuntimeSettings.COLOR_SCHEME_DARK
                } else {
                    GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                }
                return existing
            }
            synchronized(this) {
                sharedRuntime?.let { return it }
                val settings = GeckoRuntimeSettings.Builder()
                    .preferredColorScheme(
                        if (isDark) GeckoRuntimeSettings.COLOR_SCHEME_DARK
                        else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                    )
                    .consoleOutput(false)
                    .build()
                return GeckoRuntime.create(appContext.applicationContext, settings).also {
                    sharedRuntime = it
                }
            }
        }
    }

    private lateinit var geckoView: GeckoView
    private var geckoSession: GeckoSession? = null
    private var canGoBack: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        val targetUrl = intent.getStringExtra(EXTRA_URL)?.trim()
        val pageTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Swonal"
        if (targetUrl.isNullOrBlank() || (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://"))) {
            Toast.makeText(this, "Invalid page URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as SwonalApp
        val ui = app.repository.uiStateFlow.value
        val palette = SwonalThemeTokens.resolve(
            ui.activeThemeMode,
            ui.activeAccentHex,
            SwonalThemeTokens.isSystemNight(this)
        )
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bgCanvas)
        }

        // Themed in-app top navigation bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.bgSurfaceSolid)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val backBtn = TextView(this).apply {
            text = "← Back to Workout"
            textSize = 13f
            typeface = SwonalFonts.interBold(this@SwonalWebActivity)
            setTextColor(palette.accentTextColor)
            background = palette.accentPillDrawable(d, 10f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            setOnClickListener { finish() }
        }.also { PressFeedback.attachSingle(it) }
        topBar.addView(backBtn)

        val titleTv = TextView(this).apply {
            text = pageTitle
            textSize = 16f
            typeface = SwonalFonts.interExtraBold(this@SwonalWebActivity)
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(12)
            }
        }
        topBar.addView(titleTv)

        val refreshBtn = TextView(this).apply {
            text = "↻ Reload"
            textSize = 12f
            typeface = SwonalFonts.interBold(this@SwonalWebActivity)
            setTextColor(palette.textPrimary)
            background = palette.elevatedPillDrawable(d, 10f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            setOnClickListener { geckoSession?.reload() }
        }.also { PressFeedback.attachSingle(it) }
        topBar.addView(refreshBtn)
        root.addView(topBar)

        // Thin loading progress bar under header
        val barColor = if (palette.isDark) palette.accentPrimary else palette.accentInk
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            val bg = GradientDrawable().apply { setColor(palette.borderSubtle) }
            val fg = GradientDrawable().apply { setColor(barColor) }
            progressDrawable = LayerDrawable(
                arrayOf(bg, ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL))
            ).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        root.addView(progressBar)

        val viewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(palette.bgCanvas)
        }

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(palette.bgCanvas)
        }
        viewContainer.addView(geckoView)

        // Themed dark loading placeholder overlay to eliminate white flash
        val loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(palette.bgCanvas)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val spinner = ProgressBar(this).apply {
            indeterminateDrawable?.setTint(if (palette.isDark) palette.accentPrimary else palette.accentInk)
            val s = dp(40)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        loadingOverlay.addView(spinner)

        val loadingLabel = TextView(this).apply {
            text = "Loading $pageTitle..."
            textSize = 14f
            typeface = SwonalFonts.interBold(this@SwonalWebActivity)
            setTextColor(palette.textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        loadingOverlay.addView(loadingLabel)
        viewContainer.addView(loadingOverlay)

        root.addView(viewContainer)
        setContentView(root)

        val runtime = getOrCreateRuntime(applicationContext, palette.isDark)
        val session = GeckoSession().apply {
            settings.useTrackingProtection = false
            settings.displayMode = GeckoSessionSettings.DISPLAY_MODE_STANDALONE

            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    this@SwonalWebActivity.canGoBack = canGoBack
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    runOnUiThread {
                        progressBar.progress = progress
                        progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
                        if (progress >= 85) {
                            if (loadingOverlay.visibility == View.VISIBLE) {
                                loadingOverlay.animate()
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction { loadingOverlay.visibility = View.GONE }
                            }
                        }
                    }
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    runOnUiThread {
                        loadingOverlay.visibility = View.GONE
                    }
                }
            }
        }
        session.open(runtime)
        geckoView.setSession(session)
        geckoSession = session

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) {
                    geckoSession?.goBack()
                } else {
                    finish()
                }
            }
        })

        session.loadUri(targetUrl)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        geckoView.releaseSession()
        geckoSession?.close()
        geckoSession = null
        super.onDestroy()
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
