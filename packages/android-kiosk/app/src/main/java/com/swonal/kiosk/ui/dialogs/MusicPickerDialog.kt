package com.swonal.kiosk.ui.dialogs

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swonal.kiosk.R
import com.swonal.kiosk.model.MusicDevice
import com.swonal.kiosk.model.MusicPlaylist
import com.swonal.kiosk.model.MusicProviderInfo
import com.swonal.kiosk.model.MusicTrack
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.ui.InAppWebLauncher
import com.swonal.kiosk.ui.PressFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Speakers, Device Volume & Workout Playlists Dialog:
 * - Tonal Device Speaker Volume (`AudioManager.STREAM_MUSIC`) slider
 * - Active Speaker picker defaulting to on-device `librespot` ("Tonal Gym")
 * - Favorites-first Workout Playlists with unclipped `Play` button & expandable track lists
 */
class MusicPickerDialog : BaseSwonalDialog() {

    private data class MusicPickerSlice(
        val activeUserId: String?,
        val preferredProvider: String,
        val providers: List<MusicProviderInfo>,
        val devices: List<MusicDevice>,
        val activeDeviceId: String?,
        val playlists: List<MusicPlaylist>,
        val favoriteIds: Set<String>,
        val musicError: String?,
        val themeMode: String,
        val accentHex: String
    )

    override val maxDialogWidthDp: Int = 580

    private var favoritesOnly = false
    private var expandedPlaylistId: String? = null
    private var lastCacheScopeKey: String = ""
    private val tracksCache = mutableMapOf<String, List<MusicTrack>>()
    private val trackErrors = mutableMapOf<String, String>()
    private lateinit var bodyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = createCardRoot(18)
        root.addView(createDialogHeader("Speakers & Playlists"))

        bodyContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(wrapScrollable(bodyContainer, 540))

        repo.refreshMusicNow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.uiStateFlow
                    .map { st ->
                        MusicPickerSlice(
                            activeUserId = st.activeUser?.id,
                            preferredProvider = st.preferredMusicProvider,
                            providers = st.musicProviders,
                            devices = st.musicDevices,
                            activeDeviceId = st.musicState.activeDeviceId,
                            playlists = st.musicPlaylists,
                            favoriteIds = st.favoritePlaylistIds,
                            musicError = st.musicError,
                            themeMode = st.activeThemeMode,
                            accentHex = st.activeAccentHex
                        )
                    }
                    .distinctUntilChanged()
                    .collect { slice ->
                        val scopeKey = "${slice.activeUserId}:${slice.preferredProvider}"
                        if (scopeKey != lastCacheScopeKey) {
                            lastCacheScopeKey = scopeKey
                            tracksCache.clear()
                            trackErrors.clear()
                            expandedPlaylistId = null
                        }
                        renderBody(slice)
                    }
            }
        }
        return root
    }

    private fun renderBody(slice: MusicPickerSlice? = null) {
        if (!isAdded) return
        val st = repo.uiStateFlow.value
        val activeSlice = slice ?: MusicPickerSlice(
            activeUserId = st.activeUser?.id,
            preferredProvider = st.preferredMusicProvider,
            providers = st.musicProviders,
            devices = st.musicDevices,
            activeDeviceId = st.musicState.activeDeviceId,
            playlists = st.musicPlaylists,
            favoriteIds = st.favoritePlaylistIds,
            musicError = st.musicError,
            themeMode = st.activeThemeMode,
            accentHex = st.activeAccentHex
        )

        val p = palette
        val d = resources.displayMetrics.density
        bodyContainer.removeAllViews()

        if (!activeSlice.musicError.isNullOrBlank()) {
            bodyContainer.addView(TextView(requireContext()).apply {
                text = activeSlice.musicError
                textSize = 12f
                setTextColor(p.criticalText)
                background = p.criticalBannerDrawable(d, 10f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            })
        }

        // 1. Tonal Device Hardware Volume (`AudioManager.STREAM_MUSIC`)
        val audioMgr = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
        val curPct = ((curVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
        val barFillColor = if (p.isDark) p.accentPrimary else p.accentInk

        bodyContainer.addView(makeSectionLabel("Tonal Device Volume"))
        val deviceVolRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = p.elevatedPillDrawable(d, 12f, interactive = false)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val devVolPctTv = TextView(requireContext()).apply {
            text = "$curPct%"
            textSize = 12.5f
            typeface = SwonalFonts.monoBold(requireContext())
            fontFeatureSettings = "tnum"
            setTextColor(p.textPrimary)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val devSeek = SeekBar(requireContext()).apply {
            max = maxVol
            progress = curVol
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            }
            progressDrawable = createSliderTrack(p.bgSurfaceSolid, barFillColor, 6f * d)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(barFillColor)
                setSize(dp(16), dp(16))
                setStroke(dp(2), p.bgSurfaceSolid)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                        val sysMax = audioMgr.getStreamMaxVolume(AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
                        val sysProgress = ((progress.toFloat() / maxVol.toFloat()) * sysMax.toFloat()).roundToInt()
                        audioMgr.setStreamVolume(AudioManager.STREAM_SYSTEM, sysProgress, 0)
                        val pct = ((progress.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                        devVolPctTv.text = "$pct%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        deviceVolRow.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_volume)
            setColorFilter(p.accentInk)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        deviceVolRow.addView(devSeek)
        deviceVolRow.addView(devVolPctTv)
        bodyContainer.addView(deviceVolRow)

        // 2. Music Service Switcher (only when >1 configured)
        val configuredProviders = activeSlice.providers.filter { it.configured }
        if (configuredProviders.size > 1) {
            bodyContainer.addView(makeSectionLabel("Music Service"))
            val provRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            configuredProviders.forEach { prov ->
                provRow.addView(
                    makePillButton(prov.displayName, active = activeSlice.preferredProvider == prov.id) {
                        repo.switchMusicProvider(prov.id)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(36)
                        ).apply { marginEnd = dp(8) }
                    }
                )
            }
            bodyContainer.addView(provRow)
        }

        // 3. Active Speaker / Output Devices (on-device librespot sorted first!)
        val librespotDev = SwonalRepository.findLibrespotDevice(activeSlice.devices)
        val sortedDevices = activeSlice.devices.sortedByDescending {
            it.id == librespotDev?.id
        }

        val devHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            addView(TextView(requireContext()).apply {
                text = "ACTIVE SPEAKER (${sortedDevices.size})"
                textSize = 11f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(requireContext())
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(makePillButton("Refresh") { repo.refreshMusicNow() }.apply {
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
            })
        }
        bodyContainer.addView(devHeader)

        if (sortedDevices.isEmpty()) {
            bodyContainer.addView(TextView(requireContext()).apply {
                text = "No speakers detected yet. Ensure Tonal Speakers are running and tap Refresh."
                textSize = 12f
                setTextColor(p.textMuted)
                setPadding(dp(4), dp(6), dp(4), dp(12))
            })
        } else {
            sortedDevices.chunked(2).forEach { pair ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                pair.forEachIndexed { idx, dev ->
                    val active = dev.isActive || dev.id == activeSlice.activeDeviceId
                    val isOnDevice = dev.id == librespotDev?.id
                    val needsPairing = dev.id == "tonal-gym-librespot"
                    val card = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        background = if (active) p.accentMutedDrawable(d, 12f, 2f) else p.elevatedPillDrawable(d, 12f)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (needsPairing) {
                                val uid = activeSlice.activeUserId.orEmpty()
                                val url = "${repo.uiStateFlow.value.serverUrl.trimEnd('/')}/api/spotify/speaker-auth/start?userId=$uid"
                                dismiss()
                                InAppWebLauncher.open(requireContext(), url, "Pair Tonal Speakers", p)
                            } else {
                                repo.transferMusicDevice(dev.id)
                            }
                        }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx == 0 && pair.size == 2) marginEnd = dp(8)
                        }
                        val cleanSpeakerName = dev.name
                            .replace(Regex("\\(?librespot\\)?", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\s{2,}"), " ")
                            .trim()
                            .ifBlank { "Tonal Speakers" }
                        addView(TextView(requireContext()).apply {
                            text = if (isOnDevice && !cleanSpeakerName.contains("Tonal", ignoreCase = true)) {
                                "$cleanSpeakerName (Tonal)"
                            } else {
                                cleanSpeakerName
                            }
                            textSize = 13f
                            typeface = SwonalFonts.interBold(requireContext())
                            setTextColor(if (active) p.accentInk else p.textPrimary)
                        })
                        addView(TextView(requireContext()).apply {
                            text = if (needsPairing) {
                                "Tap to Pair with Spotify →"
                            } else {
                                buildString {
                                    append(dev.type)
                                    if (isOnDevice) append(" • Default")
                                    if (active) append(" • Active")
                                }
                            }
                            textSize = 11f
                            setTextColor(if (needsPairing) p.accentInk else p.textMuted)
                        })
                    }.also { PressFeedback.attachSingle(it) }
                    row.addView(card)
                }
                if (pair.size == 1) {
                    row.addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                bodyContainer.addView(row)
            }
        }

        bodyContainer.addView(makeDivider())

        // 4. Workout Playlists
        val favIds = activeSlice.favoriteIds
        val favCount = activeSlice.playlists.count { favIds.contains(it.id) }
        val plHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(requireContext()).apply {
                text = "WORKOUT PLAYLISTS"
                textSize = 11f
                letterSpacing = 0.08f
                typeface = SwonalFonts.interExtraBold(requireContext())
                setTextColor(p.textMuted)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (favCount > 0) {
                addView(makePillButton("Favorites ($favCount)", active = favoritesOnly) {
                    favoritesOnly = true
                    renderBody()
                }.apply {
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                        marginEnd = dp(6)
                    }
                })
                addView(makePillButton("All (${activeSlice.playlists.size})", active = !favoritesOnly) {
                    favoritesOnly = false
                    renderBody()
                }.apply {
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                })
            }
        }
        bodyContainer.addView(plHeader)

        val sortedPlaylists = activeSlice.playlists
            .filter { !favoritesOnly || favIds.contains(it.id) }
            .sortedByDescending { favIds.contains(it.id) }

        if (sortedPlaylists.isEmpty()) {
            bodyContainer.addView(TextView(requireContext()).apply {
                text = if (favoritesOnly) {
                    "No favorite playlists pinned yet. Tap the star next to any playlist."
                } else {
                    "No workout playlists loaded yet — tap Refresh above or connect Spotify in Application Settings."
                }
                textSize = 12f
                setTextColor(p.textMuted)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
        }

        sortedPlaylists.forEach { pl ->
            val isFav = favIds.contains(pl.id)
            val isExpanded = expandedPlaylistId == pl.id

            val plCard = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = p.elevatedPillDrawable(d, 12f, interactive = false)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val topRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            topRow.addView(ImageView(requireContext()).apply {
                setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star)
                setColorFilter(if (isFav) p.accentInk else p.textMuted)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
                setPadding(dp(5), dp(5), dp(5), dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener { repo.toggleFavoritePlaylist(pl.id) }
            }.also { PressFeedback.attachSingle(it) })

            topRow.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (expandedPlaylistId == pl.id) {
                        expandedPlaylistId = null
                        renderBody()
                    } else {
                        expandedPlaylistId = pl.id
                        trackErrors.remove(pl.id)
                        renderBody()
                        if (!tracksCache.containsKey(pl.id)) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                repo.loadPlaylistTracks(pl.id)
                                    .onSuccess { list ->
                                        tracksCache[pl.id] = list
                                        trackErrors.remove(pl.id)
                                    }
                                    .onFailure { err ->
                                        trackErrors[pl.id] = err.message ?: "Failed to load tracks"
                                    }
                                renderBody()
                            }
                        }
                    }
                }
                addView(TextView(requireContext()).apply {
                    text = pl.name
                    textSize = 13.5f
                    typeface = SwonalFonts.interBold(requireContext())
                    setTextColor(p.textPrimary)
                })
                addView(TextView(requireContext()).apply {
                    text = "${pl.trackCount} tracks • ${if (isExpanded) "Hide tracks" else "View tracks"}"
                    textSize = 11f
                    setTextColor(p.textMuted)
                })
            })

            // Crisp, unclipped Play button (zero vertical padding + centered gravity + 38dp height)
            topRow.addView(makeAccentButton("Play") {
                repo.playMusicUri(pl.uri)
                dismiss()
            }.apply {
                textSize = 13f
                minWidth = dp(68)
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            })

            plCard.addView(topRow)

            if (isExpanded) {
                val errMsg = trackErrors[pl.id]
                val tracks = tracksCache[pl.id]
                when {
                    errMsg != null -> {
                        plCard.addView(TextView(requireContext()).apply {
                            text = "$errMsg (tap playlist to retry)"
                            textSize = 11.5f
                            setTextColor(p.criticalText)
                            setPadding(dp(8), dp(8), dp(8), dp(4))
                        })
                    }
                    tracks == null -> {
                        plCard.addView(TextView(requireContext()).apply {
                            text = "Loading tracks..."
                            textSize = 11.5f
                            setTextColor(p.textMuted)
                            setPadding(dp(8), dp(8), dp(8), dp(4))
                        })
                    }
                    else -> {
                        tracks.take(25).forEach { tr ->
                            val trackRow = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                background = p.surfaceCardDrawable(d, 8f)
                                setPadding(dp(10), dp(8), dp(10), dp(8))
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { topMargin = dp(5) }
                                isClickable = true
                                isFocusable = true
                                setOnClickListener {
                                    repo.playMusicUri(tr.uri)
                                    dismiss()
                                }
                                addView(LinearLayout(requireContext()).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    addView(TextView(requireContext()).apply {
                                        text = tr.name
                                        textSize = 12f
                                        typeface = SwonalFonts.interBold(requireContext())
                                        setTextColor(p.textPrimary)
                                    })
                                    addView(TextView(requireContext()).apply {
                                        text = tr.artistName
                                        textSize = 10.5f
                                        setTextColor(p.textMuted)
                                    })
                                })
                                addView(ImageView(requireContext()).apply {
                                    setImageResource(R.drawable.ic_play)
                                    setColorFilter(p.accentInk)
                                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                                })
                            }.also { PressFeedback.attachSingle(it) }
                            plCard.addView(trackRow)
                        }
                    }
                }
            }

            bodyContainer.addView(plCard)
        }
    }

    private fun createSliderTrack(trackColor: Int, fillColor: Int, heightPx: Float): LayerDrawable {
        val radius = heightPx * 0.5f
        val bg = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = radius
            setSize(-1, heightPx.toInt().coerceAtLeast(4))
        }
        val fg = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            setSize(-1, heightPx.toInt().coerceAtLeast(4))
        }
        val clip = ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)
        val insetY = dp(6)
        return LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(0, 0, insetY, 0, insetY)
            setLayerInset(1, 0, insetY, 0, insetY)
        }
    }
}
