package com.swonal.kiosk.ui.dialogs

import com.swonal.kiosk.model.AutoCompleteSetTimer

/**
 * Picker for the active profile's "Auto-Complete Set" timer: quick Off / 5 / 10 / 20 s choices,
 * or an exact number of seconds on the same keypad used for target weight.
 */
class AutoCompleteTimerDialog : NumericKeypadDialog() {

    private var currentSec: Int = 0

    override val headerTitle: String = "Auto-Complete Set Timer"
    override val unitLabel: String = "SEC"
    override val rangeHint: String =
        "0 = Off  •  ${AutoCompleteSetTimer.MIN_SECONDS} – ${AutoCompleteSetTimer.MAX_SECONDS} seconds"
    override val submitLabel: String = "Set Timer"
    override val presets: List<Preset> = AutoCompleteSetTimer.QUICK_CHOICES_SECONDS.map { sec ->
        Preset(if (sec == 0) "Off" else "$sec s", sec.toDouble())
    }
    override val presetColumns: Int = 4
    override val maxDigits: Int = 2

    override fun prepare() {
        currentSec = repo.uiStateFlow.value.autoCompleteTimerSec
    }

    override fun initialDraft(): String = currentSec.toString()

    override fun isPresetActive(preset: Preset): Boolean = preset.value.toInt() == currentSec

    override fun onValueChosen(value: Double): Boolean {
        repo.setAutoCompleteTimerSec(value.toInt())
        return true
    }
}
