package com.swonal.kiosk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Streams 44.1kHz 16-bit stereo PCM from local on-device librespot (`127.0.0.1:41478`)
 * directly into Android's native `AudioTrack` (`STREAM_MUSIC` / `USAGE_MEDIA`).
 *
 * Why this is essential on Tonal V1 (MediaTek MT8173, Android 6.0):
 * - Physical Tonal speakers are wired to `pcm8p` (`I2S0DL1_PLayback`) and require Android's
 *   `AudioFlinger` to enable the MediaTek hardware speaker amplifier (`Speaker_Amp_Switch`).
 * - Routing librespot (`--backend pipe`) through `AudioTrack` powers the speaker amplifier,
 *   obeys `AudioManager.STREAM_MUSIC` hardware volume, supports zero-latency ducking, and
 *   cleanly mixes with Kiosk workout chimes and Voice Coach TTS without locking ALSA devices.
 */
class LibrespotAudioBridge(
    private val host: String = "127.0.0.1",
    private val port: Int = 41478
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ducked: Boolean = false

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun start() {
        scope.launch {
            while (isActive) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.tcpNoDelay = true
                    socket.soTimeout = 1500
                    socket.connect(InetSocketAddress(host, port), 2000)
                    Log.i(TAG, "Connected to local librespot PCM stream at $host:$port")
                    pumpPcmStream(socket)
                } catch (_: Exception) {
                    // Daemon or librespot not ready yet; retry quietly
                } finally {
                    runCatching { socket?.close() }
                    pauseAndReleaseTrack()
                }
                delay(2000L)
            }
        }
    }

    fun setDucked(isDucked: Boolean) {
        if (ducked == isDucked) return
        ducked = isDucked
        val gain = if (isDucked) 0.25f else 1.0f
        runCatching { activeTrack?.setVolume(gain) }
    }

    private fun pumpPcmStream(socket: Socket) {
        val input = socket.getInputStream()
        val buf = ByteArray(8192)
        var leftoverLen = 0

        while (scope.isActive && !socket.isClosed) {
            try {
                val n = input.read(buf, leftoverLen, buf.size - leftoverLen)
                if (n < 0) break
                if (n == 0) continue

                val total = leftoverLen + n
                // Stereo 16-bit PCM = 4 bytes per frame (2 bytes L + 2 bytes R)
                val alignedBytes = total and 3.inv()
                if (alignedBytes > 0) {
                    val track = ensurePlayingTrack()
                    track?.write(buf, 0, alignedBytes)
                }
                leftoverLen = total - alignedBytes
                if (leftoverLen > 0) {
                    System.arraycopy(buf, alignedBytes, buf, 0, leftoverLen)
                }
            } catch (_: SocketTimeoutException) {
                // 1.5s without PCM data -> music is paused/stopped; pause AudioTrack so AudioFlinger can standby
                pauseTrack()
            }
        }
    }

    private fun ensurePlayingTrack(): AudioTrack? {
        var track = activeTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(16384)

            track = runCatching {
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    minBuf * 2,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }.getOrNull()

            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                val gain = if (ducked) 0.25f else 1.0f
                runCatching { track.setVolume(gain) }
                activeTrack = track
            } else {
                runCatching { track?.release() }
                activeTrack = null
                return null
            }
        }

        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
        return track
    }

    private fun pauseTrack() {
        val track = activeTrack ?: return
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            runCatching {
                track.pause()
                track.flush()
            }
        }
    }

    private fun pauseAndReleaseTrack() {
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            }
        }
    }

    companion object {
        private const val TAG = "LibrespotAudioBridge"
        private const val SAMPLE_RATE = 44100
    }
}
