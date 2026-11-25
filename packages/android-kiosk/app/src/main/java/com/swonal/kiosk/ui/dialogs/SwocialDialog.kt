package com.swonal.kiosk.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.ui.PressFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Native Swocial Feed Dialog:
 * - Tab 1: Activity Feed (live friend workouts & shared routines, Sync Relays, 💪 / 🔥 reactions, 1-tap Import Routine)
 * - Tab 2: Friends & My Code (Nostr npub key, connected friends roster, add friend by npub)
 */
class SwocialDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 560

    private var activeTab: String = "feed"
    private var loading: Boolean = false
    private var syncing: Boolean = false
    private var myNpub: String = ""
    private var feedItems: List<JsonObject> = emptyList()
    private var friendsList: List<JsonObject> = emptyList()
    private var statusMessage: String? = null
    private lateinit var bodyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = createCardRoot(18)
        root.addView(createDialogHeader("Swocial Feed"))

        bodyContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(wrapScrollable(bodyContainer, 540))

        loadSwocialData()
        return root
    }

    private fun loadSwocialData() {
        val uid = repo.uiStateFlow.value.activeUser?.id ?: return
        loading = true
        renderBody()
        viewLifecycleOwner.lifecycleScope.launch {
            val ident = runCatching { repo.localNostr.getOrCreateIdentity(uid) }.getOrNull()
            val friends = runCatching { repo.localNostr.listFriends(uid) }.getOrDefault(emptyList())
            val feed = runCatching { repo.localNostr.getFeed(uid, 25) }.getOrDefault(emptyList())

            myNpub = ident?.npub.orEmpty()
            friendsList = friends.map { f ->
                buildJsonObject {
                    put("id", f.id)
                    put("npub", f.npub)
                    put("pubkeyHex", f.pubkeyHex)
                    put("displayName", f.displayName)
                }
            }
            feedItems = feed.map { item ->
                buildJsonObject {
                    put("id", item.id)
                    put("kind", item.kind)
                    put("authorPubkey", item.authorPubkey)
                    put("authorName", item.authorName)
                    put("createdAt", item.createdAt)
                    put("payload", item.payload)
                    put(
                        "reactions",
                        JsonArray(
                            item.reactions.map { r ->
                                buildJsonObject {
                                    put("id", r.id)
                                    put("authorPubkey", r.authorPubkey)
                                    put("authorName", r.authorName)
                                    put("emoji", r.emoji)
                                    put("createdAt", r.createdAt)
                                }
                            }
                        )
                    )
                }
            }
            loading = false
            renderBody()
        }
    }

    private fun syncRelays() {
        val r = repo
        val uid = r.uiStateFlow.value.activeUser?.id ?: return
        syncing = true
        renderBody()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { r.localNostr.syncFeed(uid) }
            r.notifyDirtySync()
            syncing = false
            if (!isAdded) return@launch
            loadSwocialData()
        }
    }

    private fun renderBody() {
        if (!isAdded) return
        val p = palette
        val d = resources.displayMetrics.density
        val uid = repo.uiStateFlow.value.activeUser?.id.orEmpty()
        bodyContainer.removeAllViews()

        // Top Tab Row
        val tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        tabRow.addView(makePillButton("Activity Feed (${feedItems.size})", active = activeTab == "feed") {
            activeTab = "feed"
            renderBody()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        tabRow.addView(makePillButton("Friends & My Code (${friendsList.size})", active = activeTab == "friends") {
            activeTab = "friends"
            renderBody()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        })
        tabRow.addView(makePillButton(if (syncing) "Syncing..." else "↻ Sync") {
            if (!syncing) syncRelays()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
        })
        bodyContainer.addView(tabRow)

        if (!statusMessage.isNullOrBlank()) {
            bodyContainer.addView(TextView(requireContext()).apply {
                text = statusMessage
                textSize = 12f
                setTextColor(p.accentInk)
                background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            })
        }

        if (activeTab == "feed") {
            if (loading && feedItems.isEmpty()) {
                bodyContainer.addView(TextView(requireContext()).apply {
                    text = "Loading Swocial activity feed..."
                    textSize = 12.5f
                    setTextColor(p.textMuted)
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                })
                return
            }
            if (feedItems.isEmpty()) {
                val emptyBox = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = p.elevatedPillDrawable(d, 12f, interactive = false)
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                }
                emptyBox.addView(TextView(requireContext()).apply {
                    text = "No friend activity yet"
                    textSize = 14f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                })
                emptyBox.addView(TextView(requireContext()).apply {
                    text = "Switch to 'Friends & My Code' to add friends by Swocial Code or tap Sync to fetch the latest workout posts."
                    textSize = 12f
                    setTextColor(p.textMuted)
                    setPadding(0, dp(4), 0, 0)
                })
                bodyContainer.addView(emptyBox)
                return
            }

            feedItems.forEach { post ->
                val postId = post["id"]?.jsonPrimitive?.content.orEmpty()
                val pubkey = post["authorPubkey"]?.jsonPrimitive?.content
                    ?: post["pubkey"]?.jsonPrimitive?.content.orEmpty()
                val kind = post["kind"]?.jsonPrimitive?.intOrNull ?: 30101
                val author = post["authorName"]?.jsonPrimitive?.content?.ifBlank { "Friend" } ?: "Friend"
                val payload = runCatching { post["payload"]?.jsonObject }.getOrNull()
                val reactions = runCatching { post["reactions"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

                val flexCount = reactions.count {
                    runCatching { it.jsonObject["emoji"]?.jsonPrimitive?.content == "💪" }.getOrDefault(false)
                }
                val fireCount = reactions.count {
                    runCatching { it.jsonObject["emoji"]?.jsonPrimitive?.content == "🔥" }.getOrDefault(false)
                }

                val card = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = p.elevatedPillDrawable(d, 12f, interactive = false)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }

                val titleStr = if (kind == 30102) {
                    val rName = payload?.get("name")?.jsonPrimitive?.content ?: "Shared Routine"
                    "$author shared routine: $rName"
                } else {
                    val tName = payload?.get("templateName")?.jsonPrimitive?.content
                        ?: payload?.get("routineName")?.jsonPrimitive?.content
                        ?: "Workout Session"
                    "$author completed $tName"
                }
                card.addView(TextView(requireContext()).apply {
                    text = titleStr
                    textSize = 13.5f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                })

                val metaText = if (kind == 30102) {
                    payload?.get("description")?.jsonPrimitive?.content ?: "Custom Swonal workout template"
                } else {
                    val vol = payload?.get("totalVolumeLb")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val sets = payload?.get("totalSets")?.jsonPrimitive?.intOrNull
                        ?: payload?.get("setsCount")?.jsonPrimitive?.intOrNull
                        ?: 0
                    val prs = payload?.get("prCount")?.jsonPrimitive?.intOrNull ?: 0
                    String.format(Locale.US, "Volume: %.0f LB • %d sets%s", vol, sets, if (prs > 0) " • 🏆 $prs PRs" else "")
                }
                card.addView(TextView(requireContext()).apply {
                    text = metaText
                    textSize = 11.5f
                    typeface = SwonalFonts.monoBold(requireContext())
                    setTextColor(p.accentInk)
                    setPadding(0, dp(3), 0, dp(8))
                })

                val actionRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                actionRow.addView(makePillButton("💪 ${if (flexCount > 0) flexCount else ""}".trim()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { repo.localNostr.publishReaction(uid, postId, pubkey, "💪") }
                        repo.notifyDirtySync()
                        loadSwocialData()
                    }
                }.apply {
                    textSize = 11.5f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                        marginEnd = dp(6)
                    }
                })
                actionRow.addView(makePillButton("🔥 ${if (fireCount > 0) fireCount else ""}".trim()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { repo.localNostr.publishReaction(uid, postId, pubkey, "🔥") }
                        repo.notifyDirtySync()
                        loadSwocialData()
                    }
                }.apply {
                    textSize = 11.5f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                        marginEnd = dp(8)
                    }
                })

                if (kind == 30102 && postId.isNotBlank()) {
                    actionRow.addView(makeAccentButton("Import Routine") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching { repo.localNostr.importRoutine(uid, postId) }
                                .onSuccess { tpl ->
                                    statusMessage = "Imported '${tpl.name}' into your Workout Library!"
                                    repo.refreshAppState()
                                    repo.notifyDirtySync()
                                    renderBody()
                                }
                                .onFailure { err ->
                                    statusMessage = err.message ?: "Failed to import routine"
                                    renderBody()
                                }
                        }
                    }.apply {
                        textSize = 11.5f
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                    })
                }
                card.addView(actionRow)
                bodyContainer.addView(card)
            }
        } else {
            // Friends & My Code tab
            val activeName = repo.uiStateFlow.value.activeUser?.name ?: "Lifter"
            bodyContainer.addView(makeSectionLabel("Your Public Swocial Code — $activeName"))

            val codeCard = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = p.elevatedPillDrawable(d, 12f, interactive = false)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            if (myNpub.isNotBlank()) {
                val qrFrame = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = 10f * d
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(128), dp(128)).apply {
                        marginEnd = dp(12)
                    }
                }
                val qrView = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                qrFrame.addView(qrView)
                codeCard.addView(qrFrame)
                val npubSnapshot = myNpub
                viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.Default) { encodeQr(npubSnapshot, 320) }
                    if (isAdded) qrView.setImageBitmap(bmp)
                }
            }

            val codeCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            codeCol.addView(TextView(requireContext()).apply {
                text = if (myNpub.isNotBlank()) {
                    "Scan this QR code with a friend's phone to share your Swocial code:"
                } else {
                    "Generating Swocial code..."
                }
                textSize = 11f
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })
            codeCol.addView(TextView(requireContext()).apply {
                text = myNpub.ifBlank { "Generating Swocial code..." }
                textSize = 11f
                typeface = SwonalFonts.monoBold(requireContext())
                setTextColor(p.accentInk)
            })
            codeCard.addView(codeCol)
            bodyContainer.addView(codeCard)

            bodyContainer.addView(makeSectionLabel("Add Friend by Swocial Code"))
            val npubInput = EditText(requireContext()).apply {
                hint = "Paste friend's Swocial code"
                textSize = 12.5f
                setTextColor(p.textPrimary)
                setHintTextColor(p.textMuted)
                background = p.elevatedPillDrawable(d, 10f, interactive = false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val nickInput = EditText(requireContext()).apply {
                hint = "Nickname (optional)"
                textSize = 12.5f
                setTextColor(p.textPrimary)
                setHintTextColor(p.textMuted)
                background = p.elevatedPillDrawable(d, 10f, interactive = false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            bodyContainer.addView(npubInput)
            bodyContainer.addView(nickInput)
            bodyContainer.addView(makeAccentButton("Add Friend") {
                val code = npubInput.text?.toString()?.trim().orEmpty()
                val nick = nickInput.text?.toString()?.trim()
                if (code.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { repo.localNostr.addFriend(uid, code, nick) }
                            .onSuccess {
                                repo.notifyDirtySync()
                                statusMessage = "Friend added! Syncing Swocial feed..."
                                syncRelays()
                            }
                            .onFailure { err ->
                                Toast.makeText(requireContext(), err.message ?: "Failed to add friend", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { bottomMargin = dp(12) }
            })

            bodyContainer.addView(makeSectionLabel("Connected Friends (${friendsList.size})"))
            if (friendsList.isEmpty()) {
                bodyContainer.addView(TextView(requireContext()).apply {
                    text = "No friends added yet."
                    textSize = 12f
                    setTextColor(p.textMuted)
                })
            } else {
                friendsList.forEach { fr ->
                    val nick = fr["nickname"]?.jsonPrimitive?.content
                        ?: fr["displayName"]?.jsonPrimitive?.content
                        ?: "Swonal Friend"
                    val fNpub = fr["npub"]?.jsonPrimitive?.content
                        ?: fr["friendNpub"]?.jsonPrimitive?.content.orEmpty()
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        background = p.elevatedPillDrawable(d, 10f, interactive = false)
                        setPadding(dp(12), dp(9), dp(12), dp(9))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                        addView(TextView(requireContext()).apply {
                            text = nick
                            textSize = 13f
                            typeface = SwonalFonts.interBold(requireContext())
                            setTextColor(p.textPrimary)
                        })
                        if (fNpub.isNotBlank()) {
                            addView(TextView(requireContext()).apply {
                                text = fNpub.take(28) + "..."
                                textSize = 10.5f
                                typeface = SwonalFonts.monoBold(requireContext())
                                setTextColor(p.textMuted)
                            })
                        }
                    }
                    bodyContainer.addView(row)
                }
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
