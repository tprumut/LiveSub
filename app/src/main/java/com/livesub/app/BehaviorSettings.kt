package com.livesub.app

import android.content.Context
import android.os.PowerManager

/**
 * Cihaz davranışı ve pil ile ilgili tercihler.
 */
object BehaviorSettings {
    private const val PREFS = "livesub_behavior_preferences"
    private const val STOP_ON_SCREEN_LOCK_KEY = "stop_on_screen_lock"

    /**
     * Varsayılan olarak ekran kilitlense bile dinleme sürer; böylece cihaz cebe
     * konduğunda ses kaybı olmaz. Kullanıcı isterse kilitte durdurmayı seçebilir.
     */
    fun stopOnScreenLock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(STOP_ON_SCREEN_LOCK_KEY, false)

    fun setStopOnScreenLock(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(STOP_ON_SCREEN_LOCK_KEY, enabled)
            .apply()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
