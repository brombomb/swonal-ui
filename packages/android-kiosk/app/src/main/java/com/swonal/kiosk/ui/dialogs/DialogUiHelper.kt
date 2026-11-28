package com.swonal.kiosk.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.swonal.kiosk.R
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import com.swonal.kiosk.ui.InAppWebLauncher
import com.swonal.kiosk.ui.PressFeedback
import com.swonal.kiosk.util.IdleActivityTracker
import kotlin.math.min

/**
 * Clean ScrollView enforcing an upper height bound inside `onMeasure()` (no `requestLayout()` inside layout listeners).
 */
class MaxHeightScrollView(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenCap = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        val cap = min(maxHeightPx, screenCap)
        val boundedSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, boundedSpec)
    }
}

abstract class BaseSwonalDialog : DialogFragment() {
    protected val app: SwonalApp
        get() = requireActivity().application as SwonalApp

    protected val repo: SwonalRepository
        get() = app.repository

    protected val palette: SwonalPalette
        get() {
            val st = repo.uiStateFlow.value
            return SwonalThemeTokens.resolve(
                st.activeThemeMode,
                st.activeAccentHex,
                SwonalThemeTokens.isSystemNight(requireContext())
            )
        }

    protected open val maxDialogWidthDp: Int = 460
    protected open val cancelableOnOutside: Boolean = true
    protected open val isFullScreen: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val themeRes = if (isFullScreen) {
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        } else {
            R.style.Theme_SwonalKiosk_Dialog
        }
        // Dialog windows bypass Activity.onUserInteraction, so feed the screen sleep idle clock here.
        val dialog = object : Dialog(requireContext(), themeRes) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                IdleActivityTracker.noteActivity()
                return super.dispatchTouchEvent(ev)
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                IdleActivityTracker.noteActivity()
                return super.dispatchKeyEvent(event)
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(if (isFullScreen) false else cancelableOnOutside)
        isCancelable = cancelableOnOutside
        return dialog
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        if (isFullScreen) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(palette.bgCanvas))
            window.setDimAmount(0f)
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        } else {
            val dm = resources.displayMetrics
            val maxW = (maxDialogWidthDp * dm.density).toInt().coerceAtMost((dm.widthPixels * 0.92f).toInt())
            window.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (activity as? com.swonal.kiosk.ui.MainActivity)?.reapplyImmersiveFullscreen()
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    protected fun dpf(value: Float): Float = value * resources.displayMetrics.density

    protected fun createFullScreenRoot(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bgCanvas)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    protected fun createFullScreenHeader(
        titleText: String,
        subtitleText: String? = null,
        rightContent: View? = null
    ): LinearLayout {
        val p = palette
        val d = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgDialog)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val bar = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(18))

                val backBtn = TextView(requireContext()).apply {
                    text = "← Kiosk"
                    textSize = 14f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                    gravity = Gravity.CENTER
                    background = p.elevatedPillDrawable(d, 12f)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dismiss() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(44)
                    ).apply { marginEnd = dp(18) }
                }.also { PressFeedback.attachSingle(it) }
                addView(backBtn)

                val titleCol = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(requireContext()).apply {
                        text = titleText
                        textSize = 22f
                        typeface = SwonalFonts.interBlack(requireContext())
                        setTextColor(p.textPrimary)
                    })
                    if (!subtitleText.isNullOrBlank()) {
                        addView(TextView(requireContext()).apply {
                            text = subtitleText
                            textSize = 13f
                            setTextColor(p.textMuted)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(2) }
                        })
                    }
                }
                addView(titleCol)

                if (rightContent != null) {
                    addView(rightContent)
                }
            }
            addView(bar)

            addView(View(requireContext()).apply {
                setBackgroundColor(p.borderSubtle)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
            })
        }
    }

    protected fun createFullScreenScrollBody(): Pair<android.widget.ScrollView, LinearLayout> {
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(44))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = android.widget.ScrollView(requireContext()).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(body)
        }
        return scroll to body
    }

    protected fun createSurfaceCard(paddingDp: Int = 22): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.surfaceCardDrawable(d, 22f)
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
    }

    protected fun makeCardHeading(title: String, subtitle: String? = null): LinearLayout {
        val p = palette
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            addView(TextView(requireContext()).apply {
                text = title
                textSize = 19f
                typeface = SwonalFonts.interBlack(requireContext())
                setTextColor(p.textPrimary)
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(p.textMuted)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            }
        }
    }

    protected fun createCardRoot(paddingDp: Int = 20): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.dialogCardDrawable(d, 20f)
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        }
    }

    protected fun createDialogHeader(titleText: String, showClose: Boolean = true): LinearLayout {
        val p = palette
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }

            addView(TextView(requireContext()).apply {
                text = titleText
                textSize = 18f
                typeface = SwonalFonts.interBlack(requireContext())
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (showClose) {
                val closeBtn = FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                    background = p.elevatedPillDrawable(resources.displayMetrics.density, 10f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dismiss() }
                    addView(ImageView(requireContext()).apply {
                        setImageResource(R.drawable.ic_close)
                        setColorFilter(p.textPrimary)
                        layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
                    })
                }.also { PressFeedback.attachSingle(it) }
                addView(closeBtn)
            }
        }
    }

    protected fun makeSectionLabel(label: String): TextView {
        return TextView(requireContext()).apply {
            text = label.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = SwonalFonts.interExtraBold(requireContext())
            setTextColor(palette.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        }
    }

    protected fun makeAccentButton(label: String, onClick: () -> Unit): TextView {
        val p = palette
        return TextView(requireContext()).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = SwonalFonts.interExtraBold(requireContext())
            setTextColor(p.accentTextColor)
            background = p.accentPillDrawable(resources.displayMetrics.density, 12f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }.also { PressFeedback.attachSingle(it) }
    }

    protected fun makePillButton(
        label: String,
        active: Boolean = false,
        mono: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val p = palette
        val d = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = if (mono) SwonalFonts.monoBold(requireContext()) else SwonalFonts.interBold(requireContext())
            if (mono) fontFeatureSettings = "tnum"
            setTextColor(if (active) p.accentTextColor else p.textPrimary)
            background = if (active) p.accentPillDrawable(d, 10f) else p.elevatedPillDrawable(d, 10f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }.also { PressFeedback.attachSingle(it) }
    }

    protected fun makeInput(hintText: String, initialValue: String = "", inputTypeFlag: Int? = null): EditText {
        val p = palette
        val d = resources.displayMetrics.density
        return EditText(requireContext()).apply {
            hint = hintText
            setText(initialValue)
            typeface = SwonalFonts.interRegular(requireContext())
            setTextColor(p.textPrimary)
            setHintTextColor(p.textMuted)
            textSize = 14f
            background = p.elevatedPillDrawable(d, 10f, p.borderTopHighlight, interactive = false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            if (inputTypeFlag != null) inputType = inputTypeFlag
        }
    }

    protected fun makeDivider(): View {
        return View(requireContext()).apply {
            setBackgroundColor(palette.borderSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
    }

    protected fun wrapScrollable(content: View, maxHeightDp: Int = 520): ScrollView {
        return MaxHeightScrollView(requireContext(), dp(maxHeightDp)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(content)
        }
    }

    protected fun openSafeHttpUrl(url: String, title: String = "Swonal") {
        InAppWebLauncher.open(requireContext(), url, title, palette)
    }
}
