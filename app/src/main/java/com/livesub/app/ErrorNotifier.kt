package com.livesub.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Uygulama görünür değilken oluşan hataları sistem bildirimi olarak iletir.
 * Böylece ekran kapalıyken veya video oynatıcı öndeyken sessiz hata oluşmaz.
 */
object ErrorNotifier {
    private const val CHANNEL_ID = "livesub_errors"
    private const val NOTIFICATION_ID = 2001
    private const val THROTTLE_MS = 30_000L
    private const val TAG = "LiveSubError"

    private var appContext: Context? = null
    private var appVisible = true
    private var lastMessage: String? = null
    private var lastNotifiedAt = 0L

    fun init(context: Context) {
        val application = context.applicationContext
        appContext = application
        createChannel(application)
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun notifyError(message: String, detail: String? = null) {
        val context = appContext ?: return
        if (appVisible) return

        val now = System.currentTimeMillis()
        if (message == lastMessage && now - lastNotifiedAt < THROTTLE_MS) return
        lastMessage = message
        lastNotifiedAt = now

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(TAG, "Hata bildirimi gönderilemedi: POST_NOTIFICATIONS izni yok.")
            return
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (detail.isNullOrBlank()) message else "$message\n$detail"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_livesub_notification)
            .setContentTitle("LiveSub hatası")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            AppLog.w(TAG, "Hata bildirimi gösterilemedi", error)
        }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiveSub hataları",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Dinleme veya model işlemleri sırasında oluşan hatalar"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
