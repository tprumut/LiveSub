package com.livesub.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class TranslationModelSpec(
    val id: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String
)

object TranslationModelRepository {
    // Not: Tencent'in resmi GGUF deposunda yalnızca Q4_K_M, Q6_K ve Q8_0 varyantları vardır.
    // Daha önce listelenen Q2_K/Q3_K_M dosyaları bu depoda bulunmadığı için kaldırıldı.
    // Boyut ve SHA-256 değerleri Hugging Face API'sinden alınmıştır.
    private val modelSpecs = listOf(
        TranslationModelSpec(
            id = "hy-mt2-1.8b-q4_k_m",
            displayName = "Hy-MT2 1.8B Q4_K_M (Önerilen)",
            repository = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/",
            fileName = "Hy-MT2-1.8B-Q4_K_M.gguf",
            expectedBytes = 1_133_080_448L,
            expectedSha256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
        ),
        TranslationModelSpec(
            id = "hy-mt2-1.8b-q6_k",
            displayName = "Hy-MT2 1.8B Q6_K (Yüksek kalite)",
            repository = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/",
            fileName = "Hy-MT2-1.8B-Q6_K.gguf",
            expectedBytes = 1_474_785_120L,
            expectedSha256 = "d98fe604dec1f28f58f80d7d560f7177e584d3b8e5835862687660e5ff97cb40"
        ),
        TranslationModelSpec(
            id = "hy-mt2-1.8b-q8_0",
            displayName = "Hy-MT2 1.8B Q8_0 (En yüksek kalite, çok RAM)",
            repository = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/",
            fileName = "Hy-MT2-1.8B-Q8_0.gguf",
            expectedBytes = 1_908_528_192L,
            expectedSha256 = "5c3fe0b1408a5ceb0143184ef247b11b579c525f4b02b060e6c851bb76fef1a4"
        )
    )

    private const val PREFS = "livesub_translation_preferences"
    private const val SELECTED_MODEL_KEY = "selected_translation_model"
    private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
    private const val TARGET_LANGUAGE_KEY = "target_language"
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 2_000L
    private const val PROGRESS_INTERVAL_MS = 250L

    fun defaultModel(): TranslationModelSpec = modelSpecs.first()

    fun findModel(modelId: String): TranslationModelSpec =
        modelSpecs.firstOrNull { it.id == modelId } ?: defaultModel()

    fun isTranslationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(TRANSLATION_ENABLED_KEY, true)

    fun setTranslationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TRANSLATION_ENABLED_KEY, enabled).apply()
    }

    fun getTargetLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TARGET_LANGUAGE_KEY, "tr") ?: "tr"

    fun setTargetLanguage(context: Context, langCode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TARGET_LANGUAGE_KEY, langCode).apply()
    }

    fun getSelectedModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_MODEL_KEY, "hy-mt2-1.8b-q4_k_m") ?: "hy-mt2-1.8b-q4_k_m"

    fun setSelectedModel(context: Context, modelId: String) {
        findModel(modelId) // validate
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SELECTED_MODEL_KEY, modelId).apply()
    }

    fun availableModels(): List<TranslationModelSpec> = modelSpecs

    suspend fun ensureModel(
        context: Context,
        modelId: String = "hy-mt2-1.8b-q4_k_m",
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
                check(partialFile.renameTo(modelFile)) { "Doğrulanmış çeviri modeli taşınamadı." }
                onProgress(spec.expectedBytes, spec.expectedBytes)
                return@withContext modelFile
            }
            partialFile.delete()
        }

        // Aşama 5: 1.1 GB'lık model indirmesinde geçici ağ hatalarına dayanıklılık.
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
                    error("Çeviri modeli SHA-256 doğrulaması başarısız oldu. İndirilen dosya silindi; yeniden indirilecek.")
                }
                check(partialFile.renameTo(modelFile)) { "Doğrulanmış çeviri model dosyası taşınamadı." }
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
        throw lastFailure ?: IllegalStateException("Çeviri modeli indirilemedi.")
    }

    private fun downloadRemainder(
        partialFile: File,
        spec: TranslationModelSpec,
        onProgress: (Long, Long) -> Unit
    ) {
        val offset = partialFile.length()
        val connection = (URL(spec.repository + spec.fileName).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            val append = offset > 0 && responseCode == 206
            if (responseCode !in 200..299) error("Çeviri modeli sunucusu HTTP $responseCode döndürdü.")
            if (offset > 0 && !append) partialFile.delete()

            val initialBytes = if (append) offset else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
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
            val buffer = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
