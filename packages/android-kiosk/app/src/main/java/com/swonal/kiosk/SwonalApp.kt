package com.swonal.kiosk

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.swonal.kiosk.audio.SwonalSoundEngine
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.net.SwonalRepository
import com.swonal.kiosk.net.SwonalTlsConfig
import com.swonal.kiosk.net.SwonalTlsFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SwonalApp : Application(), ImageLoaderFactory {
    lateinit var prefs: SwonalPreferences
        private set
    lateinit var tlsConfig: SwonalTlsConfig
        private set
    lateinit var repository: SwonalRepository
        private set
    lateinit var soundEngine: SwonalSoundEngine
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = SwonalPreferences(this)
        tlsConfig = SwonalTlsFactory.create(this)
        soundEngine = SwonalSoundEngine(this).apply {
            muted = prefs.soundMuted
        }
        repository = SwonalRepository(prefs = prefs, tlsConfig = tlsConfig)
        com.swonal.kiosk.ui.SwonalWebActivity.warmup(this)

        // Synchronize STREAM_SYSTEM to media volume so system UI doesn't blast deafening clicks
        runCatching {
            val audioMgr = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioMgr != null) {
                val musicMax = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val musicCur = audioMgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val systemMax = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
                val systemTarget = ((musicCur.toFloat() / musicMax.toFloat()) * systemMax.toFloat()).toInt()
                audioMgr.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, systemTarget, 0)
            }
        }
    }

    /**
     * Isolated, cookie-less OkHttpClient for Coil so external artwork/media fetches
     * (e.g. Spotify CDN i.scdn.co) NEVER receive Swonal auth cookies or Bearer tokens,
     * while still trusting our bundled modern root CAs on Android 6.0 (API 23).
     */
    override fun newImageLoader(): ImageLoader {
        val mediaClient = OkHttpClient.Builder()
            .sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(mediaClient)
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(150)
            .build()
    }
}
