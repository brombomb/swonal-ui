package com.swonal.kiosk.ui.dialogs

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Fail-closed Passcode Unlock Dialog with in-flight disable and a Server URL escape hatch.
 */
class AuthLockDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 380
    override val cancelableOnOutside: Boolean = false

    private var showServerEditor = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val p = palette
        val root = createCardRoot(22)
        root.addView(createDialogHeader("Swonal Gym Locked", showClose = false))

        val subtitle = TextView(requireContext()).apply {
            text = "Enter your Swonal passcode to unlock ${repo.uiStateFlow.value.serverUrl}."
            textSize = 12.5f
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(subtitle)

        val passcodeInput = makeInput(
            "Passcode",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(passcodeInput)

        val errLabel = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(p.criticalText)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(errLabel)

        val unlockBtn = makeAccentButton("Unlock Swonal") {
            val code = passcodeInput.text.toString()
            if (code.isNotBlank()) repo.submitPasscode(code)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        }
        root.addView(unlockBtn)

        // Escape hatch: edit server URL if kiosk was pointed at a locked or unreachable server
        val serverEditorBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val urlInput = makeInput("Server URL", repo.uiStateFlow.value.serverUrl).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(8) }
        }
        serverEditorBox.addView(urlInput)
        serverEditorBox.addView(makePillButton("Switch Server & Reconnect") {
            repo.updateConnectionEndpoints(
                newServerUrl = urlInput.text.toString(),
                newLocalDaemonUrl = repo.uiStateFlow.value.localDaemonUrl
            )
            serverEditorBox.visibility = View.GONE
            showServerEditor = false
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        })

        val toggleServerBtn = makePillButton("Change Server URL") {
            showServerEditor = !showServerEditor
            serverEditorBox.visibility = if (showServerEditor) View.VISIBLE else View.GONE
        }.apply {
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply { topMargin = dp(10) }
        }
        root.addView(toggleServerBtn)
        root.addView(serverEditorBox)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.uiStateFlow.collect { st ->
                    subtitle.text = "Enter your Swonal passcode to unlock ${st.serverUrl}."
                    unlockBtn.isEnabled = !st.authChecking
                    unlockBtn.alpha = if (st.authChecking) 0.55f else 1f
                    unlockBtn.text = if (st.authChecking) "Verifying..." else "Unlock Swonal"

                    if (!st.authRequired && isAdded) {
                        dismissAllowingStateLoss()
                    } else if (!st.authError.isNullOrBlank()) {
                        errLabel.text = st.authError
                        errLabel.visibility = View.VISIBLE
                    } else {
                        errLabel.visibility = View.GONE
                    }
                }
            }
        }

        return root
    }
}
