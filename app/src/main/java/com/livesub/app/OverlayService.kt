package com.livesub.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.livesub.translation.TranslationEngine
import com.livesub.translation.TranslationModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * Diğer uygulamaların üzerinde yüzen altyazı kutusunu yöneten servis.
 *
 * - Kutu yarı saydamdır, sürüklenerek konumu değiştirilebilir.
 * - Aynı anda en fazla iki satır gösterilir; yeni satır geldiğinde eski satır yukarı kayıp kaybolur.
 * - "Orijinal + çeviri" modunda son konuşma parçasının iki dili üst üste gösterilir.
 */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var previewJob: Job? = null
    private var preferences = OverlayPreferences()
    private val rows = mutableListOf<TextView>()
    private var lastSignature: String? = null
    private var previewing = false

    // Sürükleme durumu
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragging = false

    // Çift tıklama algılama
    private var lastTapTime = 0L
    private var tapCount = 0
    private var lastTapX = 0f
    private var lastTapY = 0f

    // Desteklenen hedef diller (TranslationEngine'den)
    private val targetLanguages = TranslationEngine.SUPPORTED_LANGUAGES.map { it.first }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (!ensureOverlayAttached()) return START_NOT_STICKY
                startObserving()
                renderNow(force = true)
            }
            ACTION_REFRESH -> {
                if (!ensureOverlayAttached()) return START_NOT_STICKY
                startObserving()
                preferences = OverlaySettings.load(this)
                applyStyle()
                // Önizleme sürerken canlı durum yazılmasın; örnek satırlar yeni stille tazelenir.
                if (previewing) {
                    renderRows(previewRows())
                } else {
                    renderNow(force = true)
                }
            }
            ACTION_PREVIEW -> {
                if (!ensureOverlayAttached()) return START_NOT_STICKY
                startObserving()
                showPreview()
            }
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureOverlayAttached(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            LiveSubState.update {
                it.copy(
                    overlayActive = false,
                    status = "Yüzen altyazı izni verilmedi; kutu gösterilemiyor.",
                    error = "Yüzen altyazı için 'Diğer uygulamaların üzerinde göster' izni gerekli."
                )
            }
            stopSelf()
            return false
        }
        if (root != null) return true

        preferences = OverlaySettings.load(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12)) // Daha geniş iç boşluk, dokunma hedefi ≥ 48dp
            elevation = dp(8).toFloat()
            // Gölge için outline provider - yumuşak siyah gölge (4dp offset, 8dp blur)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            // Erişilebilirlik: içerik açıklaması
            contentDescription = "LiveSub yüzen altyazı kutusu. Sürükleyerek taşıyın, çift tıklayarak çeviri dilini değiştirin."
            // Odaklanabilir olmamalı ama erişilebilirlik servisleri için önemli
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.positionX
            y = preferences.positionY
        }

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragging = false

                    // Çift tıklama algılama
                    val now = System.currentTimeMillis()
                    val dx = abs(event.rawX - lastTapX)
                    val dy = abs(event.rawY - lastTapY)
                    if (now - lastTapTime < 300 && dx < dp(24) && dy < dp(24)) {
                        tapCount++
                        if (tapCount == 2) {
                            // Çift tık: çeviri dilini değiştir
                            cycleTargetLanguage()
                            tapCount = 0
                            true
                        } else {
                            lastTapTime = now
                            lastTapX = event.rawX
                            lastTapY = event.rawY
                            true
                        }
                    } else {
                        tapCount = 1
                        lastTapTime = now
                        lastTapX = event.rawX
                        lastTapY = event.rawY
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchStartX).toInt()
                    val deltaY = (event.rawY - touchStartY).toInt()
                    if (abs(deltaX) > dp(4) || abs(deltaY) > dp(4)) {
                        dragging = true
                        tapCount = 0 // Sürükleme başladıysa çift tıklama iptal
                    }
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        val maxX = (metrics.widthPixels - (root?.width ?: 0)).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - (root?.height ?: 0)).coerceAtLeast(0)
                        params.x = (dragStartX + deltaX).coerceIn(0, maxX)
                        params.y = (dragStartY + deltaY).coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (error: Exception) {
                            AppLog.w(TAG, "Altyazı kutusu taşınamadı", error)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        OverlaySettings.savePosition(this, params.x, params.y)
                        preferences = preferences.copy(positionX = params.x, positionY = params.y)
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(container, params)
            root = container
            layoutParams = params
            applyStyle()
            LiveSubState.update { it.copy(overlayActive = true) }
            true
        } catch (error: Exception) {
            AppLog.e(TAG, "Yüzen altyazı kutusu eklenemedi", error)
            LiveSubState.update {
                it.copy(
                    overlayActive = false,
                    status = "Yüzen altyazı kutusu gösterilemedi.",
                    error = "Yüzen altyazı başlatılamadı: ${error.localizedMessage ?: error.javaClass.simpleName}"
                )
            }
            stopSelf()
            false
        }
    }

    private fun startObserving() {
        if (scope != null) return
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).also { currentScope ->
            currentScope.launch {
                LiveSubState.state.collect { state ->
                    if (!previewing) render(state)
                }
            }
        }
    }

    private fun previewRows(): List<OverlayRow> = listOf(
        OverlayRow("Bu bir LiveSub altyazı önizlemesidir.", dim = false),
        OverlayRow("This is a LiveSub subtitle preview.", dim = false)
    )

    private fun showPreview() {
        previewJob?.cancel()
        previewing = true
        renderRows(previewRows())
        previewJob = scope?.launch {
            delay(PREVIEW_DURATION_MS)
            previewing = false
            if (LiveSubState.state.value.isRecording) {
                renderNow(force = true)
            } else {
                removeOverlay()
                stopSelf()
            }
        }
    }

    private fun renderNow(force: Boolean = false) {
        if (force) lastSignature = null
        render(LiveSubState.state.value)
    }

    private fun render(state: LiveSubUiState) {
        if (root == null) return
        val currentPreferences = preferences
        val lines = buildRows(state, currentPreferences)
        val signature = buildString {
            append(currentPreferences.fontSizeSp).append('|')
            append(currentPreferences.textColorArgb).append('|')
            append(currentPreferences.backgroundColorArgb).append('|')
            append(currentPreferences.backgroundOpacity).append('|')
            lines.forEach { append(it.text).append(if (it.dim) '0' else '1') }
        }
        if (signature == lastSignature) return
        lastSignature = signature
        applyStyle()
        renderRows(lines)
    }

    private fun buildRows(state: LiveSubUiState, currentPreferences: OverlayPreferences): List<OverlayRow> {
        if (state.captions.isEmpty()) {
            val hint = if (state.isRecording) "LiveSub · konuşma bekleniyor…" else "LiveSub · hazır"
            return listOf(OverlayRow(hint, dim = true))
        }

        val showOriginal = currentPreferences.showOriginal || !currentPreferences.showTranslation
        val showTranslation = currentPreferences.showTranslation

        if (showOriginal && showTranslation) {
            // Son parçanın orijinali ve çevirisi üst üste; en fazla iki satır.
            val latest = state.captions.last()
            val result = mutableListOf(OverlayRow(latest.text, dim = false))
            latest.translatedText?.takeIf { it.isNotBlank() }?.let { translated ->
                result.add(OverlayRow(translated, dim = false))
            }
            return result.takeLast(MAX_LINES)
        }

        // Tek dil modu: önceki ve son parça; böylece eski satır yukarı kayarak silinir.
        val previous = state.captions.getOrNull(state.captions.size - 2)
        val latest = state.captions.last()
        val pick: (CaptionLine?) -> String? = { caption ->
            when {
                caption == null -> null
                showTranslation -> caption.translatedText?.takeIf { it.isNotBlank() } ?: caption.text
                else -> caption.text
            }
        }
        return listOfNotNull(
            pick(previous)?.let { OverlayRow(it, dim = true) },
            pick(latest)?.let { OverlayRow(it, dim = false) }
        ).takeLast(MAX_LINES)
    }

    private fun renderRows(lines: List<OverlayRow>) {
        val container = root ?: return
        while (rows.size < lines.size) {
            val row = TextView(this).apply {
                setTextColor(preferences.textColorArgb)
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                setSingleLine(true)
                // Minimum 48dp yükseklik (erişilebilirlik dokunma hedefi)
                setMinHeight(dp(48))
                setPadding(dp(4), dp(8), dp(4), dp(8))
                // Erişilebilirlik
                contentDescription = "Altyazı satırı"
            }
            container.addView(row)
            rows.add(row)
        }

        rows.forEachIndexed { index, row ->
            if (index >= lines.size) {
                row.visibility = View.GONE
                return@forEachIndexed
            }
            val line = lines[index]
            val textChanged = row.text.toString() != line.text
            row.visibility = View.VISIBLE
            if (textChanged) {
                row.text = line.text
                row.alpha = 0f
                row.translationY = dp(10).toFloat()
                row.animate()
                    .alpha(if (line.dim) DIM_ALPHA else 1f)
                    .translationY(0f)
                    .setDuration(200L)
                    .start()
            } else {
                row.alpha = if (line.dim) DIM_ALPHA else 1f
            }
        }
    }

    private fun applyStyle() {
        val container = root ?: return
        val bgColor = compositeColor(preferences.backgroundColorArgb, preferences.backgroundOpacity)
        val background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(bgColor)
        }
        container.background = background

        // Yumuşak gölge (elevation + outline provider BACKGROUND zaten ayarlandı)
        // API 28+ için clipToOutline + outlineProvider BACKGROUND yeterli
        // Daha eski API'lerde gölge manuel eklenebilir ama MatePad 11 API 29+ olduğu için sorun yok

        val maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val textColor = preferences.textColorArgb
        // Kontrast hesapla: arka plan parlak mı koyu mu?
        val bgLuminance = luminance(bgColor)
        val textLuminance = luminance(textColor)
        val isDarkBackground = bgLuminance < 0.5f
        val isLightText = textLuminance > 0.5f

        rows.forEach { row ->
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.fontSizeSp)
            row.setTextColor(textColor)
            row.maxWidth = maxWidth
            row.setMinHeight(dp(48)) // Erişilebilirlik: minimum dokunma hedefi
            // Yazı stili: kontrasta göre gölge - her zaman hafif gölge ile okunabilirlik artır
            when {
                // Koyu arka plan + açık yazı → yumuşak siyah gölge (okunabilirlik)
                isDarkBackground && isLightText -> {
                    row.setShadowLayer(4f, 2f, 2f, 0x66000000.toInt()) // 0.4 alpha
                }
                // Açık arka plan + koyu yazı → yumuşak siyah gölge
                !isDarkBackground && !isLightText -> {
                    row.setShadowLayer(4f, 2f, 2f, 0x66000000.toInt())
                }
                // Benzer tonlar → daha güçlü gölge zorunlu
                else -> {
                    row.setShadowLayer(6f, 2f, 2f, 0x99000000.toInt()) // 0.6 alpha
                }
            }
        }
    }

    /**
     * Relative luminance (sRGB) hesaplar - WCAG kontrast için.
     * 0.0 = siyah, 1.0 = beyaz
     */
    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val linear = floatArrayOf(r, g, b).map { c ->
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * linear[0] + 0.7152f * linear[1] + 0.0722f * linear[2]
    }

    private fun compositeColor(color: Int, opacity: Float): Int {
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun removeOverlay() {
        previewJob?.cancel()
        previewJob = null
        previewing = false
        scope?.cancel()
        scope = null
        val view = root
        root = null
        layoutParams = null
        rows.clear()
        lastSignature = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (error: Exception) {
                AppLog.w(TAG, "Altyazı kutusu kaldırılamadı", error)
            }
        }
        LiveSubState.update { it.copy(overlayActive = false) }
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private data class OverlayRow(val text: String, val dim: Boolean)

    /**
     * Hedef çeviri dilini sırayla değiştirir (çift tıkla).
     * Yeni dil kaydedilir ve LiveSubState güncellenir; CaptureService yeni çevirilerde bunu kullanır.
     */
    private fun cycleTargetLanguage() {
        val currentLang = TranslationModelRepository.getTargetLanguage(this)
        val currentIndex = targetLanguages.indexOf(currentLang)
        val nextIndex = (currentIndex + 1) % targetLanguages.size
        val nextLang = targetLanguages[nextIndex]

        TranslationModelRepository.setTargetLanguage(this, nextLang)
        val displayName = TranslationEngine.getLanguageDisplayName(nextLang)

        // Kullanıcıya görsel geri bildirim: altyazı kutusunda kısa süre yeni dili göster
        showLanguageChangedHint(displayName)

        // LiveSubState'i güncelle ki CaptureService bir sonraki çeviride yeni dili kullanabilsin
        LiveSubState.update { it.copy(targetLanguage = nextLang) }

        AppLog.i(TAG, "Çeviri dili değiştirildi: $currentLang -> $nextLang ($displayName)")
    }

    /**
     * Dil değişimini altyazı kutusunda kısa süre gösterir.
     */
    private fun showLanguageChangedHint(displayName: String) {
        val container = root ?: return
        val hintText = "Dil: $displayName"
        val originalRows = rows.map { it.text.toString() }.toList()

        // Tüm satırları geçici olarak dil adıyla değiştir
        rows.forEachIndexed { index, row ->
            row.visibility = View.VISIBLE
            row.text = if (index == 0) hintText else ""
            row.alpha = 1f
        }

        // 1.5 saniye sonra eski içeriği geri getir
        scope?.launch {
            delay(1500L)
            if (root != null) {
                // Normal render akışı zaten LiveSubState'ten okuyup doğru metni gösterecek
                renderNow(force = true)
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.livesub.app.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.livesub.app.action.HIDE_OVERLAY"
        const val ACTION_REFRESH = "com.livesub.app.action.REFRESH_OVERLAY"
        const val ACTION_PREVIEW = "com.livesub.app.action.PREVIEW_OVERLAY"

        private const val MAX_LINES = 2
        private const val DIM_ALPHA = 0.62f
        private const val PREVIEW_DURATION_MS = 6_000L
        private const val TAG = "LiveSubOverlay"
    }
}
