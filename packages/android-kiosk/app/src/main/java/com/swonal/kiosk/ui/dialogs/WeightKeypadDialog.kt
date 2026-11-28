package com.swonal.kiosk.ui.dialogs

import com.swonal.kiosk.model.Units

/**
 * Exact target weight keypad dialog matching `KioskView.tsx`:
 * - Supports decimal `.` entry in KG mode (`0.5 kg` increments) and range hint (`5–200 LB` / `2.5–90.5 KG`)
 * - 6 unit-aware quick presets
 */
class WeightKeypadDialog : NumericKeypadDialog() {

    private var activeUnit: String = "LB"
    private var isKg: Boolean = false

    override val headerTitle: String
        get() = "Set Target Weight (${activeUnit.uppercase()})"
    override val unitLabel: String
        get() = activeUnit.uppercase()
    override val rangeHint: String
        get() = if (isKg) "Valid range: 2.5 – 90.5 KG (0.5 KG steps)" else "Valid range: 5 – 200 LB"
    override val submitLabel: String = "Set Weight"
    override val presets: List<Preset>
        get() = (if (isKg) listOf(10, 20, 30, 45, 60, 80) else listOf(20, 45, 65, 95, 135, 185))
            .map { Preset("$it", it.toDouble()) }
    override val allowDecimal: Boolean
        get() = isKg

    override fun prepare() {
        activeUnit = repo.uiStateFlow.value.activeUnit
        isKg = Units.isKg(activeUnit)
    }

    override fun initialDraft(): String =
        Units.formatDialWeightString(repo.telemetryFlow.value.targetWeightLb, activeUnit, exact = false)

    override fun onValueChosen(value: Double): Boolean {
        if (value <= 0.0) return false
        repo.setWeight(Units.parseDisplayWeightToLb(value, activeUnit))
        return true
    }
}
