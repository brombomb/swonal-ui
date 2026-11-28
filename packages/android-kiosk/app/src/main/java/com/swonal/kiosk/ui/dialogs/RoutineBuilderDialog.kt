package com.swonal.kiosk.ui.dialogs

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutBlockItem
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Native Kiosk Routine Builder Dialog (100% offline-first):
 * - Create, Edit, and Delete custom multi-block routines (Straight Set / Superset / Circuit)
 * - 7-day schedule pills, exercise selector with muscle filter, sets/reps/weight/mode controls
 */
class RoutineBuilderDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 660

    var onSaved: (() -> Unit)? = null

    private data class DraftItem(
        var exerciseId: String,
        var sets: Int = 3,
        var targetReps: Int = 10,
        var targetWeightDisplay: Double = 45.0,
        var mode: String = "standard",
        var modifierPct: Int = 0
    )

    private data class DraftBlock(
        var blockType: String = "STRAIGHT_SET",
        var restSeconds: Int = 60,
        val items: MutableList<DraftItem> = mutableListOf()
    )

    private var editingTemplateId: String? = null
    private var draftName: String = ""
    private var draftDescription: String = ""
    private val selectedDays = mutableSetOf<Int>()
    private val draftBlocks = mutableListOf<DraftBlock>()

    private lateinit var blocksContainer: LinearLayout
    private lateinit var scheduleContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val unit = ui.activeUnit
        editingTemplateId = arguments?.getString(ARG_TEMPLATE_ID)?.takeIf { it.isNotBlank() }
        val existing = editingTemplateId?.let { tid -> ui.templates.firstOrNull { it.id == tid } }

        if (existing != null && draftBlocks.isEmpty()) {
            draftName = existing.name
            draftDescription = existing.description.orEmpty()
            selectedDays.addAll(existing.scheduleDays)
            val parsed = existing.blocks.ifEmpty {
                runCatching { SwonalJson.decodeFromString<List<WorkoutBlock>>(existing.blocksJson) }.getOrDefault(emptyList())
            }
            parsed.forEach { blk ->
                draftBlocks += DraftBlock(
                    blockType = blk.blockType,
                    restSeconds = blk.restSeconds,
                    items = blk.items.map { item ->
                        DraftItem(
                            exerciseId = item.exerciseId,
                            sets = item.sets,
                            targetReps = item.targetReps,
                            targetWeightDisplay = Units.formatDisplayWeight(item.targetWeightLb, unit),
                            mode = item.mode,
                            modifierPct = item.modifierPct
                        )
                    }.toMutableList()
                )
            }
        }
        if (draftBlocks.isEmpty()) {
            val firstEx = ui.exercises.firstOrNull()?.id.orEmpty()
            draftBlocks += DraftBlock(
                blockType = "STRAIGHT_SET",
                restSeconds = 60,
                items = if (firstEx.isNotEmpty()) {
                    mutableListOf(
                        DraftItem(
                            exerciseId = firstEx,
                            targetWeightDisplay = Units.formatDisplayWeight(45.0, unit)
                        )
                    )
                } else {
                    mutableListOf()
                }
            )
        }

        val root = createCardRoot(18)
        root.addView(createDialogHeader(if (existing != null) "Edit Routine" else "New Routine"))

        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        body.addView(makeSectionLabel("Routine Details"))
        val nameInput = makeInput("Routine Name (e.g. Push Power)", draftName).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(8)
            }
            addTextChangedListener(simpleWatcher { draftName = it })
        }
        val descInput = makeInput("Notes / Focus (optional)", draftDescription).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(8)
            }
            addTextChangedListener(simpleWatcher { draftDescription = it })
        }
        body.addView(nameInput)
        body.addView(descInput)

        body.addView(makeSectionLabel("Weekly Schedule"))
        scheduleContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        body.addView(scheduleContainer)
        renderScheduleRow()

        val blockHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        blockHeader.addView(TextView(requireContext()).apply {
            text = "WORKOUT BLOCKS (${unit})"
            textSize = 11f
            letterSpacing = 0.08f
            typeface = SwonalFonts.interExtraBold(requireContext())
            setTextColor(palette.textMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        blockHeader.addView(makePillButton("+ Add Block", active = true) {
            val defaultEx = repo.uiStateFlow.value.exercises.firstOrNull()?.id.orEmpty()
            draftBlocks += DraftBlock(
                blockType = "STRAIGHT_SET",
                restSeconds = 60,
                items = if (defaultEx.isNotEmpty()) {
                    mutableListOf(
                        DraftItem(
                            exerciseId = defaultEx,
                            targetWeightDisplay = Units.formatDisplayWeight(45.0, unit)
                        )
                    )
                } else {
                    mutableListOf()
                }
            )
            renderBlocks()
        }.apply {
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        })
        body.addView(blockHeader)

        blocksContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        body.addView(blocksContainer)
        renderBlocks()

        root.addView(wrapScrollable(body, 480))

        val footer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        if (existing != null) {
            footer.addView(makePillButton("Delete") {
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete '${existing.name}'?")
                    .setMessage("This removes the saved routine from your library.")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            repo.localEngine.deleteTemplate(existing.id)
                            repo.refreshAppState()
                            repo.notifyDirtySync()
                            onSaved?.invoke()
                            dismiss()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }.apply {
                setTextColor(palette.criticalText)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                    marginEnd = dp(8)
                }
            })
        }
        footer.addView(makeAccentButton(if (existing != null) "Save Changes" else "Create Routine") {
            saveRoutine(unit)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        })
        root.addView(footer)

        return root
    }

    private fun renderScheduleRow() {
        val p = palette
        val d = resources.displayMetrics.density
        val labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        scheduleContainer.removeAllViews()
        labels.forEachIndexed { dayIdx, label ->
            val active = dayIdx in selectedDays
            scheduleContainer.addView(TextView(requireContext()).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = SwonalFonts.monoBold(requireContext())
                setTextColor(if (active) p.accentTextColor else p.textMuted)
                background = if (active) p.accentPillDrawable(d, 8f) else p.elevatedPillDrawable(d, 8f)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (dayIdx < labels.lastIndex) marginEnd = dp(4)
                }
                setOnClickListener {
                    if (active) selectedDays.remove(dayIdx) else selectedDays.add(dayIdx)
                    renderScheduleRow()
                }
            })
        }
    }

    private fun renderBlocks() {
        if (!isAdded) return
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val unit = ui.activeUnit
        val exMap = ui.exercises.associateBy { it.id }

        blocksContainer.removeAllViews()
        draftBlocks.forEachIndexed { bIdx, blk ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = p.elevatedPillDrawable(d, 12f, interactive = false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }

            val topRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            topRow.addView(TextView(requireContext()).apply {
                text = "Block ${bIdx + 1}"
                textSize = 12.5f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val blockTypes = listOf("STRAIGHT_SET", "SUPERSET", "CIRCUIT")
            topRow.addView(makePillButton(blk.blockType.replace('_', ' '), mono = true) {
                val cur = blockTypes.indexOf(blk.blockType)
                blk.blockType = blockTypes[(cur + 1) % blockTypes.size]
                renderBlocks()
            }.apply {
                textSize = 10.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    marginEnd = dp(6)
                }
            })
            topRow.addView(makePillButton("Rest ${blk.restSeconds}s", mono = true) {
                val presets = listOf(30, 45, 60, 90, 120, 180)
                val next = presets.firstOrNull { it > blk.restSeconds } ?: presets.first()
                blk.restSeconds = next
                renderBlocks()
            }.apply {
                textSize = 10.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    marginEnd = dp(6)
                }
            })
            topRow.addView(makePillButton("Copy", mono = true) {
                duplicateBlock(bIdx)
            }.apply {
                textSize = 10.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    if (draftBlocks.size > 1) marginEnd = dp(6)
                }
            })
            if (draftBlocks.size > 1) {
                topRow.addView(makePillButton("✕") {
                    draftBlocks.removeAt(bIdx)
                    renderBlocks()
                }.apply {
                    textSize = 11f
                    setTextColor(p.criticalText)
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(30))
                })
            }
            card.addView(topRow)

            blk.items.forEachIndexed { iIdx, item ->
                val exName = exMap[item.exerciseId]?.name ?: "Pick Exercise"
                val itemRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    }
                }

                val nameRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(4)
                    }
                }
                nameRow.addView(makePillButton(exName) {
                    pickExercise { chosenId ->
                        item.exerciseId = chosenId
                        renderBlocks()
                    }
                }.apply {
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
                })
                if (blk.items.size > 1) {
                    nameRow.addView(makePillButton("Remove") {
                        blk.items.removeAt(iIdx)
                        renderBlocks()
                    }.apply {
                        textSize = 10.5f
                        setTextColor(p.criticalText)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                    })
                }
                itemRow.addView(nameRow)

                val paramsRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                paramsRow.addView(makeStepperButton("${item.sets} sets") {
                    item.sets = if (item.sets >= 6) 1 else item.sets + 1
                    renderBlocks()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(4) }
                })
                paramsRow.addView(makeStepperButton("${item.targetReps} reps") {
                    val steps = listOf(5, 6, 8, 10, 12, 15, 20)
                    item.targetReps = steps.firstOrNull { it > item.targetReps } ?: steps.first()
                    renderBlocks()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(4) }
                })
                paramsRow.addView(makeStepperButton("${formatWeight(item.targetWeightDisplay)} $unit") {
                    val maxDisp = Units.formatDisplayWeight(Units.MAX_WEIGHT_LB, unit)
                    val step = if (unit == "KG") 2.5 else 5.0
                    item.targetWeightDisplay = if (item.targetWeightDisplay + step > maxDisp) step else item.targetWeightDisplay + step
                    renderBlocks()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1.2f).apply { marginEnd = dp(4) }
                })
                val modes = listOf("standard", "chains", "eccentric", "spotter", "burnout", "smart_flex")
                val modeLower = item.mode.lowercase(Locale.US)
                val supportsModifierPct = modeLower == "chains" || modeLower == "eccentric"
                paramsRow.addView(makeStepperButton(item.mode.uppercase(Locale.US)) {
                    val nextIdx = (modes.indexOf(modeLower) + 1) % modes.size
                    val nextMode = modes[nextIdx]
                    item.mode = nextMode
                    if (nextMode == "chains" || nextMode == "eccentric") {
                        if (item.modifierPct <= 0) item.modifierPct = 20
                    } else {
                        item.modifierPct = 0
                    }
                    renderBlocks()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1.2f).apply {
                        if (supportsModifierPct) marginEnd = dp(4)
                    }
                })
                if (supportsModifierPct) {
                    paramsRow.addView(makeStepperButton("+${item.modifierPct}%") {
                        val pctSteps = listOf(10, 15, 20, 25, 30, 35, 40, 50)
                        item.modifierPct = pctSteps.firstOrNull { it > item.modifierPct } ?: pctSteps.first()
                        renderBlocks()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(32), 0.9f)
                    })
                }
                itemRow.addView(paramsRow)
                card.addView(itemRow)
            }

            card.addView(makePillButton("+ Add Exercise to Block") {
                val defaultEx = ui.exercises.firstOrNull()?.id.orEmpty()
                if (defaultEx.isNotEmpty()) {
                    pickExercise { chosenId ->
                        blk.items += DraftItem(
                            exerciseId = chosenId,
                            targetWeightDisplay = Units.formatDisplayWeight(45.0, unit)
                        )
                        renderBlocks()
                    }
                }
            }.apply {
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply {
                    topMargin = dp(2)
                }
            })

            blocksContainer.addView(card)
        }
    }

    /** Inserts a deep copy of the block directly below the original, so it can be tweaked in place. */
    private fun duplicateBlock(bIdx: Int) {
        val src = draftBlocks.getOrNull(bIdx) ?: return
        draftBlocks.add(
            bIdx + 1,
            src.copy(items = src.items.map { it.copy() }.toMutableList())
        )
        renderBlocks()
        Toast.makeText(requireContext(), "Copied as Block ${bIdx + 2}", Toast.LENGTH_SHORT).show()
    }

    private fun makeStepperButton(label: String, onClick: () -> Unit): TextView =
        makePillButton(label, mono = true, onClick = onClick).apply { textSize = 10.5f }

    private fun formatWeight(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun pickExercise(onChosen: (String) -> Unit) {
        val exercises = repo.uiStateFlow.value.exercises
        if (exercises.isEmpty()) return
        val names = exercises.map { "${it.name} (${it.muscleGroup})" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Exercise")
            .setItems(names) { _, which ->
                exercises.getOrNull(which)?.let { onChosen(it.id) }
            }
            .show()
    }

    private fun saveRoutine(unit: String) {
        val cleanName = draftName.trim()
        if (cleanName.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a routine name", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = repo.uiStateFlow.value.activeUser?.id ?: return
        val blocks = draftBlocks.mapNotNull { blk ->
            val validItems = blk.items.filter { it.exerciseId.isNotBlank() }.map { item ->
                val cleanMode = item.mode.lowercase(Locale.US)
                val preservedModifier = if (cleanMode == "chains" || cleanMode == "eccentric") {
                    item.modifierPct.coerceIn(0, 100)
                } else {
                    0
                }
                WorkoutBlockItem(
                    exerciseId = item.exerciseId,
                    sets = item.sets.coerceIn(1, 20),
                    targetReps = item.targetReps.coerceIn(1, 200),
                    mode = item.mode,
                    modifierPct = preservedModifier,
                    targetWeightLb = item.targetWeightDisplay
                )
            }
            if (validItems.isEmpty()) null else WorkoutBlock(blk.blockType, blk.restSeconds, validItems)
        }
        if (blocks.isEmpty()) {
            Toast.makeText(requireContext(), "Add at least one exercise", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val tid = editingTemplateId
                if (tid != null) {
                    repo.localEngine.updateTemplate(
                        templateId = tid,
                        name = cleanName,
                        description = draftDescription.trim().ifEmpty { null },
                        blocks = blocks,
                        unit = unit,
                        scheduleDays = selectedDays.sorted()
                    )
                } else {
                    repo.localEngine.createTemplate(
                        userId = uid,
                        name = cleanName,
                        description = draftDescription.trim().ifEmpty { null },
                        blocks = blocks,
                        unit = unit,
                        scheduleDays = selectedDays.sorted()
                    )
                }
            }.onSuccess {
                repo.refreshAppState()
                repo.notifyDirtySync()
                Toast.makeText(requireContext(), "Routine '$cleanName' saved", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
                dismiss()
            }.onFailure { err ->
                Toast.makeText(requireContext(), err.message ?: "Failed to save routine", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun simpleWatcher(onUpdate: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            onUpdate(s?.toString().orEmpty())
        }
    }

    companion object {
        private const val ARG_TEMPLATE_ID = "template_id"

        fun newInstance(templateId: String? = null, onSaved: (() -> Unit)? = null): RoutineBuilderDialog {
            return RoutineBuilderDialog().apply {
                this.onSaved = onSaved
                arguments = Bundle().apply {
                    if (!templateId.isNullOrBlank()) putString(ARG_TEMPLATE_ID, templateId)
                }
            }
        }
    }
}
