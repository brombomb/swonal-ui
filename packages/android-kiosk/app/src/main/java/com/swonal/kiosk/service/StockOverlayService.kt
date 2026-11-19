package com.swonal.kiosk.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.ui.MainActivity
import com.swonal.kiosk.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * High-priority floating overlay escape hatch that stays accessible whenever
 * Stock Tonal UI (com.tonal.apps.machineclient2.beta) is running.
 *
 * Allows the user to tap "⚡ Return to Swonal" directly from the touchscreen
 * at any time, unlocking Android LockTaskMode, terminating stock apps,
 * and bringing Swonal Kiosk back into focus.
 */
class StockOverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var monitorJob: Job? = null
    private var isReturning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        createFloatingEscapeHatch()
        overlayView?.visibility = View.GONE
        startActivityMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "swonal_overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Swonal Stock Mode Escape Hatch",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Swonal Escape Hatch")
            .setContentText("Tap overlay to return to Swonal Kiosk")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
    private fun createFloatingEscapeHatch() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wm = windowManager ?: return

        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(24f)
            y = dp(24f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            elevation = 16f * d

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F1722")) // Deep dark charcoal with high opacity
                setStroke(dp(2f), Color.parseColor("#00F0FF")) // Electric Cyan border
                cornerRadius = 24f * d
            }
            background = bg
        }

        val iconTv = TextView(this).apply {
            text = "⚡"
            textSize = 15f
            setTextColor(Color.parseColor("#00F0FF"))
            setPadding(0, 0, dp(8f), 0)
        }
        root.addView(iconTv)

        statusTextView = TextView(this).apply {
            text = "Return to Swonal"
            textSize = 13f
            typeface = SwonalFonts.interBold(this@StockOverlayService)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(statusTextView)

        // Draggable & Clickable touch handler
        root.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val touchSlop = dp(10f)

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (initialTouchX - event.rawX).toInt() // Gravity is RIGHT, so delta is inverted
                        val dy = (event.rawY - initialTouchY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update layout", e)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val totalDx = abs(event.rawX - initialTouchX)
                        val totalDy = abs(event.rawY - initialTouchY)
                        if (totalDx < touchSlop && totalDy < touchSlop) {
                            v.performClick()
                            handleReturnToSwonal()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(root, params)
            overlayView = root
            Log.i(TAG, "Floating escape hatch added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view to WindowManager", e)
        }
    }

    private fun handleReturnToSwonal() {
        if (isReturning) return
        isReturning = true

        statusTextView?.apply {
            text = "Switching to Swonal..."
            setTextColor(Color.parseColor("#00F0FF"))
        }

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Break Android LockTaskMode pinning via Tonal's own internal broadcast
                RootShell.run("am broadcast -a com.tonal.apps.machineclient2.EXIT_KIOSK")

                // 2. Kill stock Tonal app and Tonal launcher holding hardware / lock task
                RootShell.run("am force-stop com.tonal.apps.machineclient2.beta")
                RootShell.run("am force-stop com.tonal.tonallauncher")

                // 3. Launch Swonal Kiosk MainActivity
                val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(launchIntent)

                // 4. Signal local daemon & server-app to reclaim motor serial & BLE hardware
                (application as? SwonalApp)?.repository?.switchToCustomMode()

                delay(800)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing return to Swonal", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun startActivityMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val focusResult = RootShell.run("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
                    val stdout = focusResult.stdout

                    val isSwonalFocused = stdout.contains("com.swonal.kiosk")
                    val isStockFocused = stdout.contains("machineclient2") || stdout.contains("tonallauncher")

                    withContext(Dispatchers.Main) {
                        if (isSwonalFocused && !isReturning) {
                            // Don't clutter Swonal's own screen
                            overlayView?.visibility = View.GONE
                        } else if (isStockFocused) {
                            // Display prominently when user is in Stock Tonal
                            overlayView?.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Activity monitor poll error", e)
                }
                delay(1500)
            }
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StockOverlayService"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            try {
                val intent = Intent(context, StockOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start StockOverlayService", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StockOverlayService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop StockOverlayService", e)
            }
        }
    }
}
