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
import com.swonal.kiosk.net.SyncMode
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.launch

/**
 * Native Settings modal for choosing:
 * - Standalone (Offline-First on-device SQLite + :3000 LAN Companion)
 * - Home / Cloud Server Sync (Optional always-on server, multi-device sync, 24/7 MCP queue & HA MQTT)
 */
class ServerSyncSettingsDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 520
    private var selectedMode: SyncMode = SyncMode.STANDALONE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        selectedMode = ui.syncMode
        val p = palette
        val d = resources.displayMetrics.density

        val root = createCardRoot(18)
        root.addView(createDialogHeader("Standalone & Server Sync"))

        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        val statusCard = TextView(requireContext()).apply {
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        body.addView(statusCard)

        body.addView(makeSectionLabel("Operating Mode"))
        val modeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        lateinit var btnStandalone: TextView
        lateinit var btnServerSync: TextView
        lateinit var syncFieldsBox: LinearLayout

        fun refreshModeButtons() {
            val stand = selectedMode == SyncMode.STANDALONE
            btnStandalone.background = if (stand) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
            btnStandalone.setTextColor(if (stand) p.accentTextColor else p.textPrimary)
            btnServerSync.background = if (!stand) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
            btnServerSync.setTextColor(if (!stand) p.accentTextColor else p.textPrimary)
            syncFieldsBox.visibility = if (stand) View.GONE else View.VISIBLE
        }

        btnStandalone = makePillButton("Standalone (Offline-First)", active = selectedMode == SyncMode.STANDALONE) {
            selectedMode = SyncMode.STANDALONE
            refreshModeButtons()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        btnServerSync = makePillButton("Home / Cloud Sync", active = selectedMode == SyncMode.SERVER_SYNC) {
            selectedMode = SyncMode.SERVER_SYNC
            refreshModeButtons()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        modeRow.addView(btnStandalone)
        modeRow.addView(btnServerSync)
        body.addView(modeRow)

        syncFieldsBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedMode == SyncMode.SERVER_SYNC) View.VISIBLE else View.GONE
        }
        syncFieldsBox.addView(makeSectionLabel("Optional Home / Cloud Server"))
        val serverUrlInput = makeInput("Sync Server URL (https://swonal.lan or http://192.168.1.50:3000)", ui.syncServerUrl).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(6)
            }
        }
        val hasSavedToken = !app.prefs.apiToken.isNullOrBlank()
        val tokenInput = makeInput(
            if (hasSavedToken) "API Bearer Token saved (leave blank to keep)" else "Optional API Bearer Token",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(8)
            }
        }
        syncFieldsBox.addView(serverUrlInput)
        syncFieldsBox.addView(tokenInput)

        val syncActionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        syncActionRow.addView(makePillButton("Sync Now") {
            viewLifecycleOwner.lifecycleScope.launch {
                val res = repo.triggerSyncNow()
                res.onSuccess {
                    Toast.makeText(requireContext(), "Synced cleanly with Home Server", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), err.message ?: "Sync failed (saved offline)", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        syncActionRow.addView(makePillButton("Pull / Restore from Server") {
            viewLifecycleOwner.lifecycleScope.launch {
                val res = repo.restoreFromSyncServer()
                res.onSuccess {
                    Toast.makeText(requireContext(), "Restored routines & history from server", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), err.message ?: "Restore failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.3f)
        })
        syncFieldsBox.addView(syncActionRow)
        body.addView(syncFieldsBox)

        body.addView(makeSectionLabel("Local Motor Daemon (Loopback Only)"))
        val daemonInput = makeInput("http://127.0.0.1:8765", ui.localDaemonUrl).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(10)
            }
        }
        body.addView(daemonInput)

        val saveRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        saveRow.addView(makeAccentButton("Save Mode & Reconnect") {
            val rawDaemon = daemonInput.text.toString().trim()
            val normalizedDaemon = SwonalPreferences.normalizeLoopbackDaemonUrl(rawDaemon)
            val syncUrl = serverUrlInput.text.toString().trim()
            if (selectedMode == SyncMode.SERVER_SYNC && syncUrl.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a Home/Cloud Server URL or choose Standalone", Toast.LENGTH_SHORT).show()
                return@makeAccentButton
            }
            repo.updateSyncConfiguration(
                newSyncMode = selectedMode,
                newSyncServerUrl = if (selectedMode == SyncMode.SERVER_SYNC) syncUrl else "",
                newLocalDaemonUrl = normalizedDaemon,
                newApiToken = tokenInput.text.toString().trim().ifEmpty { null }
            )
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        })
        if (hasSavedToken) {
            saveRow.addView(makePillButton("Clear Token") {
                repo.updateSyncConfiguration(
                    newSyncMode = selectedMode,
                    newSyncServerUrl = serverUrlInput.text.toString().trim(),
                    newLocalDaemonUrl = daemonInput.text.toString().trim(),
                    clearToken = true
                )
                Toast.makeText(requireContext(), "Cleared saved token", Toast.LENGTH_SHORT).show()
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                    marginStart = dp(8)
                }
            })
        }
        body.addView(saveRow)

        root.addView(wrapScrollable(body, 520))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.uiStateFlow.collect { st ->
                    val lanPrimary = st.networkInfo?.ips?.firstOrNull()
                    val lastSync = st.lastSyncedAtMs?.let { IsoTime.formatUtc(it) } ?: "Never"
                    statusCard.text = buildString {
                        append("Mode:         ").append(if (st.syncMode == SyncMode.STANDALONE) "STANDALONE (SERVERLESS)" else "HOME / CLOUD SYNC").append('\n')
                        append("Local Web:    ").append(lanPrimary?.let { "http://$it:${repo.embeddedServerPort.takeIf { p -> p > 0 } ?: 53013}" } ?: st.localWebServerUrl).append('\n')
                        append("Sync Link:    ").append(
                            when {
                                st.syncMode == SyncMode.STANDALONE -> "NOT NEEDED (100% LOCAL)"
                                st.syncConnected -> "CONNECTED (${st.syncServerUrl})"
                                else -> "OFFLINE — LOCAL FALLBACK ACTIVE"
                            }
                        ).append('\n')
                        append("Pending Push: ").append(st.pendingSyncCount).append(" changes\n")
                        append("Last Synced:  ").append(lastSync)
                        if (!st.syncError.isNullOrBlank()) {
                            append("\nLast Error:   ").append(st.syncError)
                        }
                    }
                }
            }
        }

        return root
    }
}
