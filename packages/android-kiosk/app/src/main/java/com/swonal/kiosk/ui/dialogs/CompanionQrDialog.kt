package com.swonal.kiosk.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.swonal.kiosk.theme.SwonalFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native ZXing Companion QR Modal:
 * - Scrollable URL chips supporting unlimited LAN/Public IPs
 * - Background-thread QR generation (Dispatchers.Default)
 * - Tap-to-copy URL + Direct Open button
 */
class CompanionQrDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 430

    private var selectedUrl: String = ""
    private var qrJob: Job? = null
    private lateinit var qrImage: ImageView
    private lateinit var urlLabel: TextView
    private lateinit var chipsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ui = repo.uiStateFlow.value
        val p = palette
        val d = resources.displayMetrics.density

        val root = createCardRoot(20)
        root.addView(createDialogHeader("Companion Remote"))

        root.addView(TextView(requireContext()).apply {
            text = "Scan with your phone to control weights, build workouts, and view progress."
            textSize = 12.5f
            setTextColor(p.textMuted)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        })

        val qrCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpf(16f)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(232), dp(232)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }
        qrImage = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrCard.addView(qrImage)
        root.addView(qrCard)

        val chipsScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        chipsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll.addView(chipsContainer)
        root.addView(chipsScroll)

        urlLabel = TextView(requireContext()).apply {
            textSize = 11.5f
            typeface = SwonalFonts.monoBold(requireContext())
            setTextColor(p.accentInk)
            gravity = Gravity.CENTER
            background = p.elevatedPillDrawable(d, 10f)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Swonal Companion URL", selectedUrl))
                Toast.makeText(requireContext(), "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(urlLabel)

        root.addView(makeAccentButton("Open /companion Directly") {
            openSafeHttpUrl(selectedUrl)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(10) }
        })

        val candidates = buildCandidateUrls(
            serverUrl = ui.serverUrl,
            publicUrl = ui.networkInfo?.publicUrl,
            ips = ui.networkInfo?.ips.orEmpty(),
            port = ui.networkInfo?.port ?: 3000,
            activeSessionId = ui.sessionState.activeSessionId
        )
        selectedUrl = candidates.firstOrNull()?.second ?: "${ui.serverUrl}/companion"
        renderCandidates(candidates)
        updateQr(selectedUrl)

        return root
    }

    private fun buildCandidateUrls(
        serverUrl: String,
        publicUrl: String?,
        ips: List<String>,
        port: Int,
        activeSessionId: String?
    ): List<Pair<String, String>> {
        val suffix = if (!activeSessionId.isNullOrBlank()) {
            "/companion?session=$activeSessionId"
        } else {
            "/companion"
        }
        val list = mutableListOf<Pair<String, String>>()
        if (!publicUrl.isNullOrBlank()) {
            list += "Public URL" to "${publicUrl.trimEnd('/')}$suffix"
        }
        ips.forEach { ip ->
            list += "LAN $ip" to "http://$ip:$port$suffix"
        }
        if (list.isEmpty()) {
            list += "Server" to "${serverUrl.trimEnd('/')}$suffix"
        }
        return list.distinctBy { it.second }
    }

    private fun renderCandidates(candidates: List<Pair<String, String>>) {
        chipsContainer.removeAllViews()
        candidates.forEach { (label, url) ->
            chipsContainer.addView(makePillButton(label, active = url == selectedUrl) {
                selectedUrl = url
                renderCandidates(candidates)
                updateQr(url)
            }.apply {
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32)
                ).apply { marginEnd = dp(6) }
            })
        }
    }

    private fun updateQr(url: String) {
        urlLabel.text = "$url  (tap to copy)"
        qrJob?.cancel()
        qrJob = viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { encodeQr(url, 512) }
            if (isAdded) {
                qrImage.setImageBitmap(bmp)
            }
        }
    }

    private fun encodeQr(text: String, dim: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            dim,
            dim,
            mapOf(EncodeHintType.MARGIN to 2)
        )
        val pixels = IntArray(dim * dim)
        val dark = 0xFF07090E.toInt()
        for (y in 0 until dim) {
            val row = y * dim
            for (x in 0 until dim) {
                pixels[row + x] = if (bitMatrix[x, y]) dark else Color.WHITE
            }
        }
        return Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, dim, 0, 0, dim, dim)
        }
    }
}
