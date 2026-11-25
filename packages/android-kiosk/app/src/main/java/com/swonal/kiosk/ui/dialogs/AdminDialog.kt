package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Full-page native Kiosk Hardware Admin & Exercise Catalog screen (replaces GeckoView /admin on-device).
 * Mirrors the spacious athletic layout of AdminView.tsx while rendering 100% natively.
 */
class AdminDialog : BaseSwonalDialog() {

    override val isFullScreen: Boolean = true

    private var selectedMuscle: String = "All"
    private lateinit var filterRow: LinearLayout
    private lateinit var listContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val root = createFullScreenRoot()

        root.addView(createFullScreenHeader(
            "Hardware Admin & Calibration",
            "Motor bridge controls, strain-gauge zeroing, and exercise catalog"
        ))

        val (scroll, body) = createFullScreenScrollBody()
        root.addView(scroll)

        // 1. Hardware & Calibration Card
        val hwCard = createSurfaceCard()
        hwCard.addView(makeCardHeading("Tonal Motor Bridge & Calibration"))

        val hwStatus = if (ui.daemonConnected) {
            if (ui.isMockMode) "Connected (Mock Hardware Mode)" else "Connected (Physical Tonal CDC 30Hz)"
        } else {
            "Disconnected"
        }
        hwCard.addView(TextView(requireContext()).apply {
            text = "Daemon Status: $hwStatus\nEndpoint:      ${app.prefs.localDaemonUrl}"
            textSize = 14f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.textPrimary)
            setLineSpacing(dpf(4f), 1f)
            background = p.elevatedPillDrawable(d, 16f, interactive = false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        })

        val actionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        actionRow.addView(makeAccentButton("Zero Strain Gauges") {
            repo.zeroCalibration()
            Toast.makeText(requireContext(), "Zero-offset calibration sent to tonal-daemon", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) }
        })
        actionRow.addView(makePillButton("Hardware Diagnostics") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("diagnostics") == null) {
                DiagnosticsDialog().show(fm, "diagnostics")
            }
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) }
        })
        actionRow.addView(makePillButton("Bug Report") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("bug_report") == null) {
                BugReportDialog().show(fm, "bug_report")
            }
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        })
        hwCard.addView(actionRow)
        body.addView(hwCard)

        // 2. Exercise Catalog Card
        val catalogCard = createSurfaceCard()
        catalogCard.addView(makeCardHeading(
            "Exercise Catalog (${ui.exercises.size})",
            "Recommended arm height (H), rotation (R), and angle (A) presets"
        ))

        filterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        catalogCard.addView(filterRow)

        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        catalogCard.addView(listContainer)
        body.addView(catalogCard)

        renderFilters()
        renderExercises()
        return root
    }

    private fun renderFilters() {
        val p = palette
        val d = resources.displayMetrics.density
        val muscles = listOf("All") + repo.uiStateFlow.value.exercises.map { it.muscleGroup }.distinct().sorted()
        filterRow.removeAllViews()
        muscles.take(8).forEachIndexed { idx, m ->
            val active = m == selectedMuscle
            filterRow.addView(makePillButton(m, active = active) {
                selectedMuscle = m
                renderFilters()
                renderExercises()
            }.apply {
                textSize = 12.5f
                background = if (active) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
                setTextColor(if (active) p.accentTextColor else p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (idx < muscles.take(8).lastIndex) marginEnd = dp(6)
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
                background = p.elevatedPillDrawable(d, 14f, interactive = false)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val left = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = ex.name
                    textSize = 15.5f
                    typeface = SwonalFonts.interExtraBold(requireContext())
                    setTextColor(p.textPrimary)
                })
                addView(TextView(requireContext()).apply {
                    text = "${ex.muscleGroup}  •  ${ex.accessory}${if (ex.unilateral) "  •  Unilateral" else ""}"
                    textSize = 13f
                    setTextColor(p.textMuted)
                })
            }
            row.addView(left)
            row.addView(TextView(requireContext()).apply {
                text = "H${ex.recommendedHeight}  R${ex.recommendedRotation}  A${ex.recommendedAngle}"
                textSize = 14f
                typeface = SwonalFonts.monoBold(requireContext())
                setTextColor(p.accentInk)
                background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            })
            listContainer.addView(row)
        }
    }
}
