package com.swonal.kiosk.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.net.AppUpdateManager
import com.swonal.kiosk.net.BugReportContext
import com.swonal.kiosk.net.BugReportSender
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.net.SyncMode
import com.swonal.kiosk.net.UpdateCheckResult
import com.swonal.kiosk.server.EmbeddedSwonalServer
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalThemeTokens
import com.swonal.kiosk.ui.PressFeedback
import com.swonal.kiosk.util.IsoTime
import kotlinx.coroutines.launch

/**
 * Full-page native Kiosk Application Settings & Hardware Admin screen
 * (replaces GeckoView /settings and /admin on-device with zero nested modal jumps):
 *   - Tab 0: Profiles (Active lifter Units, Theme, Form Demo Video, VBT & Symmetry, Accent swatches, Household roster)
 *   - Tab 1: AI Coach (Local Offline or BYOK OpenAI / Anthropic / Gemini / Ollama + Vosk info)
 *   - Tab 2: Network & TLS (Embedded :53013 HTTP, opt-in :53443 HTTPS + /api/tls/ca.crt)
 *   - Tab 3: Admin & System (Latest main release check, embedded Hardware Diagnostics & Motor Controls,
 *            embedded Cloud & Home Server Sync, embedded Exercise Catalog, embedded Send Bug Report)
 */
open class AppSettingsDialog : BaseSwonalDialog() {

    override val isFullScreen: Boolean = true

    protected open val defaultInitialTab: Int = 0
    private var activeTab = 0
    private var selectedMuscle: String = "All"

    private lateinit var tabBar: LinearLayout
    private lateinit var bodyContainer: LinearLayout

    // Live-updating text views for embedded diagnostics & sync on Tab 3
    private var liveDiagStatusTv: TextView? = null
    private var liveSyncStatusTv: TextView? = null
    private var latestVersionTv: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = arguments?.getInt(ARG_INITIAL_TAB, defaultInitialTab) ?: defaultInitialTab
    }

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
        root.addView(createFullScreenHeader(
            "Application Settings",
            "Profiles, AI Coach, Network & TLS, Hardware Admin & System",
            versionBadge
        ))

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
        observeLiveAdminTelemetry()
        return root
    }

    private fun observeLiveAdminTelemetry() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.uiStateFlow.collect { st ->
                        updateLiveSyncCard(st)
                        updateLiveDiagCard(st)
                    }
                }
            }
        }
    }

    private fun updateLiveDiagCard(st: com.swonal.kiosk.net.KioskUiState) {
        val tv = liveDiagStatusTv ?: return
        val t = repo.telemetryFlow.value
        val hwStatus = if (st.daemonConnected) {
            if (st.isMockMode) "CONNECTED (MOCK MODE)" else "CONNECTED (TONAL CDC 30Hz)"
        } else {
            "DISCONNECTED"
        }
        tv.text = buildString {
            append("Motor Daemon:  ").append(hwStatus).append('\n')
            append("Daemon URL:    ").append(st.localDaemonUrl).append('\n')
            append("Weight State:  ").append(if (t.weightEnabled) "ENGAGED (${t.targetWeightLb.toInt()} lb)" else "OFF (Target ${t.targetWeightLb.toInt()} lb)").append('\n')
            append("Cable Load:    L ${String.format("%.1f", t.leftCable.loadLb)} lb  •  R ${String.format("%.1f", t.rightCable.loadLb)} lb\n")
            append("Arm Position:  L(H${t.armPositions.height.left} R${t.armPositions.rotation.left} A${t.armPositions.angle.left})  •  R(H${t.armPositions.height.right} R${t.armPositions.rotation.right} A${t.armPositions.angle.right})\n")
            append("Active Mode:   ").append(t.profileMode.uppercase()).append("  •  Reps: ").append(t.repCount)
        }
    }

    private fun updateLiveSyncCard(st: com.swonal.kiosk.net.KioskUiState) {
        val tv = liveSyncStatusTv ?: return
        val lanPrimary = st.networkInfo?.ips?.firstOrNull()
        val lastSync = st.lastSyncedAtMs?.let { IsoTime.formatUtc(it) } ?: "Never"
        tv.text = buildString {
            append("Mode:         ").append(if (st.syncMode == SyncMode.STANDALONE) "STANDALONE (OFFLINE-FIRST)" else "HOME / CLOUD SYNC").append('\n')
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

    private fun renderTabs() {
        val p = palette
        val d = resources.displayMetrics.density
        val labels = listOf("Profiles", "AI Coach", "Network & TLS", "Admin & System")
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
        liveDiagStatusTv = null
        liveSyncStatusTv = null
        latestVersionTv = null
        bodyContainer.removeAllViews()
        when (activeTab) {
            0 -> renderProfilesTab()
            1 -> renderAiCoachTab()
            2 -> renderNetworkTlsTab()
            else -> renderAdminSystemTab()
        }
    }

    // -------------------------------------------------------------------------
    // TAB 0: PROFILES (All Personal Prefs: Units, Theme, Form Demo, VBT, Accent + Roster)
    // -------------------------------------------------------------------------

    private fun renderProfilesTab() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val activeUser = ui.activeUser
        val currentHex = (activeUser?.accentColor ?: "#00F0FF").uppercase()

        // 1. Personal Preferences Card for Active Profile
        val prefsCard = createSurfaceCard()
        prefsCard.addView(makeCardHeading(
            "Personal Preferences — ${activeUser?.name ?: "Active Lifter"}",
            "Units, theme, form demo video, VBT/symmetry telemetry, and accent color"
        ))

        // Toggle rows: Theme, Units, Form Demo Video, VBT & Symmetry
        var localDark = p.isDark
        var localUnit = ui.activeUnit
        var localDemoPip = ui.showDemoPip
        var localTelemetry = ui.telemetryDisplayMode

        val (themeRow, themeValTv) = makeLargeToggleRow("Theme", if (localDark) "Dark" else "Light")
        themeRow.setOnClickListener {
            localDark = !localDark
            themeValTv.text = if (localDark) "Dark" else "Light"
            repo.toggleTheme()
            bodyContainer.postDelayed({ if (isAdded) renderBody() }, 120L)
        }
        prefsCard.addView(themeRow)

        val (unitRow, unitValTv) = makeLargeToggleRow("Weight Units", localUnit)
        unitRow.setOnClickListener {
            localUnit = if (localUnit.equals("KG", ignoreCase = true)) "LB" else "KG"
            unitValTv.text = localUnit
            repo.toggleUnit()
            bodyContainer.postDelayed({ if (isAdded) renderBody() }, 120L)
        }
        prefsCard.addView(unitRow)

        val (demoRow, demoValTv) = makeLargeToggleRow("Form Demo Video (PiP)", if (localDemoPip) "Shown" else "Hidden")
        demoRow.setOnClickListener {
            localDemoPip = !localDemoPip
            demoValTv.text = if (localDemoPip) "Shown" else "Hidden"
            repo.setShowDemoPip(localDemoPip)
        }
        prefsCard.addView(demoRow)

        val (vbtRow, vbtValTv) = makeLargeToggleRow("VBT & Symmetry Telemetry", localTelemetry.label)
        vbtRow.setOnClickListener {
            localTelemetry = localTelemetry.next()
            vbtValTv.text = localTelemetry.label
            repo.cycleTelemetryDisplayMode()
        }
        prefsCard.addView(vbtRow)

        // Accent Color Swatches
        prefsCard.addView(makeSectionLabel("Accent Color"))
        SwonalThemeTokens.ACCENT_SWATCHES.chunked(4).forEach { rowSwatches ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            rowSwatches.forEach { swatch ->
                val colorInt = ColorMath.parseHexColor(swatch.hex)
                val isSelected = currentHex.equals(swatch.hex, ignoreCase = true)
                val item = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        repo.updateUserAccent(swatch.hex)
                        bodyContainer.postDelayed({ if (isAdded) renderBody() }, 120L)
                    }
                    addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                        background = p.circleSwatchDrawable(colorInt, d, isSelected)
                    })
                    addView(TextView(requireContext()).apply {
                        text = swatch.name.substringBefore(" ")
                        textSize = 12f
                        setTextColor(p.textMuted)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(5) }
                    })
                }
                row.addView(item)
            }
            prefsCard.addView(row)
        }

        val hexRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hexInput = makeInput("#00F0FF", currentHex).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) }
        }
        hexRow.addView(hexInput)
        hexRow.addView(makePillButton("Apply Hex") {
            val raw = hexInput.text.toString().trim()
            val normalized = if (raw.startsWith("#")) raw else "#$raw"
            if (normalized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                repo.updateUserAccent(normalized.uppercase())
                bodyContainer.postDelayed({ if (isAdded) renderBody() }, 120L)
            } else {
                Toast.makeText(requireContext(), "Enter a valid 6-digit hex like #1A5FB4", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        })
        prefsCard.addView(hexRow)
        bodyContainer.addView(prefsCard)

        // 2. Household Lifters Card
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
                    bodyContainer.postDelayed({ if (isAdded) renderBody() }, 120L)
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

        // 3. Add Household Lifter Card
        val addCard = createSurfaceCard()
        addCard.addView(makeCardHeading("Add Household Lifter", "Create a new profile with custom accent theme"))
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
    }

    private fun makeLargeToggleRow(label: String, initialValue: String): Pair<LinearLayout, TextView> {
        val p = palette
        val d = resources.displayMetrics.density
        val valueTv = TextView(requireContext()).apply {
            text = initialValue
            textSize = 14.5f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
            background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = p.elevatedPillDrawable(d, 14f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            isClickable = true
            isFocusable = true
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 15.5f
                typeface = SwonalFonts.interBold(requireContext())
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueTv)
        }.also { PressFeedback.attachSingle(it) }
        return row to valueTv
    }

    // -------------------------------------------------------------------------
    // TAB 1: AI COACH
    // -------------------------------------------------------------------------

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

        val voiceInfoCard = createSurfaceCard()
        voiceInfoCard.addView(makeCardHeading("On-Device Offline Voice (Vosk)"))
        voiceInfoCard.addView(TextView(requireContext()).apply {
            text = "Tap the Voice button beside Device Volume on the main Kiosk screen for 100% offline speech-to-text over Android AudioRecord (no Google Play Services required). Commands like \"add 5 pounds\", \"weight off\", or \"next exercise\" execute locally."
            textSize = 14f
            setTextColor(p.textMuted)
            setLineSpacing(dpf(3f), 1f)
            background = p.elevatedPillDrawable(d, 14f, interactive = false)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        bodyContainer.addView(voiceInfoCard)
    }

    // -------------------------------------------------------------------------
    // TAB 2: NETWORK & TLS
    // -------------------------------------------------------------------------

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
    }

    // -------------------------------------------------------------------------
    // TAB 3: ADMIN & SYSTEM (ALL EMBEDDED INLINE — NO MODAL JUMPS!)
    //   1. App Version & Latest GitHub Release (main)
    //   2. Embedded Hardware Diagnostics & Motor Controls
    //   3. Embedded Cloud & Home Server Sync
    //   4. Embedded Exercise Catalog
    //   5. Embedded Send Bug Report
    // -------------------------------------------------------------------------

    private fun renderAdminSystemTab() {
        repo.refreshDiagnostics()
        renderVersionUpdateCard()
        renderEmbeddedHardwareDiagnosticsCard()
        renderEmbeddedCloudSyncCard()
        renderEmbeddedExerciseCatalogCard()
        renderEmbeddedBugReportCard()
    }

    private fun renderVersionUpdateCard() {
        val p = palette
        val d = resources.displayMetrics.density
        val card = createSurfaceCard()
        card.addView(makeCardHeading(
            "App Version & Latest Release (main)",
            "Check GitHub Releases for the latest signed Swonal Kiosk build"
        ))

        val statusTv = TextView(requireContext()).apply {
            text = "Installed:      v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nLatest on main: Checking GitHub Releases..."
            textSize = 14f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.textPrimary)
            setLineSpacing(dpf(4f), 1f)
            background = p.elevatedPillDrawable(d, 16f, interactive = false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        latestVersionTv = statusTv
        card.addView(statusTv)

        var latestInfo: com.swonal.kiosk.net.AppUpdateInfo? = null
        lateinit var checkBtn: TextView
        fun runVersionCheck(openIfAvailable: Boolean) {
            statusTv.text = "Installed:      v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nLatest on main: Checking..."
            viewLifecycleOwner.lifecycleScope.launch {
                val res = AppUpdateManager.checkForUpdate()
                if (!isAdded) return@launch
                when (res) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        latestInfo = res.info
                        statusTv.text = "Installed:      v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nLatest on main: v${res.info.versionName} (UPDATE AVAILABLE)"
                        checkBtn.text = "Install Update v${res.info.versionName}"
                        if (openIfAvailable) {
                            val fm = parentFragmentManager
                            if (!fm.isStateSaved && fm.findFragmentByTag("app_update") == null) {
                                UpdateDialog.newInstance(res.info).show(fm, "app_update")
                            }
                        }
                    }
                    is UpdateCheckResult.UpToDate -> {
                        latestInfo = null
                        statusTv.text = "Installed:      v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nLatest on main: v${res.currentVersion} (Up to date)"
                        checkBtn.text = "Check for Updates"
                    }
                    is UpdateCheckResult.Error -> {
                        statusTv.text = "Installed:      v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nLatest on main: ${res.message}"
                    }
                }
            }
        }

        checkBtn = makePillButton("Check for Updates") {
            val info = latestInfo
            if (info != null) {
                val fm = parentFragmentManager
                if (!fm.isStateSaved && fm.findFragmentByTag("app_update") == null) {
                    UpdateDialog.newInstance(info).show(fm, "app_update")
                }
            } else {
                runVersionCheck(openIfAvailable = true)
            }
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        card.addView(checkBtn)
        bodyContainer.addView(card)

        // Auto-check latest release on main when opening Admin & System
        runVersionCheck(openIfAvailable = false)
    }

    private fun renderEmbeddedHardwareDiagnosticsCard() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        val card = createSurfaceCard()
        card.addView(makeCardHeading(
            "Hardware Diagnostics & Motor Bridge",
            "Live Tonal CDC telemetry, strain-gauge zeroing, and Stock/Custom mode switching"
        ))

        val diagTv = TextView(requireContext()).apply {
            textSize = 13.5f
            typeface = SwonalFonts.monoBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
            setLineSpacing(dpf(4f), 1f)
            background = p.elevatedPillDrawable(d, 16f, interactive = false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        liveDiagStatusTv = diagTv
        updateLiveDiagCard(ui)
        card.addView(diagTv)

        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        row1.addView(makePillButton("Emergency Weight OFF") {
            repo.disableWeight()
            Toast.makeText(requireContext(), "Weight OFF sent", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14f
            setTextColor(p.criticalText)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        })
        row1.addView(makePillButton("Reset Reps") {
            repo.resetReps()
            Toast.makeText(requireContext(), "Rep counter reset", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        })
        row1.addView(makeAccentButton("Zero Calibration") {
            repo.zeroCalibration()
            Toast.makeText(requireContext(), "Zero-offset calibration sent to tonal-daemon", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        })
        card.addView(row1)

        val row2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(makePillButton("Switch to Custom Mode") {
            repo.switchToCustomMode()
            Toast.makeText(requireContext(), "Switched to Swonal Custom Mode", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) }
        })
        row2.addView(makePillButton("Switch to Stock Tonal Mode") {
            viewLifecycleOwner.lifecycleScope.launch {
                val safe = repo.disableWeightAndAwait()
                if (!safe) {
                    Toast.makeText(requireContext(), "Disengage weight before switching to Stock mode", Toast.LENGTH_LONG).show()
                } else {
                    repo.switchToStockMode()
                    Toast.makeText(requireContext(), "Switching to Stock Tonal UI...", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        })
        card.addView(row2)
        bodyContainer.addView(card)
    }

    private fun renderEmbeddedCloudSyncCard() {
        val p = palette
        val d = resources.displayMetrics.density
        val ui = repo.uiStateFlow.value
        var selectedMode = ui.syncMode

        val card = createSurfaceCard()
        card.addView(makeCardHeading(
            "Cloud & Home Server Sync",
            "Run 100% Standalone offline, or pair an optional Home/Cloud server for 24/7 MCP & always-on web history"
        ))

        val statusTv = TextView(requireContext()).apply {
            textSize = 13.5f
            typeface = SwonalFonts.monoBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
            setLineSpacing(dpf(4f), 1f)
            background = p.elevatedPillDrawable(d, 16f, interactive = false)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        liveSyncStatusTv = statusTv
        updateLiveSyncCard(ui)
        card.addView(statusTv)

        val modeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        lateinit var btnStandalone: TextView
        lateinit var btnServerSync: TextView
        lateinit var syncFieldsBox: LinearLayout

        fun refreshModeButtons() {
            val stand = selectedMode == SyncMode.STANDALONE
            btnStandalone.background = if (stand) p.accentPillDrawable(d, 12f) else p.elevatedPillDrawable(d, 12f)
            btnStandalone.setTextColor(if (stand) p.accentTextColor else p.textPrimary)
            btnServerSync.background = if (!stand) p.accentPillDrawable(d, 12f) else p.elevatedPillDrawable(d, 12f)
            btnServerSync.setTextColor(if (!stand) p.accentTextColor else p.textPrimary)
            syncFieldsBox.visibility = if (stand) View.GONE else View.VISIBLE
        }

        btnStandalone = makePillButton("Standalone (Offline-First)", active = selectedMode == SyncMode.STANDALONE) {
            selectedMode = SyncMode.STANDALONE
            refreshModeButtons()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) }
        }
        btnServerSync = makePillButton("Home / Cloud Sync", active = selectedMode == SyncMode.SERVER_SYNC) {
            selectedMode = SyncMode.SERVER_SYNC
            refreshModeButtons()
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        modeRow.addView(btnStandalone)
        modeRow.addView(btnServerSync)
        card.addView(modeRow)

        syncFieldsBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedMode == SyncMode.SERVER_SYNC) View.VISIBLE else View.GONE
        }
        val serverUrlInput = makeInput("Sync Server URL (e.g. http://192.168.0.21:3000)", ui.syncServerUrl).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
        }
        val hasSavedToken = !app.prefs.apiToken.isNullOrBlank()
        val tokenInput = makeInput(
            if (hasSavedToken) "API Bearer Token saved (leave blank to keep)" else "Optional API Bearer Token",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(12)
            }
        }
        syncFieldsBox.addView(serverUrlInput)
        syncFieldsBox.addView(tokenInput)

        val syncActionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
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
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) }
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
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.3f)
        })
        syncFieldsBox.addView(syncActionRow)
        card.addView(syncFieldsBox)

        card.addView(makeSectionLabel("Local Motor Daemon (Loopback Only)"))
        val daemonInput = makeInput("http://127.0.0.1:8765", ui.localDaemonUrl).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(12)
            }
        }
        card.addView(daemonInput)

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
            Toast.makeText(requireContext(), "Sync configuration saved", Toast.LENGTH_SHORT).show()
        }.apply {
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
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
                renderBody()
            }.apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)).apply {
                    marginStart = dp(10)
                }
            })
        }
        card.addView(saveRow)
        bodyContainer.addView(card)
    }

    private fun renderEmbeddedExerciseCatalogCard() {
        val p = palette
        val d = resources.displayMetrics.density
        val all = repo.uiStateFlow.value.exercises
        val card = createSurfaceCard()
        card.addView(makeCardHeading(
            "Exercise Catalog (${all.size})",
            "Recommended arm height (H), rotation (R), and angle (A) presets"
        ))

        val filterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val listBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun renderCatalog() {
            val muscles = listOf("All") + all.map { it.muscleGroup }.distinct().sorted()
            filterRow.removeAllViews()
            muscles.take(8).forEachIndexed { idx, m ->
                val active = m == selectedMuscle
                filterRow.addView(makePillButton(m, active = active) {
                    selectedMuscle = m
                    renderCatalog()
                }.apply {
                    textSize = 12.5f
                    background = if (active) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
                    setTextColor(if (active) p.accentTextColor else p.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (idx < muscles.take(8).lastIndex) marginEnd = dp(6)
                    }
                })
            }

            val filtered = if (selectedMuscle == "All") all else all.filter { it.muscleGroup == selectedMuscle }
            listBox.removeAllViews()
            filtered.forEach { ex ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = p.elevatedPillDrawable(d, 14f, interactive = false)
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                row.addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(requireContext()).apply {
                        text = ex.name
                        textSize = 15.5f
                        typeface = SwonalFonts.interExtraBold(requireContext())
                        setTextColor(p.textPrimary)
                    })
                    addView(TextView(requireContext()).apply {
                        text = "${ex.muscleGroup}  •  ${ex.accessory}${if (ex.unilateral) "  •  Unilateral" else ""}"
                        textSize = 13f
                        setTextColor(p.textMuted)
                    })
                })
                row.addView(TextView(requireContext()).apply {
                    text = "H${ex.recommendedHeight}  R${ex.recommendedRotation}  A${ex.recommendedAngle}"
                    textSize = 14f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                    background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                })
                listBox.addView(row)
            }
        }

        card.addView(filterRow)
        card.addView(listBox)
        renderCatalog()
        bodyContainer.addView(card)
    }

    private fun renderEmbeddedBugReportCard() {
        val p = palette
        val ui = repo.uiStateFlow.value
        val prefs = app.prefs
        val builtInWebhook = BuildConfig.BUG_REPORT_WEBHOOK_URL.trim()
        val resolvedWebhook = BugReportSender.resolveWebhookUrl(prefs)

        val card = createSurfaceCard()
        card.addView(makeCardHeading(
            "Send PII-Scrubbed Bug Report",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · v${BuildConfig.VERSION_NAME}"
        ))

        val noteInput = makeInput("Describe what happened (e.g. button didn't respond, weight mode reset)...", "").apply {
            textSize = 15f
            isSingleLine = false
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(noteInput)

        val includeLogsCheck = CheckBox(requireContext()).apply {
            text = "Attach sanitized diagnostic logs (IPs, tokens, emails & profile names stripped)"
            isChecked = true
            textSize = 14f
            setTextColor(p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(includeLogsCheck)

        var webhookInput: EditText? = null
        if (builtInWebhook.isEmpty() || !prefs.bugReportWebhookUrl.isNullOrBlank()) {
            webhookInput = makeInput(
                "Discord Webhook URL (https://discord.com/api/webhooks/...)",
                prefs.bugReportWebhookUrl.orEmpty()
            ).apply {
                textSize = 14.5f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(50)
                ).apply { bottomMargin = dp(12) }
            }
            card.addView(webhookInput)
        }

        val statusText = TextView(requireContext()).apply {
            textSize = 13.5f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(statusText)

        lateinit var sendBtn: TextView
        sendBtn = makeAccentButton("Send Bug Report to Discord") {
            val enteredWebhook = webhookInput?.text?.toString()?.trim().orEmpty()
            if (webhookInput != null && enteredWebhook.isNotEmpty()) {
                prefs.bugReportWebhookUrl = enteredWebhook
            }
            val targetWebhook = enteredWebhook.ifEmpty { resolvedWebhook }
            if (targetWebhook.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.setTextColor(p.criticalText)
                statusText.text = "No Discord webhook configured. Paste a Discord webhook URL above."
                return@makeAccentButton
            }

            sendBtn.isEnabled = false
            sendBtn.text = "Sanitizing & Sending..."
            statusText.visibility = View.VISIBLE
            statusText.setTextColor(p.textMuted)
            statusText.text = "Collecting & scrubbing diagnostic logs..."

            val t = repo.telemetryFlow.value
            val ctx = BugReportContext(
                userNote = noteInput.text.toString(),
                includeLogs = includeLogsCheck.isChecked,
                syncModeLabel = ui.syncMode.label,
                daemonConnected = ui.daemonConnected,
                daemonState = "${t.mode}/${t.profileMode}, weightOn=${t.weightEnabled}",
                pendingSyncCount = ui.pendingSyncCount,
                redactNames = ui.users.map { it.name }
            )

            viewLifecycleOwner.lifecycleScope.launch {
                val res = BugReportSender.sendReport(targetWebhook, ctx, app.tlsConfig)
                if (!isAdded) return@launch
                sendBtn.isEnabled = true
                sendBtn.text = "Send Bug Report to Discord"
                res.onSuccess {
                    noteInput.setText("")
                    statusText.setTextColor(p.accentInk)
                    statusText.text = "Bug report sent to Discord (v${BuildConfig.VERSION_NAME})!"
                    Toast.makeText(requireContext(), "Bug report sent to Discord", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    statusText.setTextColor(p.criticalText)
                    statusText.text = err.message ?: "Failed to send bug report"
                }
            }
        }.apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        }
        card.addView(sendBtn)
        bodyContainer.addView(card)
    }

    companion object {
        private const val ARG_INITIAL_TAB = "initial_tab"

        fun newInstance(initialTab: Int = 0): AppSettingsDialog = AppSettingsDialog().apply {
            arguments = Bundle().apply { putInt(ARG_INITIAL_TAB, initialTab) }
        }
    }
}
