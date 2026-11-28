package com.swonal.kiosk.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.model.ArmPositions
import com.swonal.kiosk.net.AppUpdateManager
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import com.swonal.kiosk.util.IdleActivityTracker
import com.swonal.kiosk.util.RootShell
import com.swonal.kiosk.util.ScreenSleepPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional "Sleep screen after inactivity" (Application Settings → Admin & System).
 *
 * While the host activity is started, ticks once a second and asks [ScreenSleepPolicy]
 * whether to warn or sleep. Never sleeps while weight is engaged, a rest timer is running,
 * or an app update is installing. Sleep is requested over root with KEYCODE_SLEEP (223),
 * which only turns the screen off (unlike POWER, it never toggles the screen back on).
 */
class ScreenSleepController(private val activity: AppCompatActivity) {

    private val app: SwonalApp
        get() = activity.application as SwonalApp

    private val powerManager =
        activity.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var warningDialog: ScreenSleepWarningDialog? = null
    private var cableBaselineLeft: Double? = null
    private var cableBaselineRight: Double? = null
    private var lastArmPositions: ArmPositions? = null
    private var lastTickMs: Long? = null
    private var screenWasOn = true
    private var sleepInFlight = false

    fun attach() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Returning to (or waking up into) the kiosk always starts a fresh countdown.
                resetIdleState()
                try {
                    while (isActive) {
                        tick()
                        delay(TICK_MS)
                    }
                } finally {
                    dismissWarning()
                }
            }
        }
    }

    private fun isScreenOn(): Boolean = powerManager.isInteractive

    private fun resetIdleState() {
        IdleActivityTracker.noteActivity()
        cableBaselineLeft = null
        cableBaselineRight = null
        lastArmPositions = null
        lastTickMs = null
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val gap = ScreenSleepPolicy.isTickGap(lastTickMs, now)
        lastTickMs = now

        if (!isScreenOn()) {
            // Screen already off (power button or our own sleep): nothing to do.
            screenWasOn = false
            dismissWarning()
            return
        }
        if (!screenWasOn || gap) {
            // Just woke up (or the CPU was suspended between ticks). elapsedRealtime kept counting
            // through deep sleep, so the idle clock is stale: start a fresh countdown instead of
            // sleeping again immediately.
            screenWasOn = true
            resetIdleState()
            lastTickMs = now
        }

        val repo = app.repository
        trackCableMovement()

        val blocked = repo.isWeightCurrentlyEngaged() ||
            repo.uiStateFlow.value.restSecondsRemaining > 0 ||
            AppUpdateManager.isUpdateInProgress.get()

        when (val decision = ScreenSleepPolicy.decide(
            timeoutMinutes = app.prefs.screenSleepTimeoutMinutes,
            idleMs = IdleActivityTracker.idleMs(),
            blocked = blocked
        )) {
            ScreenSleepPolicy.Decision.Disabled,
            ScreenSleepPolicy.Decision.Awake -> dismissWarning()
            ScreenSleepPolicy.Decision.Blocked -> {
                IdleActivityTracker.noteActivity()
                dismissWarning()
            }
            is ScreenSleepPolicy.Decision.Warning -> showWarning(decision.secondsLeft)
            ScreenSleepPolicy.Decision.Sleep -> {
                val warned = warningDialog?.isShowing == true
                dismissWarning()
                if (warned) {
                    requestSleep()
                } else {
                    // Never turn the screen off without the countdown having been on screen
                    // (e.g. the dialog failed to show, or the main thread stalled through it).
                    IdleActivityTracker.noteActivity()
                }
            }
        }
    }

    private fun trackCableMovement() {
        val frame = app.repository.telemetryFlow.value
        val left = frame.leftCable.position
        val right = frame.rightCable.position
        val moved = ScreenSleepPolicy.cableMoved(
            cableBaselineLeft,
            cableBaselineRight,
            left,
            right,
            frame.leftCable.velocity,
            frame.rightCable.velocity
        )
        if (moved) IdleActivityTracker.noteActivity()
        if (moved || cableBaselineLeft == null || cableBaselineRight == null) {
            cableBaselineLeft = left
            cableBaselineRight = right
        }

        // Raising/lowering/rotating the arms (or pulling a lock pin) is also someone at the machine.
        val arms = frame.armPositions
        val previousArms = lastArmPositions
        if (previousArms != null && previousArms != arms) IdleActivityTracker.noteActivity()
        lastArmPositions = arms
    }

    private fun showWarning(secondsLeft: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val existing = warningDialog
        if (existing != null && existing.isShowing) {
            existing.update(secondsLeft)
            return
        }
        val st = app.repository.uiStateFlow.value
        val palette = SwonalThemeTokens.resolve(
            st.activeThemeMode,
            st.activeAccentHex,
            SwonalThemeTokens.isSystemNight(activity)
        )
        warningDialog = ScreenSleepWarningDialog(activity, palette) {
            IdleActivityTracker.noteActivity()
            dismissWarning()
        }.also {
            it.update(secondsLeft)
            runCatching { it.show() }
        }
    }

    private fun dismissWarning() {
        val dlg = warningDialog ?: return
        warningDialog = null
        runCatching { if (dlg.isShowing) dlg.dismiss() }
    }

    private fun requestSleep() {
        if (sleepInFlight) return
        sleepInFlight = true
        activity.lifecycleScope.launch {
            try {
                // Deliberately no KEYCODE_POWER fallback: POWER toggles, so a slow screen-off
                // could race it and turn the screen straight back on.
                val result = RootShell.run("input keyevent $KEYCODE_SLEEP")
                if (!result.isSuccess) {
                    Log.w(TAG, "KEYCODE_SLEEP failed (${result.exitCode}): ${result.stderr}")
                }
                delay(SLEEP_VERIFY_DELAY_MS)
                if (isScreenOn()) {
                    Log.w(TAG, "Screen still on after KEYCODE_SLEEP (root unavailable?)")
                    if (!failureToastShown && !activity.isFinishing && !activity.isDestroyed) {
                        failureToastShown = true
                        Toast.makeText(
                            activity,
                            "Screen sleep failed: root access is required",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                // Success or failure, restart the countdown instead of retrying every second.
                IdleActivityTracker.noteActivity()
                sleepInFlight = false
            }
        }
    }

    private companion object {
        /** One toast per app process is enough to explain a missing root grant. */
        @Volatile
        var failureToastShown = false

        const val TAG = "ScreenSleep"
        const val TICK_MS = 1_000L
        const val SLEEP_VERIFY_DELAY_MS = 1_500L
        const val KEYCODE_SLEEP = 223
    }
}

/**
 * Full-screen "Going to sleep" countdown. Tapping anywhere keeps the screen awake.
 * Not focusable, so it never steals focus or breaks the kiosk's immersive mode.
 */
private class ScreenSleepWarningDialog(
    context: Context,
    palette: SwonalPalette,
    private val onStayAwake: () -> Unit
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    private val countdownTv: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        countdownTv = TextView(context).apply {
            textSize = 72f
            typeface = SwonalFonts.monoExtraBold(context)
            setTextColor(palette.accentInk)
            gravity = Gravity.CENTER
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
            background = GradientDrawable().apply {
                setColor(palette.bgDialog)
                cornerRadius = 24f * d
                setStroke(dp(1), palette.borderSubtle)
            }
            addView(TextView(context).apply {
                text = "Going to sleep"
                textSize = 26f
                typeface = SwonalFonts.interBold(context)
                setTextColor(palette.textPrimary)
                gravity = Gravity.CENTER
            })
            addView(countdownTv)
            addView(TextView(context).apply {
                text = "Tap anywhere to stay awake"
                textSize = 17f
                typeface = SwonalFonts.interSemiBold(context)
                setTextColor(palette.textMuted)
                gravity = Gravity.CENTER
            })
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { onStayAwake() }
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        setContentView(root)
        setCancelable(true)
        setOnCancelListener { onStayAwake() }
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    fun update(secondsLeft: Int) {
        countdownTv.text = secondsLeft.toString()
    }
}
