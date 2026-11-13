package com.livesub.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.lifecycleScope
import com.livesub.translation.TranslationEngine
import com.livesub.translation.TranslationModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.content.res.Configuration

class MainActivity : ComponentActivity() {
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var overlayPreferences by mutableStateOf(OverlayPreferences())
    private var overlayPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(true)
    private var batteryOptimizationIgnored by mutableStateOf(false)
    private var stopOnScreenLock by mutableStateOf(false)
    private var pendingStartAfterOverlay = false
    private var notificationRequestStartsCapture = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            continueWithProjectionPermission()
        } else {
            LiveSubState.setError("Ses yakalama için Android RECORD_AUDIO izni gerekli. Ayarlardan izni verip yeniden deneyin.")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionGranted = hasNotificationPermission()
        if (notificationRequestStartsCapture) {
            notificationRequestStartsCapture = false
            startProjectionConsent()
        }
    }

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (!overlayPermissionGranted) {
            LiveSubState.setError(
                "Yüzen altyazı izni verilmedi. Ayarlardan 'Diğer uygulamaların üzerinde göster' iznini açın " +
                    "veya yüzen altyazıyı kapatın."
            )
        }
        if (pendingStartAfterOverlay) {
            pendingStartAfterOverlay = false
            if (overlayPermissionGranted || !overlayPreferences.enabled) {
                continueWithProjectionPermission()
            }
        }
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(CaptureService.EXTRA_LANGUAGE, LiveSubState.state.value.selectedLanguage)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                else startService(serviceIntent)
                LiveSubState.update {
                    it.copy(
                        isRecording = true,
                        status = "Sistem sesi dinleniyor · konuşma bekleniyor…",
                        error = null,
                        errorDetail = null,
                        captions = emptyList()
                    )
                }
            } catch (error: Exception) {
                LiveSubState.setError(
                    "Ses yakalama servisi başlatılamadı: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    error.stackTraceToString()
                )
            }
        } else {
            LiveSubState.setError("Ekran yakalama izni verilmedi; ses kaydı başlatılmadı.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlayPreferences = OverlaySettings.load(this)
        stopOnScreenLock = BehaviorSettings.stopOnScreenLock(this)
        refreshPermissionStates()

        // Tercihleri yükle
        val savedTranslationEnabled = TranslationModelRepository.isTranslationEnabled(applicationContext)
        val savedTargetLang = TranslationModelRepository.getTargetLanguage(applicationContext)
        val savedTranslationModel = TranslationModelRepository.getSelectedModel(applicationContext)
        LiveSubState.update {
            it.copy(
                isTranslationEnabled = savedTranslationEnabled,
                targetLanguage = savedTargetLang,
                selectedModel = WhisperModelRepository.selectedModel(applicationContext),
                stopOnScreenLock = stopOnScreenLock,
                selectedTranslationModel = savedTranslationModel
            )
        }

        // Whisper ve Hy-MT modellerini hazırla
        loadModels()

        setContent {
            val ui by LiveSubState.state.collectAsState()
            var screen by rememberSaveable { mutableStateOf(SCREEN_LIVE) }
            val isDarkTheme = LocalConfiguration.current.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            LiveSubTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (screen == SCREEN_SETTINGS) {
                        SettingsScreen(
                            ui = ui,
                            overlayPreferences = overlayPreferences,
                            overlayPermissionGranted = overlayPermissionGranted,
                            notificationPermissionGranted = notificationPermissionGranted,
                            batteryOptimizationIgnored = batteryOptimizationIgnored,
                            stopOnScreenLock = stopOnScreenLock,
                            onBack = { screen = SCREEN_LIVE },
                            onReloadModels = { loadModels() },
                            onWhisperModelSelected = { modelId -> selectWhisperModel(modelId) },
                            onTranslationToggled = { enabled -> setTranslationEnabled(enabled) },
                            onSourceLanguageSelected = { language ->
                                LiveSubState.update { it.copy(selectedLanguage = language) }
                            },
                            onTargetLanguageSelected = { language -> setTargetLanguage(language) },
                            onOverlayChanged = { preferences -> updateOverlayPreferences(preferences) },
                            onRequestOverlayPermission = { requestOverlayPermission() },
                            onPreviewOverlay = { previewOverlay() },
                            onStopOnScreenLockChanged = { enabled -> updateStopOnScreenLock(enabled) },
                            onRequestBatteryExemption = { requestBatteryExemption() },
                            onRequestNotificationPermission = { requestNotificationPermission() },
                            onTranslationModelSelected = { modelId -> selectTranslationModel(modelId) }
                        )
                    } else {
                        LiveScreen(
                            ui = ui,
                            overlayPreferences = overlayPreferences,
                            overlayPermissionGranted = overlayPermissionGranted,
                            onStartCapture = { requestCapturePermissions() },
                            onStopCapture = { stopCapture() },
                            onToggleOverlay = { enabled ->
                                updateOverlayPreferences(overlayPreferences.copy(enabled = enabled))
                            },
                            onRequestOverlayPermission = { requestOverlayPermission() },
                            onPreviewOverlay = { previewOverlay() },
                            onOpenSettings = { screen = SCREEN_SETTINGS },
                            onDismissError = { LiveSubState.clearError() },
                            onRetryModels = { loadModels() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ErrorNotifier.setAppVisible(true)
        refreshPermissionStates()
        // İzin ekranından dönüşte ActivityResult gelmezse diye güvenli yeniden deneme.
        if (pendingStartAfterOverlay && Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlay = false
            continueWithProjectionPermission()
        }
    }

    override fun onPause() {
        ErrorNotifier.setAppVisible(false)
        super.onPause()
    }

    private fun refreshPermissionStates() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        notificationPermissionGranted = hasNotificationPermission()
        batteryOptimizationIgnored = BehaviorSettings.isIgnoringBatteryOptimizations(this)
        stopOnScreenLock = BehaviorSettings.stopOnScreenLock(this)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCapturePermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            continueWithProjectionPermission()
        }
    }

    private fun continueWithProjectionPermission() {
        if (overlayPreferences.enabled && !Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlay = true
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationRequestStartsCapture = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startProjectionConsent()
        }
    }

    private fun startProjectionConsent() {
        projectionConsent.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        try {
            startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
        } catch (error: Exception) {
            LiveSubState.setError(
                "Dinleme durdurulamadı: ${error.localizedMessage ?: error.javaClass.simpleName}",
                error.stackTraceToString()
            )
        }
    }

    private fun loadModels() {
        lifecycleScope.launch {
            try {
                val whisperModelId = WhisperModelRepository.selectedModel(applicationContext)
                LiveSubState.update {
                    it.copy(
                        selectedModel = whisperModelId,
                        status = "Whisper modeli hazırlanıyor…",
                        error = null,
                        errorDetail = null
                    )
                }
                WhisperEngine.ensureLoaded(applicationContext, whisperModelId)

                if (LiveSubState.state.value.isTranslationEnabled) {
                    val translationModelId = LiveSubState.state.value.selectedTranslationModel
                    TranslationEngine.ensureLoaded(
                        applicationContext,
                        translationModelId,
                        onProgress = { progress ->
                            LiveSubState.update { it.copy(translationModelProgress = progress) }
                        },
                        onStatus = { status ->
                            LiveSubState.update { it.copy(status = status) }
                        }
                    )
                    LiveSubState.update {
                        it.copy(
                            translationModelReady = true,
                            status = "Tüm modeller hazır · Dinlemeye başlanabilir"
                        )
                    }
                } else {
                    LiveSubState.update {
                        it.copy(translationModelReady = false, status = "Whisper hazır · Çeviri kapalı")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LiveSubState.setError(
                    "Modeller hazırlanamadı: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    error.stackTraceToString()
                )
            }
        }
    }

    private fun selectWhisperModel(modelId: String) {
        if (LiveSubState.state.value.isRecording) return
        WhisperModelRepository.saveSelectedModel(applicationContext, modelId)
        LiveSubState.update { it.copy(selectedModel = modelId) }
        loadModels()
    }

    private fun setTranslationEnabled(enabled: Boolean) {
        TranslationModelRepository.setTranslationEnabled(applicationContext, enabled)
        LiveSubState.update { it.copy(isTranslationEnabled = enabled) }
        if (enabled && !LiveSubState.state.value.translationModelReady) {
            loadModels()
        } else if (!enabled) {
            LiveSubState.update { it.copy(isTranslating = false) }
        }
    }

    private fun setTargetLanguage(language: String) {
        TranslationModelRepository.setTargetLanguage(applicationContext, language)
        LiveSubState.update { it.copy(targetLanguage = language) }
    }

    private fun selectTranslationModel(modelId: String) {
        if (LiveSubState.state.value.isRecording) return
        TranslationModelRepository.setSelectedModel(applicationContext, modelId)
        LiveSubState.update { it.copy(selectedTranslationModel = modelId) }
        loadModels()
    }

    private fun updateStopOnScreenLock(enabled: Boolean) {
        BehaviorSettings.setStopOnScreenLock(applicationContext, enabled)
        stopOnScreenLock = enabled
        LiveSubState.update { it.copy(stopOnScreenLock = enabled) }
    }

    private fun updateOverlayPreferences(preferences: OverlayPreferences) {
        overlayPreferences = preferences
        OverlaySettings.save(applicationContext, preferences)
        if (!preferences.enabled) {
            try {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
            } catch (_: Exception) { }
        } else if (LiveSubState.state.value.overlayActive) {
            try {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
            } catch (_: Exception) { }
        }
    }

    private fun requestOverlayPermission() {
        val packageUri = Uri.parse("package:$packageName")
        try {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        } catch (error: Exception) {
            try {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (fallbackError: Exception) {
                LiveSubState.setError(
                    "Yüzen altyazı izin ekranı açılamadı. Ayarlar > Uygulamalar > LiveSub > Diğer uygulamaların üzerinde göster yolunu kullanın.",
                    error.toString()
                )
            }
        }
    }

    private fun previewOverlay() {
        if (!overlayPreferences.enabled) {
            updateOverlayPreferences(overlayPreferences.copy(enabled = true))
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        try {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PREVIEW))
        } catch (error: Exception) {
            LiveSubState.setError(
                "Altyazı önizlemesi başlatılamadı: ${error.localizedMessage ?: error.javaClass.simpleName}",
                error.stackTraceToString()
            )
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (error: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (fallbackError: Exception) {
                LiveSubState.setError(
                    "Pil optimizasyonu ayarı açılamadı. Ayarlar > Pil > Uygulama başlatma yolunu kullanın.",
                    error.toString()
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationRequestStartsCapture = false
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val SCREEN_LIVE = "live"
        private const val SCREEN_SETTINGS = "settings"
    }
}
