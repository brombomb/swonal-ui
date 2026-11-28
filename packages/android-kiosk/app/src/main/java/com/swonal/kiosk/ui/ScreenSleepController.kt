package com.swonal.kiosk.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import com.swonal.kiosk.util.ScreenSleepPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional "Sleep screen after inactivity" (Application Settings → Admin & System).
 *
 * While the host activity is started, ticks once a second and asks [ScreenSleepPolicy]
 * whether to warn or sleep. Never sleeps while weight is engaged, a rest timer is running,
 * or an app update is installing.
 *
 * "Sleep" blanks the screen instead of turning the display off: a black full-screen overlay
 * with the backlight forced to its minimum. The display stays on, so a touch anywhere wakes
 * it (a real screen-off on Android 6 disables the touchscreen and needs the power button).
 * Because the kiosk never leaves the foreground, the UI heartbeat stays alive. Any activity
 * while blanked (Companion command, voice, cable or arm movement) or a blocking condition
 * (e.g. weight engaged from a phone) wakes the screen within one tick.
 *
 * [sleepNow] (Quick Preferences → Power → Sleep) blanks the screen the same way on demand,
 * whatever the timeout setting is, and wakes on the same touch / activity / blocking rules.
 */
class ScreenSleepController(private val activity: AppCompatActivity) {

    private val app: SwonalApp
        get() = activity.application as SwonalApp

    private val powerManager =
        activity.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var warningDialog: ScreenSleepWarningDialog? = null
    private var blankOverlay: ScreenBlankOverlay? = null
    private var cableBaselineLeft: Double? = null
    private var cableBaselineRight: Double? = null
    private var lastArmPositions: ArmPositions? = null
    private var lastTickMs: Long? = null
    private var screenWasOn = true

    /** [IdleActivityTracker.clock] time when [sleepNow] blanked the screen, or null when not manually asleep. */
    private var manualSleepStartedMs: Long? = null

    /**
     * Blanks the screen right away (touch anywhere to wake). Refuses while weight is engaged,
     * a rest timer is running, or an update is installing, and returns false in that case.
     */
    fun sleepNow(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (isBlocked()) return false
        dismissWarning()
        if (blankOverlay == null) blankScreen()
        if (blankOverlay == null) return false
        // The tap on "Sleep" was the last activity; anything noted after this point wakes up.
        IdleActivityTracker.noteActivity()
        manualSleepStartedMs = IdleActivityTracker.clock()
        return true
    }

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
                    wake()
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
            // Display turned off with the power button: nothing to do until it's back on.
            screenWasOn = false
            dismissWarning()
            return
        }
        if (!screenWasOn || gap) {
            // Just woke up (or the CPU was suspended between ticks). elapsedRealtime kept counting
            // through deep sleep, so the idle clock is stale: start a fresh countdown instead of
            // blanking again immediately.
            screenWasOn = true
            resetIdleState()
            lastTickMs = now
        }

        trackCableMovement()

        val blocked = isBlocked()

        val manualStart = manualSleepStartedMs
        if (manualStart != null) {
            if (blankOverlay == null) {
                manualSleepStartedMs = null
            } else if (
                ScreenSleepPolicy.shouldWakeFromManualSleep(
                    sleepStartedMs = manualStart,
                    nowMs = IdleActivityTracker.clock(),
                    idleMs = IdleActivityTracker.idleMs(),
                    blocked = blocked
                )
            ) {
                wake()
            } else {
                // Manually asleep: the timeout setting (maybe Off) must not wake the screen.
                return
            }
        }

        val decision = ScreenSleepPolicy.decide(
            timeoutMinutes = app.prefs.screenSleepTimeoutMinutes,
            idleMs = IdleActivityTracker.idleMs(),
            blocked = blocked
        )
        // While blanked, anything other than "still asleep" (fresh activity, a blocking
        // condition such as weight engaged from a phone, or the setting turned off) wakes up.
        if (blankOverlay != null && decision != ScreenSleepPolicy.Decision.Sleep) wake()

        when (decision) {
            ScreenSleepPolicy.Decision.Disabled,
            ScreenSleepPolicy.Decision.Awake -> dismissWarning()
            ScreenSleepPolicy.Decision.Blocked -> {
                IdleActivityTracker.noteActivity()
                dismissWarning()
            }
            is ScreenSleepPolicy.Decision.Warning -> showWarning(decision.secondsLeft)
            ScreenSleepPolicy.Decision.Sleep -> {
                if (blankOverlay != null) return
                val warned = warningDialog?.isShowing == true
                dismissWarning()
                if (warned) {
                    blankScreen()
                } else {
                    // Never blank the screen without the countdown having been on screen
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

    private fun isBlocked(): Boolean {
        val repo = app.repository
        return repo.isWeightCurrentlyEngaged() ||
            repo.uiStateFlow.value.restSecondsRemaining > 0 ||
            AppUpdateManager.isUpdateInProgress.get()
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

    private fun blankScreen() {
        if (blankOverlay != null || activity.isFinishing || activity.isDestroyed) return
        val overlay = ScreenBlankOverlay(activity) { wake() }
        blankOverlay = overlay
        if (runCatching { overlay.show() }.isFailure) {
            blankOverlay = null
            IdleActivityTracker.noteActivity()
        }
    }

    /** Removes the blank overlay (restoring brightness) and restarts the idle countdown. */
    private fun wake() {
        manualSleepStartedMs = null
        val overlay = blankOverlay ?: return
        blankOverlay = null
        runCatching { if (overlay.isShowing) overlay.dismiss() }
        IdleActivityTracker.noteActivity()
    }

    private companion object {
        const val TICK_MS = 1_000L
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

/**
 * The "asleep" screen: solid black, backlight at its minimum, display still on so touch works.
 * The first touch wakes it and is swallowed, so it never presses whatever is underneath.
 * Not focusable, so it never steals focus or breaks the kiosk's immersive mode.
 */
@SuppressLint("ClickableViewAccessibility")
private class ScreenBlankOverlay(
    context: Context,
    private val onWake: () -> Unit
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = View(context).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onWake()
                true
            }
        }
        setContentView(root)
        setCancelable(false)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            attributes = attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            }
        }
    }
}
