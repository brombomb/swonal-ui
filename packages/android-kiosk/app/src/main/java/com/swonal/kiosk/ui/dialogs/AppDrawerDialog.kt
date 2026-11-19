package com.swonal.kiosk.ui.dialogs

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.service.StockOverlayService
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.ui.PressFeedback
import kotlinx.coroutines.launch

/**
 * Simplified Menu dialog:
 * - History, Settings (including AI Coach), Admin (all open seamlessly in-app)
 * - Stock Tonal UI (with safe disengage & motor daemon handoff)
 * - Developer tools (Android Settings / Termux / Magisk) hidden unless Developer Mode is enabled
 */
class AppDrawerDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 440

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val p = palette
        val d = resources.displayMetrics.density
        val serverUrl = ui.serverUrl.trimEnd('/')

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Menu"))

        if (ui.authRequired) {
            root.addView(TextView(requireContext()).apply {
                text = "Unlock Swonal before accessing menu screens."
                textSize = 13f
                setTextColor(p.warningText)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return root
        }

        // Primary Swonal Views (open seamlessly in-app)
        val sections = listOf(
            Triple("History & PRs", "Workout log, volume trends, and personal records", "$serverUrl/history"),
            Triple("Application Settings", "Preferences, AI Coach configuration, Spotify & security", "$serverUrl/settings"),
            Triple("Admin & Exercises", "Exercise catalog, media loops, and hardware calibration", "$serverUrl/admin")
        )
        sections.forEach { (title, subtitle, url) ->
            root.addView(makeMenuCard(title, subtitle, d) {
                dismiss()
                openSafeHttpUrl(url, title)
            })
        }

        root.addView(makeDivider())

        // Stock Tonal UI handoff
        root.addView(makeMenuCard(
            "Stock Tonal UI",
            "Disengage load, hand off motor control, and launch Tonal",
            d
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                val disengaged = repo.disableWeightAndAwait()
                if (!disengaged) {
                    Toast.makeText(
                        requireContext(),
                        "Could not confirm weight disengage — aborting Stock Tonal launch",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val switched = repo.switchToStockMode()
                if (!switched) {
                    Toast.makeText(
                        requireContext(),
                        "Stock mode handoff failed — check tonal-daemon before launching Tonal",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                // Launch escape hatch overlay so user is never trapped in stock mode
                StockOverlayService.start(requireContext())
                launchPackage("com.tonal.apps.machineclient2.beta", "Stock Tonal UI")
            }
        })

        // Developer-only device utilities (hidden in normal use)
        if (ui.developerMode) {
            root.addView(makeDivider())
            root.addView(makeSectionLabel("Developer Tools"))
            val devRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            devRow.addView(makePillButton("Android Settings") {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            })
            devRow.addView(makePillButton("Termux") {
                launchPackage("com.termux", "Termux")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            })
            devRow.addView(makePillButton("Magisk") {
                launchPackage("com.topjohnwu.magisk", "Magisk")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            })
            root.addView(devRow)
        }

        return root
    }

    private fun makeMenuCard(
        title: String,
        subtitle: String,
        d: Float,
        onClick: () -> Unit
    ): LinearLayout {
        val p = palette
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = p.elevatedPillDrawable(d, 12f)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(requireContext()).apply {
                text = title
                textSize = 14f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.textPrimary)
            })
            addView(TextView(requireContext()).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(p.textMuted)
            })
        }.also { PressFeedback.attachSingle(it) }
    }

    private fun launchPackage(pkgName: String, label: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkgName)
        if (intent != null) {
            startActivity(intent)
            dismiss()
        } else {
            Toast.makeText(requireContext(), "$label is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
