package com.livesub.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat'e ek olarak uygulama dosyasına da yazan basit günlükleyici.
 *
 * Bazı üretici ROM'ları (ör. Huawei/HarmonyOS) üçüncü taraf uygulama etiketlerini logcat'te
 * göstermez. Bu sınıf aynı satırları `files/logs/livesub.log` dosyasına da yazar; böylece
 * `adb shell run-as com.livesub.app cat files/logs/livesub.log` ile saha testlerinde okunabilir.
 */
object AppLog {
    private const val MAX_BYTES = 1_500_000L
    private const val BACKUP_SUFFIX = ".1"

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val folder = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(folder, "livesub.log")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        append("W", tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        append("E", tag, message, error)
    }

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) {
                    val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                val line = buildString {
                    append(timeFormat.format(Date()))
                    append(' ').append(level).append('/').append(tag).append(": ").append(message)
                    if (error != null) {
                        append('\n')
                        append(error.stackTraceToString())
                    }
                    append('\n')
                }
                file.appendText(line)
            } catch (_: Exception) {
                // Günlük yazılamazsa uygulama akışını bozmamak için sessizce yut.
            }
        }
    }
}
