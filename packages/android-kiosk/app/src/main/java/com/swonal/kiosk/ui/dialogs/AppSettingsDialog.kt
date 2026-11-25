package com.swonal.kiosk.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.server.EmbeddedSwonalServer
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch

/**
 * Full-page native Kiosk Application Settings screen (replaces GeckoView /settings on-device).
 * Mirrors the spacious multi-tab layout of SettingsView.tsx:
 *   - Profiles (switch/add/remove household lifters, accent swatches, Swocial shortcut)
 *   - AI Coach (Local Offline or BYOK OpenAI / Anthropic / Gemini / Ollama)
 *   - Network & TLS (Embedded :53013 HTTP, opt-in :53443 HTTPS + /api/tls/ca.crt, Home Server Sync)
 *   - System (App Version, Hardware Admin, Bug Report, Stock Tonal Handoff)
 */
class AppSettingsDialog : BaseSwonalDialog() {

    override val isFullScreen: Boolean = true

    private var activeTab = 0 // 0 = Profiles, 1 = AI Coach, 2 = Network & TLS, 3 = System
    private lateinit var tabBar: LinearLayout
    private lateinit var bodyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val p = palette
        val d = resources.displayMetrics.density
        val root = createFullScreenRoot()

        val versionBadge = TextView(requireContext()).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 13f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
            background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(createFullScreenHeader("Application Settings", "Household, AI Coach, Network & System", versionBadge))

        tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.bgDialog)
            setPadding(dp(24), dp(12), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(tabBar)
        root.addView(View(requireContext()).apply {
            setBackgroundColor(p.borderSubtle)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
        })

        val (scroll, body) = createFullScreenScrollBody()
        bodyContainer = body
        root.addView(scroll)

        renderTabs()
        renderBody()
        return root
    }

    private fun renderTabs() {
        val p = palette
        val d = resources.displayMetrics.density
        val labels = listOf("Profiles", "AI Coach", "Network & TLS", "System")
        tabBar.removeAllViews()
        labels.forEachIndexed { idx, label ->
            val isSel = activeTab == idx
            tabBar.addView(makePillButton(label, active = isSel) {
                activeTab = idx
                renderTabs()
                renderBody()
            }.apply {
                textSize = 14f
                background = if (isSel) p.accentPillDrawable(d, 12f) else p.elevatedPillDrawable(d, 12f)
                setTextColor(if (isSel) p.accentTextColor else p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (idx < labels.lastIndex) marginEnd = dp(10)
                }
            })
        }
    }

    private fun renderBody() {
        bodyContainer.removeAllViews()
        when (activeTab) {
            0 -> renderProfilesTab()
            1 -> renderAiCoachTab()
            2 -> renderNetworkTlsTab()
            else -> renderSystemTab()
        }
    }

    private fun renderProfilesTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value

        val rosterCard = createSurfaceCard()
        rosterCard.addView(makeCardHeading("Household Lifters", "Tap any lifter to switch active profile"))

        ui.users.forEach { u ->
            val isActive = u.id == ui.activeUser?.id
            val userColor = runCatching { Color.parseColor(u.accentColor) }.getOrDefault(p.accentPrimary)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isActive) p.accentMutedDrawable(d, 16f, 1.5f) else p.elevatedPillDrawable(d, 16f)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            row.addView(View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(userColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(14) }
            })
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
                    text = if (isActive) "${u.name}  •  Active" else u.name
                    textSize = 16.5f
                    typeface = SwonalFonts.interExtraBold(requireContext())
                    setTextColor(if (isActive) p.accentInk else p.textPrimary)
                })
                addView(TextView(requireContext()).apply {
                    text = "${u.weightUnit}  •  ${u.themeMode} theme  •  ${u.accentColor}"
                    textSize = 13f
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
                    textSize = 13f
                    setTextColor(p.criticalText)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                })
            }
            rosterCard.addView(row)
        }
        bodyContainer.addView(rosterCard)

        val addCard = createSurfaceCard()
        addCard.addView(makeCardHeading("Add Household Lifter", "Create a profile with custom accent theme"))
        val nameInput = makeInput("Lifter name").apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(12)
            }
        }
        addCard.addView(nameInput)

        val colorInput = makeInput("#00F0FF", "#00F0FF").apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) }
        }
        val swatchRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        listOf("#1A5FB4", "#10B981", "#00F0FF", "#F59E0B", "#EC4899", "#A855F7").forEach { hex ->
            swatchRow.addView(View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                    setStroke(dp(2), p.borderSubtle)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { colorInput.setText(hex) }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            })
        }
        addCard.addView(swatchRow)

        val addRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(colorInput)
            addView(makeAccentButton("+ Add Profile") {
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
                textSize = 14.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50))
            })
        }
        addCard.addView(addRow)
        bodyContainer.addView(addCard)

        val swocialCard = createSurfaceCard()
        swocialCard.addView(makeCardHeading("Swocial Peer-to-Peer Friends", "Share your Swocial QR code & sync friend workouts via Nostr"))
        swocialCard.addView(makePillButton("Open Swocial Friends & QR Code →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("swocial") == null) {
                SwocialDialog().show(fm, "swocial")
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        })
        bodyContainer.addView(swocialCard)
    }

    private fun renderAiCoachTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val providers = listOf("local", "openai", "anthropic", "gemini", "ollama")
        var selectedProvider = "local"

        val coachCard = createSurfaceCard()
        coachCard.addView(makeCardHeading(
            "AI Voice Coach & BYOK LLM",
            "Works 100% offline with Local mode, or connect your own API key (encrypted with Android Keystore AES-256-GCM)"
        ))

        val providerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val modelInput = makeInput("Model (e.g. gpt-4o-mini, gemini-2.5-flash)", "").apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
        }
        val apiKeyInput = makeInput("API Key (leave blank to keep existing)", "").apply {
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
        }
        val baseUrlInput = makeInput("Custom Base URL (optional / Ollama)", "").apply {
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(16)
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
                    textSize = 13f
                    background = if (active) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
                    setTextColor(if (active) p.accentTextColor else p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (idx < providers.lastIndex) marginEnd = dp(8)
                    }
                })
            }
        }

        coachCard.addView(providerRow)
        coachCard.addView(modelInput)
        coachCard.addView(apiKeyInput)
        coachCard.addView(baseUrlInput)

        viewLifecycleOwner.lifecycleScope.launch {
            val cfg = runCatching { repo.localAiCoach.getPublicConfig() }.getOrNull()
            if (!isAdded) return@launch
            if (cfg != null) {
                selectedProvider = cfg.provider.lowercase().ifBlank { "local" }
                modelInput.setText(cfg.model)
                baseUrlInput.setText(cfg.baseUrl)
            }
            renderProviderPills()
        }

        coachCard.addView(makeAccentButton("Save AI Coach Settings") {
            val prov = selectedProvider
            val model = modelInput.text.toString().trim()
            val key = apiKeyInput.text.toString().trim().takeIf { it.isNotEmpty() }
            val baseUrl = baseUrlInput.text.toString().trim()
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    repo.localAiCoach.setConfig(
                        provider = prov,
                        apiKey = key,
                        model = model,
                        baseUrl = baseUrl
                    )
                }.onSuccess {
                    apiKeyInput.setText("")
                    Toast.makeText(requireContext(), "AI Coach saved ($prov)", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "Failed to save AI config: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        })
        bodyContainer.addView(coachCard)
    }

    private fun renderNetworkTlsTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val lanIp = EmbeddedSwonalServer.discoverLanIpv4s().firstOrNull()
        val port = app.prefs.embeddedPort
        val httpsPort = SwonalPreferences.DEFAULT_EMBEDDED_HTTPS_PORT

        val tlsCard = createSurfaceCard()
        tlsCard.addView(makeCardHeading(
            "Embedded LAN Companion & On-Device HTTPS",
            "Enable HTTPS (:53443) so mobile browsers allow Phone Companion microphone/voice commands without a reverse proxy"
        ))

        tlsCard.addView(TextView(requireContext()).apply {
            text = "Companion HTTP:  ${lanIp?.let { "http://$it:$port/companion" } ?: ui.localWebServerUrl}\n" +
                "Companion HTTPS: ${if (app.prefs.localHttpsEnabled) (lanIp?.let { "https://$it:$httpsPort/companion" } ?: "Enabled (:$httpsPort)") else "Off (opt-in)"}\n" +
                (if (app.prefs.localHttpsEnabled && lanIp != null) "Root CA Cert:    http://$lanIp:$port/api/tls/ca.crt\n" else "") +
                "Passcode Lock:   ${if (app.prefs.lastKnownAuthEnabled) "Enabled" else "Off"}"
            textSize = 14f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.textPrimary)
            setLineSpacing(dpf(4f), 1f)
            background = p.elevatedPillDrawable(d, 16f, interactive = false)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        })

        tlsCard.addView(makeAccentButton(
            if (app.prefs.localHttpsEnabled) "Disable On-Device HTTPS (:$httpsPort)" else "Enable On-Device HTTPS (:$httpsPort)"
        ) {
            val next = !app.prefs.localHttpsEnabled
            app.reconfigureLocalHttps(next)
            Toast.makeText(
                requireContext(),
                if (next) "Starting local HTTPS on :$httpsPort..." else "Local HTTPS disabled",
                Toast.LENGTH_SHORT
            ).show()
            renderBody()
        }.apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(12)
            }
        })

        if (app.prefs.localHttpsEnabled) {
            tlsCard.addView(TextView(requireContext()).apply {
                text = "One-time phone setup: Visit the Root CA Cert URL above on your phone to install Swonal Local Root CA. " +
                    "On iOS, also enable it under Settings → General → About → Certificate Trust Settings. Then open the Companion HTTPS URL."
                textSize = 13f
                setTextColor(p.textMuted)
                setLineSpacing(dpf(3f), 1f)
            })
        }
        bodyContainer.addView(tlsCard)

        val syncCard = createSurfaceCard()
        syncCard.addView(makeCardHeading(
            "Standalone & Home Server Sync",
            "Current Mode: ${ui.syncMode.label}"
        ))
        syncCard.addView(makePillButton("Configure Home / Cloud Sync Server →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("server_sync") == null) {
                ServerSyncSettingsDialog().show(fm, "server_sync")
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        })
        bodyContainer.addView(syncCard)
    }

    private fun renderSystemTab() {
        val p = palette
        val d = resources.displayMetrics.density

        val sysCard = createSurfaceCard()
        sysCard.addView(makeCardHeading("System & Diagnostics", "Swonal Kiosk v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"))

        sysCard.addView(makePillButton("Hardware Admin & Exercise Catalog →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("admin") == null) {
                AdminDialog().show(fm, "admin")
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
        })

        sysCard.addView(makePillButton("Hardware Diagnostics & Telemetry →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("diagnostics") == null) {
                DiagnosticsDialog().show(fm, "diagnostics")
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
        })

        sysCard.addView(makePillButton("Send PII-Scrubbed Bug Report →") {
            val fm = parentFragmentManager
            dismiss()
            if (!fm.isStateSaved && fm.findFragmentByTag("bug_report") == null) {
                BugReportDialog().show(fm, "bug_report")
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        })

        bodyContainer.addView(sysCard)

        val infoCard = createSurfaceCard()
        infoCard.addView(makeCardHeading("On-Device Offline Voice (Vosk)"))
        infoCard.addView(TextView(requireContext()).apply {
            text = "Use the Voice button beside Device Volume on the main Kiosk screen for 100% offline speech-to-text over Android AudioRecord (no Google Play Services required)."
            textSize = 14f
            setTextColor(p.textMuted)
            setLineSpacing(dpf(3f), 1f)
            background = p.elevatedPillDrawable(d, 14f, interactive = false)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        bodyContainer.addView(infoCard)
    }
}
