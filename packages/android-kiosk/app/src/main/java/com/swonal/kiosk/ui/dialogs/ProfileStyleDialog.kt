package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.theme.SwonalThemeTokens

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

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Accent Color & Profiles"))
        root.addView(makeSectionLabel("Active Profile: ${activeUser?.name ?: "Lifter"}"))

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
            root.addView(row)
        }

        val hexRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
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
        root.addView(hexRow)

        root.addView(makeDivider())
        root.addView(makeSectionLabel("Add Household Lifter"))

        val nameInput = makeInput("Lifter name").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(nameInput)

        val newColorRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val newColorInput = makeInput("#CCFF00", "#CCFF00").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }
        newColorRow.addView(newColorInput)
        newColorRow.addView(makeAccentButton("+ Add Profile") {
            val name = nameInput.text.toString().trim()
            val hex = newColorInput.text.toString().trim().let {
                if (it.matches(Regex("^#[0-9A-Fa-f]{6}$"))) it.uppercase() else "#CCFF00"
            }
            if (name.isNotEmpty()) {
                repo.createHouseholdUser(name, hex)
                dismiss()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
        })
        root.addView(newColorRow)

        return root
    }
}
