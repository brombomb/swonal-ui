package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.model.PersonalRecord
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.WorkoutSession
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Native Kiosk History & Personal Records screen (replaces GeckoView /history on-device).
 * Web /history remains available on optional server-app for always-on viewing when Tonal is off.
 */
class HistoryDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 640

    private var selectedUserId: String = ""
    private var selectedTab: Int = 0 // 0 = Sessions, 1 = PRs
    private var sessions: List<WorkoutSession> = emptyList()
    private var prs: List<PersonalRecord> = emptyList()
    private var expandedSessionId: String? = null

    private lateinit var profileBar: LinearLayout
    private lateinit var kpiRow: LinearLayout
    private lateinit var tabSessionsBtn: TextView
    private lateinit var tabPrsBtn: TextView
    private lateinit var contentList: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        selectedUserId = ui.activeUser?.id ?: ui.users.firstOrNull()?.id.orEmpty()

        val root = createCardRoot(20)
        root.addView(createDialogHeader("History & Personal Records"))

        profileBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(profileBar)

        kpiRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(kpiRow)

        val tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        tabSessionsBtn = makePillButton("Recent Sessions", active = true) {
            selectedTab = 0
            renderTabsAndBody()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) }
        }
        tabPrsBtn = makePillButton("Personal Records", active = false) {
            selectedTab = 1
            renderTabsAndBody()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        tabRow.addView(tabSessionsBtn)
        tabRow.addView(tabPrsBtn)
        root.addView(tabRow)

        contentList = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(460)
            )
            addView(contentList)
        })

        renderProfileBar()
        loadUserData()
        return root
    }

    private fun renderProfileBar() {
        val ui = repo.uiStateFlow.value
        profileBar.removeAllViews()
        ui.users.forEach { u ->
            val isSelected = u.id == selectedUserId
            profileBar.addView(makePillButton(u.name, active = isSelected) {
                selectedUserId = u.id
                expandedSessionId = null
                renderProfileBar()
                loadUserData()
            }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply { marginEnd = dp(6) }
            })
        }
    }

    private fun loadUserData() {
        val uid = selectedUserId
        if (uid.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            sessions = runCatching { repo.localDb.listSessions(uid, limit = 50) }.getOrDefault(emptyList())
            prs = runCatching { repo.localDb.listPrs(uid) }.getOrDefault(emptyList())
            if (!isAdded) return@launch
            renderKpis()
            renderTabsAndBody()
        }
    }

    private fun renderKpis() {
        val p = palette
        val d = resources.displayMetrics.density
        val user = repo.uiStateFlow.value.users.firstOrNull { it.id == selectedUserId }
        val unit = user?.weightUnit ?: repo.uiStateFlow.value.activeUnit
        val totalVolLb = sessions.sumOf { it.totalVolume }

        kpiRow.removeAllViews()
        val kpis = listOf(
            "WORKOUTS" to sessions.size.toString(),
            "TOTAL VOLUME" to "${Units.formatCompactWeight(totalVolLb, unit)} $unit",
            "PERSONAL RECORDS" to prs.size.toString()
        )
        kpis.forEachIndexed { idx, (label, value) ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = p.elevatedPillDrawable(d, 12f, interactive = false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < kpis.lastIndex) marginEnd = dp(8)
                }
                addView(TextView(requireContext()).apply {
                    text = label
                    textSize = 9.5f
                    letterSpacing = 0.08f
                    typeface = SwonalFonts.interExtraBold(requireContext())
                    setTextColor(p.textMuted)
                })
                addView(TextView(requireContext()).apply {
                    text = value
                    textSize = 16f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            }
            kpiRow.addView(card)
        }
    }

    private fun renderTabsAndBody() {
        val p = palette
        val d = resources.displayMetrics.density
        val user = repo.uiStateFlow.value.users.firstOrNull { it.id == selectedUserId }
        val unit = user?.weightUnit ?: repo.uiStateFlow.value.activeUnit
        val exMap = repo.uiStateFlow.value.exercises.associateBy { it.id }

        tabSessionsBtn.background = if (selectedTab == 0) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
        tabSessionsBtn.setTextColor(if (selectedTab == 0) p.accentTextColor else p.textPrimary)
        tabPrsBtn.background = if (selectedTab == 1) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
        tabPrsBtn.setTextColor(if (selectedTab == 1) p.accentTextColor else p.textPrimary)

        contentList.removeAllViews()

        if (selectedTab == 0) {
            if (sessions.isEmpty()) {
                contentList.addView(TextView(requireContext()).apply {
                    text = "No workouts logged yet for ${user?.name ?: "this lifter"}. Complete a set on Tonal to start tracking!"
                    textSize = 13f
                    setTextColor(p.textMuted)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(36), dp(16), dp(36))
                })
                return
            }
            sessions.forEach { sess ->
                val isExpanded = expandedSessionId == sess.id
                val card = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = p.elevatedPillDrawable(d, 12f)
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        expandedSessionId = if (isExpanded) null else sess.id
                        renderTabsAndBody()
                    }
                }
                val topRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dateLabel = sess.startedAt.replace("T", " ").take(16)
                val title = sess.template?.name?.takeIf { it.isNotBlank() } ?: "Free Lift Session"
                val leftCol = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(requireContext()).apply {
                        text = title
                        textSize = 13.5f
                        typeface = SwonalFonts.interBold(requireContext())
                        setTextColor(p.textPrimary)
                    })
                    addView(TextView(requireContext()).apply {
                        text = "$dateLabel • ${sess.setLogs.size} sets"
                        textSize = 11f
                        setTextColor(p.textMuted)
                    })
                }
                topRow.addView(leftCol)
                topRow.addView(TextView(requireContext()).apply {
                    text = "${Units.formatCompactWeight(sess.totalVolume, unit)} $unit"
                    textSize = 13f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                })
                card.addView(topRow)

                if (isExpanded && sess.setLogs.isNotEmpty()) {
                    card.addView(makeDivider())
                    sess.setLogs.forEach { sl ->
                        val exName = sl.exercise?.name ?: exMap[sl.exerciseId]?.name ?: "Exercise"
                        val w = Units.formatCompactWeight(sl.weightLb, unit)
                        card.addView(TextView(requireContext()).apply {
                            text = "Set ${sl.setNumber} • $exName — $w $unit × ${sl.repsCompleted} (${sl.mode.uppercase(Locale.US)})"
                            textSize = 11.5f
                            typeface = SwonalFonts.monoBold(requireContext())
                            setTextColor(p.textPrimary)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(4) }
                        })
                    }
                }
                contentList.addView(card)
            }
        } else {
            if (prs.isEmpty()) {
                contentList.addView(TextView(requireContext()).apply {
                    text = "No personal records yet for ${user?.name ?: "this lifter"}."
                    textSize = 13f
                    setTextColor(p.textMuted)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(36), dp(16), dp(36))
                })
                return
            }
            prs.sortedByDescending { it.oneRepMaxEst }.forEach { pr ->
                val exName = pr.exercise?.name ?: exMap[pr.exerciseId]?.name ?: pr.exerciseId
                val muscle = pr.exercise?.muscleGroup ?: exMap[pr.exerciseId]?.muscleGroup.orEmpty()
                val est1rm = Units.formatCompactWeight(pr.oneRepMaxEst, unit)
                val maxW = Units.formatCompactWeight(pr.maxWeightLb, unit)
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = p.elevatedPillDrawable(d, 12f, interactive = false)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                val left = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(requireContext()).apply {
                        text = exName
                        textSize = 13f
                        typeface = SwonalFonts.interBold(requireContext())
                        setTextColor(p.textPrimary)
                    })
                    if (muscle.isNotBlank()) {
                        addView(TextView(requireContext()).apply {
                            text = muscle
                            textSize = 11f
                            setTextColor(p.textMuted)
                        })
                    }
                }
                row.addView(left)
                row.addView(TextView(requireContext()).apply {
                    text = "1RM $est1rm $unit\nMax $maxW $unit"
                    textSize = 11.5f
                    gravity = Gravity.END
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                })
                contentList.addView(row)
            }
        }
    }
}
