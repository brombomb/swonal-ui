package com.swonal.kiosk.audio

import android.content.Context
import com.swonal.kiosk.model.SwonalJson
import com.swonal.kiosk.net.SwonalTlsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 100% offline on-device Speech-to-Text engine using Vosk over Android AudioRecord (16 kHz PCM).
 * Works natively on AOSP Android 6.0 (API 23) ARM64 Tonal V1 tablets with ZERO Google Play Services.
 *
 * On first activation, downloads the compact ~40 MB English acoustic model once into `filesDir`,
 * verifies & unpacks safely (zip-slip guarded), and runs completely offline thereafter.
 */
class OfflineVoiceRecognizer(
    private val context: Context,
    private val tlsConfig: SwonalTlsConfig?
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null

    @Volatile
    var isListening: Boolean = false
        private set

    fun isModelCached(): Boolean = findModelDir(modelRootDir()) != null

    private fun modelRootDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    suspend fun ensureModelReady(onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val root = modelRootDir()
        findModelDir(root)?.let { return@withContext it }

        // 1. Fast path: copy Vosk acoustic model bundled directly inside the APK assets
        val bundledManifest = runCatching {
            context.assets.open("$MODEL_DIR_NAME/asset-manifest.txt").bufferedReader().use { it.readLines() }
        }.getOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.contains("..") && !it.startsWith("/") }

        if (!bundledManifest.isNullOrEmpty()) {
            onProgress("Preparing bundled offline voice model...")
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            val canonicalRoot = root.canonicalPath + File.separator
            for (rel in bundledManifest) {
                val outFile = File(root, rel)
                if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                    throw SecurityException("Blocked invalid bundled voice asset path: $rel")
                }
                outFile.parentFile?.mkdirs()
                context.assets.open("$MODEL_DIR_NAME/$rel").use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
            findModelDir(root)?.let { return@withContext it }
        }

        // 2. Fallback: download once over HTTPS if APK was built without bundled assets
        onProgress("Downloading offline voice model (~40 MB, one-time)...")
        val tmpZip = File(context.cacheDir, "$MODEL_DIR_NAME.zip.tmp")
        val client = OkHttpClient.Builder().apply {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(120, TimeUnit.SECONDS)
            if (tlsConfig != null) {
                sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
            }
        }.build()

        val req = Request.Builder()
            .url(MODEL_DOWNLOAD_URL)
            .header("User-Agent", "SwonalKiosk/VoskModelFetcher")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Model download HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Empty model download body")
            body.byteStream().use { input ->
                FileOutputStream(tmpZip).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        }

        onProgress("Unpacking offline voice model...")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        val canonicalRoot = root.canonicalPath + File.separator
        ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(root, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalRoot)) {
                    throw SecurityException("Blocked zip-slip path in voice model archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
            }
        }
        tmpZip.delete()

        findModelDir(root) ?: throw IOException("Unpacked voice model missing required am/ or conf/ directory")
    }

    suspend fun startListening(
        onProgress: (String) -> Unit,
        onUtterance: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopListening()
        val dir = ensureModelReady(onProgress)
        val loadedModel = withContext(Dispatchers.IO) {
            model ?: Model(dir.absolutePath).also { model = it }
        }
        val recognizer = Recognizer(loadedModel, 16000.0f)
        val service = SpeechService(recognizer, 16000.0f)
        speechService = service
        isListening = true
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) = Unit
            override fun onResult(hypothesis: String?) {
                val spoken = extractUtteranceText(hypothesis)
                if (!spoken.isNullOrBlank()) {
                    com.swonal.kiosk.util.IdleActivityTracker.noteActivity()
                    onUtterance(spoken)
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val spoken = extractUtteranceText(hypothesis)
                if (!spoken.isNullOrBlank()) {
                    com.swonal.kiosk.util.IdleActivityTracker.noteActivity()
                    onUtterance(spoken)
                }
            }

            override fun onError(exception: Exception?) {
                isListening = false
                onError(exception?.message ?: "Offline voice recognizer error")
            }

            override fun onTimeout() = Unit
        })
    }

    fun stopListening() {
        isListening = false
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
    }

    fun close() {
        stopListening()
        runCatching { model?.close() }
        model = null
    }

    private fun findModelDir(root: File): File? {
        if (!root.exists() || !root.isDirectory) return null
        if (File(root, "am/final.mdl").isFile && File(root, "conf").isDirectory) return root
        return root.listFiles()?.firstOrNull { child ->
            child.isDirectory && File(child, "am/final.mdl").isFile && File(child, "conf").isDirectory
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_DOWNLOAD_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        fun extractUtteranceText(voskJson: String?): String? {
            if (voskJson.isNullOrBlank()) return null
            val obj = runCatching { SwonalJson.parseToJsonElement(voskJson).jsonObject }.getOrNull()
                ?: return null
            return obj["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
