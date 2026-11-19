package com.swonal.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zero-latency native SoundPool audio cues matching `packages/server-app/src/client/src/utils/soundEngine.ts`.
 * Synthesizes waveform PCM WAVs off the Main thread with atomic writes and load-completion tracking.
 */
class SwonalSoundEngine(context: Context) {
    private enum class Wave { SINE, TRIANGLE, SAWTOOTH }

    private data class ToneSpec(
        val freqHz: Double,
        val durationSec: Double,
        val wave: Wave,
        val gainPeak: Double,
        val delaySec: Double
    )

    private val appContext = context.applicationContext
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = ConcurrentHashMap<Cue, Int>()
    private val readySampleIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    var muted: Boolean = false

    @Volatile
    private var released: Boolean = false

    enum class Cue {
        WEIGHT_ENGAGE,
        WEIGHT_DISENGAGE,
        REP_COMPLETE,
        COUNTDOWN_TICK,
        COUNTDOWN_FINAL,
        SET_COMPLETE,
        PERSONAL_RECORD,
        SAFETY_CUTOUT,
        ARM_ALIGNED,
        TAP
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                readySampleIds.add(sampleId)
            }
        }
        initScope.launch { initializeSamples() }
    }

    private fun initializeSamples() {
        val dir = File(appContext.cacheDir, "swonal_chimes_$CACHE_VERSION")
        runCatching { dir.mkdirs() }
        // Clean up any legacy unversioned directory
        runCatching { File(appContext.cacheDir, "swonal_chimes").deleteRecursively() }

        val specs = mapOf(
            Cue.WEIGHT_ENGAGE to listOf(
                ToneSpec(523.25, 0.12, Wave.TRIANGLE, 0.15, 0.00),
                ToneSpec(659.25, 0.18, Wave.TRIANGLE, 0.18, 0.07)
            ),
            Cue.WEIGHT_DISENGAGE to listOf(
                ToneSpec(440.00, 0.12, Wave.SINE, 0.12, 0.00),
                ToneSpec(329.63, 0.16, Wave.SINE, 0.12, 0.06)
            ),
            Cue.REP_COMPLETE to listOf(
                ToneSpec(880.00, 0.09, Wave.SINE, 0.11, 0.00)
            ),
            Cue.COUNTDOWN_TICK to listOf(
                ToneSpec(660.00, 0.10, Wave.SINE, 0.15, 0.00)
            ),
            Cue.COUNTDOWN_FINAL to listOf(
                ToneSpec(1046.50, 0.28, Wave.SINE, 0.15, 0.00)
            ),
            Cue.SET_COMPLETE to listOf(
                ToneSpec(523.25, 0.14, Wave.TRIANGLE, 0.15, 0.00),
                ToneSpec(659.25, 0.14, Wave.TRIANGLE, 0.15, 0.09),
                ToneSpec(783.99, 0.26, Wave.TRIANGLE, 0.18, 0.18)
            ),
            Cue.PERSONAL_RECORD to listOf(
                ToneSpec(523.25, 0.14, Wave.TRIANGLE, 0.18, 0.00),
                ToneSpec(659.25, 0.14, Wave.TRIANGLE, 0.18, 0.10),
                ToneSpec(783.99, 0.14, Wave.TRIANGLE, 0.18, 0.20),
                ToneSpec(1046.50, 0.42, Wave.TRIANGLE, 0.22, 0.30)
            ),
            Cue.SAFETY_CUTOUT to listOf(
                ToneSpec(310.00, 0.18, Wave.SAWTOOTH, 0.16, 0.00),
                ToneSpec(230.00, 0.28, Wave.SAWTOOTH, 0.18, 0.20)
            ),
            Cue.ARM_ALIGNED to listOf(
                ToneSpec(659.25, 0.09, Wave.TRIANGLE, 0.13, 0.00),
                ToneSpec(880.00, 0.14, Wave.TRIANGLE, 0.14, 0.07)
            ),
            Cue.TAP to listOf(
                ToneSpec(1200.00, 0.015, Wave.SINE, 0.04, 0.00)
            )
        )

        specs.forEach { (cue, tones) ->
            if (released) return
            runCatching {
                val file = File(dir, "${cue.name.lowercase()}.wav")
                if (!file.exists() || file.length() <= 44L) {
                    val tmp = File(dir, "${cue.name.lowercase()}.wav.tmp")
                    writeWavFile(tmp, renderPcm(tones))
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                }
                val id = soundPool.load(file.absolutePath, 1)
                if (id > 0) soundIds[cue] = id
            }
        }
    }

    fun play(cue: Cue) {
        if (muted || released) return
        val id = soundIds[cue] ?: return
        if (!readySampleIds.contains(id)) return
        runCatching { soundPool.play(id, 1f, 1f, 1, 0, 1f) }
    }

    /**
     * Subtle, tactile click feedback scaled gently through USAGE_MEDIA so it
     * naturally tracks the device hardware volume rather than deafening the user.
     */
    fun playTap() {
        if (muted || released) return
        val id = soundIds[Cue.TAP] ?: return
        if (!readySampleIds.contains(id)) return
        runCatching { soundPool.play(id, 0.20f, 0.20f, 1, 0, 1f) }
    }

    fun release() {
        released = true
        readySampleIds.clear()
        soundIds.clear()
        runCatching { soundPool.release() }
    }

    private fun renderPcm(tones: List<ToneSpec>): ShortArray {
        val sampleRate = 22050
        val totalSec = tones.maxOf { it.delaySec + it.durationSec } + 0.03
        val numSamples = (totalSec * sampleRate).toInt().coerceAtLeast(1)
        val mix = DoubleArray(numSamples)

        for (tone in tones) {
            val startIdx = (tone.delaySec * sampleRate).toInt()
            val len = (tone.durationSec * sampleRate).toInt().coerceAtLeast(1)
            val attackLen = max(1, (0.015 * sampleRate).toInt())
            val decayLen = max(1, len - attackLen)
            val lnAttackRatio = ln(tone.gainPeak / 0.001)
            val lnDecayRatio = ln(0.001 / tone.gainPeak)

            for (i in 0 until len) {
                val idx = startIdx + i
                if (idx >= numSamples) break
                val t = i.toDouble() / sampleRate
                val phase = (t * tone.freqHz) % 1.0
                val sample = when (tone.wave) {
                    Wave.SINE -> sin(2.0 * PI * phase)
                    Wave.TRIANGLE -> if (phase < 0.25) phase * 4.0 else if (phase < 0.75) 2.0 - phase * 4.0 else phase * 4.0 - 4.0
                    Wave.SAWTOOTH -> 2.0 * phase - 1.0
                }
                val env = if (i < attackLen) {
                    0.001 * exp(lnAttackRatio * (i.toDouble() / attackLen))
                } else {
                    tone.gainPeak * exp(lnDecayRatio * ((i - attackLen).toDouble() / decayLen))
                }
                mix[idx] += sample * env * 2.2
            }
        }

        return ShortArray(numSamples) { idx ->
            val clamped = max(-1.0, min(1.0, mix[idx]))
            (clamped * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun writeWavFile(file: File, pcm: ShortArray) {
        val sampleRate = 22050
        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort(s)

        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(body.array())
        }
    }

    companion object {
        private const val CACHE_VERSION = "v2"
    }
}
