package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.model.SetRepsPolicy
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Shown when "Complete & Log Set" is tapped with COMPLETED REPS at 0 (upstream issue #16).
 * Prefilled with the step's target reps (10 in Free Lift); the first key press replaces the
 * prefill. "Log Set" only works for 1..500 reps; Cancel logs nothing.
 */
class RepCountDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 350

    private var draftText: String = ""
    private var replaceOnNextDigit: Boolean = true
    private lateinit var draftDisplay: TextView
    private lateinit var logButton: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val suggested = arguments?.getInt(ARG_SUGGESTED_REPS, SetRepsPolicy.FREE_LIFT_DEFAULT_REPS)
            ?: SetRepsPolicy.FREE_LIFT_DEFAULT_REPS
        draftText = savedInstanceState?.getString(STATE_DRAFT) ?: suggested.toString()
        replaceOnNextDigit = savedInstanceState?.getBoolean(STATE_REPLACE, true) ?: true

        val p = palette
        val d = resources.displayMetrics.density
        val root = createCardRoot(20)
        root.addView(createDialogHeader("No reps counted"))

        root.addView(TextView(requireContext()).apply {
            text = "The rep counter didn't pick up this set. How many reps did you do?"
            textSize = 13f
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        val displayBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = p.elevatedPillDrawable(d, 14f, p.borderTopHighlight, interactive = false)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        draftDisplay = TextView(requireContext()).apply {
            textSize = 36f
            typeface = SwonalFonts.monoExtraBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
        }
        displayBox.addView(draftDisplay)
        displayBox.addView(TextView(requireContext()).apply {
            text = " REPS"
            textSize = 15f
            typeface = SwonalFonts.interBold(requireContext())
            setTextColor(p.textMuted)
        })
        root.addView(displayBox)

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("Clear", "0", "⌫")
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
                val btn = makePillButton(key, mono = key.length == 1 && key[0].isDigit()) {
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

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        actions.addView(makePillButton("Cancel") { dismiss() }.apply {
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        })
        logButton = makeAccentButton("Log Set") {
            val reps = SetRepsPolicy.parseEnteredReps(draftText) ?: return@makeAccentButton
            repo.logCompletedSet(reps)
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        actions.addView(logButton)
        root.addView(actions)

        refreshDraft()
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, draftText)
        outState.putBoolean(STATE_REPLACE, replaceOnNextDigit)
    }

    private fun handleKey(key: String) {
        draftText = when (key) {
            "Clear" -> ""
            "⌫" -> draftText.dropLast(1)
            else -> when {
                replaceOnNextDigit || draftText == "0" -> key
                draftText.length < 3 -> draftText + key
                else -> draftText
            }
        }
        replaceOnNextDigit = false
        refreshDraft()
    }

    private fun refreshDraft() {
        draftDisplay.text = if (draftText.isEmpty()) "0" else draftText
        val valid = SetRepsPolicy.parseEnteredReps(draftText) != null
        logButton.isEnabled = valid
        logButton.alpha = if (valid) 1f else 0.4f
    }

    companion object {
        private const val ARG_SUGGESTED_REPS = "suggested_reps"
        private const val STATE_DRAFT = "draft"
        private const val STATE_REPLACE = "replace_on_next_digit"

        fun newInstance(suggestedReps: Int): RepCountDialog = RepCountDialog().apply {
            arguments = Bundle().apply { putInt(ARG_SUGGESTED_REPS, suggestedReps) }
        }
    }
}
