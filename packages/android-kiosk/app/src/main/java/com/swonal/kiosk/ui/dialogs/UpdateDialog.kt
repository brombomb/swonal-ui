package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.net.AppUpdateInfo
import com.swonal.kiosk.net.AppUpdateManager
import com.swonal.kiosk.net.UpdateInstallResult
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native In-App Update Dialog:
 * - Shows current -> target version and a short, human-readable "What's New" changelog
 * - Blocks installation while weight is engaged for physical motor safety
 * - Downloads APK with live progress and performs silent root install on rooted Tonal V1
 */
class UpdateDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 520

    private var updateInfo: AppUpdateInfo? = null
    private var installing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val info = updateInfo ?: AppUpdateManager.latestAvailableUpdate
        val p = palette
        val d = resources.displayMetrics.density
        val root = createCardRoot(20)
        root.addView(createDialogHeader("Swonal Update Available"))

        if (info == null) {
            root.addView(TextView(requireContext()).apply {
                text = "You are running the latest version (v${BuildConfig.VERSION_NAME})."
                textSize = 13.5f
                setTextColor(p.textPrimary)
                setPadding(0, dp(8), 0, dp(12))
            })
            root.addView(makePillButton("Close") { dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            })
            return root
        }

        // Version transition badge row
        val verRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        verRow.addView(TextView(requireContext()).apply {
            text = "v${BuildConfig.VERSION_NAME}  →  v${info.versionName}"
            textSize = 13f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
            background = p.elevatedPillDrawable(d, 10f, interactive = false)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        if (info.apkSizeBytes > 0L) {
            val sizeMb = info.apkSizeBytes / (1024.0 * 1024.0)
            verRow.addView(TextView(requireContext()).apply {
                text = String.format("%.1f MB", sizeMb)
                textSize = 11.5f
                typeface = SwonalFonts.monoBold(requireContext())
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            })
        }
        root.addView(verRow)

        // Human-readable "What's New" card
        root.addView(makeSectionLabel("What's New"))
        val notesContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        info.changelogBullets.forEachIndexed { idx, bullet ->
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = dp(7)
                }
            }
            line.addView(TextView(requireContext()).apply {
                text = "•"
                textSize = 13f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.accentInk)
                layoutParams = LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            line.addView(TextView(requireContext()).apply {
                text = bullet
                textSize = 12.5f
                setTextColor(p.textPrimary)
                setLineSpacing(dpf(2f), 1f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            notesContainer.addView(line)
        }
        root.addView(wrapScrollable(notesContainer, 280))

        // Progress UI (shown during download & install)
        val statusText = TextView(requireContext()).apply {
            textSize = 12f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(6) }
        }
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(statusText)
        root.addView(progressBar)

        // Action Buttons
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val laterBtn = makePillButton("Later") {
            if (!installing) {
                AppUpdateManager.dismissedVersionInSession = info.versionName
                dismiss()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }

        lateinit var installBtn: TextView
        installBtn = makeAccentButton("Install Update") {
            if (installing) return@makeAccentButton
            if (repo.isWeightCurrentlyEngaged()) {
                Toast.makeText(
                    requireContext(),
                    "Disengage weight before installing updates",
                    Toast.LENGTH_LONG
                ).show()
                return@makeAccentButton
            }
            installing = true
            isCancelable = false
            laterBtn.isEnabled = false
            laterBtn.alpha = 0.45f
            installBtn.isEnabled = false
            installBtn.text = "Updating..."
            statusText.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                val result = AppUpdateManager.downloadAndInstall(
                    context = requireContext().applicationContext,
                    update = info,
                    isWeightEngaged = { repo.isWeightCurrentlyEngaged() },
                    onProgress = { pct, msg ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (!isAdded) return@launch
                            progressBar.progress = pct
                            statusText.text = msg
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    when (result) {
                        is UpdateInstallResult.InstalledAndRelaunching -> {
                            statusText.text = "Installed! Relaunching Swonal..."
                        }
                        is UpdateInstallResult.FallbackInstallerLaunched -> {
                            installing = false
                            isCancelable = true
                            dismiss()
                        }
                        is UpdateInstallResult.Failed -> {
                            installing = false
                            isCancelable = true
                            laterBtn.isEnabled = true
                            laterBtn.alpha = 1f
                            installBtn.isEnabled = true
                            installBtn.text = "Retry Install"
                            statusText.setTextColor(p.criticalText)
                            statusText.text = result.message
                        }
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.4f)
        }

        btnRow.addView(laterBtn)
        btnRow.addView(installBtn)
        root.addView(btnRow)
        return root
    }

    companion object {
        fun newInstance(info: AppUpdateInfo? = null): UpdateDialog {
            return UpdateDialog().apply { updateInfo = info }
        }
    }
}
