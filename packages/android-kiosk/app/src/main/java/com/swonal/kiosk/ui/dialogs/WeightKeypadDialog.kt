package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Exact target weight keypad dialog matching `KioskView.tsx`:
 * - Supports decimal `.` entry in KG mode (`0.5 kg` increments) and range hint (`5–200 LB` / `2.5–90.5 KG`)
 * - 6 unit-aware quick presets
 */
class WeightKeypadDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 350

    private var draftText: String = ""
    private lateinit var draftDisplay: TextView
    private var activeUnit: String = "LB"
    private var isKg: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val telemetry = repo.telemetryFlow.value
        activeUnit = ui.activeUnit
        isKg = Units.isKg(activeUnit)
        draftText = Units.formatDialWeightString(telemetry.targetWeightLb, activeUnit, exact = false)

        val p = palette
        val d = resources.displayMetrics.density
        val root = createCardRoot(20)
        root.addView(createDialogHeader("Set Target Weight (${activeUnit.uppercase()})"))

        // Draft display box + inline Clear button in KG mode
        val displayBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = p.elevatedPillDrawable(d, 14f, p.borderTopHighlight, interactive = false)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val centerNumWrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        draftDisplay = TextView(requireContext()).apply {
            text = draftText
            textSize = 36f
            typeface = SwonalFonts.monoExtraBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
        }
        val unitView = TextView(requireContext()).apply {
            text = " ${activeUnit.uppercase()}"
            textSize = 15f
            typeface = SwonalFonts.interBold(requireContext())
            setTextColor(p.textMuted)
        }
        centerNumWrap.addView(draftDisplay)
        centerNumWrap.addView(unitView)
        displayBox.addView(centerNumWrap)

        if (isKg) {
            displayBox.addView(makePillButton("Clear") { handleKey("Clear") }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            })
        }
        root.addView(displayBox)

        // Valid range hint
        root.addView(TextView(requireContext()).apply {
            text = if (isKg) "Valid range: 2.5 – 90.5 KG (0.5 KG steps)" else "Valid range: 5 – 200 LB"
            textSize = 11f
            typeface = SwonalFonts.monoMedium(requireContext())
            gravity = Gravity.CENTER
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        // 6 Quick Presets (3x2 grid)
        val presets = if (isKg) listOf(10, 20, 30, 45, 60, 80) else listOf(20, 45, 65, 95, 135, 185)
        for (rowIdx in 0 until 2) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            for (colIdx in 0 until 3) {
                val presetVal = presets[rowIdx * 3 + colIdx]
                val btn = makePillButton("$presetVal", mono = true) {
                    applyAndClose(presetVal.toDouble())
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (colIdx < 2) marginEnd = dp(8)
                    }
                }
                row.addView(btn)
            }
            root.addView(row)
        }

        // 3x4 Numeric Keypad (includes "." in KG mode for 0.5 kg precision)
        val bottomRow = if (isKg) listOf(".", "0", "⌫") else listOf("Clear", "0", "⌫")
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            bottomRow
        )
        keys.forEach { keyRow ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            keyRow.forEachIndexed { idx, key ->
                val isMonoKey = key.length == 1 && (key[0].isDigit() || key == ".")
                val btn = makePillButton(key, mono = isMonoKey) {
                    handleKey(key)
                }.apply {
                    textSize = if (key == "Clear") 13f else 18f
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (idx < 2) marginEnd = dp(8)
                    }
                }
                row.addView(btn)
            }
            root.addView(row)
        }

        // Submit CTA
        root.addView(
            makeAccentButton("Set Weight") {
                val parsed = draftText.toDoubleOrNull()
                if (parsed != null && parsed > 0.0) {
                    applyAndClose(parsed)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply { topMargin = dp(6) }
            }
        )

        return root
    }

    private fun handleKey(key: String) {
        draftText = when (key) {
            "Clear" -> "0"
            "⌫" -> if (draftText.length <= 1) "0" else draftText.dropLast(1)
            "." -> {
                if (!isKg || draftText.contains('.')) draftText
                else if (draftText.length < 4) "$draftText." else draftText
            }
            else -> {
                val dotIdx = draftText.indexOf('.')
                when {
                    dotIdx >= 0 && draftText.length - dotIdx > 1 -> draftText // max 1 decimal digit
                    draftText == "0" -> key
                    draftText.length < 5 -> draftText + key
                    else -> draftText
                }
            }
        }
        draftDisplay.text = draftText
    }

    private fun applyAndClose(displayValue: Double) {
        val lb = Units.parseDisplayWeightToLb(displayValue, activeUnit)
        repo.setWeight(lb)
        dismiss()
    }
}
