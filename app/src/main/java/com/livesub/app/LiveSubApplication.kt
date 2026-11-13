package com.livesub.app

import android.app.Application
import android.content.ComponentCallbacks2

/**
 * Uygulama genelinde bellek baskısı yönetimi ve hata bildirimi altyapısını kurar.
 */
class LiveSubApplication : Application(), ComponentCallbacks2 {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        ErrorNotifier.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> LiveSubState.trimForMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LiveSubState.trimForMemory()
    }
}
