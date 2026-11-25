package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Native Kiosk Admin & Exercise Catalog dialog (replaces GeckoView /admin on-device).
 */
class AdminDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 640

    private var selectedMuscle: String = "All"
    private lateinit var filterRow: LinearLayout
    private lateinit var listContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = createCardRoot(20)
        root.addView(createDialogHeader("Admin & Exercise Catalog"))

        val actionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        actionRow.addView(makePillButton("Calibrate Cable Zero") {
            repo.zeroCalibration()
            Toast.makeText(requireContext(), "Zero-offset calibration sent to tonal-daemon", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        actionRow.addView(makePillButton("Hardware Diagnostics") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("diagnostics") == null) {
                DiagnosticsDialog().show(fm, "diagnostics")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        actionRow.addView(makePillButton("Bug Report") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("bug_report") == null) {
                BugReportDialog().show(fm, "bug_report")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        })
        root.addView(actionRow)

        filterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(filterRow)

        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(450)
            )
            addView(listContainer)
        })

        renderFilters()
        renderExercises()
        return root
    }

    private fun renderFilters() {
        val p = palette
        val d = resources.displayMetrics.density
        val muscles = listOf("All") + repo.uiStateFlow.value.exercises.map { it.muscleGroup }.distinct().sorted()
        filterRow.removeAllViews()
        muscles.take(7).forEachIndexed { idx, m ->
            val active = m == selectedMuscle
            filterRow.addView(makePillButton(m, active = active) {
                selectedMuscle = m
                renderFilters()
                renderExercises()
            }.apply {
                textSize = 10.5f
                background = if (active) p.accentPillDrawable(d, 8f) else p.elevatedPillDrawable(d, 8f)
                setTextColor(if (active) p.accentTextColor else p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < muscles.take(7).lastIndex) marginEnd = dp(4)
                }
            })
        }
    }

    private fun renderExercises() {
        val p = palette
        val d = resources.displayMetrics.density
        val all = repo.uiStateFlow.value.exercises
        val filtered = if (selectedMuscle == "All") all else all.filter { it.muscleGroup == selectedMuscle }

        listContainer.removeAllViews()
        filtered.forEach { ex ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = p.elevatedPillDrawable(d, 10f, interactive = false)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val left = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = ex.name
                    textSize = 13f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                })
                addView(TextView(requireContext()).apply {
                    text = "${ex.muscleGroup} • ${ex.accessory}${if (ex.unilateral) " • Unilateral" else ""}"
                    textSize = 11f
                    setTextColor(p.textMuted)
                })
            }
            row.addView(left)
            row.addView(TextView(requireContext()).apply {
                text = "H${ex.recommendedHeight} R${ex.recommendedRotation} A${ex.recommendedAngle}"
                textSize = 11.5f
                typeface = SwonalFonts.monoBold(requireContext())
                setTextColor(p.accentInk)
            })
            listContainer.addView(row)
        }
    }
}
