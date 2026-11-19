package com.swonal.kiosk.ui.dialogs

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.model.PrHit
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Post-Workout Summary Modal matching `PostWorkoutSummaryModal.tsx`:
 * - Survives recreation cleanly via `newInstance()` JSON Bundle arguments
 * - Bounded `MaxHeightScrollView` so footer buttons never clip off-screen
 * - Includes CSV & JSON session export buttons alongside Done CTA
 */
class PostWorkoutSummaryDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 560

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val session = arguments?.getString(ARG_SESSION_JSON)?.let {
            runCatching { SwonalJson.decodeFromString<WorkoutSession>(it) }.getOrNull()
        }
        val prHits = arguments?.getString(ARG_PRS_JSON)?.let {
            runCatching { SwonalJson.decodeFromString<List<PrHit>>(it) }.getOrNull()
        }.orEmpty()

        val ui = repo.uiStateFlow.value
        val unit = ui.activeUnit
        val p = palette
        val d = resources.displayMetrics.density

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Workout Complete"))

        if (session == null) {
            root.post { if (isAdded) dismissAllowingStateLoss() }
            return root
        }

        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(requireContext()).apply {
            text = session.template?.name ?: "Free Lift Session"
            textSize = 18f
            typeface = SwonalFonts.interExtraBold(requireContext())
            setTextColor(p.accentInk)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        // Compute KPIs (preferring server-computed volumeLb when present)
        val totalSets = session.setLogs.size
        val totalReps = session.setLogs.sumOf { it.repsCompleted }
        val totalVolLb = if (session.totalVolume > 0) {
            session.totalVolume
        } else {
            session.setLogs.sumOf { it.volumeLb ?: (it.weightLb * it.repsCompleted) }
        }
        val durationSec = computeDurationSec(session.startedAt, session.completedAt)
        val rpeValues = session.setLogs.mapNotNull { it.rpe }
        val avgRpe = if (rpeValues.isNotEmpty()) String.format(Locale.US, "%.1f", rpeValues.average()) else "—"

        val kpis = listOf(
            "TOTAL VOLUME" to "${Units.formatCompactWeight(totalVolLb, unit, exact = true)} $unit",
            "SETS & REPS" to "$totalSets sets • $totalReps reps",
            "DURATION" to Units.formatDurationHuman(durationSec),
            "AVG RPE" to avgRpe
        )
        kpis.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            pair.forEachIndexed { idx, (label, value) ->
                row.addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = p.elevatedPillDrawable(d, 12f, interactive = false)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx == 0) marginEnd = dp(8)
                    }
                    addView(TextView(requireContext()).apply {
                        text = label
                        textSize = 10f
                        typeface = SwonalFonts.interExtraBold(requireContext())
                        setTextColor(p.textMuted)
                    })
                    addView(TextView(requireContext()).apply {
                        text = value
                        textSize = 18f
                        typeface = SwonalFonts.monoExtraBold(requireContext())
                        fontFeatureSettings = "tnum"
                        setTextColor(p.textPrimary)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                    })
                })
            }
            content.addView(row)
        }

        if (prHits.isNotEmpty()) {
            val prCard = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = p.accentMutedDrawable(d, 14f, 1.5f, interactive = false)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(10)
                }
            }
            prCard.addView(TextView(requireContext()).apply {
                text = "PERSONAL RECORDS BROKEN (${prHits.size})"
                textSize = 11.5f
                typeface = SwonalFonts.interExtraBold(requireContext())
                setTextColor(p.accentInk)
            })
            prHits.forEach { hit ->
                prCard.addView(TextView(requireContext()).apply {
                    text = "${hit.exerciseName} — Est 1RM ${Units.formatCompactWeight(hit.oneRepMaxEst, unit)} $unit (${Units.formatCompactWeight(hit.weightLb, unit)} × ${hit.reps})"
                    textSize = 12.5f
                    typeface = SwonalFonts.monoBold(requireContext())
                    fontFeatureSettings = "tnum"
                    setTextColor(p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(5) }
                })
            }
            content.addView(prCard)
        }

        val muscleVols = session.setLogs
            .groupBy { it.exercise?.muscleGroup ?: "Other" }
            .mapValues { (_, logs) ->
                logs.size to logs.sumOf { it.volumeLb ?: (it.weightLb * it.repsCompleted) }
            }
            .entries
            .sortedByDescending { it.value.second }

        if (muscleVols.isNotEmpty()) {
            content.addView(makeSectionLabel("Muscle Group Breakdown"))
            val grandVol = max(1.0, muscleVols.sumOf { it.value.second })
            muscleVols.forEach { (muscle, pair) ->
                val (setsCount, vol) = pair
                val pct = ((vol / grandVol) * 100.0).roundToInt().coerceIn(4, 100)
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                val labelRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                labelRow.addView(TextView(requireContext()).apply {
                    text = "$muscle ($setsCount sets)"
                    textSize = 12f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                labelRow.addView(TextView(requireContext()).apply {
                    text = "${Units.formatCompactWeight(vol, unit)} $unit ($pct%)"
                    textSize = 11.5f
                    typeface = SwonalFonts.monoBold(requireContext())
                    fontFeatureSettings = "tnum"
                    setTextColor(p.textMuted)
                })
                row.addView(labelRow)

                val track = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = p.elevatedPillDrawable(d, 4f, interactive = false)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(8)
                    ).apply { topMargin = dp(4) }
                }
                track.addView(View(requireContext()).apply {
                    background = GradientDrawable().apply {
                        setColor(if (p.isDark) p.accentPrimary else p.accentInk)
                        cornerRadius = dpf(4f)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.toFloat())
                })
                if (pct < 100) {
                    track.addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - pct).toFloat())
                    })
                }
                row.addView(track)
                content.addView(row)
            }
        }

        root.addView(wrapScrollable(content, 460))

        // Footer: CSV & JSON Export + Done CTA
        val baseServer = ui.serverUrl.trimEnd('/')
        val uidQuery = "?userId=${session.userId}"
        val footerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        footerRow.addView(makePillButton("CSV") {
            openSafeHttpUrl("$baseServer/api/export/sessions.csv$uidQuery")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply {
                marginEnd = dp(8)
            }
        })
        footerRow.addView(makePillButton("JSON") {
            openSafeHttpUrl("$baseServer/api/export/sessions.json$uidQuery")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply {
                marginEnd = dp(8)
            }
        })
        footerRow.addView(makeAccentButton("Done — Return to Ready") {
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        })
        root.addView(footerRow)

        return root
    }

    private fun computeDurationSec(startedAt: String, completedAt: String?): Long {
        if (startedAt.isBlank() || completedAt.isNullOrBlank()) return 0L
        fun parseIsoMs(iso: String): Long? {
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
            for (pat in patterns) {
                val ms = runCatching {
                    SimpleDateFormat(pat, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(iso)?.time
                }.getOrNull()
                if (ms != null) return ms
            }
            return null
        }
        val s = parseIsoMs(startedAt) ?: return 0L
        val e = parseIsoMs(completedAt) ?: return 0L
        return max(0L, (e - s) / 1000L)
    }

    companion object {
        private const val ARG_SESSION_JSON = "session_json"
        private const val ARG_PRS_JSON = "prs_json"

        fun newInstance(session: WorkoutSession, prHits: List<PrHit>): PostWorkoutSummaryDialog {
            return PostWorkoutSummaryDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_JSON, SwonalJson.encodeToString(session))
                    putString(ARG_PRS_JSON, SwonalJson.encodeToString(prHits))
                }
            }
        }
    }
}
