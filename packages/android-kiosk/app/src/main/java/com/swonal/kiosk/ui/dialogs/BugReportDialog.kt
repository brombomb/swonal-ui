package com.swonal.kiosk.ui.dialogs

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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.net.BugReportContext
import com.swonal.kiosk.net.BugReportSender
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.launch

/**
 * Native Kiosk Bug Report Dialog:
 * - Displays App Version (versionName + versionCode) and device/daemon summary
 * - Captures a user description and optionally attaches PII-scrubbed diagnostic logs
 * - Sends to the configured Discord webhook (build-time secret or user-provided override)
 */
class BugReportDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 520

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val p = palette
        val d = resources.displayMetrics.density
        val app = requireActivity().application as SwonalApp
        val prefs = app.prefs
        val builtInWebhook = BuildConfig.BUG_REPORT_WEBHOOK_URL.trim()
        val resolvedWebhook = BugReportSender.resolveWebhookUrl(prefs)

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Send Bug Report"))

        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        // App Version & Device Banner
        body.addView(TextView(requireContext()).apply {
            text = "Swonal Kiosk v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n" +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${ui.syncMode.label}"
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.textPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        body.addView(makeSectionLabel("What happened?"))
        val noteInput = makeInput("Describe the issue (e.g. button didn't respond, weight mode reset)...", "").apply {
            isSingleLine = false
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        body.addView(noteInput)

        val includeLogsCheck = CheckBox(requireContext()).apply {
            text = "Attach sanitized diagnostic logs (IPs, tokens, emails & profile names stripped)"
            isChecked = true
            textSize = 12f
            setTextColor(p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        body.addView(includeLogsCheck)

        // Webhook URL input (shown when no build-time webhook is baked in, or if user already set an override)
        var webhookInput: EditText? = null
        if (builtInWebhook.isEmpty() || !prefs.bugReportWebhookUrl.isNullOrBlank()) {
            body.addView(makeSectionLabel("Discord Webhook URL"))
            webhookInput = makeInput(
                "https://discord.com/api/webhooks/...",
                prefs.bugReportWebhookUrl.orEmpty()
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply { bottomMargin = dp(10) }
            }
            body.addView(webhookInput)
        } else {
            body.addView(TextView(requireContext()).apply {
                text = "Reports are sent directly to the Swonal Discord bug-reports channel. No workout history or credentials are included."
                textSize = 11.5f
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            })
        }

        val statusText = TextView(requireContext()).apply {
            textSize = 12f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        body.addView(statusText)

        lateinit var sendBtn: TextView
        sendBtn = makePillButton("Send Bug Report to Discord", active = true) {
            val enteredWebhook = webhookInput?.text?.toString()?.trim().orEmpty()
            if (webhookInput != null && enteredWebhook.isNotEmpty()) {
                prefs.bugReportWebhookUrl = enteredWebhook
            }
            val targetWebhook = enteredWebhook.ifEmpty { resolvedWebhook }
            if (targetWebhook.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.setTextColor(p.criticalText)
                statusText.text = "No Discord webhook configured. Paste a Discord webhook URL above."
                return@makePillButton
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
                res.onSuccess {
                    Toast.makeText(
                        requireContext(),
                        "Bug report sent to Discord (v${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                }.onFailure { err ->
                    sendBtn.text = "Send Bug Report to Discord"
                    statusText.setTextColor(p.criticalText)
                    statusText.text = err.message ?: "Failed to send bug report"
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        }
        body.addView(sendBtn)

        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(body)
        })
        return root
    }
}
