package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.service.StockOverlayService
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch

/**
 * Hardware & Diagnostics Dialog:
 * - Clean user view: status, motor/calibration controls, mock controls (in mock mode only), data exports
 * - Server & Local Fail-Safe Endpoints are strictly gated behind Developer Mode (tap header 5x)
 * - AI Coach configuration lives in Application Settings (/settings)
 */
class DiagnosticsDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 540
    private var devTapCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        repo.refreshDiagnostics()
        val ui = repo.uiStateFlow.value
        val p = palette
        val d = resources.displayMetrics.density

        val root = createCardRoot(18)
        val header = createDialogHeader("Hardware & Diagnostics")
        header.setOnClickListener {
            if (repo.uiStateFlow.value.developerMode) return@setOnClickListener
            devTapCount++
            if (devTapCount >= 5) {
                devTapCount = 0
                repo.setDeveloperMode(true)
                Toast.makeText(requireContext(), "Developer Mode enabled", Toast.LENGTH_SHORT).show()
            } else if (devTapCount >= 3) {
                Toast.makeText(
                    requireContext(),
                    "${5 - devTapCount} more taps for Developer Mode",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(header)

        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        // Live Transport, Calibration, Session & HA Status Box
        val statusBox = TextView(requireContext()).apply {
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        content.addView(statusBox)

        // Hardware Mode & Calibration Controls
        content.addView(makeSectionLabel("Motor Bridge & Calibration"))
        val modeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        modeRow.addView(makePillButton("Emergency Weight OFF") {
            repo.disableWeight()
        }.apply {
            setTextColor(p.criticalText)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        modeRow.addView(makePillButton("Reset Reps") {
            repo.resetReps()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        modeRow.addView(makePillButton("Zero Calibration") {
            repo.zeroCalibration()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        })
        content.addView(modeRow)

        val tonalModeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        tonalModeRow.addView(makePillButton("Switch to Custom Mode") {
            repo.switchToCustomMode()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) }
        })
        tonalModeRow.addView(makePillButton("Switch to Stock Mode") {
            viewLifecycleOwner.lifecycleScope.launch {
                val safe = repo.disableWeightAndAwait()
                if (!safe) {
                    Toast.makeText(requireContext(), "Disengage weight before switching to Stock mode", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                StockOverlayService.start(requireContext())
                repo.switchToStockMode()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        })
        content.addView(tonalModeRow)

        // Mock / Simulator Hardware Controls (ONLY visible when daemon reports mock_mode == true)
        val mockSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (ui.isMockMode) View.VISIBLE else View.GONE
        }
        mockSection.addView(makeSectionLabel("Mock Simulator Controls"))
        val bleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        bleRow.addView(makePillButton("BLE Toggle") {
            repo.triggerMockBle("toggle_weight")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
        })
        bleRow.addView(makePillButton("BLE Skip Track") {
            repo.triggerMockBle("spotify_next")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
        })
        bleRow.addView(makePillButton("BLE E-Stop") {
            repo.triggerMockBle("emergency_stop")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        })
        mockSection.addView(bleRow)

        mockSection.addView(makePillButton("Test Bar Tilt Cutout (Mock Only)") {
            repo.triggerMockBarTilt()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply { bottomMargin = dp(8) }
        })
        content.addView(mockSection)

        // Home Assistant & Data Export
        content.addView(makeSectionLabel("Home Assistant & Data Export"))
        content.addView(makePillButton("Publish Home Assistant Test Event") {
            repo.testHomeAssistant()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply { bottomMargin = dp(8) }
        })

        val baseLocalWeb = ui.localWebServerUrl.trimEnd('/')
        val uidQuery = ui.activeUser?.id?.let { "?userId=$it" }.orEmpty()
        val exportRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        listOf(
            "Export CSV" to "$baseLocalWeb/api/export/sessions.csv$uidQuery",
            "Export JSON" to "$baseLocalWeb/api/export/sessions.json$uidQuery",
            "Backup DB" to "$baseLocalWeb/api/admin/backup/db"
        ).forEachIndexed { idx, (label, url) ->
            exportRow.addView(makePillButton(label) {
                openSafeHttpUrl(url, label)
            }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx < 2) marginEnd = dp(6)
                }
            })
        }
        content.addView(exportRow)

        // In-App Auto-Updater Check
        lateinit var checkUpdateBtn: TextView
        checkUpdateBtn = makePillButton("Check for App Updates (v${com.swonal.kiosk.BuildConfig.VERSION_NAME})") {
            checkUpdateBtn.isEnabled = false
            checkUpdateBtn.text = "Checking for updates..."
            viewLifecycleOwner.lifecycleScope.launch {
                val res = com.swonal.kiosk.net.AppUpdateManager.checkForUpdate()
                if (!isAdded) return@launch
                checkUpdateBtn.isEnabled = true
                when (res) {
                    is com.swonal.kiosk.net.UpdateCheckResult.UpdateAvailable -> {
                        checkUpdateBtn.text = "Update Available: v${res.info.versionName} →"
                        val fm = parentFragmentManager
                        if (fm.isStateSaved) return@launch
                        dismiss()
                        if (fm.findFragmentByTag("app_update") == null) {
                            UpdateDialog.newInstance(res.info).show(fm, "app_update")
                        }
                    }
                    is com.swonal.kiosk.net.UpdateCheckResult.UpToDate -> {
                        checkUpdateBtn.text = "Swonal v${res.currentVersion} is up to date ✓"
                    }
                    is com.swonal.kiosk.net.UpdateCheckResult.Error -> {
                        checkUpdateBtn.text = "Check for App Updates (v${com.swonal.kiosk.BuildConfig.VERSION_NAME})"
                        Toast.makeText(requireContext(), "Update check failed: ${res.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(8) }
        }
        content.addView(checkUpdateBtn)

        content.addView(makePillButton("Send Bug Report (Discord)") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("bug_report") == null) {
                BugReportDialog().show(fm, "bug_report")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(8) }
        })

        // Link to Application Settings & Standalone/Server Sync Settings
        content.addView(makePillButton("Application & AI Coach Settings →") {
            dismiss()
            openSafeHttpUrl("$baseLocalWeb/settings", "Application Settings")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(8) }
        })
        content.addView(makePillButton("Standalone & Home Server Sync →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("server_sync") == null) {
                ServerSyncSettingsDialog().show(fm, "server_sync")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        })

        // Developer-Mode-Only Section: Server & Local Fail-Safe Endpoints
        val devSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (ui.developerMode) View.VISIBLE else View.GONE
        }
        devSection.addView(makeDivider())
        devSection.addView(makeSectionLabel("Developer: Server & Fail-Safe Endpoints"))

        val serverUrlInput = makeInput("Server URL (http://192.168.1.100:3000)", ui.serverUrl).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) }
        }
        val daemonUrlInput = makeInput("Local Loopback Daemon (http://127.0.0.1:8765)", ui.localDaemonUrl).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) }
        }
        val hasSavedToken = !app.prefs.apiToken.isNullOrBlank()
        val tokenInput = makeInput(
            if (hasSavedToken) "API Token saved (leave blank to keep)" else "Optional API Bearer Token",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(8) }
        }
        devSection.addView(serverUrlInput)
        devSection.addView(daemonUrlInput)
        devSection.addView(tokenInput)

        val saveRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        saveRow.addView(makeAccentButton("Save & Reconnect") {
            val rawDaemon = daemonUrlInput.text.toString().trim()
            val normalizedDaemon = SwonalPreferences.normalizeLoopbackDaemonUrl(rawDaemon)
            if (rawDaemon.isNotEmpty() && !normalizedDaemon.equals(SwonalPreferences.normalizeBaseUrl(rawDaemon, SwonalPreferences.DEFAULT_DAEMON_URL), ignoreCase = true)) {
                Toast.makeText(requireContext(), "Local daemon must be loopback (127.0.0.1 / localhost)", Toast.LENGTH_SHORT).show()
                daemonUrlInput.setText(SwonalPreferences.DEFAULT_DAEMON_URL)
                return@makeAccentButton
            }
            repo.updateConnectionEndpoints(
                newServerUrl = serverUrlInput.text.toString(),
                newLocalDaemonUrl = normalizedDaemon,
                newApiToken = tokenInput.text.toString().trim().ifEmpty { null }
            )
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        })
        if (hasSavedToken) {
            saveRow.addView(makePillButton("Clear Token") {
                repo.updateConnectionEndpoints(
                    newServerUrl = serverUrlInput.text.toString(),
                    newLocalDaemonUrl = daemonUrlInput.text.toString(),
                    clearToken = true
                )
                Toast.makeText(requireContext(), "Saved API token cleared", Toast.LENGTH_SHORT).show()
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                    marginStart = dp(8)
                }
            })
        }
        devSection.addView(saveRow)

        devSection.addView(makePillButton("Disable Developer Mode") {
            repo.setDeveloperMode(false)
            Toast.makeText(requireContext(), "Developer Mode disabled", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        })
        content.addView(devSection)

        root.addView(wrapScrollable(content, 540))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.uiStateFlow.collect { st ->
                    mockSection.visibility = if (st.isMockMode) View.VISIBLE else View.GONE
                    devSection.visibility = if (st.developerMode) View.VISIBLE else View.GONE

                    val cal = st.calibrationStatus
                    val ha = st.haStatus
                    val lastSession = st.recentSessions.firstOrNull()
                    val unit = st.activeUnit
                    statusBox.text = buildString {
                        append("Mode:         ").append(if (st.syncMode == com.swonal.kiosk.net.SyncMode.STANDALONE) "STANDALONE (OFFLINE-FIRST)" else "HOME / CLOUD SYNC").append('\n')
                        append("Local Web:    ").append(st.networkInfo?.ips?.firstOrNull()?.let { "http://$it:${st.networkInfo?.port ?: 3000}" } ?: st.localWebServerUrl).append('\n')
                        append("Server Sync:  ").append(if (st.syncMode == com.swonal.kiosk.net.SyncMode.STANDALONE) "OFF (LOCAL ONLY)" else if (st.syncConnected || st.wsConnected) "CONNECTED (${st.pendingSyncCount} pending)" else "OFFLINE (${st.pendingSyncCount} pending)").append('\n')
                        append("Daemon Link:  ").append(if (st.daemonConnected) (if (st.isMockMode) "MOCK 30Hz" else "TONAL CDC 30Hz") else "OFFLINE").append('\n')
                        append("Fail-Safe:    ").append(if (st.localFailSafeConnected) "LOCAL 127.0.0.1 READY (<5ms)" else "STANDBY").append('\n')
                        if (lastSession != null) {
                            val vol = Units.formatCompactWeight(lastSession.totalVolume, unit, exact = true)
                            append("Last Session: ").append(vol).append(' ').append(unit)
                                .append(" (").append(st.recentSessions.size).append(" logged)\n")
                        }
                        if (cal != null) {
                            append("Calibration:  L=").append(cal.zeroOffsets.left)
                                .append(" R=").append(cal.zeroOffsets.right)
                                .append(" (").append(cal.state).append(")\n")
                        }
                        if (ha != null) {
                            append("MQTT/HA:      ")
                                .append(if (ha.mqttConnected) "CONNECTED (${ha.mqttTopicPrefix})" else if (ha.mqttConfigured) "ERROR" else "UNCONFIGURED")
                                .append(" • Webhook: ").append(if (ha.webhookConfigured) "ON" else "OFF")
                        }
                    }
                }
            }
        }

        return root
    }
}
