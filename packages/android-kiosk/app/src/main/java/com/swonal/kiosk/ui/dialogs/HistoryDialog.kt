package com.swonal.kiosk.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-page native Kiosk History & Personal Records screen (replaces GeckoView /history on-device).
 * Matches the spacious athletic layout of HistoryView.tsx while rendering 100% natively.
 */
class HistoryDialog : BaseSwonalDialog() {

    override val isFullScreen: Boolean = true

    private var selectedUserId: String = ""
    private var sessions: List<WorkoutSession> = emptyList()
    private var prs: List<PersonalRecord> = emptyList()
    private var expandedSessionId: String? = null

    private lateinit var headerContainer: LinearLayout
    private lateinit var bodyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        selectedUserId = ui.activeUser?.id ?: ui.users.firstOrNull()?.id.orEmpty()

        val root = createFullScreenRoot()
        headerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(headerContainer)

        val (scroll, body) = createFullScreenScrollBody()
        bodyContainer = body
        root.addView(scroll)

        renderHeader()
        loadUserData()
        return root
    }

    private fun renderHeader() {
        val ui = repo.uiStateFlow.value
        val activeUser = ui.users.firstOrNull { it.id == selectedUserId }
        val p = palette
        val d = resources.displayMetrics.density

        val profileRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            ui.users.forEach { u ->
                val isSelected = u.id == selectedUserId
                val userAccent = runCatching { Color.parseColor(u.accentColor) }.getOrDefault(p.accentPrimary)
                val pill = TextView(requireContext()).apply {
                    text = u.name
                    textSize = 14f
                    typeface = SwonalFonts.interBold(requireContext())
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(9), dp(18), dp(9))
                    if (isSelected) {
                        val uPalette = SwonalThemeTokens.resolve(ui.activeThemeMode, u.accentColor, !p.isDark)
                        background = GradientDrawable().apply {
                            setColor(userAccent)
                            cornerRadius = 999f
                        }
                        setTextColor(uPalette.accentTextColor)
                    } else {
                        background = p.elevatedPillDrawable(d, 999f)
                        setTextColor(p.textPrimary)
                        alpha = 0.8f
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (selectedUserId != u.id) {
                            selectedUserId = u.id
                            expandedSessionId = null
                            renderHeader()
                            loadUserData()
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(42)
                    ).apply { marginStart = dp(8) }
                }
                addView(pill)
            }
        }

        val title = activeUser?.let { "${it.name}'s History & PRs" } ?: "History & Personal Records"
        headerContainer.removeAllViews()
        headerContainer.addView(createFullScreenHeader(title, rightContent = profileRow))
    }

    private fun loadUserData() {
        val uid = selectedUserId
        if (uid.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            sessions = runCatching { repo.localDb.listSessions(uid, limit = 50) }.getOrDefault(emptyList())
            prs = runCatching { repo.localDb.listPrs(uid) }.getOrDefault(emptyList())
            if (!isAdded) return@launch
            renderBody()
        }
    }

    private fun renderBody() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val user = ui.users.firstOrNull { it.id == selectedUserId }
        val unit = user?.weightUnit ?: ui.activeUnit
        val exMap = ui.exercises.associateBy { it.id }

        bodyContainer.removeAllViews()

        // 1. Progression Overview & KPI Card
        val overviewCard = createSurfaceCard()
        overviewCard.addView(makeCardHeading("Strength Progression", "All-time training volume & milestones"))

        val totalVolLb = sessions.sumOf { it.totalVolume }
        val totalSets = sessions.sumOf { it.setLogs.size }
        val kpiRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val kpis = listOf(
            "WORKOUTS" to sessions.size.toString(),
            "TOTAL VOLUME" to "${Units.formatCompactWeight(totalVolLb, unit)} $unit",
            "TOTAL SETS" to totalSets.toString(),
            "PERSONAL RECORDS" to prs.size.toString()
        )
        kpis.forEachIndexed { idx, (label, value) ->
            kpiRow.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = p.elevatedPillDrawable(d, 16f, interactive = false)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < kpis.lastIndex) marginEnd = dp(10)
                }
                addView(TextView(requireContext()).apply {
                    text = label
                    textSize = 11f
                    letterSpacing = 0.08f
                    typeface = SwonalFonts.interExtraBold(requireContext())
                    setTextColor(p.textMuted)
                })
                addView(TextView(requireContext()).apply {
                    text = value
                    textSize = 22f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            })
        }
        overviewCard.addView(kpiRow)

        // Volume bar strip for recent sessions (up to 10)
        val recentForChart = sessions.take(10).reversed()
        if (recentForChart.isNotEmpty()) {
            val maxVol = recentForChart.maxOf { it.totalVolume }.coerceAtLeast(1.0)
            overviewCard.addView(TextView(requireContext()).apply {
                text = "RECENT SESSION VOLUME ($unit)"
                textSize = 11f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(requireContext())
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            })
            val chartRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                background = p.elevatedPillDrawable(d, 16f, interactive = false)
                setPadding(dp(14), dp(16), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(140)
                )
            }
            recentForChart.forEachIndexed { idx, s ->
                val frac = (s.totalVolume / maxVol).toFloat().coerceIn(0.08f, 1f)
                val barCol = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < recentForChart.lastIndex) marginEnd = dp(8)
                    }
                    addView(View(requireContext()).apply {
                        background = GradientDrawable().apply {
                            setColor(p.accentPrimary)
                            cornerRadius = dpf(8f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (dp(82) * frac).toInt().coerceAtLeast(dp(8))
                        )
                    })
                    addView(TextView(requireContext()).apply {
                        text = s.startedAt.substring(5, 10)
                        textSize = 10.5f
                        typeface = SwonalFonts.monoBold(requireContext())
                        setTextColor(p.textMuted)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) }
                    })
                }
                chartRow.addView(barCol)
            }
            overviewCard.addView(chartRow)
        }
        bodyContainer.addView(overviewCard)

        // 2. Personal Records Card (2-column grid matching HistoryView.tsx)
        val prCard = createSurfaceCard()
        prCard.addView(makeCardHeading("Personal Records (${prs.size})", "Estimated 1-Rep Max & Heaviest Set"))

        if (prs.isEmpty()) {
            prCard.addView(TextView(requireContext()).apply {
                text = "Complete sets to start tracking Personal Records for ${user?.name ?: "this lifter"}."
                textSize = 14f
                setTextColor(p.textMuted)
                background = p.elevatedPillDrawable(d, 16f, interactive = false)
                setPadding(dp(18), dp(20), dp(18), dp(20))
            })
        } else {
            val sortedPrs = prs.sortedByDescending { it.oneRepMaxEst }
            var i = 0
            while (i < sortedPrs.size) {
                val pairRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                for (col in 0..1) {
                    val idx = i + col
                    if (idx < sortedPrs.size) {
                        val pr = sortedPrs[idx]
                        val exName = pr.exercise?.name ?: exMap[pr.exerciseId]?.name ?: pr.exerciseId
                        val muscle = pr.exercise?.muscleGroup ?: exMap[pr.exerciseId]?.muscleGroup.orEmpty()
                        val est1rm = Units.formatCompactWeight(pr.oneRepMaxEst, unit)
                        val maxW = Units.formatCompactWeight(pr.maxWeightLb, unit)
                        pairRow.addView(LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = p.elevatedPillDrawable(d, 16f, interactive = false)
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (col == 0) marginEnd = dp(10)
                            }
                            addView(LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                addView(TextView(requireContext()).apply {
                                    text = exName
                                    textSize = 15f
                                    typeface = SwonalFonts.interExtraBold(requireContext())
                                    setTextColor(p.textPrimary)
                                })
                                if (muscle.isNotBlank()) {
                                    addView(TextView(requireContext()).apply {
                                        text = muscle
                                        textSize = 12.5f
                                        setTextColor(p.textMuted)
                                    })
                                }
                            })
                            addView(LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.END
                                addView(TextView(requireContext()).apply {
                                    text = "$est1rm $unit 1RM"
                                    textSize = 15f
                                    typeface = SwonalFonts.monoBold(requireContext())
                                    setTextColor(p.accentInk)
                                })
                                addView(TextView(requireContext()).apply {
                                    text = "Best: $maxW $unit"
                                    textSize = 12f
                                    typeface = SwonalFonts.monoBold(requireContext())
                                    setTextColor(p.textMuted)
                                })
                            })
                        })
                    } else {
                        pairRow.addView(View(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    }
                }
                prCard.addView(pairRow)
                i += 2
            }
        }
        bodyContainer.addView(prCard)

        // 3. Recent Sessions Card
        val sessCard = createSurfaceCard()
        sessCard.addView(makeCardHeading("Recent Sessions (${sessions.size})", "Tap any workout to expand logged sets"))

        if (sessions.isEmpty()) {
            sessCard.addView(TextView(requireContext()).apply {
                text = "No workouts logged yet for ${user?.name ?: "this lifter"}. Complete a set on Tonal to start tracking!"
                textSize = 14f
                setTextColor(p.textMuted)
                background = p.elevatedPillDrawable(d, 16f, interactive = false)
                setPadding(dp(18), dp(20), dp(18), dp(20))
            })
        } else {
            sessions.forEach { sess ->
                val isExpanded = expandedSessionId == sess.id
                val rowCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = p.elevatedPillDrawable(d, 16f)
                    setPadding(dp(18), dp(15), dp(18), dp(15))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        expandedSessionId = if (isExpanded) null else sess.id
                        renderBody()
                    }
                }
                val topRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dateLabel = sess.startedAt.replace("T", " ").take(16)
                val title = sess.template?.name?.takeIf { it.isNotBlank() } ?: "Free Lift Session"
                topRow.addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(requireContext()).apply {
                        text = title
                        textSize = 16f
                        typeface = SwonalFonts.interExtraBold(requireContext())
                        setTextColor(p.textPrimary)
                    })
                    addView(TextView(requireContext()).apply {
                        text = "$dateLabel  •  ${sess.setLogs.size} sets"
                        textSize = 13f
                        setTextColor(p.textMuted)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                    })
                })
                topRow.addView(TextView(requireContext()).apply {
                    text = "${Units.formatCompactWeight(sess.totalVolume, unit)} $unit"
                    textSize = 17f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                })
                rowCard.addView(topRow)

                if (isExpanded && sess.setLogs.isNotEmpty()) {
                    rowCard.addView(makeDivider())
                    sess.setLogs.forEach { sl ->
                        val exName = sl.exercise?.name ?: exMap[sl.exerciseId]?.name ?: "Exercise"
                        val w = Units.formatCompactWeight(sl.weightLb, unit)
                        rowCard.addView(TextView(requireContext()).apply {
                            text = "Set ${sl.setNumber}  •  $exName  —  $w $unit × ${sl.repsCompleted} (${sl.mode.uppercase(Locale.US)})"
                            textSize = 13.5f
                            typeface = SwonalFonts.monoBold(requireContext())
                            setTextColor(p.textPrimary)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(6) }
                        })
                    }
                }
                sessCard.addView(rowCard)
            }
        }
        bodyContainer.addView(sessCard)
    }
}
