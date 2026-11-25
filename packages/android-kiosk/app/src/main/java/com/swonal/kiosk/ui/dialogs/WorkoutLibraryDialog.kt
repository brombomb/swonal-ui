package com.swonal.kiosk.ui.dialogs

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutTemplate
import com.swonal.kiosk.theme.SwonalFonts

/**
 * Native Routines & Exercises Workout Library Dialog matching `KioskView.tsx`:
 * - Supports filtering routines by Active Lifter, All Lifters, or any individual household member
 * - Uses `elevatedPillDrawable` cards for crisp Light Mode contrast and matched row heights
 */
class WorkoutLibraryDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 680

    private var selectedTab = 0 // 0 = Routines, 1 = Exercises
    private var selectedLifterFilter: String = "ACTIVE" // "ACTIVE", "ALL", or specific userId
    private var searchQuery = ""
    private var selectedMuscle = "ALL"
    private var selectedAccessory = "ALL"
    private val expandedGroups = mutableSetOf<String>()

    private lateinit var tabRoutines: TextView
    private lateinit var tabExercises: TextView
    private lateinit var contentContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val root = createCardRoot(18)
        root.addView(createDialogHeader("Workout Library"))

        val tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        tabRoutines = makePillButton("Saved Routines (${ui.templates.size})", active = true) {
            selectedTab = 0
            renderContent()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        tabExercises = makePillButton("Free Lift Catalog (${ui.exercises.size})", active = false) {
            selectedTab = 1
            renderContent()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        tabRow.addView(tabRoutines)
        tabRow.addView(tabExercises)
        root.addView(tabRow)

        contentContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(wrapScrollable(contentContainer, 520))

        renderContent()
        return root
    }

    private fun renderContent() {
        val p = palette
        val d = resources.displayMetrics.density
        tabRoutines.background = if (selectedTab == 0) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
        tabRoutines.setTextColor(if (selectedTab == 0) p.accentTextColor else p.textPrimary)
        tabExercises.background = if (selectedTab == 1) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
        tabExercises.setTextColor(if (selectedTab == 1) p.accentTextColor else p.textPrimary)

        contentContainer.removeAllViews()
        if (selectedTab == 0) renderRoutinesTab() else renderExercisesTab()
    }

    private fun renderRoutinesTab() {
        val ui = repo.uiStateFlow.value
        val activeUserId = ui.activeUser?.id
        val d = resources.displayMetrics.density
        val p = palette

        val topControls = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        // Horizontal filter chips: Active Lifter, All Lifters, plus individual household profiles
        val filterScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = dp(8) }
        }
        val filterRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val filters = mutableListOf("ACTIVE" to "Active Lifter", "ALL" to "All Lifters")
        ui.users.forEach { u -> filters += u.id to u.name }

        filters.forEach { (key, label) ->
            filterRow.addView(makePillButton(label, active = selectedLifterFilter == key) {
                selectedLifterFilter = key
                renderContent()
            }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    marginEnd = dp(6)
                }
            })
        }
        filterScroll.addView(filterRow)
        topControls.addView(filterScroll)
        topControls.addView(makeAccentButton("+ New Routine") {
            val fm = parentFragmentManager
            if (!fm.isStateSaved && fm.findFragmentByTag("routine_builder") == null) {
                RoutineBuilderDialog.newInstance(null) { renderContent() }.show(fm, "routine_builder")
            }
        }.apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        })
        contentContainer.addView(topControls)

        val filtered = ui.templates.filter { tpl ->
            when (selectedLifterFilter) {
                "ALL" -> true
                "ACTIVE" -> activeUserId == null || tpl.userId == activeUserId
                else -> tpl.userId == selectedLifterFilter
            }
        }

        if (filtered.isEmpty()) {
            contentContainer.addView(TextView(requireContext()).apply {
                text = "No saved routines found. Tap '+ New Routine' to create one directly on Tonal!"
                setTextColor(p.textMuted)
                textSize = 13f
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }

        val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0=Sun..6=Sat
        val sortedFiltered = filtered.sortedByDescending { tpl -> todayDow in tpl.scheduleDays }

        sortedFiltered.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            pair.forEachIndexed { idx, tpl ->
                row.addView(buildRoutineCard(tpl, d).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx == 0 && pair.size == 2) marginEnd = dp(10)
                    }
                })
            }
            if (pair.size == 1) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            contentContainer.addView(row)
        }
    }

    private fun buildRoutineCard(tpl: WorkoutTemplate, d: Float): LinearLayout {
        val ui = repo.uiStateFlow.value
        val p = palette
        val owner = ui.users.firstOrNull { it.id == tpl.userId }
        val blocks = tpl.blocks.ifEmpty {
            runCatching { SwonalJson.decodeFromString<List<WorkoutBlock>>(tpl.blocksJson) }.getOrDefault(emptyList())
        }
        val totalSets = blocks.sumOf { blk -> blk.items.sumOf { it.sets } }
        val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val isScheduledToday = todayDow in tpl.scheduleDays

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = p.elevatedPillDrawable(d, 14f, interactive = false)
            setPadding(dp(14), dp(14), dp(14), dp(14))

            val meta = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (owner != null) {
                val ownerColor = ColorMath.parseHexColor(owner.accentColor)
                meta.addView(TextView(requireContext()).apply {
                    text = owner.name
                    textSize = 10.5f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(ColorMath.getContrastTextColor(ownerColor))
                    background = GradientDrawable().apply {
                        setColor(ownerColor)
                        cornerRadius = dpf(8f)
                    }
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                })
            }
            if (isScheduledToday) {
                meta.addView(TextView(requireContext()).apply {
                    text = "TODAY"
                    textSize = 9.5f
                    typeface = SwonalFonts.interExtraBold(requireContext())
                    setTextColor(p.successText)
                    background = p.successPillDrawable(d, 7f, interactive = false)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                })
            }
            meta.addView(TextView(requireContext()).apply {
                text = "${blocks.size} blocks • $totalSets sets"
                textSize = 11f
                typeface = SwonalFonts.monoBold(requireContext())
                fontFeatureSettings = "tnum"
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            })
            addView(meta)

            addView(TextView(requireContext()).apply {
                text = tpl.name
                textSize = 15f
                typeface = SwonalFonts.interExtraBold(requireContext())
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })

            if (!tpl.description.isNullOrBlank()) {
                addView(TextView(requireContext()).apply {
                    text = tpl.description
                    textSize = 12f
                    setTextColor(p.textMuted)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }

            if (blocks.isNotEmpty()) {
                val summary = blocks.joinToString(" • ") { blk ->
                    "${blk.blockType.replace('_', ' ')} (${blk.items.size})"
                }
                addView(TextView(requireContext()).apply {
                    text = summary
                    textSize = 10f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                })
            }

            // Interactive 7-day schedule pill selector (0=Sun..6=Sat)
            val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
            val scheduleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            dayLabels.forEachIndexed { dayIdx, letter ->
                val active = dayIdx in tpl.scheduleDays
                scheduleRow.addView(TextView(requireContext()).apply {
                    text = letter
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(if (active) p.accentTextColor else p.textMuted)
                    background = if (active) p.accentPillDrawable(d, 8f) else p.elevatedPillDrawable(d, 8f)
                    layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                        if (dayIdx < dayLabels.lastIndex) marginEnd = dp(4)
                    }
                    setOnClickListener {
                        val next = if (active) tpl.scheduleDays - dayIdx else (tpl.scheduleDays + dayIdx).sorted()
                        repo.updateTemplateSchedule(tpl, next)
                        renderContent()
                    }
                })
            }
            addView(scheduleRow)

            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
            })

            val actionRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            actionRow.addView(makePillButton("Edit") {
                val fm = parentFragmentManager
                if (!fm.isStateSaved && fm.findFragmentByTag("routine_builder") == null) {
                    RoutineBuilderDialog.newInstance(tpl.id) { renderContent() }.show(fm, "routine_builder")
                }
            }.apply {
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(38)
                ).apply { marginEnd = dp(8) }
            })
            actionRow.addView(makeAccentButton(if (isScheduledToday) "Start Today's Routine" else "Start Routine") {
                repo.startWorkoutTemplate(tpl)
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            })
            addView(actionRow)
        }
    }

    private fun renderExercisesTab() {
        val ui = repo.uiStateFlow.value
        val d = resources.displayMetrics.density
        val allMuscles = listOf("ALL") + ui.exercises.map { it.muscleGroup }.distinct().sorted()
        val accessories = listOf("ALL", "HANDLES", "BAR", "ROPE", "BODYWEIGHT")

        val searchRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val searchBox = makeInput("Search movements...", searchQuery).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            ).apply { marginEnd = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    renderExerciseListOnly()
                }
            })
        }
        val diceBtn = makePillButton("🎲", active = true) {
            val currentUi = repo.uiStateFlow.value
            val q = searchQuery.trim().lowercase()
            val pool = currentUi.exercises.filter { ex ->
                (selectedMuscle == "ALL" || ex.muscleGroup == selectedMuscle) &&
                    (selectedAccessory == "ALL" || ex.accessory == selectedAccessory) &&
                    (q.isEmpty() || ex.name.lowercase().contains(q) || ex.muscleGroup.lowercase().contains(q))
            }.ifEmpty { currentUi.exercises }
            if (pool.isNotEmpty()) {
                val currentId = currentUi.activeExercise?.id
                val candidates = pool.filter { it.id != currentId }.ifEmpty { pool }
                val picked = candidates.random()
                repo.selectFreeExercise(picked)
                dismiss()
            }
        }.apply {
            textSize = 18f
            contentDescription = "Random workout"
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(42))
        }
        searchRow.addView(searchBox)
        searchRow.addView(diceBtn)
        contentContainer.addView(searchRow)

        val accRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        accessories.forEach { acc ->
            accRow.addView(makePillButton(acc, active = selectedAccessory == acc, mono = true) {
                selectedAccessory = acc
                renderContent()
            }.apply {
                textSize = 10.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32)
                ).apply { marginEnd = dp(6) }
            })
        }
        contentContainer.addView(accRow)

        val muscleScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val muscleRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        allMuscles.forEach { m ->
            muscleRow.addView(makePillButton(m, active = selectedMuscle == m) {
                selectedMuscle = m
                renderContent()
            }.apply {
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32)
                ).apply { marginEnd = dp(6) }
            })
        }
        muscleScroll.addView(muscleRow)
        contentContainer.addView(muscleScroll)

        val listHost = LinearLayout(requireContext()).apply {
            tag = "exercise_list_host"
            orientation = LinearLayout.VERTICAL
        }
        contentContainer.addView(listHost)
        populateExerciseGroups(listHost, d)
    }

    private fun renderExerciseListOnly() {
        val host = contentContainer.findViewWithTag<LinearLayout>("exercise_list_host") ?: return
        host.removeAllViews()
        populateExerciseGroups(host, resources.displayMetrics.density)
    }

    private fun populateExerciseGroups(host: LinearLayout, d: Float) {
        val ui = repo.uiStateFlow.value
        val p = palette
        val q = searchQuery.trim().lowercase()
        val prByEx = ui.prs.associateBy { it.exerciseId }

        val filtered = ui.exercises.filter { ex ->
            (selectedMuscle == "ALL" || ex.muscleGroup.equals(selectedMuscle, ignoreCase = true)) &&
                (selectedAccessory == "ALL" || ex.accessory.equals(selectedAccessory, ignoreCase = true)) &&
                (q.isEmpty() || ex.name.lowercase().contains(q) || ex.muscleGroup.lowercase().contains(q))
        }

        val grouped = filtered.groupBy { it.muscleGroup }.toSortedMap()
        val forceExpand = q.isNotEmpty() || selectedMuscle != "ALL" || selectedAccessory != "ALL"

        if (grouped.isEmpty()) {
            host.addView(TextView(requireContext()).apply {
                text = "No matching exercises."
                setTextColor(p.textMuted)
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }

        grouped.forEach { (muscle, list) ->
            val isExpanded = forceExpand || expandedGroups.contains(muscle) || grouped.size == 1
            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = p.elevatedPillDrawable(d, 10f)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (expandedGroups.contains(muscle)) expandedGroups.remove(muscle)
                    else expandedGroups.add(muscle)
                    renderExerciseListOnly()
                }
                addView(TextView(requireContext()).apply {
                    text = "$muscle (${list.size})"
                    textSize = 13f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(requireContext()).apply {
                    text = if (isExpanded) "▾" else "▸"
                    textSize = 14f
                    setTextColor(p.accentInk)
                })
            }
            host.addView(header)

            if (isExpanded) {
                list.chunked(2).forEach { pair ->
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                    }
                    pair.forEachIndexed { idx, ex ->
                        row.addView(buildExerciseTile(ex, prByEx[ex.id]?.oneRepMaxEst, ui.activeUnit, d).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                                if (idx == 0 && pair.size == 2) marginEnd = dp(6)
                            }
                        })
                    }
                    if (pair.size == 1) {
                        row.addView(View(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    }
                    host.addView(row)
                }
            }
        }
    }

    private fun buildExerciseTile(ex: Exercise, oneRmLb: Double?, unit: String, d: Float): LinearLayout {
        val p = palette
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = p.elevatedPillDrawable(d, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                repo.selectFreeExercise(ex)
                dismiss()
            }

            addView(TextView(requireContext()).apply {
                text = ex.name
                textSize = 13f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.textPrimary)
            })

            val metaText = buildString {
                append("${ex.accessory} • H${ex.recommendedHeight} R${ex.recommendedRotation} A${ex.recommendedAngle}")
                if (oneRmLb != null && oneRmLb > 0) {
                    append(" • 1RM ${Units.formatCompactWeight(oneRmLb, unit)} $unit")
                }
            }
            addView(TextView(requireContext()).apply {
                text = metaText
                textSize = 10.5f
                typeface = SwonalFonts.monoBold(requireContext())
                fontFeatureSettings = "tnum"
                setTextColor(p.accentInk)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }
    }
}
