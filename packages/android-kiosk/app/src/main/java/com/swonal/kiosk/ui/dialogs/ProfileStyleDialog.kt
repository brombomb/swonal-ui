package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalThemeTokens
import com.swonal.kiosk.ui.PressFeedback

class ProfileStyleDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 460

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val activeUser = ui.activeUser
        val currentHex = (activeUser?.accentColor ?: "#00F0FF").uppercase()
        val d = resources.displayMetrics.density
        val p = palette

        var localUnit = ui.activeUnit
        var localDark = p.isDark
        var localDemoPip = ui.showDemoPip
        var localTelemetry = ui.telemetryDisplayMode

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Accent Color & Profiles"))

        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        body.addView(makeSectionLabel("Active Profile: ${activeUser?.name ?: "Lifter"}"))

        SwonalThemeTokens.ACCENT_SWATCHES.chunked(4).forEach { rowSwatches ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            rowSwatches.forEach { swatch ->
                val colorInt = ColorMath.parseHexColor(swatch.hex)
                val isSelected = currentHex.equals(swatch.hex, ignoreCase = true)
                val item = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        repo.updateUserAccent(swatch.hex)
                        dismiss()
                    }
                    addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                        background = p.circleSwatchDrawable(colorInt, d, isSelected)
                    })
                    addView(TextView(requireContext()).apply {
                        text = swatch.name.substringBefore(" ")
                        textSize = 10.5f
                        setTextColor(p.textMuted)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                    })
                }
                row.addView(item)
            }
            body.addView(row)
        }

        val hexRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val hexInput = makeInput("#00F0FF", currentHex).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }
        hexRow.addView(hexInput)
        hexRow.addView(makePillButton("Apply Hex") {
            val raw = hexInput.text.toString().trim()
            val normalized = if (raw.startsWith("#")) raw else "#$raw"
            if (normalized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                repo.updateUserAccent(normalized.uppercase())
                dismiss()
            }
        })
        body.addView(hexRow)

        // Theme toggle directly under Apply Hex
        val (themeRow, themeValueTv) = makeToggleRow(
            "Theme",
            if (localDark) "Dark" else "Light",
            d
        )
        themeRow.setOnClickListener {
            localDark = !localDark
            themeValueTv.text = if (localDark) "Dark" else "Light"
            repo.toggleTheme()
            dismiss()
        }
        body.addView(themeRow)

        // Display & Units Preferences
        body.addView(makeDivider())
        body.addView(makeSectionLabel("Display & Units"))

        val (unitRow, unitValueTv) = makeToggleRow(
            "Units",
            localUnit,
            d
        )
        unitRow.setOnClickListener {
            localUnit = if (localUnit.equals("KG", ignoreCase = true)) "LB" else "KG"
            unitValueTv.text = localUnit
            repo.toggleUnit()
        }
        body.addView(unitRow)

        val (demoRow, demoValueTv) = makeToggleRow(
            "Form Demo Video",
            if (localDemoPip) "Shown" else "Hidden",
            d
        )
        demoRow.setOnClickListener {
            localDemoPip = !localDemoPip
            demoValueTv.text = if (localDemoPip) "Shown" else "Hidden"
            repo.setShowDemoPip(localDemoPip)
        }
        body.addView(demoRow)

        val (vbtRow, vbtValueTv) = makeToggleRow(
            "VBT & Symmetry",
            localTelemetry.label,
            d
        )
        vbtRow.setOnClickListener {
            localTelemetry = localTelemetry.next()
            vbtValueTv.text = localTelemetry.label
            repo.cycleTelemetryDisplayMode()
        }
        body.addView(vbtRow)

        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(body)
        })
        return root
    }

    private fun makeToggleRow(
        label: String,
        initialValue: String,
        d: Float
    ): Pair<LinearLayout, TextView> {
        val p = palette
        val valueTv = TextView(requireContext()).apply {
            text = initialValue
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = p.elevatedPillDrawable(d, 10f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 12f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueTv)
        }.also { PressFeedback.attachSingle(it) }
        return row to valueTv
    }
}
