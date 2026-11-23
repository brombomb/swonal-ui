package com.swonal.kiosk.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.swonal.kiosk.BuildConfig
import com.swonal.kiosk.R
import com.swonal.kiosk.SwonalApp
import com.swonal.kiosk.audio.SwonalSoundEngine
import com.swonal.kiosk.databinding.ActivityKioskBinding
import com.swonal.kiosk.model.ArmAlignmentHelper
import com.swonal.kiosk.model.ArmAlignmentStatus
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.model.SymmetryStatus
import com.swonal.kiosk.model.TelemetryFrame
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.VbtMetrics
import com.swonal.kiosk.net.KioskEffect
import com.swonal.kiosk.net.KioskUiState
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.net.TelemetryDisplayMode
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import com.swonal.kiosk.ui.dialogs.AppDrawerDialog
import com.swonal.kiosk.ui.dialogs.AuthLockDialog
import com.swonal.kiosk.ui.dialogs.CoachPromptDialog
import com.swonal.kiosk.ui.dialogs.CompanionQrDialog
import com.swonal.kiosk.ui.dialogs.DiagnosticsDialog
import com.swonal.kiosk.ui.dialogs.MusicPickerDialog
import com.swonal.kiosk.ui.dialogs.PostWorkoutSummaryDialog
import com.swonal.kiosk.ui.dialogs.ProfileStyleDialog
import com.swonal.kiosk.ui.dialogs.SwocialDialog
import com.swonal.kiosk.ui.dialogs.WeightKeypadDialog
import com.swonal.kiosk.ui.dialogs.WorkoutLibraryDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskBinding
    private lateinit var app: SwonalApp

    private var currentPalette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private var lastPaletteKey: String = ""
    private var lastUsersKey: String = ""
    private var lastMediaUrl: String? = null
    private var lastAlbumArtUrl: String? = null
    private var exoPlayer: ExoPlayer? = null
    private var seekingVolume: Boolean = false
    private var seekingDeviceVolume: Boolean = false
    private lateinit var audioManager: AudioManager
    private var statusDotAnimator: ObjectAnimator? = null

    // 30Hz hot-path state caches (eliminates per-frame Drawable allocations & requestLayout() storms!)
    private var lastRepCount: Int = Int.MIN_VALUE
    private var lastWeightCenterInfo: String = ""
    private var lastArmSyncKey: String = ""
    private var lastArmExerciseId: String? = null
    private var lastArmStatus: ArmAlignmentStatus? = null
    private var lastAccessory: String = ""
    private var confirmedAccessoryExerciseId: String? = null
    private var confirmedAccessoryName: String? = null
    private var lastModeKey: String = ""
    private var lastSymStatus: SymmetryStatus? = null
    private var lastSymBadgeText: String = ""
    private var lastLeftPct: Int = Int.MIN_VALUE
    private var lastRightPct: Int = Int.MIN_VALUE
    private var lastVelCurrentStr: String = ""
    private var lastVelUnitStr: String = ""
    private var lastVelPeakStr: String = ""
    private var lastVelDropStr: String = ""
    private var lastVelDropWarn: Boolean? = null
    private var lastFatigueAlert: Boolean? = null
    private var lastHardwareStatusKey: String = ""
    private var lastPartnerKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        app = application as SwonalApp
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleLaunchIntent(intent)

        binding.leftCableBar.label = "LEFT"
        binding.rightCableBar.label = "RIGHT"
        applyTypographyAndClipping()
        wireClickListeners()
        syncDeviceVolumeSlider()

        app.repository.start()
        observeStateFlows()
    }

    override fun onResume() {
        super.onResume()
        syncDeviceVolumeSlider()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            binding.root.post { syncDeviceVolumeSlider() }
        }
        return handled
    }

    override fun onStart() {
        super.onStart()
        app.repository.markUiHeartbeat(true)
        // Re-sync video player if Form Demo card was active before backgrounding
        val ui = app.repository.uiStateFlow.value
        val mediaUrl = resolveMediaUrl(ui.serverUrl, ui.activeExercise?.mediaUrl)
        if (ui.showDemoPip && !mediaUrl.isNullOrBlank()) {
            loadFormDemoMedia(mediaUrl)
        }
    }

    override fun onStop() {
        super.onStop()
        app.repository.markUiHeartbeat(false)
        // Physical safety: disengage live resistance if user switches away from kiosk
        if (app.repository.isWeightCurrentlyEngaged()) {
            app.repository.disableWeight()
        }
        releaseFormDemoVideo()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusDotAnimator?.cancel()
        releaseFormDemoVideo()
    }

    /**
     * Only accept `server_url` Intent extras in debug builds to prevent malicious local apps
     * from hijacking the exported MainActivity in production.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val urlExtra = intent?.getStringExtra("server_url")?.trim()
        if (!urlExtra.isNullOrBlank() && urlExtra != app.prefs.serverUrl) {
            app.repository.updateConnectionEndpoints(urlExtra, app.prefs.localDaemonUrl)
        }
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyTypographyAndClipping() {
        val interBold = SwonalFonts.interBold(this)
        val interExtraBold = SwonalFonts.interExtraBold(this)
        val interBlack = SwonalFonts.interBlack(this)
        val monoBold = SwonalFonts.monoBold(this)
        val monoExtraBold = SwonalFonts.monoExtraBold(this)

        // Display & label Inter weights
        binding.tvExerciseTitle.typeface = interBlack
        binding.tvRoutinePill.typeface = interExtraBold
        binding.tvMuscleGroup.typeface = interBold
        binding.tvExerciseSubtitle.typeface = interBold
        binding.btnCompleteSet.typeface = interBlack
        binding.btnFinishWorkout.typeface = interBold
        binding.tvUpNextTitle.typeface = interExtraBold
        binding.tvTrackTitle.typeface = interExtraBold
        binding.tvActiveSpeaker.typeface = interBold
        binding.tvSymmetryHeader.typeface = interBlack
        binding.tvVelocityHeader.typeface = interBlack
        listOf(
            binding.btnModeStandard,
            binding.btnModeEccentric,
            binding.btnModeChains,
            binding.btnModeSpotter,
            binding.btnModeBurnout
        ).forEach { it.typeface = interExtraBold }

        // JetBrains Mono + tabular-nums (`tnum`) across all live numeric readouts & steppers
        listOf(
            binding.tvCompletedReps,
            binding.tvTargetReps,
            binding.tvRestCountdown,
            binding.btnStepMinusLarge,
            binding.btnStepMinusSmall,
            binding.btnStepPlusSmall,
            binding.btnStepPlusLarge,
            binding.tvVelocityCurrent
        ).forEach {
            it.typeface = monoExtraBold
            it.fontFeatureSettings = "tnum"
        }

        listOf(
            binding.tvWeightCenterInfo,
            binding.tvPrBadge,
            binding.tvSymmetryBadge,
            binding.tvSymLeftPct,
            binding.tvSymRightPct,
            binding.tvVelocityDrop,
            binding.tvVelocityPeak,
            binding.tvVelocityUnit,
            binding.tvDeviceVolLabel,
            binding.tvDeviceVolumePct,
            binding.tvSpotifyVolLabel,
            binding.tvVolumePct,
            binding.tvArmSync,
            binding.tvUpNextMeta,
            binding.tvCenteredStepStatus,
            binding.tvHardwareStatus,
            binding.btnAccHandles,
            binding.btnAccBar,
            binding.btnAccRope
        ).forEach {
            it.typeface = monoBold
            it.fontFeatureSettings = "tnum"
        }

        // Clip rounded corners on album art & Form Demo video/image frame
        val d = resources.displayMetrics.density
        applyRoundedClip(binding.albumArtFrame, 10f * d)
        applyRoundedClip(binding.formDemoMediaFrame, 10f * d)
    }

    private fun applyRoundedClip(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    private fun wireClickListeners() {
        val repo = app.repository

        // Top Bar Controls
        binding.btnPartnerMode.setOnClickListener { repo.togglePartnerMode() }
        binding.btnQuickPrefs.setOnClickListener { anchor ->
            val ui = repo.uiStateFlow.value
            KioskPopups.showQuickPreferences(
                context = this,
                anchor = anchor,
                initialUi = ui,
                palette = currentPalette,
                onToggleUnit = { repo.toggleUnit() },
                onToggleTheme = { repo.toggleTheme() },
                onToggleDemoPip = { repo.setShowDemoPip(!repo.uiStateFlow.value.showDemoPip) },
                onCycleTelemetry = { repo.cycleTelemetryDisplayMode() },
                onOpenProfiles = { showDialogOnce("profiles") { ProfileStyleDialog() } },
                onOpenDiagnostics = { showDialogOnce("diagnostics") { DiagnosticsDialog() } },
                onOpenMenu = { showDialogOnce("app_drawer") { AppDrawerDialog() } },
                onOpenWebPath = { path, title -> openServerWebPath(path, title) }
            )
        }
        binding.btnSwocial.setOnClickListener {
            showDialogOnce("swocial") { SwocialDialog() }
        }
        binding.btnCompanionQr.setOnClickListener {
            showDialogOnce("qr") { CompanionQrDialog() }
        }
        binding.btnAppDrawer.setOnClickListener {
            showDialogOnce("app_drawer") { AppDrawerDialog() }
        }
        binding.btnHardwareStatus.setOnClickListener {
            showDialogOnce("diagnostics") { DiagnosticsDialog() }
        }

        // Banners
        binding.btnDismissQueued.setOnClickListener { repo.clearQueuedWorkout() }
        binding.btnStartQueued.setOnClickListener { repo.startQueuedWorkout() }
        binding.btnAckSafety.setOnClickListener { repo.acknowledgeSafetyEvent() }

        // Exercise Header Action Column
        binding.btnWorkoutLibrary.setOnClickListener {
            showDialogOnce("library") { WorkoutLibraryDialog() }
        }
        binding.btnTodayRoutine.setOnClickListener {
            val tpl = findTodayScheduledRoutine(repo.uiStateFlow.value)
            if (tpl != null) {
                repo.startWorkoutTemplate(tpl)
            }
        }
        val openArmGuide: (View) -> Unit = { anchor ->
            val ui = repo.uiStateFlow.value
            KioskPopups.showArmPositionGuide(
                context = this,
                anchor = anchor,
                palette = currentPalette,
                telemetry = repo.telemetryFlow.value,
                exercise = ui.activeExercise,
                isMockMode = ui.isMockMode,
                onSyncMockArms = if (ui.isMockMode) { { repo.syncMockArmsToActiveExercise() } } else null,
                liveTelemetryProvider = { repo.telemetryFlow.value }
            )
        }
        binding.btnArmSync.setOnClickListener { openArmGuide(binding.btnArmInfo) }
        binding.btnArmInfo.setOnClickListener { anchor -> openArmGuide(anchor) }
        val confirmAttachment: (String) -> Unit = { acc ->
            val curExId = repo.uiStateFlow.value.activeExercise?.id
            confirmedAccessoryExerciseId = curExId
            confirmedAccessoryName = acc
            lastAccessory = ""
            repo.setAccessory(acc)
            renderAccessoryButtonsIfChanged(acc)
        }
        binding.btnAccHandles.setOnClickListener { confirmAttachment("HANDLES") }
        binding.btnAccBar.setOnClickListener { confirmAttachment("BAR") }
        binding.btnAccRope.setOnClickListener { confirmAttachment("ROPE") }

        // Docked Stepper & Form Demo Row
        binding.btnPrevExercise.setOnClickListener { repo.stepRoutine(-1) }
        binding.btnNextExercise.setOnClickListener { repo.stepRoutine(1) }
        binding.btnCloseFormDemo.setOnClickListener { repo.setShowDemoPip(false) }
        binding.btnRestoreFormDemo.setOnClickListener { repo.setShowDemoPip(true) }

        // Load Dial Interactions
        binding.loadDialView.onToggleWeight = { repo.toggleWeight() }
        binding.loadDialView.onEditWeightClick = {
            if (!repo.isWeightCurrentlyEngaged()) {
                showDialogOnce("weight_keypad") { WeightKeypadDialog() }
            }
        }

        // Rest Timer Controls
        binding.btnRestAdd15.setOnClickListener { repo.addRestSeconds(15) }
        binding.btnRestSkip.setOnClickListener { repo.skipRestTimer() }

        // Weight Stepper Strip
        binding.btnStepMinusLarge.setOnClickListener {
            val step = if (Units.isKg(repo.uiStateFlow.value.activeUnit)) -2.5 else -5.0
            repo.stepWeight(step)
        }
        binding.btnStepMinusSmall.setOnClickListener {
            val step = if (Units.isKg(repo.uiStateFlow.value.activeUnit)) -0.5 else -1.0
            repo.stepWeight(step)
        }
        binding.btnStepPlusSmall.setOnClickListener {
            val step = if (Units.isKg(repo.uiStateFlow.value.activeUnit)) 0.5 else 1.0
            repo.stepWeight(step)
        }
        binding.btnStepPlusLarge.setOnClickListener {
            val step = if (Units.isKg(repo.uiStateFlow.value.activeUnit)) 2.5 else 5.0
            repo.stepWeight(step)
        }

        // PR & Progressive Overload
        binding.prBadgePill.setOnClickListener { openServerWebPath("/history", "History & PRs") }
        binding.overloadReadyPill.setOnClickListener {
            val sug = repo.uiStateFlow.value.overloadSuggestion ?: return@setOnClickListener
            if (sug.ready && sug.recommendedWeightLb >= Units.MIN_WEIGHT_LB) {
                repo.setWeight(sug.recommendedWeightLb, allowWhileEngaged = false)
            }
        }

        // VBT Fatigue Spotter Alert
        binding.btnEnableSpotterAlert.setOnClickListener { repo.setDynamicMode("spotter") }

        // Set Control Deck
        binding.btnCompleteSet.setOnClickListener { repo.logCompletedSet() }
        binding.btnFinishWorkout.setOnClickListener { repo.finishWorkout() }
        binding.btnModeStandard.setOnClickListener { repo.setDynamicMode("standard") }
        binding.btnModeEccentric.setOnClickListener { repo.setDynamicMode("eccentric") }
        binding.btnModeChains.setOnClickListener { repo.setDynamicMode("chains") }
        binding.btnModeSpotter.setOnClickListener { repo.setDynamicMode("spotter") }
        binding.btnModeBurnout.setOnClickListener { repo.setDynamicMode("burnout") }

        // Voice Coach
        binding.btnOpenCoachModal.setOnClickListener {
            showDialogOnce("coach") { CoachPromptDialog() }
        }
        binding.btnCoachSuggestedAction.setOnClickListener {
            val act = repo.uiStateFlow.value.coachAction ?: return@setOnClickListener
            repo.executeCoachAction(act)
        }

        // Music Dock
        binding.btnDismissMusicError.setOnClickListener { repo.clearMusicError() }
        binding.btnMusicPrev.setOnClickListener { repo.musicPrevious() }
        binding.btnMusicPlayPause.setOnClickListener { repo.toggleMusicPlayPause() }
        binding.btnMusicNext.setOnClickListener { repo.musicNext() }
        binding.btnMuteChimes.setOnClickListener {
            repo.toggleSoundMuted()
            app.soundEngine.muted = repo.uiStateFlow.value.soundMuted
        }
        binding.btnMusicPicker.setOnClickListener {
            showDialogOnce("music_picker") { MusicPickerDialog() }
        }
        binding.btnSelectSpeaker.setOnClickListener { anchor ->
            repo.refreshMusicNow()
            KioskPopups.showSpeakerPicker(
                context = this,
                anchor = anchor,
                ui = repo.uiStateFlow.value,
                palette = currentPalette,
                onSelectDevice = { devId -> repo.transferMusicDevice(devId) },
                onRefresh = { repo.refreshMusicNow() },
                onOpenMusicLibrary = { showDialogOnce("music_picker") { MusicPickerDialog() } }
            )
        }
        binding.deviceVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvDeviceVolumePct.text = "$progress%"
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val target = ((progress / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    val sysMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
                    val sysTarget = ((progress / 100f) * sysMax).roundToInt().coerceIn(0, sysMax)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, sysTarget, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekingDeviceVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekingDeviceVolume = false
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val target = ((binding.deviceVolumeSeek.progress / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val sysMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
                val sysTarget = ((binding.deviceVolumeSeek.progress / 100f) * sysMax).roundToInt().coerceIn(0, sysMax)
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, sysTarget, 0)
                app.soundEngine.play(SwonalSoundEngine.Cue.COUNTDOWN_TICK)
                syncDeviceVolumeSlider()
            }
        })
        binding.musicVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvVolumePct.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekingVolume = false
                seekBar?.let { repo.setMusicVolume(it.progress) }
            }
        })

        // Tactile press-scale, opacity dip & haptic/click feedback on all interactive kiosk buttons
        PressFeedback.attach(
            binding.btnPartnerMode,
            binding.btnQuickPrefs,
            binding.btnSwocial,
            binding.btnCompanionQr,
            binding.btnAppDrawer,
            binding.btnHardwareStatus,
            binding.btnDismissQueued,
            binding.btnStartQueued,
            binding.btnAckSafety,
            binding.btnWorkoutLibrary,
            binding.btnTodayRoutine,
            binding.btnArmSync,
            binding.btnArmInfo,
            binding.btnAccHandles,
            binding.btnAccBar,
            binding.btnAccRope,
            binding.btnPrevExercise,
            binding.btnNextExercise,
            binding.btnCloseFormDemo,
            binding.btnRestoreFormDemo,
            binding.btnRestAdd15,
            binding.btnRestSkip,
            binding.btnStepMinusLarge,
            binding.btnStepMinusSmall,
            binding.btnStepPlusSmall,
            binding.btnStepPlusLarge,
            binding.prBadgePill,
            binding.overloadReadyPill,
            binding.btnEnableSpotterAlert,
            binding.btnCompleteSet,
            binding.btnFinishWorkout,
            binding.btnModeStandard,
            binding.btnModeEccentric,
            binding.btnModeChains,
            binding.btnModeSpotter,
            binding.btnModeBurnout,
            binding.btnOpenCoachModal,
            binding.btnCoachSuggestedAction,
            binding.btnDismissMusicError,
            binding.btnMusicPrev,
            binding.btnMusicPlayPause,
            binding.btnMusicNext,
            binding.btnMuteChimes,
            binding.btnMusicPicker,
            binding.btnSelectSpeaker
        )
    }

    private fun syncDeviceVolumeSlider() {
        if (seekingDeviceVolume || !::audioManager.isInitialized) return
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = ((curVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        binding.deviceVolumeSeek.progress = pct
        binding.tvDeviceVolumePct.text = "$pct%"
    }

    private fun observeStateFlows() {
        val repo = app.repository
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 0. Main-thread watchdog heartbeat (stops automatically if UI thread freezes or stops)
                launch {
                    while (isActive) {
                        repo.markUiHeartbeat(true)
                        delay(500L)
                    }
                }

                // 1. High-Frequency 30Hz Telemetry Hot Path (Canvas + strictly diffed discrete labels)
                launch {
                    repo.telemetryFlow.collect { frame ->
                        renderTelemetryHotPath(frame, repo.uiStateFlow.value)
                    }
                }

                // 2. High-Frequency 30Hz VBT & Symmetry Metrics
                launch {
                    repo.vbtFlow.collect { vbt ->
                        renderVbtHotPath(vbt, repo.uiStateFlow.value)
                    }
                }

                // 3. Discrete UI / Session / Music / Theme State
                launch {
                    repo.uiStateFlow.collect { ui ->
                        renderDiscreteUiState(ui, repo.telemetryFlow.value)
                    }
                }

                // 4. One-shot Effects (SoundPool chimes, Workout Summary, Toasts)
                launch {
                    repo.effectsFlow.collect { effect ->
                        when (effect) {
                            is KioskEffect.PlayCue -> {
                                app.soundEngine.muted = repo.uiStateFlow.value.soundMuted
                                app.soundEngine.play(effect.cue)
                            }
                            is KioskEffect.ShowWorkoutSummary -> {
                                showDialogOnce("summary") {
                                    PostWorkoutSummaryDialog.newInstance(effect.session, effect.prHits)
                                }
                            }
                            is KioskEffect.ShowMessage -> {
                                Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resetHotPathCaches() {
        lastRepCount = Int.MIN_VALUE
        lastWeightCenterInfo = ""
        lastArmSyncKey = ""
        lastAccessory = ""
        lastModeKey = ""
        lastSymStatus = null
        lastSymBadgeText = ""
        lastLeftPct = Int.MIN_VALUE
        lastRightPct = Int.MIN_VALUE
        lastVelCurrentStr = ""
        lastVelUnitStr = ""
        lastVelPeakStr = ""
        lastVelDropStr = ""
        lastVelDropWarn = null
        lastFatigueAlert = null
        lastHardwareStatusKey = ""
        lastPartnerKey = ""
    }

    /** 30Hz hot path: updates custom Canvas views directly; skips all unchanged Drawables & text! */
    private fun renderTelemetryHotPath(frame: TelemetryFrame, ui: KioskUiState) {
        val unit = ui.activeUnit
        binding.loadDialView.updateTelemetry(
            enabled = frame.weightEnabled,
            targetLb = frame.targetWeightLb,
            appliedLb = frame.appliedResistanceLb,
            unit = unit
        )
        binding.leftCableBar.updateCable(frame.leftCable, unit)
        binding.rightCableBar.updateCable(frame.rightCable, unit)

        if (frame.repCount != lastRepCount) {
            lastRepCount = frame.repCount
            binding.tvCompletedReps.text = frame.repCount.toString()
        }

        val pr1Rm = ui.activeExercisePr?.oneRepMaxEst
        val centerInfo = when {
            pr1Rm != null && pr1Rm > 0 -> "${((frame.targetWeightLb / pr1Rm) * 100.0).roundToInt()}% 1RM"
            frame.modifierPct > 0 -> "+${frame.modifierPct}% ${frame.profileMode.uppercase(Locale.US)}"
            Units.isKg(unit) -> "2.5–90 KG"
            else -> "5–200 LB"
        }
        if (centerInfo != lastWeightCenterInfo) {
            lastWeightCenterInfo = centerInfo
            binding.tvWeightCenterInfo.text = centerInfo
        }

        val showTelemetry = ui.telemetryDisplayMode == TelemetryDisplayMode.ALWAYS
        val targetVis = if (showTelemetry) View.VISIBLE else View.GONE
        if (binding.telemetryStrip.visibility != targetVis) {
            binding.telemetryStrip.visibility = targetVis
            if (targetVis == View.VISIBLE) {
                renderVbtHotPath(app.repository.vbtFlow.value, ui)
            }
        }

        renderArmAlignmentBadgeIfChanged(frame, ui)
        renderAccessoryButtonsIfChanged(frame.accessory)
        renderDynamicModeButtonsIfChanged(frame.profileMode, frame.modifierPct)
    }

    private fun renderVbtHotPath(vbt: VbtMetrics, ui: KioskUiState) {
        if (binding.telemetryStrip.visibility != View.VISIBLE) return
        val unit = ui.activeUnit
        val sym = vbt.symmetry
        val p = currentPalette
        val d = resources.displayMetrics.density

        binding.symmetrySliderView.updateSymmetry(sym)
        binding.velocityGaugeView.updateFillPercent(vbt.gaugeFillPercent)

        if (sym.leftLoadPct != lastLeftPct) {
            lastLeftPct = sym.leftLoadPct
            binding.tvSymLeftPct.text = "L ${sym.leftLoadPct}%"
        }
        if (sym.rightLoadPct != lastRightPct) {
            lastRightPct = sym.rightLoadPct
            binding.tvSymRightPct.text = "R ${sym.rightLoadPct}%"
        }

        val badgeStr = String.format(Locale.US, "%s • Δ %.1f", sym.status.name, sym.positionDelta)
        if (badgeStr != lastSymBadgeText) {
            lastSymBadgeText = badgeStr
            binding.tvSymmetryBadge.text = badgeStr
        }
        if (sym.status != lastSymStatus) {
            lastSymStatus = sym.status
            when (sym.status) {
                SymmetryStatus.CRITICAL -> {
                    binding.tvSymmetryBadge.background = p.criticalBannerDrawable(d, 8f)
                    binding.tvSymmetryBadge.setTextColor(p.criticalText)
                }
                SymmetryStatus.DRIFT -> {
                    binding.tvSymmetryBadge.background = p.warningPillDrawable(d, 8f)
                    binding.tvSymmetryBadge.setTextColor(p.warningText)
                }
                SymmetryStatus.BALANCED -> {
                    binding.tvSymmetryBadge.background = p.accentMutedDrawable(d, 8f, 1f, interactive = false)
                    binding.tvSymmetryBadge.setTextColor(p.accentInk)
                }
            }
        }

        val velStr = Units.formatVelocityValue(vbt.currentVelocityCmSec, unit)
        if (velStr != lastVelCurrentStr) {
            lastVelCurrentStr = velStr
            binding.tvVelocityCurrent.text = velStr
        }
        val velUnit = Units.velocityUnitLabel(unit)
        if (velUnit != lastVelUnitStr) {
            lastVelUnitStr = velUnit
            binding.tvVelocityUnit.text = velUnit
        }
        val peakStr = "Peak ${Units.formatVelocity(vbt.peakSetVelocityCmSec, unit)}"
        if (peakStr != lastVelPeakStr) {
            lastVelPeakStr = peakStr
            binding.tvVelocityPeak.text = peakStr
        }
        val dropStr = String.format(Locale.US, "-%.0f%% Velocity Drop", vbt.velocityDropPct)
        if (dropStr != lastVelDropStr) {
            lastVelDropStr = dropStr
            binding.tvVelocityDrop.text = dropStr
        }
        val dropWarn = vbt.velocityDropPct >= 25.0
        if (dropWarn != lastVelDropWarn) {
            lastVelDropWarn = dropWarn
            binding.tvVelocityDrop.setTextColor(if (dropWarn) p.warningText else p.accentInk)
        }
        if (vbt.fatigueAlert != lastFatigueAlert) {
            lastFatigueAlert = vbt.fatigueAlert
            binding.btnEnableSpotterAlert.visibility = if (vbt.fatigueAlert) View.VISIBLE else View.GONE
            binding.tvVelocityPeak.visibility = if (vbt.fatigueAlert) View.GONE else View.VISIBLE
        }
    }

    private fun findTodayScheduledRoutine(ui: KioskUiState): com.swonal.kiosk.model.WorkoutTemplate? {
        val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0=Sun..6=Sat
        val activeUserId = ui.activeUser?.id
        val matchingForUser = ui.templates.firstOrNull { tpl ->
            todayDow in tpl.scheduleDays && (activeUserId == null || tpl.userId == activeUserId)
        }
        return matchingForUser ?: ui.templates.firstOrNull { tpl -> todayDow in tpl.scheduleDays }
    }

    private fun renderDiscreteUiState(ui: KioskUiState, telemetry: TelemetryFrame) {
        // 1. Opaque Auth Lock Privacy Scrim + Dialog
        binding.authLockScrim.visibility = if (ui.authRequired) View.VISIBLE else View.GONE
        if (ui.authRequired) {
            showDialogOnce("auth_lock") { AuthLockDialog() }
        }

        // 2. Theme & Accent Palette (honors "dark" | "light" | "system")
        val sysNight = SwonalThemeTokens.isSystemNight(this)
        val paletteKey = "${ui.activeThemeMode}|${ui.activeAccentHex}|$sysNight"
        if (paletteKey != lastPaletteKey) {
            lastPaletteKey = paletteKey
            currentPalette = SwonalThemeTokens.resolve(ui.activeThemeMode, ui.activeAccentHex, sysNight)
            resetHotPathCaches()
            applyPaletteToAllViews(currentPalette)
        }
        val p = currentPalette
        val d = resources.displayMetrics.density

        // 3. Household Profile Pills
        val usersKey = ui.users.joinToString("|") { "${it.id}:${it.name}:${it.accentColor}" } +
            "::active=${ui.activeUser?.id}::dark=${p.isDark}"
        if (usersKey != lastUsersKey) {
            lastUsersKey = usersKey
            rebuildProfilePills(ui)
        }

        // 4. Partner Work-In Toggle (diffed to avoid per-second drawable reallocations)
        val todayScheduled = findTodayScheduledRoutine(ui)
        if (todayScheduled != null && ui.activeTemplateName != todayScheduled.name) {
            binding.btnTodayRoutine.visibility = View.VISIBLE
            binding.tvTodayRoutine.text = "Today: ${todayScheduled.name} ▶"
        } else {
            binding.btnTodayRoutine.visibility = View.GONE
        }

        val partnerKey = "${ui.users.size >= 2}|${ui.sessionState.partnerModeEnabled}|${ui.activeUser?.name}|${ui.partnerUser?.name}"
        if (partnerKey != lastPartnerKey) {
            lastPartnerKey = partnerKey
            if (ui.users.size >= 2) {
                binding.btnPartnerMode.visibility = View.VISIBLE
                val enabled = ui.sessionState.partnerModeEnabled
                binding.tvPartnerLabel.text = if (enabled) {
                    "Work In: ${ui.activeUser?.name ?: "A"} ⇄ ${ui.partnerUser?.name ?: "B"}"
                } else {
                    "Work In"
                }
                binding.btnPartnerMode.background = if (enabled) p.accentPillDrawable(d, 12f) else p.elevatedPillDrawable(d, 12f)
                val fg = if (enabled) p.accentTextColor else p.textPrimary
                binding.tvPartnerLabel.setTextColor(fg)
                binding.ivPartnerIcon.setColorFilter(if (enabled) fg else p.accentInk)
            } else {
                binding.btnPartnerMode.visibility = View.GONE
            }
        }

        // 5. Hardware Telemetry Status Pill + Pulsing Connected Dot
        val hwKey = "${ui.isUsingLocalFailSafe}|${ui.wsConnected}|${ui.daemonConnected}|${ui.isMockMode}"
        if (hwKey != lastHardwareStatusKey) {
            lastHardwareStatusKey = hwKey
            val dotDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val connectedLive = ui.wsConnected && ui.daemonConnected
            when {
                ui.isUsingLocalFailSafe -> {
                    binding.btnHardwareStatus.background = p.warningPillDrawable(d, 12f, interactive = true)
                    binding.tvHardwareStatus.text = "LOCAL FAIL-SAFE"
                    binding.tvHardwareStatus.setTextColor(p.warningText)
                    dotDrawable.setColor(0xFFF59E0B.toInt())
                }
                connectedLive -> {
                    binding.btnHardwareStatus.background = p.accentMutedDrawable(d, 12f, 1.5f)
                    binding.tvHardwareStatus.text = if (ui.isMockMode) "MOCK 30Hz" else "TONAL CDC 30Hz"
                    binding.tvHardwareStatus.setTextColor(p.accentInk)
                    dotDrawable.setColor(if (p.isDark) p.accentPrimary else p.accentInk)
                }
                else -> {
                    binding.btnHardwareStatus.background = p.warningPillDrawable(d, 12f, interactive = true)
                    binding.tvHardwareStatus.text = "DAEMON WAIT"
                    binding.tvHardwareStatus.setTextColor(p.warningText)
                    dotDrawable.setColor(0xFFFBBF24.toInt())
                }
            }
            binding.statusDot.background = dotDrawable
            updateStatusDotPulse(connectedLive || ui.isUsingLocalFailSafe)
        }

        // 6. Queued Workout & Safety Cutout Banners
        val queued = ui.sessionState.queuedWorkout
        if (queued != null) {
            binding.queuedWorkoutBanner.visibility = View.VISIBLE
            binding.tvQueuedTitle.text = queued.templateName
        } else {
            binding.queuedWorkoutBanner.visibility = View.GONE
        }

        val safety = ui.safetyEvent
        if (safety != null) {
            binding.safetyCutoutBanner.visibility = View.VISIBLE
            binding.tvSafetyTitle.text = "SAFETY CUTOUT: ${safety.reason}"
            val deltaStr = safety.delta?.let { String.format(Locale.US, " (Bar Δ %.1f)", it) } ?: ""
            binding.tvSafetyBody.text = "Resistance safely disabled$deltaStr. Level cables before re-engaging."
        } else {
            binding.safetyCutoutBanner.visibility = View.GONE
        }

        // 7. Exercise Header Card
        val ex = ui.activeExercise
        val step = ui.currentStep
        binding.tvRoutinePill.text = if (ui.activeTemplateName != null && step != null) {
            "${ui.activeTemplateName} • ${step.blockType.replace('_', ' ')} • Step ${ui.currentStepIdx + 1}/${ui.routineSteps.size}"
        } else {
            "FREE LIFT MODE"
        }
        binding.tvMuscleGroup.text = ex?.muscleGroup ?: ""
        binding.tvExerciseTitle.text = ex?.name ?: "Select Movement"
        binding.tvExerciseSubtitle.text = if (step != null) {
            "Set ${step.setNumber} of ${step.totalSetsInItem} • Target ${step.targetReps} reps"
        } else {
            ex?.notes ?: "Tap weight gauge or use handle click to engage"
        }

        // 8. Docked Stepper & Form Demo Row (Fixed height = zero layout jump)
        val inRoutine = ui.routineSteps.isNotEmpty()
        binding.tvPrevLabel.text = if (inRoutine) "Prev Step" else "Prev Exercise"
        binding.tvNextLabel.text = if (inRoutine) "Next Step" else "Next Exercise"

        val mediaUrl = resolveMediaUrl(ui.serverUrl, ex?.mediaUrl)
        if (ui.showDemoPip && !mediaUrl.isNullOrBlank() && ex != null) {
            binding.formDemoCard.visibility = View.VISIBLE
            binding.tvCenteredStepStatus.visibility = View.GONE
            binding.btnRestoreFormDemo.visibility = View.GONE
            binding.tvFormDemoName.text = ex.name
            loadFormDemoMedia(mediaUrl)
        } else {
            binding.formDemoCard.visibility = View.GONE
            releaseFormDemoVideo()
            if (ui.activeTemplateName != null && ui.routineSteps.isNotEmpty()) {
                binding.tvCenteredStepStatus.visibility = View.VISIBLE
                binding.tvCenteredStepStatus.text = "${ui.activeTemplateName} • Step ${ui.currentStepIdx + 1} / ${ui.routineSteps.size}"
            } else {
                binding.tvCenteredStepStatus.visibility = View.GONE
            }
            binding.btnRestoreFormDemo.visibility =
                if (!mediaUrl.isNullOrBlank() && !ui.showDemoPip) View.VISIBLE else View.GONE
        }

        // 9. Weight Stepper Labels (LB vs KG)
        if (Units.isKg(ui.activeUnit)) {
            binding.btnStepMinusLarge.text = "-2.5"
            binding.btnStepMinusSmall.text = "-0.5"
            binding.btnStepPlusSmall.text = "+0.5"
            binding.btnStepPlusLarge.text = "+2.5"
        } else {
            binding.btnStepMinusLarge.text = "-5"
            binding.btnStepMinusSmall.text = "-1"
            binding.btnStepPlusSmall.text = "+1"
            binding.btnStepPlusLarge.text = "+5"
        }

        // 10. Rest Timer Overlay & Atmospheric Edge Glow
        val restSec = ui.restSecondsRemaining
        binding.backdropView.setRestTimerActive(restSec > 0)
        if (restSec > 0) {
            binding.restTimerOverlay.visibility = View.VISIBLE
            binding.tvRestCountdown.text = Units.formatCountdown(restSec)
        } else {
            binding.restTimerOverlay.visibility = View.GONE
        }

        // 11. PR 1RM, PR Celebration & Progressive Overload Badges
        val pr = ui.activeExercisePr
        if (pr != null && pr.oneRepMaxEst > 0) {
            val est = Units.formatCompactWeight(pr.oneRepMaxEst, ui.activeUnit)
            val maxW = Units.formatCompactWeight(pr.maxWeightLb, ui.activeUnit)
            binding.tvPrBadge.text = "PR 1RM: $est ${ui.activeUnit} (Max $maxW ${ui.activeUnit})"
            binding.tvPrBadge.setTextColor(p.accentInk)
            binding.ivPrTrophy.setColorFilter(p.accentInk)
        } else {
            binding.tvPrBadge.text = "PR 1RM: Baseline Pending"
            binding.tvPrBadge.setTextColor(p.textMuted)
            binding.ivPrTrophy.setColorFilter(p.textMuted)
        }

        if (!ui.lastPrBanner.isNullOrBlank()) {
            binding.prCelebrationPill.visibility = View.VISIBLE
            binding.tvPrCelebration.text = "NEW PR! ${ui.lastPrBanner}"
        } else {
            binding.prCelebrationPill.visibility = View.GONE
        }

        val overload = ui.overloadSuggestion
        if (overload != null && overload.ready) {
            binding.overloadReadyPill.visibility = View.VISIBLE
            val bump = Units.formatCompactWeight(overload.recommendedBumpLb, ui.activeUnit)
            binding.tvOverloadReady.text = "+$bump ${ui.activeUnit} Ready"
        } else {
            binding.overloadReadyPill.visibility = View.GONE
        }

        // 12. Set Control Deck
        if (step != null) {
            binding.tvTargetReps.visibility = View.VISIBLE
            binding.tvTargetReps.text = "/ ${step.targetReps}"
        } else {
            binding.tvTargetReps.visibility = View.GONE
        }
        binding.btnFinishWorkout.visibility =
            if (ui.sessionState.activeSessionId != null) View.VISIBLE else View.GONE

        val nextStep = ui.nextStep
        val nextEx = ui.nextExercise
        if (nextStep != null && nextEx != null) {
            binding.tvUpNextTitle.text = nextEx.name
            binding.tvUpNextMeta.visibility = View.VISIBLE
            binding.tvUpNextMeta.text =
                "${nextEx.accessory} • H${nextEx.recommendedHeight} R${nextEx.recommendedRotation} A${nextEx.recommendedAngle} • ${nextStep.targetReps} reps"
        } else {
            binding.tvUpNextMeta.visibility = View.GONE
            binding.tvUpNextTitle.text = if (inRoutine) {
                "Final Step in Routine"
            } else {
                "Free Lift — Tap Routines to Load"
            }
        }

        val coachCfg = ui.coachConfig
        val showCoach = coachCfg != null &&
            (coachCfg.provider != "local" || coachCfg.hasApiKey || !coachCfg.baseUrl.isNullOrBlank())
        binding.inlineCoachRow.visibility = if (showCoach) View.VISIBLE else View.GONE
        if (showCoach) {
            binding.tvCoachPreview.text = ui.coachReply
                ?: "Ask AI Coach (${coachCfg?.provider?.uppercase()}) about PRs, form, or say 'Add 5 lbs'..."
            val act = ui.coachAction
            if (act != null && act.label.isNotBlank()) {
                binding.btnCoachSuggestedAction.visibility = View.VISIBLE
                binding.btnCoachSuggestedAction.text = act.label
            } else {
                binding.btnCoachSuggestedAction.visibility = View.GONE
            }
        }

        // 13. Music Dock
        renderMusicDock(ui)

        // 14. Re-sync telemetry & VBT-coupled highlights (cheap due to diff caches)
        renderTelemetryHotPath(telemetry, ui)
        renderVbtHotPath(app.repository.vbtFlow.value, ui)
    }

    private fun updateStatusDotPulse(shouldPulse: Boolean) {
        if (shouldPulse) {
            if (statusDotAnimator == null) {
                statusDotAnimator = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.30f).apply {
                    duration = 900L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            statusDotAnimator?.cancel()
            statusDotAnimator = null
            binding.statusDot.alpha = 1f
        }
    }

    private fun renderMusicDock(ui: KioskUiState) {
        if (!ui.musicConfigured) {
            binding.musicDock.visibility = View.GONE
            return
        }
        binding.musicDock.visibility = View.VISIBLE
        val ms = ui.musicState

        if (!ui.musicError.isNullOrBlank()) {
            binding.musicErrorRow.visibility = View.VISIBLE
            binding.tvMusicError.text = ui.musicError
        } else {
            binding.musicErrorRow.visibility = View.GONE
        }

        binding.tvTrackTitle.text = ms.trackName.ifBlank { "Not Playing" }
        val activeDev = ui.musicDevices.firstOrNull { it.isActive || it.id == ms.activeDeviceId }
        val defaultLibrespot = SwonalRepository.findLibrespotDevice(ui.musicDevices)
        val rawSpeaker = activeDev?.name ?: defaultLibrespot?.name ?: "Select Speaker"
        val speakerDisplayName = rawSpeaker
            .replace(Regex("\\(?librespot\\)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .ifBlank { "Tonal Speakers" }
        binding.tvActiveSpeaker.text = speakerDisplayName

        binding.tvTrackSubtitle.text = if (ms.isConnected) {
            ms.artistName.ifBlank { "Spotify Connect" }
        } else {
            "Connect a music service to play"
        }
        binding.tvDuckedBadge.visibility = if (ms.isDucked) View.VISIBLE else View.GONE
        (application as? SwonalApp)?.librespotAudioBridge?.setDucked(ms.isDucked)

        val pct = if (ms.durationMs > 0) ((ms.progressMs * 1000L) / ms.durationMs).toInt().coerceIn(0, 1000) else 0
        binding.musicProgressBar.progress = pct

        val artUrl = ms.artworkUrl
        if (artUrl != lastAlbumArtUrl) {
            lastAlbumArtUrl = artUrl
            if (!artUrl.isNullOrBlank()) {
                binding.ivAlbumArt.visibility = View.VISIBLE
                binding.ivAlbumArt.load(artUrl) { crossfade(true) }
            } else {
                binding.ivAlbumArt.visibility = View.GONE
            }
        }

        binding.ivMusicPlayPause.setImageResource(if (ms.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val transportAlpha = if (ms.isConnected) 1.0f else 0.40f
        binding.btnMusicPrev.alpha = transportAlpha
        binding.btnMusicPlayPause.alpha = transportAlpha
        binding.btnMusicNext.alpha = transportAlpha
        binding.btnMusicPrev.isEnabled = ms.isConnected
        binding.btnMusicPlayPause.isEnabled = ms.isConnected
        binding.btnMusicNext.isEnabled = ms.isConnected

        val vol = (ms.volumePct ?: 70).coerceIn(0, 100)
        if (!seekingVolume) {
            binding.musicVolumeSeek.progress = vol
            binding.tvVolumePct.text = "$vol%"
        }
        syncDeviceVolumeSlider()

        binding.ivMuteChimes.setImageResource(if (ui.soundMuted) R.drawable.ic_volume_x else R.drawable.ic_activity)
        binding.ivMuteChimes.setColorFilter(if (ui.soundMuted) currentPalette.textMuted else currentPalette.accentInk)
    }

    private fun renderArmAlignmentBadgeIfChanged(frame: TelemetryFrame, ui: KioskUiState) {
        val ex = ui.activeExercise ?: return
        val alignment = ArmAlignmentHelper.evaluate(frame, ex)
        val arms = frame.armPositions

        // Reset chime transition state & attachment confirmation whenever the active exercise changes
        if (lastArmExerciseId != ex.id) {
            lastArmExerciseId = ex.id
            lastArmStatus = alignment.status
            lastAccessory = ""
        } else if (lastArmStatus != null &&
            lastArmStatus != ArmAlignmentStatus.ALIGNED &&
            alignment.status == ArmAlignmentStatus.ALIGNED
        ) {
            app.soundEngine.muted = ui.soundMuted
            app.soundEngine.play(SwonalSoundEngine.Cue.ARM_ALIGNED)
            lastArmStatus = alignment.status
        } else {
            lastArmStatus = alignment.status
        }

        val key = "${ex.recommendedHeight}:${ex.recommendedRotation}:${ex.recommendedAngle}|" +
            "${arms.height.left}:${arms.rotation.left}:${arms.angle.left}:${alignment.leftUnlocked}|" +
            "${arms.height.right}:${arms.rotation.right}:${arms.angle.right}:${alignment.rightUnlocked}|" +
            "${alignment.status}"
        if (key == lastArmSyncKey) return
        lastArmSyncKey = key

        val p = currentPalette
        val d = resources.displayMetrics.density
        val colorGreen = if (p.isDark) 0xFF34D399.toInt() else 0xFF059669.toInt()
        val colorYellow = if (p.isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
        val colorRed = if (p.isDark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()

        val sb = android.text.SpannableStringBuilder()
        fun appendColored(text: String, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Row 1: Target
        appendColored(
            String.format(
                Locale.US,
                "Target  H%-2d R%d  A%d\n",
                ex.recommendedHeight,
                ex.recommendedRotation,
                ex.recommendedAngle
            ),
            p.textMuted
        )

        // Helper for Left / Right rows with per-value Yellow (moving) / Green (matched) / Red (mismatched)
        fun appendArmRow(label: String, h: Int, r: Int, a: Int, moving: Boolean, newline: Boolean) {
            appendColored(label, p.textPrimary)
            val hColor = when {
                moving -> colorYellow
                h == ex.recommendedHeight -> colorGreen
                else -> colorRed
            }
            val rColor = when {
                moving -> colorYellow
                r == ex.recommendedRotation -> colorGreen
                else -> colorRed
            }
            val aColor = when {
                moving -> colorYellow
                a == ex.recommendedAngle -> colorGreen
                else -> colorRed
            }
            appendColored(String.format(Locale.US, "H%-2d ", h), hColor)
            appendColored(String.format(Locale.US, "R%d  ", r), rColor)
            appendColored(String.format(Locale.US, "A%d", a), aColor)
            if (newline) sb.append("\n")
        }

        appendArmRow("Left    ", arms.height.left, arms.rotation.left, arms.angle.left, alignment.leftUnlocked, true)
        appendArmRow("Right   ", arms.height.right, arms.rotation.right, arms.angle.right, alignment.rightUnlocked, false)

        binding.tvArmSync.text = sb
        val allMatched = alignment.leftMatched && alignment.rightMatched
        val anyMoving = alignment.leftUnlocked || alignment.rightUnlocked
        when {
            anyMoving -> {
                binding.btnArmSync.background = p.warningPillDrawable(d, 10f, interactive = true)
                binding.btnArmInfo.setColorFilter(p.warningText)
            }
            allMatched -> {
                binding.btnArmSync.background = p.successPillDrawable(d, 10f, interactive = true)
                binding.btnArmInfo.setColorFilter(p.successText)
            }
            else -> {
                binding.btnArmSync.background = p.elevatedPillDrawable(d, 10f, interactive = true)
                binding.btnArmInfo.setColorFilter(p.textMuted)
            }
        }
    }

    private fun renderAccessoryButtonsIfChanged(activeAccessory: String) {
        val ui = app.repository.uiStateFlow.value
        val exId = ui.activeExercise?.id.orEmpty()
        val targetAcc = ui.activeExercise?.accessory?.uppercase(Locale.US) ?: "HANDLES"
        val norm = activeAccessory.uppercase(Locale.US)
        val isConfirmed = confirmedAccessoryExerciseId == exId && confirmedAccessoryName == targetAcc
        val cacheKey = "$exId|$targetAcc|$norm|$isConfirmed"
        if (cacheKey == lastAccessory) return
        lastAccessory = cacheKey

        val p = currentPalette
        val d = resources.displayMetrics.density
        val mapping = listOf(
            "HANDLES" to binding.btnAccHandles,
            "BAR" to binding.btnAccBar,
            "ROPE" to binding.btnAccRope
        )
        mapping.forEach { (acc, view) ->
            val isTarget = acc == targetAcc
            val isConfirmedTarget = isTarget && isConfirmed
            val isSelectedNonTarget = !isTarget && norm == acc
            when {
                isConfirmedTarget -> {
                    view.background = p.successPillDrawable(d, 9f, interactive = true)
                    view.setTextColor(p.successText)
                    view.alpha = 1f
                }
                isTarget -> {
                    view.background = p.warningPillDrawable(d, 9f, interactive = true)
                    view.setTextColor(p.warningText)
                    view.alpha = 1f
                }
                isSelectedNonTarget -> {
                    view.background = p.accentMutedDrawable(d, 9f, 1.8f)
                    view.setTextColor(p.accentInk)
                    view.alpha = 1f
                }
                else -> {
                    view.background = p.elevatedPillDrawable(d, 9f)
                    view.setTextColor(p.textMuted)
                    view.alpha = 0.72f
                }
            }
        }
    }

    private fun renderDynamicModeButtonsIfChanged(activeMode: String, modifierPct: Int) {
        val norm = activeMode.lowercase(Locale.US)
        val key = "$norm|$modifierPct"
        if (key == lastModeKey) return
        lastModeKey = key

        val p = currentPalette
        val d = resources.displayMetrics.density
        val modes = listOf(
            Triple("standard", "Standard", binding.btnModeStandard),
            Triple("eccentric", "Eccentric", binding.btnModeEccentric),
            Triple("chains", "Chains", binding.btnModeChains),
            Triple("spotter", "Spotter", binding.btnModeSpotter),
            Triple("burnout", "Burnout", binding.btnModeBurnout)
        )
        modes.forEach { (id, label, view) ->
            val selected = norm == id
            view.text = if (selected && modifierPct > 0) "$label (+$modifierPct%)" else label
            view.background = if (selected) p.accentPillDrawable(d, 12f) else p.elevatedPillDrawable(d, 12f)
            view.setTextColor(if (selected) p.accentTextColor else p.textPrimary)
        }
    }

    private fun rebuildProfilePills(ui: KioskUiState) {
        val container = binding.profilePillsContainer
        container.removeAllViews()
        val p = currentPalette
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        ui.users.forEach { user ->
            val isActive = user.id == ui.activeUser?.id
            val userAccent = ColorMath.parseHexColor(user.accentColor)
            // Use darkened accentInk border in Light Mode so Volt Lime/Cyan remain crisp on white
            val ringBorderColor = if (p.isDark) userAccent else ColorMath.mixAccentInkLight(userAccent)
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isActive) {
                    GradientDrawable().apply {
                        setColor(ColorMath.withAlpha(userAccent, 0.16f))
                        cornerRadius = 12f * d
                        setStroke(dp(2), ringBorderColor)
                    }
                } else {
                    p.elevatedPillDrawable(d, 12f)
                }
                alpha = if (isActive) 1f else 0.75f
                setPadding(dp(8), dp(5), dp(12), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply { marginEnd = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener { app.repository.switchUser(user.id) }

                addView(TextView(this@MainActivity).apply {
                    text = user.name.take(1).uppercase(Locale.US)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    typeface = SwonalFonts.interBlack(this@MainActivity)
                    setTextColor(ColorMath.getContrastTextColor(userAccent))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(userAccent)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) }
                })

                addView(TextView(this@MainActivity).apply {
                    text = user.name
                    textSize = 13f
                    typeface = SwonalFonts.interBold(this@MainActivity)
                    setTextColor(p.textPrimary)
                })
            }.also { PressFeedback.attachSingle(it) }
            container.addView(pill)
        }
    }

    private fun applyPaletteToAllViews(p: SwonalPalette) {
        val d = resources.displayMetrics.density
        binding.rootContainer.setBackgroundColor(p.bgCanvas)
        binding.authLockScrim.setBackgroundColor(p.bgCanvas)
        binding.backdropView.applyPalette(p)
        binding.loadDialView.applyPalette(p)
        binding.leftCableBar.applyPalette(p)
        binding.rightCableBar.applyPalette(p)
        binding.symmetrySliderView.applyPalette(p)
        binding.velocityGaugeView.applyPalette(p)

        // Surface Cards + Material Elevations (gives Light Mode crisp diffused depth)
        val cardElev = (if (p.isDark) 8f else 5f) * d
        val floatElev = 14f * d
        binding.topStatusBar.background = p.surfaceCardDrawable(d, 18f)
        binding.exerciseHeaderCard.background = p.surfaceCardDrawable(d, 18f)
        binding.formDemoCard.background = p.surfaceCardDrawable(d, 14f)
        binding.setControlDeck.background = p.surfaceCardDrawable(d, 18f)
        binding.musicDock.background = p.secondaryBarDrawable(d, 18f)
        binding.telemetryStrip.background = p.recessedStripDrawable(d, 18f)
        binding.restTimerOverlay.background = p.surfaceCardDrawable(d, 18f, p.accentPrimary, 2f)

        listOf(
            binding.topStatusBar,
            binding.exerciseHeaderCard,
            binding.formDemoCard,
            binding.setControlDeck,
            binding.musicDock
        ).forEach { ViewCompat.setElevation(it, cardElev) }
        ViewCompat.setElevation(binding.restTimerOverlay, floatElev)

        // Banners
        binding.queuedWorkoutBanner.background = p.surfaceCardDrawable(d, 14f, p.accentPrimary, 2f)
        binding.ivQueuedIcon.setColorFilter(p.accentInk)
        binding.tvQueuedSubtitle.setTextColor(p.accentInk)
        binding.tvQueuedTitle.setTextColor(p.textPrimary)
        binding.btnDismissQueued.background = p.elevatedPillDrawable(d, 10f)
        binding.btnDismissQueued.setTextColor(p.textPrimary)
        binding.btnStartQueued.background = p.accentPillDrawable(d, 10f)
        binding.btnStartQueued.setTextColor(p.accentTextColor)

        binding.safetyCutoutBanner.background = p.criticalBannerDrawable(d, 14f)
        binding.ivSafetyIcon.setColorFilter(p.criticalBorder)
        binding.tvSafetyTitle.setTextColor(p.criticalText)
        binding.tvSafetyBody.setTextColor(p.criticalText)
        binding.btnAckSafety.background = p.criticalBannerDrawable(d, 10f, interactive = true)
        binding.btnAckSafety.setTextColor(p.textPrimary)

        // Top Bar Icon Pills
        binding.btnQuickPrefs.background = p.elevatedPillDrawable(d, 12f)
        binding.ivQuickPrefs.setColorFilter(p.accentInk)
        binding.btnSwocial.background = p.elevatedPillDrawable(d, 12f)
        binding.ivSwocial.setColorFilter(p.accentInk)
        binding.btnCompanionQr.background = p.elevatedPillDrawable(d, 12f)
        binding.ivCompanionQr.setColorFilter(p.accentInk)
        binding.tvCompanionQr.setTextColor(p.textPrimary)
        binding.btnAppDrawer.background = p.elevatedPillDrawable(d, 12f)
        binding.ivAppDrawer.setColorFilter(p.accentInk)

        // Exercise Header
        binding.tvRoutinePill.background = p.accentPillDrawable(d, 99f, interactive = false)
        binding.tvRoutinePill.setTextColor(p.accentTextColor)
        binding.tvMuscleGroup.setTextColor(p.textMuted)
        binding.tvExerciseTitle.setTextColor(p.textPrimary)
        binding.tvExerciseSubtitle.setTextColor(p.textMuted)
        binding.btnWorkoutLibrary.background = p.elevatedPillDrawable(d, 10f)
        binding.ivWorkoutLibrary.setColorFilter(p.accentInk)
        binding.tvWorkoutLibrary.setTextColor(p.textPrimary)
        binding.btnTodayRoutine.background = p.accentPillDrawable(d, 10f)
        binding.tvTodayRoutine.setTextColor(p.accentTextColor)

        // Stepper & Form Demo Row
        binding.btnPrevExercise.background = p.elevatedPillDrawable(d, 12f)
        binding.ivPrev.setColorFilter(p.accentInk)
        binding.tvPrevLabel.setTextColor(p.textPrimary)
        binding.btnNextExercise.background = p.elevatedPillDrawable(d, 12f)
        binding.ivNext.setColorFilter(p.accentInk)
        binding.tvNextLabel.setTextColor(p.textPrimary)
        binding.tvFormDemoLabel.setTextColor(p.textMuted)
        binding.tvFormDemoName.setTextColor(p.textPrimary)
        binding.btnCloseFormDemo.setColorFilter(p.textMuted)
        binding.tvCenteredStepStatus.setTextColor(p.textMuted)
        binding.btnRestoreFormDemo.background = p.elevatedPillDrawable(d, 10f)
        binding.btnRestoreFormDemo.setTextColor(p.accentInk)

        // Rest Timer
        binding.tvRestLabel.setTextColor(p.textMuted)
        binding.tvRestCountdown.setTextColor(p.accentInk)
        binding.btnRestAdd15.background = p.elevatedPillDrawable(d, 10f)
        binding.btnRestAdd15.setTextColor(p.textPrimary)
        binding.btnRestSkip.background = p.accentPillDrawable(d, 10f)
        binding.ivRestSkip.setColorFilter(p.accentTextColor)
        binding.tvRestSkip.setTextColor(p.accentTextColor)

        // Weight Stepper Strip
        listOf(
            binding.btnStepMinusLarge,
            binding.btnStepMinusSmall,
            binding.btnStepPlusSmall,
            binding.btnStepPlusLarge
        ).forEach {
            it.background = p.elevatedPillDrawable(d, 12f)
            it.setTextColor(p.textPrimary)
        }
        binding.tvWeightCenterInfo.setTextColor(p.textMuted)

        // PR & Overload Pills
        binding.prBadgePill.background = p.elevatedPillDrawable(d, 10f)
        binding.prCelebrationPill.background = p.accentPillDrawable(d, 10f, interactive = false)
        binding.ivPrSparkle.setColorFilter(p.accentTextColor)
        binding.tvPrCelebration.setTextColor(p.accentTextColor)
        binding.overloadReadyPill.background = p.accentMutedDrawable(d, 10f, 1.2f)
        binding.ivOverload.setColorFilter(p.accentInk)
        binding.tvOverloadReady.setTextColor(p.accentInk)

        // Telemetry Strip
        binding.ivSymmetry.setColorFilter(p.accentInk)
        binding.tvSymmetryHeader.setTextColor(p.textPrimary)
        binding.tvSymLeftPct.setTextColor(p.textMuted)
        binding.tvSymCenterLabel.setTextColor(p.textMuted)
        binding.tvSymRightPct.setTextColor(p.textMuted)
        binding.telemetryDivider.setBackgroundColor(p.borderSubtle)
        binding.ivVelocity.setColorFilter(p.accentInk)
        binding.tvVelocityHeader.setTextColor(p.textPrimary)
        binding.tvVelocityPeak.setTextColor(p.textMuted)
        binding.tvVelocityCurrent.setTextColor(p.textPrimary)
        binding.tvVelocityUnit.setTextColor(p.textMuted)
        binding.btnEnableSpotterAlert.background = p.warningPillDrawable(d, 8f, interactive = true)
        binding.ivZapSpotter.setColorFilter(p.warningText)
        binding.tvZapSpotter.setTextColor(p.warningText)

        // Set Control Deck
        binding.tvRepsLabel.setTextColor(p.textMuted)
        binding.tvCompletedReps.setTextColor(p.textPrimary)
        binding.tvTargetReps.setTextColor(p.textMuted)
        binding.btnCompleteSet.background = p.accentPillDrawable(d, 14f)
        binding.btnCompleteSet.setTextColor(p.accentTextColor)
        binding.btnFinishWorkout.background = p.elevatedPillDrawable(d, 14f)
        binding.btnFinishWorkout.setTextColor(p.textPrimary)
        binding.upNextCard.background = p.elevatedPillDrawable(d, 12f, interactive = false)
        binding.tvUpNextLabel.setTextColor(p.textMuted)
        binding.tvUpNextTitle.setTextColor(p.textPrimary)
        binding.tvUpNextMeta.setTextColor(p.accentInk)
        binding.deckDivider.setBackgroundColor(p.borderSubtle)

        // Inline Coach (uses emerald successPillDrawable matching web bg-emerald-500/25)
        binding.inlineCoachRow.background = p.elevatedPillDrawable(d, 12f, interactive = false)
        binding.ivCoachBot.setColorFilter(p.accentInk)
        binding.tvCoachPreview.setTextColor(p.textPrimary)
        binding.btnCoachSuggestedAction.background = p.successPillDrawable(d, 8f)
        binding.btnCoachSuggestedAction.setTextColor(p.successText)
        binding.btnOpenCoachModal.background = p.accentPillDrawable(d, 10f)
        binding.ivCoachMic.setColorFilter(p.accentTextColor)
        binding.tvOpenCoachLabel.setTextColor(p.accentTextColor)

        // Music Dock + Custom Rounded ProgressBar & swonal-range SeekBar
        binding.musicErrorRow.background = p.criticalBannerDrawable(d, 8f)
        binding.tvMusicError.setTextColor(p.criticalText)
        binding.btnDismissMusicError.setColorFilter(p.criticalText)
        binding.albumArtFrame.background = p.accentMutedDrawable(d, 10f, 1f, interactive = false)
        binding.ivAlbumFallback.setColorFilter(p.accentInk)
        binding.tvTrackTitle.setTextColor(p.textPrimary)
        binding.tvTrackSubtitle.setTextColor(p.textMuted)
        binding.tvDuckedBadge.background = p.warningPillDrawable(d, 6f)
        binding.tvDuckedBadge.setTextColor(p.warningText)

        val barFillColor = if (p.isDark) p.accentPrimary else p.accentInk
        val makeSeekThumb = {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(barFillColor)
                setSize((14f * d).toInt(), (14f * d).toInt())
                setStroke((2f * d).toInt().coerceAtLeast(1), p.bgSurfaceSolid)
            }
        }
        binding.musicProgressBar.progressDrawable = createRoundedTrackDrawable(p.bgElevated, barFillColor, 4f * d)
        binding.deviceVolumeSeek.progressDrawable = createRoundedTrackDrawable(p.bgElevated, barFillColor, 6f * d)
        binding.deviceVolumeSeek.thumb = makeSeekThumb()
        binding.musicVolumeSeek.progressDrawable = createRoundedTrackDrawable(p.bgElevated, barFillColor, 6f * d)
        binding.musicVolumeSeek.thumb = makeSeekThumb()

        binding.btnMusicPrev.background = p.elevatedPillDrawable(d, 10f)
        binding.ivMusicPrev.setColorFilter(p.textPrimary)
        binding.btnMusicPlayPause.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.accentPrimary)
        }
        binding.ivMusicPlayPause.setColorFilter(p.accentTextColor)
        binding.btnMusicNext.background = p.elevatedPillDrawable(d, 10f)
        binding.ivMusicNext.setColorFilter(p.textPrimary)

        binding.btnSelectSpeaker.background = p.accentMutedDrawable(d, 10f, 1.2f)
        binding.ivSpeakerIcon.setColorFilter(p.accentInk)
        binding.tvActiveSpeaker.setTextColor(p.textPrimary)
        binding.tvSpeakerChevron.setTextColor(p.accentInk)

        binding.deviceVolumePill.background = p.elevatedPillDrawable(d, 10f, interactive = false)
        binding.ivDeviceVolume.setColorFilter(p.accentInk)
        binding.tvDeviceVolLabel.setTextColor(p.textMuted)
        binding.tvDeviceVolumePct.setTextColor(p.textPrimary)

        binding.volumePill.background = p.elevatedPillDrawable(d, 10f, interactive = false)
        binding.ivVolume.setColorFilter(p.accentInk)
        binding.tvSpotifyVolLabel.setTextColor(p.textMuted)
        binding.tvVolumePct.setTextColor(p.textPrimary)
        binding.btnMuteChimes.background = p.elevatedPillDrawable(d, 10f)
        binding.btnMusicPicker.background = p.elevatedPillDrawable(d, 10f)
        binding.ivMusicPicker.setColorFilter(p.accentInk)
        binding.tvMusicPicker.setTextColor(p.textPrimary)

        lastUsersKey = ""
    }

    private fun createRoundedTrackDrawable(trackColor: Int, fillColor: Int, heightPx: Float): LayerDrawable {
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
        return LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun resolveMediaUrl(serverUrl: String, mediaUrl: String?): String? {
        if (mediaUrl.isNullOrBlank()) return null
        return if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
            mediaUrl
        } else {
            "${serverUrl.trimEnd('/')}/${mediaUrl.trimStart('/')}"
        }
    }

    private fun loadFormDemoMedia(fullUrl: String) {
        if (fullUrl == lastMediaUrl) return
        lastMediaUrl = fullUrl
        val isVideo = fullUrl.matches(Regex(".*\\.(mp4|webm)(\\?.*)?$", RegexOption.IGNORE_CASE))
        if (isVideo) {
            binding.ivFormDemo.visibility = View.GONE
            binding.pvFormDemo.visibility = View.VISIBLE
            val player = exoPlayer ?: ExoPlayer.Builder(this).build().also {
                exoPlayer = it
                binding.pvFormDemo.player = it
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.volume = 0f
            }
            player.setMediaItem(MediaItem.fromUri(fullUrl))
            player.prepare()
            player.playWhenReady = true
        } else {
            // Pause any active ExoPlayer WITHOUT resetting lastMediaUrl (prevents Coil reload loop!)
            exoPlayer?.pause()
            binding.pvFormDemo.visibility = View.GONE
            binding.ivFormDemo.visibility = View.VISIBLE
            binding.ivFormDemo.load(fullUrl) {
                crossfade(true)
            }
        }
    }

    private fun releaseFormDemoVideo() {
        lastMediaUrl = null
        binding.pvFormDemo.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun openServerWebPath(path: String, title: String = "Swonal") {
        val url = "${app.repository.uiStateFlow.value.serverUrl.trimEnd('/')}$path"
        InAppWebLauncher.open(this, url, title, currentPalette)
    }

    private fun showDialogOnce(tag: String, factory: () -> androidx.fragment.app.DialogFragment) {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        factory().show(supportFragmentManager, tag)
    }
}
