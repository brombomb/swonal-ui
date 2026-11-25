package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.server.EmbeddedSwonalServer
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch

/**
 * Native Kiosk Application Settings screen (replaces GeckoView /settings on-device):
 * - Profiles: Switch/Add/Delete household lifters, toggle Units/Theme/Demo Video
 * - AI Coach: Configure Local Offline or BYOK (OpenAI / Anthropic / Gemini / Ollama)
 * - Sync & Network: Embedded :53013 LAN info, Home/Cloud Sync settings, Bug Reports, App Version
 */
class AppSettingsDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 640

    private var activeTab = 0 // 0 = Profiles, 1 = AI Coach, 2 = Sync & System
    private lateinit var tabBar: LinearLayout
    private lateinit var bodyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = createCardRoot(20)
        root.addView(createDialogHeader("Application Settings"))

        tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(tabBar)

        bodyContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(480)
            )
            addView(bodyContainer)
        })

        renderTabs()
        renderBody()
        return root
    }

    private fun renderTabs() {
        val p = palette
        val d = resources.displayMetrics.density
        val labels = listOf("Profiles", "AI Coach", "Sync & System")
        tabBar.removeAllViews()
        labels.forEachIndexed { idx, label ->
            val isSel = activeTab == idx
            tabBar.addView(makePillButton(label, active = isSel) {
                activeTab = idx
                renderTabs()
                renderBody()
            }.apply {
                textSize = 12f
                background = if (isSel) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
                setTextColor(if (isSel) p.accentTextColor else p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx < labels.lastIndex) marginEnd = dp(6)
                }
            })
        }
    }

    private fun renderBody() {
        bodyContainer.removeAllViews()
        when (activeTab) {
            0 -> renderProfilesTab()
            1 -> renderAiCoachTab()
            else -> renderSyncSystemTab()
        }
    }

    private fun renderProfilesTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value

        bodyContainer.addView(makeSectionLabel("Household Lifters"))
        ui.users.forEach { u ->
            val isActive = u.id == ui.activeUser?.id
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isActive) p.accentMutedDrawable(d, 12f, 1.5f) else p.elevatedPillDrawable(d, 12f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val info = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    repo.switchUser(u.id)
                    renderBody()
                }
                addView(TextView(requireContext()).apply {
                    text = if (isActive) "${u.name} (Active)" else u.name
                    textSize = 13.5f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(if (isActive) p.accentInk else p.textPrimary)
                })
                addView(TextView(requireContext()).apply {
                    text = "${u.weightUnit} • ${u.themeMode} • ${u.accentColor}"
                    textSize = 11f
                    setTextColor(p.textMuted)
                })
            }
            row.addView(info)

            if (ui.users.size > 1) {
                row.addView(makePillButton("Remove") {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { repo.localEngine.deleteUser(u.id) }.onSuccess {
                            repo.refreshAppState()
                            repo.notifyDirtySync()
                            renderBody()
                        }.onFailure { err ->
                            Toast.makeText(requireContext(), err.message ?: "Could not remove lifter", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.apply {
                    textSize = 11f
                    setTextColor(p.criticalText)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
                })
            }
            bodyContainer.addView(row)
        }

        bodyContainer.addView(makeDivider())
        bodyContainer.addView(makeSectionLabel("Add Household Lifter"))
        val nameInput = makeInput("Lifter name").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(8)
            }
        }
        bodyContainer.addView(nameInput)
        val addRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val colorInput = makeInput("#00F0FF", "#00F0FF").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }
        addRow.addView(colorInput)
        addRow.addView(makeAccentButton("+ Add Profile") {
            val name = nameInput.text.toString().trim()
            val hex = colorInput.text.toString().trim().let {
                if (it.matches(Regex("^#[0-9A-Fa-f]{6}$"))) it.uppercase() else "#00F0FF"
            }
            if (name.isNotEmpty()) {
                repo.createHouseholdUser(name, hex)
                nameInput.setText("")
                Toast.makeText(requireContext(), "Added profile $name", Toast.LENGTH_SHORT).show()
                bodyContainer.postDelayed({ if (isAdded) renderBody() }, 250L)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
        })
        bodyContainer.addView(addRow)

        bodyContainer.addView(makeDivider())
        bodyContainer.addView(makePillButton("Open Swocial Friends & QR Code →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("swocial") == null) {
                SwocialDialog().show(fm, "swocial")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })
    }

    private fun renderAiCoachTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val providers = listOf("local", "openai", "anthropic", "gemini", "ollama")
        var selectedProvider = "local"

        bodyContainer.addView(makeSectionLabel("AI Voice Coach Provider"))
        val providerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val modelInput = makeInput("Model (e.g. gpt-4o-mini, gemini-2.5-flash)", "").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(8)
            }
        }
        val apiKeyInput = makeInput("API Key (leave blank to keep existing)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(8)
            }
        }
        val baseUrlInput = makeInput("Custom Base URL (optional / Ollama)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(10)
            }
        }

        fun renderProviderPills() {
            providerRow.removeAllViews()
            providers.forEachIndexed { idx, prov ->
                val active = prov == selectedProvider
                providerRow.addView(makePillButton(prov.uppercase(), active = active) {
                    selectedProvider = prov
                    renderProviderPills()
                }.apply {
                    textSize = 10.5f
                    background = if (active) p.accentPillDrawable(d, 8f) else p.elevatedPillDrawable(d, 8f)
                    setTextColor(if (active) p.accentTextColor else p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        if (idx < providers.lastIndex) marginEnd = dp(4)
                    }
                })
            }
        }

        bodyContainer.addView(providerRow)
        bodyContainer.addView(modelInput)
        bodyContainer.addView(apiKeyInput)
        bodyContainer.addView(baseUrlInput)

        viewLifecycleOwner.lifecycleScope.launch {
            val cfg = runCatching { repo.localAiCoach.getPublicConfig() }.getOrNull()
            if (cfg != null && isAdded) {
                selectedProvider = cfg.provider
                modelInput.setText(cfg.model)
                baseUrlInput.setText(cfg.baseUrl.orEmpty())
                renderProviderPills()
            } else {
                renderProviderPills()
            }
        }

        bodyContainer.addView(makeAccentButton("Save AI Coach Config") {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    repo.localAiCoach.setConfig(
                        provider = selectedProvider,
                        model = modelInput.text.toString().trim(),
                        apiKey = apiKeyInput.text.toString().trim().ifEmpty { null },
                        baseUrl = baseUrlInput.text.toString().trim().ifEmpty { null }
                    )
                }.onSuccess {
                    apiKeyInput.setText("")
                    Toast.makeText(requireContext(), "AI Coach saved ($selectedProvider)", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), err.message ?: "Failed to save config", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(10)
            }
        })

        bodyContainer.addView(TextView(requireContext()).apply {
            text = "For full MCP JSON configs & Spotify/Home Assistant integrations, open Companion Settings from your phone or tap below."
            textSize = 11.5f
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })
        bodyContainer.addView(makePillButton("Open Full Web Settings (Spotify / HA / MCP QR)") {
            val url = "${repo.uiStateFlow.value.localWebServerUrl.trimEnd('/')}/settings"
            dismiss()
            openSafeHttpUrl(url, "Application Settings")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })
    }

    private fun renderSyncSystemTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val app = requireActivity().application as SwonalApp
        val lanIp = EmbeddedSwonalServer.discoverLanIpv4s().firstOrNull()
        val port = repo.embeddedServerPort.takeIf { it > 0 } ?: 53013

        bodyContainer.addView(makeSectionLabel("On-Device Companion & Sync"))
        bodyContainer.addView(TextView(requireContext()).apply {
            text = "Companion URL:  ${lanIp?.let { "http://$it:$port/companion" } ?: ui.localWebServerUrl}\n" +
                "Sync Mode:      ${ui.syncMode.label}\n" +
                "App Version:    v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n" +
                "Passcode Lock:  ${if (app.prefs.lastKnownAuthEnabled) "Enabled" else "Off"}"
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.textPrimary)
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        bodyContainer.addView(makePillButton("Standalone & Home Server Sync Settings →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("server_sync") == null) {
                ServerSyncSettingsDialog().show(fm, "server_sync")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(8)
            }
        })

        bodyContainer.addView(makePillButton("Hardware & Diagnostics →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("diagnostics") == null) {
                DiagnosticsDialog().show(fm, "diagnostics")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(8)
            }
        })

        bodyContainer.addView(makePillButton("Send Bug Report (Discord)") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("bug_report") == null) {
                BugReportDialog().show(fm, "bug_report")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })
    }
}
