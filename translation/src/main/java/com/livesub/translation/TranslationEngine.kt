package com.livesub.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TranslationResult(
    val translatedText: String,
    val latencyMillis: Long
)

object TranslationEngine {
    private val loadMutex = Mutex()
    private val nativeMutex = Any()
    @Volatile private var contextPointer: Long = 0L
    @Volatile private var isLoaded: Boolean = false

    val SUPPORTED_LANGUAGES = listOf(
        "tr" to "Turkish",
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "nl" to "Dutch",
        "pl" to "Polish"
    )

    fun getLanguageDisplayName(code: String): String {
        return when (code.lowercase()) {
            "tr" -> "Türkçe"
            "en" -> "İngilizce"
            "de" -> "Almanca"
            "fr" -> "Fransızca"
            "es" -> "İspanyolca"
            "it" -> "İtalyanca"
            "pt" -> "Portekizce"
            "ru" -> "Rusça"
            "zh" -> "Çince"
            "ja" -> "Japonca"
            "ko" -> "Korece"
            "ar" -> "Arapça"
            "nl" -> "Felemenkçe"
            "pl" -> "Lehçe"
            else -> code.uppercase()
        }
    }

    fun getLanguagePromptName(code: String): String {
        return SUPPORTED_LANGUAGES.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second ?: "Turkish"
    }

    suspend fun ensureLoaded(
        context: Context,
        modelId: String = "hy-mt2-1.8b-q4_k_m",
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit
    ) = loadMutex.withLock {
        if (contextPointer != 0L && isLoaded) return@withLock
        val appContext = context.applicationContext
        val spec = TranslationModelRepository.findModel(modelId)

        onStatus("${spec.displayName} modeli hazırlanıyor…")
        val modelFile = TranslationModelRepository.ensureModel(appContext, spec.id) { downloaded, total ->
            onProgress(downloaded.toFloat() / total.coerceAtLeast(1L))
        }

        onStatus("Hy-MT çeviri modeli belleğe yükleniyor…")
        val pointer = withContext(Dispatchers.IO) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            synchronized(nativeMutex) {
                TranslationNative.nativeLoadModel(modelFile.absolutePath, threads)
            }
        }

        check(pointer != 0L) { "Çeviri modeli yüklenemedi. GGUF dosyası bozuk veya bellek yetersiz." }
        synchronized(nativeMutex) {
            contextPointer = pointer
            isLoaded = true
        }
        onProgress(1f)
        onStatus("Hy-MT çeviri modeli hazır · ${spec.displayName}")
    }

    fun translate(sourceText: String, targetLanguageCode: String): TranslationResult {
        val cleanInput = sourceText.trim()
        if (cleanInput.isEmpty()) return TranslationResult("", 0L)

        synchronized(nativeMutex) {
            val pointer = contextPointer
            check(pointer != 0L && isLoaded) { "Çeviri modeli henüz yüklenmedi." }

            val targetLanguageName = getLanguagePromptName(targetLanguageCode)
            // Modelin resmi çeviri istem şablonu:
            // Translate the following segment into {target_language}, without additional explanation.
            // {source_text}
            val promptInstruction = "Translate the following segment into $targetLanguageName, without additional explanation.\n$cleanInput"

            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val startTime = System.currentTimeMillis()
            val rawOutput = TranslationNative.nativeTranslate(pointer, promptInstruction, maxTokens = 128, threads = threads)
            val latency = System.currentTimeMillis() - startTime

            // Özel token temizliği
            val cleanedOutput = rawOutput
                .replace("<｜hy_place▁holder▁no▁2｜>", "")
                .replace("<｜hy_Assistant｜>", "")
                .replace("<|hy_place_holder_no_2|>", "")
                .replace("<|hy_Assistant|>", "")
                .trim()

            return TranslationResult(cleanedOutput, latency)
        }
    }

    fun release() {
        synchronized(nativeMutex) {
            val pointer = contextPointer
            if (pointer != 0L) {
                contextPointer = 0L
                isLoaded = false
                TranslationNative.nativeFree(pointer)
            }
        }
    }
}
