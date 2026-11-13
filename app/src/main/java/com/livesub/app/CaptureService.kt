package com.livesub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.livesub.translation.TranslationEngine
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private data class TranslationTask(
    val captionId: Long,
    val text: String,
    val targetLanguage: String
)

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var sessionActive = false
    private val segments = LinkedBlockingQueue<FloatArray>(8)  // 4 -> 8: daha fazla tampon
    private val translationQueue = LinkedBlockingQueue<TranslationTask>(8)
    private val stopOnce = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var whisperThread: Thread? = null
    private var translationThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiverRegistered = false

    /** Ekran kapanıp açıldığında dinleme ve yüzen altyazı davranışını yönetir. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (BehaviorSettings.stopOnScreenLock(context)) {
                        stopSession("Ekran kilitlendi; dinleme durduruldu.")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (sessionActive && OverlaySettings.load(context).enabled) {
                        refreshOverlay()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("Kayıt kullanıcı tarafından durduruldu.")
            ACTION_START -> startSession(intent)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (sessionActive) return
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val projectionData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            } ?: error("MediaProjection izin verisi bulunamadı.")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
            val mainHandler = Handler(Looper.getMainLooper())
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSession("Ekran yakalama izni durduruldu; dinleme sona erdi.")
                }
            }, mainHandler)
            registerScreenReceiver()
            acquireWakeLock()
            startAudioCapture()
            showOverlayIfEnabled()
        } catch (error: Exception) {
            AppLog.e(TAG, "Yakalama servisi başlatılamadı", error)
            LiveSubState.setError(
                "Yakalama servisi başlatılamadı: ${error.localizedMessage ?: error.javaClass.simpleName}",
                error.stackTraceToString()
            )
            stopSession(null)
        }
    }

    private fun startAudioCapture() {
        val activeProjection = projection ?: error("MediaProjection oluşturulamadı.")
        val sampleRate = SAMPLE_RATE
        val inputChannel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimumBytes = AudioRecord.getMinBufferSize(sampleRate, inputChannel, encoding)
        check(minimumBytes > 0) { "AudioRecord tampon boyutu alınamadı: $minimumBytes" }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(inputChannel)
            .build()
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimumBytes * 2, sampleRate * 2))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord başlatılamadı." }

        audioRecord = recorder
        sessionActive = true
        stopOnce.set(false)
        segments.clear()
        translationQueue.clear()

        // 3 ayrı bağımsız iş parçacığı: Ses Yakalama, Whisper Transkripsiyonu ve Tencent Hy-MT Çevirisi
        // Not: Bu iş parçacıkları CPU'ya doygun çalışır. THREAD_PRIORITY_BACKGROUND (-10) ile
        // sistem onları ses yakalama/oynatma işlerine göre ciddi biçimde kısıyor ve gecikme
        // artıyordu. Varsayılan öncelik, dinleme sürerken makul CPU payı bırakır.
        translationThread = thread(name = "LiveSubTranslationWorker") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            translateQueuedSegments()
        }
        whisperThread = thread(name = "LiveSubWhisperWorker") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            transcribeQueuedSegments()
        }
        captureThread = thread(name = "LiveSubAudioCapture") { captureAndSegment(recorder) }
    }

    private fun captureAndSegment(recorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val blockSamples = 1_600
        val blockDurationMs = blockSamples * 1_000L / SAMPLE_RATE
        val input = ShortArray(blockSamples)
        val preRoll = ArrayDeque<ShortArray>()
        val speechSamples = ArrayList<Short>(SAMPLE_RATE * 8)
        var speechActive = false
        var speechDurationMs = 0L
        var silenceDurationMs = 0L
        var consecutiveQuietMs = 0L
        val maxChunkSamples = SAMPLE_RATE * MAX_SEGMENT_SECONDS
        val minVoiceMs = 300L  // 350 -> 300 ms: daha kısa konuşmaları da yakala
        val silenceLimitMs = 300L  // 500 -> 300 ms: sessizlikte çok çabuk böl
        val rmsThreshold = 520.0
        var failure: String? = null
        var startupRmsSum = 0.0
        var startupBlocks = 0

        try {
            recorder.startRecording()
            LiveSubState.update { it.copy(isRecording = true, status = "Sistem sesi dinleniyor · konuşma bekleniyor…", error = null) }
            AppLog.i(
                TAG,
                "Yakalama başladı: source=${recorder.audioSource} sampleRate=${recorder.sampleRate} " +
                    "channels=${recorder.channelCount} format=${recorder.audioFormat} block=${blockDurationMs}ms"
            )

            while (sessionActive) {
                val count = recorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (!sessionActive) break
                    if (count < 0) error(audioRecordError(count))
                    continue
                }
                val block = input.copyOf(count)
                val blockMs = count * 1_000L / SAMPLE_RATE
                val rms = calculateRms(block)
                val speechNow = rms >= rmsThreshold

                // İlk 3 saniyenin ortalama ses düzeyi: yakalama gerçekten ses alıyor mu?
                if (startupBlocks < 30) {
                    startupRmsSum += rms
                    startupBlocks++
                    if (startupBlocks == 30) {
                        AppLog.i(
                            TAG,
                            "Başlangıç ses düzeyi: ortalama RMS=${(startupRmsSum / 30).toInt()} " +
                                "(konuşma eşiği=${rmsThreshold.toInt()})"
                        )
                    }
                }

                if (!speechActive) {
                    if (speechNow) {
                        speechActive = true
                        speechDurationMs = blockMs
                        silenceDurationMs = 0
                        preRoll.forEach { prior -> prior.forEach { speechSamples.add(it) } }
                        block.forEach { speechSamples.add(it) }
                        preRoll.clear()
                    } else {
                        preRoll.addLast(block)
                        consecutiveQuietMs += blockMs
                        while (consecutiveQuietMs > PREROLL_MS && preRoll.isNotEmpty()) {
                            val removed = preRoll.removeFirst()
                            consecutiveQuietMs -= removed.size * 1_000L / SAMPLE_RATE
                        }
                    }
                } else {
                    block.forEach { speechSamples.add(it) }
                    if (speechNow) {
                        speechDurationMs += blockMs
                        silenceDurationMs = 0
                    } else {
                        silenceDurationMs += blockMs
                    }

                    if (silenceDurationMs >= silenceLimitMs) {
                        val trimCount = minOf(speechSamples.size, (silenceDurationMs * SAMPLE_RATE / 1_000L).toInt())
                        repeat(trimCount) { speechSamples.removeAt(speechSamples.lastIndex) }
                        if (speechDurationMs >= minVoiceMs) enqueueSpeech(speechSamples)
                        speechSamples.clear()
                        speechActive = false
                        speechDurationMs = 0
                        silenceDurationMs = 0
                        consecutiveQuietMs = 0
                        preRoll.clear()
                    } else if (speechSamples.size >= maxChunkSamples) {
                        enqueueSpeech(speechSamples)
                        val overlapSamples = minOf(speechSamples.size, SAMPLE_RATE / 5)
                        val overlap = speechSamples.takeLast(overlapSamples)
                        speechSamples.clear()
                        speechSamples.addAll(overlap)
                        speechDurationMs = 0
                        silenceDurationMs = 0
                    }
                }
            }

            if (speechActive && speechDurationMs >= minVoiceMs) enqueueSpeech(speechSamples)
        } catch (error: Exception) {
            if (sessionActive) {
                failure = error.localizedMessage ?: error.javaClass.simpleName
                AppLog.e(TAG, "Ses yakalama döngüsü başarısız", error)
                LiveSubState.setError("Ses yakalama hatası: $failure", error.stackTraceToString())
            }
        } finally {
            sessionActive = false
            try { recorder.stop() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
            audioRecord = null
            stopProjection()
            whisperThread?.interrupt()
            whisperThread?.join(2_000)
            whisperThread = null
            translationThread?.interrupt()
            translationThread?.join(2_000)
            translationThread = null
            unregisterScreenReceiver()
            releaseWakeLock()
            hideOverlay()
            LiveSubState.update {
                it.copy(
                    isRecording = false,
                    queuedSegments = 0,
                    status = failure?.let { message -> "Yakalama hatası: $message" } ?: "Dinleme durduruldu."
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enqueueSpeech(samples: List<Short>) {
        if (samples.size < SAMPLE_RATE / 3) return
        val pcm = FloatArray(samples.size) { index -> samples[index] / 32768f }
        // Tanı: parçanın süresi ve ses düzeyi (sessiz yakalama / VAD sorunlarını ayırt etmek için).
        var sumSquares = 0.0
        var peak = 0
        for (sample in samples) {
            val value = sample.toInt()
            sumSquares += value.toDouble() * value
            if (value > peak) peak = value else if (-value > peak) peak = -value
        }
        val segmentRms = kotlin.math.sqrt(sumSquares / samples.size)
        AppLog.i(
            TAG,
            "Konuşma parçası: ${samples.size * 1_000L / SAMPLE_RATE}ms rms=${segmentRms.toInt()} peak=$peak"
        )
        if (!segments.offer(pcm)) {
            AppLog.w(TAG, "Transkripsiyon kuyruğu dolu; bu parça atlandı.")
            LiveSubState.update { it.copy(status = "Whisper kuyruğu doldu; bu kısa parça işlenemedi.") }
            return
        }
        LiveSubState.update {
            it.copy(status = "Konuşma parçası kuyruğa alındı…", queuedSegments = segments.size)
        }
    }

    private fun transcribeQueuedSegments() {
        while (sessionActive || segments.isNotEmpty()) {
            val audio = try {
                segments.poll(150, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            val startedAt = SystemClock.elapsedRealtimeNanos()
            val cpuStartedAt = Process.getElapsedCpuTime()
            LiveSubState.update { it.copy(isTranscribing = true, status = "Whisper konuşmayı çözüyor…") }
            try {
                val transcript = WhisperEngine.transcribe(audio, LiveSubState.state.value.selectedLanguage)
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
                val cpuMs = Process.getElapsedCpuTime() - cpuStartedAt
                val cpuPercent = if (elapsedMs > 0) cpuMs * 100f / elapsedMs else 0f
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                val durationSeconds = audio.size.toFloat() / SAMPLE_RATE

                if (transcript.isNotBlank()) {
                    val captionId = System.nanoTime()
                    LiveSubState.addCaption(captionId, transcript, durationSeconds, elapsedMs)

                    // Çeviri etkinse ve model hazırsa çeviri iş kuyruğuna gönder (Whisper'ı bloklamaz)
                    val currentState = LiveSubState.state.value
                    if (currentState.isTranslationEnabled && currentState.translationModelReady) {
                        val task = TranslationTask(captionId, transcript, currentState.targetLanguage)
                        if (!translationQueue.offer(task)) {
                            AppLog.w(TAG, "Çeviri kuyruğu dolu; en eski öğe çıkarılıyor")
                            translationQueue.poll()
                            translationQueue.offer(task)
                        }
                    }
                } else {
                    LiveSubState.update { it.copy(status = "Ses parçasında tanınabilir konuşma bulunamadı.", latencyMillis = elapsedMs) }
                }
                LiveSubState.update {
                    it.copy(
                        processCpuPercent = cpuPercent,
                        memoryMb = memory.totalPss / 1024,
                        queuedSegments = segments.size
                    )
                }
                AppLog.i(
                    TAG,
                    "Whisper segment: audio=${durationSeconds}s latency=${elapsedMs}ms cpu=${cpuMs}ms " +
                        "rssPss=${memory.totalPss}KB textLen=${transcript.length} metin=${transcript.take(120)}"
                )
            } catch (error: Exception) {
                AppLog.e(TAG, "Whisper transkripsiyonu başarısız", error)
                LiveSubState.reportError(
                    "Whisper hatası: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    error.stackTraceToString()
                )
            } finally {
                LiveSubState.update { it.copy(isTranscribing = false, queuedSegments = segments.size) }
            }
        }
    }

    private fun translateQueuedSegments() {
        while (sessionActive || translationQueue.isNotEmpty()) {
            val task = try {
                translationQueue.poll(150, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            if (!LiveSubState.state.value.isTranslationEnabled) continue

            LiveSubState.update { it.copy(isTranslating = true, status = "Hy-MT çeviriyor…") }
            try {
                val translationResult = TranslationEngine.translate(
                    task.text,
                    task.targetLanguage
                )
                if (translationResult.translatedText.isNotBlank()) {
                    LiveSubState.updateCaptionWithTranslation(
                        task.captionId,
                        translationResult.translatedText,
                        translationResult.latencyMillis
                    )
                }
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                LiveSubState.update {
                    it.copy(
                        memoryMb = memory.totalPss / 1024,
                        status = "Çeviri tamamlandı · ${translationResult.latencyMillis} ms"
                    )
                }
                AppLog.i(
                    TAG,
                    "Hy-MT translation: latency=${translationResult.latencyMillis}ms target=${task.targetLanguage} " +
                        "textLen=${translationResult.translatedText.length} metin=${translationResult.translatedText.take(120)}"
                )
            } catch (error: Exception) {
                AppLog.e(TAG, "Translation failed", error)
                LiveSubState.reportError(
                    "Çeviri hatası: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    error.stackTraceToString()
                )
            } finally {
                LiveSubState.update { it.copy(isTranslating = false) }
            }
        }
    }

    private fun calculateRms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return kotlin.math.sqrt(sum / samples.size.coerceAtLeast(1))
    }

    private fun audioRecordError(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord geçersiz işlem hatası (ERROR_INVALID_OPERATION, kod $code)."
        AudioRecord.ERROR_BAD_VALUE -> "AudioRecord geçersiz parametre hatası (ERROR_BAD_VALUE, kod $code)."
        AudioRecord.ERROR_DEAD_OBJECT -> "Ses aygıtı bağlantısı koptu (ERROR_DEAD_OBJECT, kod $code). Dinlemeyi yeniden başlatın."
        AudioRecord.ERROR -> "AudioRecord genel hata verdi (ERROR, kod $code)."
        else -> "AudioRecord okuma hatası (kod $code)."
    }

    private fun stopSession(status: String?) {
        if (!stopOnce.compareAndSet(false, true)) return
        sessionActive = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        stopProjection()
        if (status != null) {
            LiveSubState.update { it.copy(isRecording = false, status = status) }
        }
        if (captureThread == null) {
            unregisterScreenReceiver()
            releaseWakeLock()
            hideOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopProjection() {
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
    }

    // ---------------------------------------------------------------------
    // Yüzen altyazı yönetimi (Aşama 4)
    // ---------------------------------------------------------------------

    private fun showOverlayIfEnabled() {
        if (!OverlaySettings.load(this).enabled) return
        if (!Settings.canDrawOverlays(this)) {
            LiveSubState.update {
                it.copy(
                    overlayActive = false,
                    status = "Yüzen altyazı izni yok; altyazılar yalnızca uygulamada gösteriliyor."
                )
            }
            return
        }
        try {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        } catch (error: Exception) {
            AppLog.w(TAG, "Yüzen altyazı servisi başlatılamadı", error)
            LiveSubState.update {
                it.copy(
                    overlayActive = false,
                    status = "Yüzen altyazı başlatılamadı; altyazılar uygulamada gösteriliyor."
                )
            }
        }
    }

    private fun refreshOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        } catch (error: Exception) {
            AppLog.w(TAG, "Yüzen altyazı yenilenemedi", error)
        }
    }

    private fun hideOverlay() {
        try {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
        } catch (error: Exception) {
            AppLog.w(TAG, "Yüzen altyazı gizlenemedi", error)
        }
    }

    // ---------------------------------------------------------------------
    // Pil ve ekran durumu (Aşama 5)
    // ---------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveSub:capture").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
            AppLog.i(TAG, "Kısmi uyandırma kilidi alındı; ekran kapalıyken dinleme sürer.")
        } catch (error: Exception) {
            AppLog.w(TAG, "Uyandırma kilidi alınamadı", error)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (error: Exception) {
            AppLog.w(TAG, "Uyandırma kilidi bırakılamadı", error)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (error: Exception) {
            AppLog.w(TAG, "Ekran durumu alıcısı kaldırılamadı", error)
        }
    }

    // ---------------------------------------------------------------------
    // Bildirimler
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "LiveSub canlı dinleme", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ses yakalama ve altyazı servisinin çalışma durumu"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveSub canlı altyazı")
            .setContentText("Sistem sesi dinleniyor, yazıya ve istenen dile çevriliyor")
            .setSmallIcon(R.drawable.ic_livesub_notification)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        sessionActive = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        stopProjection()
        whisperThread?.interrupt()
        translationThread?.interrupt()
        unregisterScreenReceiver()
        releaseWakeLock()
        hideOverlay()
        LiveSubState.update { it.copy(isRecording = false) }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession("LiveSub kapatıldı; dinleme durduruldu.")
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val ACTION_START = "com.livesub.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.livesub.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_LANGUAGE = "language"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SEGMENT_SECONDS = 3  // 5 -> 3 sn: hedef ~5 sn toplam gecikme
        private const val PREROLL_MS = 250L
        private const val CHANNEL_ID = "livesub_capture"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WAKE_LOCK_MS = 8L * 60 * 60 * 1000
        private const val TAG = "LiveSubCapture"
    }
}
