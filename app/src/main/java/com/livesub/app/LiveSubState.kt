package com.livesub.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptionLine(
    val id: Long,
    val text: String,
    val translatedText: String? = null,
    val audioSeconds: Float,
    val latencyMillis: Long,
    val translationLatencyMillis: Long = 0L
)

data class LiveSubUiState(
    val status: String = "Model hazırlanıyor…",
    val modelReady: Boolean = false,
    val modelProgress: Float = 0f,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val selectedLanguage: String = "auto",
    val selectedModel: String = "base",
    val captions: List<CaptionLine> = emptyList(),
    val latencyMillis: Long = 0L,
    val processCpuPercent: Float = 0f,
    val memoryMb: Int = 0,
    val queuedSegments: Int = 0,
    val error: String? = null,
    val errorDetail: String? = null,
    val overlayActive: Boolean = false,
    val stopOnScreenLock: Boolean = false,
    // Aşama 3: Yerel Tencent Hy-MT Çevirisi Durumu
    val isTranslationEnabled: Boolean = true,
    val targetLanguage: String = "tr",
    val translationModelReady: Boolean = false,
    val translationModelProgress: Float = 0f,
    val isTranslating: Boolean = false,
    val translationLatencyMillis: Long = 0L,
    val selectedTranslationModel: String = "hy-mt2-1.8b-q4_k_m"
)

object LiveSubState {
    private const val MAX_CAPTIONS = 80
    private const val MAX_CAPTIONS_LOW_MEMORY = 20

    private val mutableState = MutableStateFlow(LiveSubUiState())
    val state: StateFlow<LiveSubUiState> = mutableState.asStateFlow()

    fun update(transform: (LiveSubUiState) -> LiveSubUiState) {
        mutableState.update(transform)
    }

    fun addCaption(id: Long, text: String, audioSeconds: Float, latencyMillis: Long) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        update { current ->
            current.copy(
                captions = (current.captions + CaptionLine(id, cleanText, null, audioSeconds, latencyMillis)).takeLast(MAX_CAPTIONS),
                latencyMillis = latencyMillis,
                status = "Yeni konuşma parçası yazıya çevrildi",
                error = null,
                errorDetail = null
            )
        }
    }

    fun updateCaptionWithTranslation(id: Long, translatedText: String, translationLatencyMillis: Long) {
        val cleanTranslation = translatedText.trim()
        if (cleanTranslation.isEmpty()) return
        update { current ->
            val updated = current.captions.map { caption ->
                if (caption.id == id) {
                    caption.copy(translatedText = cleanTranslation, translationLatencyMillis = translationLatencyMillis)
                } else caption
            }
            current.copy(
                captions = updated,
                translationLatencyMillis = translationLatencyMillis,
                status = "Çeviri tamamlandı"
            )
        }
    }

    /**
     * Kullanıcıya gösterilecek mesajı ve teknik ayrıntıyı birlikte saklar.
     * Uygulama arka plandayken hata bildirimi de gönderilir (Aşama 5).
     */
    fun setError(message: String, detail: String? = null) {
        update { current ->
            current.copy(
                status = message,
                error = message,
                errorDetail = detail,
                isRecording = false
            )
        }
        ErrorNotifier.notifyError(message, detail)
    }

    /**
     * Dinlemeyi sonlandırmayan hatalar (tek bir Whisper/çeviri parçası gibi) için
     * durum mesajını günceller; kayıt durumuna dokunmaz.
     */
    fun reportError(message: String, detail: String? = null) {
        update { it.copy(status = message, error = message, errorDetail = detail) }
        ErrorNotifier.notifyError(message, detail)
    }

    fun clearError() {
        update { it.copy(error = null, errorDetail = null) }
    }

    /** Sistem bellek baskısı bildirdiğinde döküm geçmişini küçültür. */
    fun trimForMemory() {
        update { current ->
            if (current.captions.size <= MAX_CAPTIONS_LOW_MEMORY) current
            else current.copy(captions = current.captions.takeLast(MAX_CAPTIONS_LOW_MEMORY))
        }
    }
}
