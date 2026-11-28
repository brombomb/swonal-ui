package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Shared exact-number keypad (draft display, range hint, quick presets, 3x4 keypad, submit CTA).
 * Subclasses: [WeightKeypadDialog] (target weight) and [AutoCompleteTimerDialog] (timer seconds).
 */
abstract class NumericKeypadDialog : BaseSwonalDialog() {

    protected data class Preset(val label: String, val value: Double)

    override val maxDialogWidthDp: Int = 350

    private var draftText: String = ""
    private lateinit var draftDisplay: TextView

    protected abstract val headerTitle: String
    protected abstract val unitLabel: String
    protected abstract val rangeHint: String
    protected abstract val submitLabel: String
    protected abstract val presets: List<Preset>
    protected open val presetColumns: Int = 3

    /** Allows one decimal digit (adds a "." key; Clear moves next to the display). */
    protected open val allowDecimal: Boolean = false
    protected open val maxDigits: Int = 5

    /** Called before the view is built; subclasses read repo state here. */
    protected open fun prepare() = Unit
    protected abstract fun initialDraft(): String

    /** Highlights the preset matching the current value. */
    protected open fun isPresetActive(preset: Preset): Boolean = false

    /** A preset was tapped or the draft submitted. Return true to close the dialog. */
    protected abstract fun onValueChosen(value: Double): Boolean

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        prepare()
        draftText = initialDraft()

        val p = palette
        val d = resources.displayMetrics.density
        val root = createCardRoot(20)
        root.addView(createDialogHeader(headerTitle))

        // Draft display box (+ inline Clear button when "." takes the bottom-left key)
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
            text = " $unitLabel"
            textSize = 15f
            typeface = SwonalFonts.interBold(requireContext())
            setTextColor(p.textMuted)
        }
        centerNumWrap.addView(draftDisplay)
        centerNumWrap.addView(unitView)
        displayBox.addView(centerNumWrap)

        if (allowDecimal) {
            displayBox.addView(makePillButton("Clear") { handleKey("Clear") }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            })
        }
        root.addView(displayBox)

        // Valid range hint
        root.addView(TextView(requireContext()).apply {
            text = rangeHint
            textSize = 11f
            typeface = SwonalFonts.monoMedium(requireContext())
            gravity = Gravity.CENTER
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        // Quick presets grid
        val cols = presetColumns.coerceAtLeast(1)
        presets.chunked(cols).forEach { rowPresets ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            rowPresets.forEachIndexed { colIdx, preset ->
                val isNumeric = preset.label.all { it.isDigit() || it == '.' }
                val btn = makePillButton(preset.label, active = isPresetActive(preset), mono = isNumeric) {
                    if (onValueChosen(preset.value)) dismiss()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (colIdx < cols - 1) marginEnd = dp(8)
                    }
                }
                row.addView(btn)
            }
            // Keep a short last row's buttons the same width as the full rows above.
            repeat(cols - rowPresets.size) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                })
            }
            root.addView(row)
        }

        // 3x4 Numeric Keypad
        val bottomRow = if (allowDecimal) listOf(".", "0", "⌫") else listOf("Clear", "0", "⌫")
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
            makeAccentButton(submitLabel) {
                val parsed = draftText.toDoubleOrNull()
                if (parsed != null && onValueChosen(parsed)) {
                    dismiss()
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
                if (!allowDecimal || draftText.contains('.')) draftText
                else if (draftText.length < maxDigits - 1) "$draftText." else draftText
            }
            else -> {
                val dotIdx = draftText.indexOf('.')
                when {
                    dotIdx >= 0 && draftText.length - dotIdx > 1 -> draftText // max 1 decimal digit
                    draftText == "0" -> key
                    draftText.length < maxDigits -> draftText + key
                    else -> draftText
                }
            }
        }
        draftDisplay.text = draftText
    }
}
