package com.livesub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object WhisperNative {
    init {
        System.loadLibrary("livesub")
    }

    external fun nativeLoadModel(modelPath: String): Long
    external fun nativeTranscribe(contextPointer: Long, samples: FloatArray, language: String, threads: Int): String
    external fun nativeFree(contextPointer: Long)
}

object WhisperEngine {
    private val loadMutex = Mutex()
    private val nativeMutex = Any()
    @Volatile private var contextPointer: Long = 0L
    @Volatile private var loadedModelId: String? = null

    suspend fun ensureLoaded(context: Context, modelId: String) = loadMutex.withLock {
        if (contextPointer != 0L && loadedModelId == modelId) return@withLock
        val appContext = context.applicationContext
        val spec = WhisperModelRepository.findModel(modelId)
        if (contextPointer != 0L) {
            withContext(Dispatchers.IO) {
                synchronized(nativeMutex) {
                    WhisperNative.nativeFree(contextPointer)
                    contextPointer = 0L
                    loadedModelId = null
                }
            }
        }

        LiveSubState.update { it.copy(modelReady = false, modelProgress = 0f, status = "${spec.displayName} modeli hazırlanıyor…") }
        val modelFile = WhisperModelRepository.ensureModel(appContext, modelId) { downloaded, total ->
            LiveSubState.update { it.copy(modelProgress = downloaded.toFloat() / total.coerceAtLeast(1L)) }
        }
        LiveSubState.update { it.copy(status = "Whisper modeli belleğe yükleniyor…") }
        val pointer = withContext(Dispatchers.IO) {
            synchronized(nativeMutex) { WhisperNative.nativeLoadModel(modelFile.absolutePath) }
        }
        check(pointer != 0L) { "Whisper modeli yüklenemedi. Dosya bozuk veya bellek yetersiz olabilir." }
        synchronized(nativeMutex) {
            contextPointer = pointer
            loadedModelId = modelId
        }
        LiveSubState.update {
            it.copy(modelReady = true, modelProgress = 1f, status = "Whisper hazır · ${spec.displayName}", error = null)
        }
    }

    fun transcribe(samples: FloatArray, language: String): String {
        synchronized(nativeMutex) {
            val pointer = contextPointer
            check(pointer != 0L) { "Whisper modeli henüz yüklenmedi." }
            val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            return WhisperNative.nativeTranscribe(pointer, samples, language, threadCount)
        }
    }

    fun release() {
        synchronized(nativeMutex) {
            val pointer = contextPointer
            if (pointer != 0L) {
                contextPointer = 0L
                loadedModelId = null
                WhisperNative.nativeFree(pointer)
            }
        }
    }
}

data class WhisperModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String
)

object WhisperModelRepository {
    private val modelSpecs = listOf(
        WhisperModelSpec(
            id = "base",
            displayName = "Base q5_1",
            fileName = "ggml-base-q5_1.bin",
            expectedBytes = 59_707_625L,
            expectedSha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        ),
        WhisperModelSpec(
            id = "small",
            displayName = "Small q5_1",
            fileName = "ggml-small-q5_1.bin",
            expectedBytes = 190_085_487L,
            expectedSha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        )
    )
    private const val REPOSITORY = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val PREFS = "livesub_model_preferences"
    private const val SELECTED_MODEL_KEY = "selected_model"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_500L
    private const val PROGRESS_INTERVAL_MS = 200L

    fun availableModels(): List<WhisperModelSpec> = modelSpecs

    fun findModel(modelId: String): WhisperModelSpec =
        modelSpecs.firstOrNull { it.id == modelId } ?: error("Bilinmeyen Whisper modeli: $modelId")

    fun selectedModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_MODEL_KEY, "base") ?: "base"

    fun saveSelectedModel(context: Context, modelId: String) {
        findModel(modelId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SELECTED_MODEL_KEY, modelId).apply()
    }

    suspend fun ensureModel(
        context: Context,
        modelId: String,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val spec = findModel(modelId)
        val folder = File(context.filesDir, "models").apply { mkdirs() }
        val modelFile = File(folder, spec.fileName)
        if (modelFile.isFile && modelFile.length() == spec.expectedBytes && sha256(modelFile) == spec.expectedSha256) {
            onProgress(spec.expectedBytes, spec.expectedBytes)
            return@withContext modelFile
        }
        if (modelFile.exists()) modelFile.delete()

        val partialFile = File(folder, "${spec.fileName}.part")
        if (partialFile.length() > spec.expectedBytes) partialFile.delete()
        if (partialFile.length() == spec.expectedBytes) {
            if (sha256(partialFile) == spec.expectedSha256) {
                check(partialFile.renameTo(modelFile)) { "Doğrulanmış model dosyası taşınamadı." }
                onProgress(spec.expectedBytes, spec.expectedBytes)
                return@withContext modelFile
            }
            partialFile.delete()
        }

        // Aşama 5: geçici ağ hatalarında kaldığı yerden en fazla 3 kez dener.
        var lastFailure: Exception? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                downloadRemainder(partialFile, spec, onProgress)
                check(partialFile.length() == spec.expectedBytes) {
                    "Model indirme boyutu uyuşmuyor: ${partialFile.length()} / ${spec.expectedBytes} bayt. Yeniden denemede kaldığı yerden sürecek."
                }
                val actualHash = sha256(partialFile)
                if (actualHash != spec.expectedSha256) {
                    partialFile.delete()
                    error("Model SHA-256 doğrulaması başarısız oldu. İndirilen dosya silindi; yeniden indirilecek.")
                }
                check(partialFile.renameTo(modelFile)) { "Doğrulanmış model dosyası taşınamadı." }
                onProgress(spec.expectedBytes, spec.expectedBytes)
                return@withContext modelFile
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    onProgress(partialFile.length().coerceAtMost(spec.expectedBytes), spec.expectedBytes)
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Whisper modeli indirilemedi.")
    }

    private fun downloadRemainder(
        partialFile: File,
        spec: WhisperModelSpec,
        onProgress: (Long, Long) -> Unit
    ) {
        val offset = partialFile.length()
        val connection = (URL(REPOSITORY + spec.fileName).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            val append = offset > 0 && responseCode == 206
            if (responseCode !in 200..299) error("Model sunucusu HTTP $responseCode döndürdü.")
            if (offset > 0 && !append) partialFile.delete()
            val initialBytes = if (append) offset else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = initialBytes
                    var lastReportAt = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= PROGRESS_INTERVAL_MS || total >= spec.expectedBytes) {
                            lastReportAt = now
                            onProgress(total.coerceAtMost(spec.expectedBytes), spec.expectedBytes)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
