package com.swonal.kiosk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.swonal.kiosk.net.KioskUiState
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import kotlin.math.roundToInt

object KioskPopups {

    fun showQuickPreferences(
        context: Context,
        anchor: View,
        palette: SwonalPalette,
        onOpenProfiles: () -> Unit,
        onOpenMenu: () -> Unit,
        onOpenWebPath: (path: String, title: String) -> Unit,
        onCheckUpdates: () -> Unit,
        onOpenBugReport: () -> Unit
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        lateinit var popup: PopupWindow

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.solidSurfaceDrawable(d, 16f, palette.borderTopHighlight)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            addView(TextView(context).apply {
                text = "SETTINGS"
                textSize = 10.5f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(context)
                setTextColor(palette.textMuted)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(palette.textMuted)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { popup.dismiss() }
            }.also { PressFeedback.attachSingle(it) })
        }
        root.addView(header)

        fun addIconRow(label: String, iconRes: Int, bottomMarginDp: Int = 6, onClick: () -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = palette.elevatedPillDrawable(d, 10f)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(38)
                ).apply { bottomMargin = dp(bottomMarginDp) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(palette.accentInk)
                    layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                        marginEnd = dp(10)
                    }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 12f
                    typeface = SwonalFonts.interBold(context)
                    setTextColor(palette.textPrimary)
                })
            }.also { PressFeedback.attachSingle(it) }
            root.addView(row)
        }

        addIconRow("User Prefs", com.swonal.kiosk.R.drawable.ic_users) {
            onOpenProfiles()
        }
        addIconRow("Menu & Stock Tonal", com.swonal.kiosk.R.drawable.ic_apps) {
            onOpenMenu()
        }
        addIconRow("Application Settings", com.swonal.kiosk.R.drawable.ic_settings) {
            onOpenWebPath("/settings", "Application Settings")
        }
        addIconRow("Check for Updates", com.swonal.kiosk.R.drawable.ic_sparkles) {
            onCheckUpdates()
        }
        addIconRow("Send Bug Report", com.swonal.kiosk.R.drawable.ic_alert_triangle, bottomMarginDp = 0) {
            onOpenBugReport()
        }

        popup = PopupWindow(root, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 16f * d
            isOutsideTouchable = true
            setTouchInterceptor { _, _ ->
                com.swonal.kiosk.util.IdleActivityTracker.noteActivity()
                false
            }
        }
        popup.showAsDropDown(anchor, -dp(194), dp(8))
    }

    fun showArmPositionGuide(
        context: Context,
        anchor: View,
        palette: SwonalPalette,
        telemetry: com.swonal.kiosk.model.TelemetryFrame? = null,
        exercise: com.swonal.kiosk.model.Exercise? = null,
        isMockMode: Boolean = false,
        onSyncMockArms: (() -> Unit)? = null,
        liveTelemetryProvider: (() -> com.swonal.kiosk.model.TelemetryFrame)? = null
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        lateinit var popup: PopupWindow

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.solidSurfaceDrawable(d, 14f, palette.borderTopHighlight)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(context).apply {
                text = "ARM POSITION & SENSORS (LIVE)"
                textSize = 10.5f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(context)
                setTextColor(palette.accentInk)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 13f
                setTextColor(palette.textMuted)
                setPadding(dp(6), dp(2), dp(4), dp(2))
                setOnClickListener { popup.dismiss() }
            }.also { PressFeedback.attachSingle(it) })
        }
        root.addView(header)

        if (isMockMode && onSyncMockArms != null) {
            root.addView(makePopupButton(context, "Simulate Aligned Arms (Mock)", palette, d) {
                onSyncMockArms()
                popup.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(36)
                ).apply { bottomMargin = dp(8) }
            })
        }

        listOf(
            "H (Height): Rail notch (1 bottom – 9 top)",
            "R (Rotation): Arm swing latch (2 – 6, 1 = stowed)",
            "A (Angle): Arm pivot (1 down – 5 up, 0 = folded)"
        ).forEach { line ->
            root.addView(TextView(context).apply {
                text = line
                textSize = 11f
                setTextColor(palette.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            })
        }

        // Live Arm Symmetry & Position Panel (refreshed every 250ms while open)
        val symBanner = TextView(context).apply {
            textSize = 11.5f
            typeface = SwonalFonts.interExtraBold(context)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        root.addView(symBanner)

        val liveBox = TextView(context).apply {
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(context)
            setTextColor(palette.textPrimary)
            background = palette.elevatedPillDrawable(d, 10f, interactive = false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(liveBox)

        fun rotLabel(r: Int): String = if (r == 1) "R1(Stow)" else "R$r"
        fun angLabel(a: Int): String = if (a == 0) "A0(Fold)" else "A$a"

        var lastPanelKey = ""
        var lastBannerState = -1
        fun updateLivePanel() {
            val frame = liveTelemetryProvider?.invoke() ?: telemetry
            if (frame == null) {
                val panelKey = "${exercise?.id}|UNAVAILABLE"
                if (panelKey == lastPanelKey) return
                lastPanelKey = panelKey
                if (lastBannerState != -2) {
                    lastBannerState = -2
                    symBanner.text = "ARM SENSORS UNAVAILABLE"
                    symBanner.setTextColor(palette.warningText)
                    symBanner.background = palette.warningPillDrawable(d, 8f, interactive = false)
                }
                liveBox.text = buildString {
                    if (exercise != null) {
                        append("Target: ${com.swonal.kiosk.model.ArmPositionSets.targetCoords(exercise)}\n")
                    }
                    append("Waiting for live arm sensor telemetry...")
                }
                return
            }

            val arms = frame.armPositions
            val leftLocked = !(arms.rotation.leftUnlocked || arms.height.leftUnlocked || arms.angle.leftUnlocked)
            val rightLocked = !(arms.rotation.rightUnlocked || arms.height.rightUnlocked || arms.angle.rightUnlocked)
            val hMatch = arms.height.left == arms.height.right
            val rMatch = arms.rotation.left == arms.rotation.right
            val aMatch = arms.angle.left == arms.angle.right
            val symmetric = leftLocked && rightLocked && hMatch && rMatch && aMatch

            val panelKey = "${exercise?.id}|${arms.height.left}:${arms.rotation.left}:${arms.angle.left}:$leftLocked|" +
                "${arms.height.right}:${arms.rotation.right}:${arms.angle.right}:$rightLocked"
            if (panelKey == lastPanelKey) return
            lastPanelKey = panelKey

            val bannerState = when {
                !leftLocked || !rightLocked -> 0
                symmetric -> 1
                else -> 2
            }
            if (bannerState != lastBannerState) {
                lastBannerState = bannerState
                when (bannerState) {
                    0 -> {
                        symBanner.text = "ARM LOCK PIN OPEN — LOCK BEFORE LIFTING"
                        symBanner.setTextColor(palette.warningText)
                        symBanner.background = palette.warningPillDrawable(d, 8f, interactive = false)
                    }
                    1 -> {
                        symBanner.text = "ARMS SYMMETRIC (L = R)"
                        symBanner.setTextColor(palette.successText)
                        symBanner.background = palette.successPillDrawable(d, 8f, interactive = false)
                    }
                    else -> {
                        symBanner.text = "ARMS UNEVEN — ADJUST TO MATCH"
                        symBanner.setTextColor(palette.criticalText)
                        symBanner.background = palette.criticalBannerDrawable(d, 8f)
                    }
                }
            }

            liveBox.text = buildString {
                if (exercise != null) {
                    append("Target: ${com.swonal.kiosk.model.ArmPositionSets.targetCoords(exercise)}\n")
                }
                append("Left:   H${arms.height.left}  ${rotLabel(arms.rotation.left)}  ${angLabel(arms.angle.left)}")
                if (!leftLocked) append(" [UNLOCKED]")
                append('\n')
                append("Right:  H${arms.height.right}  ${rotLabel(arms.rotation.right)}  ${angLabel(arms.angle.right)}")
                if (!rightLocked) append(" [UNLOCKED]")
                append('\n')
                append(
                    "Match:  H:${if (hMatch) "✓" else "≠"}  R:${if (rMatch) "✓" else "≠"}  A:${if (aMatch) "✓" else "≠"}"
                )
            }
        }

        updateLivePanel()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val refresher = object : Runnable {
            override fun run() {
                updateLivePanel()
                handler.postDelayed(this, 250L)
            }
        }
        handler.postDelayed(refresher, 250L)

        popup = PopupWindow(root, dp(324), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 16f * d
            isOutsideTouchable = true
            setTouchInterceptor { _, _ ->
                com.swonal.kiosk.util.IdleActivityTracker.noteActivity()
                false
            }
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(refresher)
                if (popup.isShowing) popup.dismiss()
            }
        }
        anchor.addOnAttachStateChangeListener(attachListener)
        popup.setOnDismissListener {
            handler.removeCallbacks(refresher)
            anchor.removeOnAttachStateChangeListener(attachListener)
        }
        popup.showAsDropDown(anchor, -dp(260), dp(6))
    }

    private fun makePopupButton(
        context: Context,
        label: String,
        palette: SwonalPalette,
        d: Float,
        mono: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = if (mono) SwonalFonts.monoBold(context) else SwonalFonts.interBold(context)
            setTextColor(palette.textPrimary)
            background = palette.elevatedPillDrawable(d, 10f)
            setOnClickListener { onClick() }
        }.also { PressFeedback.attachSingle(it) }
    }

    private fun makeToggleRow(
        context: Context,
        title: String,
        value: String,
        palette: SwonalPalette,
        d: Float
    ): Pair<LinearLayout, TextView> {
        fun dp(v: Int) = (v * d).toInt()
        val valTv = TextView(context).apply {
            text = value
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(context)
            setTextColor(palette.accentInk)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = palette.elevatedPillDrawable(d, 10f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            addView(TextView(context).apply {
                text = title
                textSize = 12f
                typeface = SwonalFonts.interBold(context)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valTv)
        }.also { PressFeedback.attachSingle(it) }
        return row to valTv
    }

    private fun createSliderTrack(trackColor: Int, fillColor: Int, heightPx: Float): LayerDrawable {
        val radius = heightPx * 0.5f
        val bg = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = radius
            setSize(-1, heightPx.toInt().coerceAtLeast(4))
        }
        val fg = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            setSize(-1, heightPx.toInt().coerceAtLeast(4))
        }
        val clip = ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)
        val insetY = (heightPx * 0.9f).roundToInt().coerceAtLeast(4)
        return LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(0, 0, insetY, 0, insetY)
            setLayerInset(1, 0, insetY, 0, insetY)
        }
    }

    /**
     * Fast inline speaker selector anchored directly above the Music Dock's speaker pill.
     * Sorts the on-device Tonal `librespot` speaker first so it's always 1 tap away.
     */
    fun showSpeakerPicker(
        context: Context,
        anchor: View,
        ui: KioskUiState,
        palette: SwonalPalette,
        onSelectDevice: (String) -> Unit,
        onRefresh: () -> Unit,
        onOpenMusicLibrary: () -> Unit
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        lateinit var popup: PopupWindow
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.solidSurfaceDrawable(d, 16f, palette.borderTopHighlight)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(context).apply {
                text = "ACTIVE SPEAKER"
                textSize = 10.5f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(context)
                setTextColor(palette.textMuted)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "Refresh"
                textSize = 11f
                typeface = SwonalFonts.interBold(context)
                setTextColor(palette.accentInk)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setOnClickListener {
                    onRefresh()
                    popup.dismiss()
                }
            }.also { PressFeedback.attachSingle(it) })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(palette.textMuted)
                setPadding(dp(6), dp(2), dp(4), dp(2))
                setOnClickListener { popup.dismiss() }
            }.also { PressFeedback.attachSingle(it) })
        }
        root.addView(header)

        val localLibrespot = SwonalRepository.findLibrespotDevice(ui.musicDevices)
        val sortedDevices = ui.musicDevices.sortedWith(
            compareByDescending<com.swonal.kiosk.model.MusicDevice> { it.id == localLibrespot?.id }
                .thenByDescending { it.isActive || it.id == ui.musicState.activeDeviceId }
                .thenBy { it.name.lowercase() }
        )

        if (sortedDevices.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "No Spotify Connect speakers discovered yet. Open Spotify or select Tonal Speakers."
                textSize = 11.5f
                setTextColor(palette.textMuted)
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })
        } else {
            sortedDevices.forEach { dev ->
                val isLocal = dev.id == localLibrespot?.id
                val needsPairing = dev.id == "tonal-gym-librespot"
                val isActive = dev.isActive || dev.id == ui.musicState.activeDeviceId
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (isActive) {
                        palette.accentMutedDrawable(d, 10f, 1.6f)
                    } else {
                        palette.elevatedPillDrawable(d, 10f)
                    }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popup.dismiss()
                        if (needsPairing) {
                            val uid = ui.activeUser?.id.orEmpty()
                            val app = context.applicationContext as? com.swonal.kiosk.SwonalApp
                            val url = app?.repository?.buildSpeakerPairingUrl(uid)
                                ?: "${ui.serverUrl.trimEnd('/')}/api/spotify/speaker-auth/start?userId=$uid"
                            InAppWebLauncher.open(context, url, "Pair Tonal Speakers", palette)
                        } else {
                            onSelectDevice(dev.id)
                        }
                    }
                }.also { PressFeedback.attachSingle(it) }

                val info = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val cleanDevName = dev.name
                    .replace(Regex("\\(?librespot\\)?", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                    .ifBlank { "Tonal Speakers" }
                info.addView(TextView(context).apply {
                    text = if (isLocal && !cleanDevName.contains("Tonal", ignoreCase = true)) {
                        "$cleanDevName (Tonal)"
                    } else {
                        cleanDevName
                    }
                    textSize = 12.5f
                    typeface = SwonalFonts.interBold(context)
                    setTextColor(if (isActive) palette.accentInk else palette.textPrimary)
                })
                info.addView(TextView(context).apply {
                    text = if (needsPairing) {
                        "Tap to Pair with Spotify →"
                    } else {
                        buildString {
                            append(dev.type)
                            if (isLocal) append(" • Default")
                        }
                    }
                    textSize = 10.5f
                    setTextColor(if (needsPairing) palette.accentInk else palette.textMuted)
                })
                row.addView(info)

                if (isActive) {
                    row.addView(TextView(context).apply {
                        text = "ACTIVE"
                        textSize = 9.5f
                        typeface = SwonalFonts.monoBold(context)
                        setTextColor(palette.accentTextColor)
                        background = palette.accentPillDrawable(d, 6f, interactive = false)
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                    })
                }
                root.addView(row)
            }
        }

        root.addView(makePopupButton(context, "Browse Playlists & Library →", palette, d) {
            popup.dismiss()
            onOpenMusicLibrary()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
                topMargin = dp(4)
            }
        })

        val widthPx = dp(310)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredHeight = root.measuredHeight

        popup = PopupWindow(root, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 18f * d
            isOutsideTouchable = true
            setTouchInterceptor { _, _ ->
                com.swonal.kiosk.util.IdleActivityTracker.noteActivity()
                false
            }
        }
        // Open directly above the speaker pill so it stays inside the viewport
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val popupY = (loc[1] - measuredHeight - dp(8)).coerceAtLeast(dp(16))
        val popupX = loc[0].coerceAtLeast(dp(16))
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX, popupY)
    }
}
