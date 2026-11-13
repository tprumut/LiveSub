package com.livesub.app

import android.content.Context

/**
 * Yüzen altyazı kutusunun görünüm ve davranış tercihleri.
 * Tüm değerler SharedPreferences içinde saklanır; servis ve arayüz aynı kaynağı okur.
 */
data class OverlayPreferences(
    val enabled: Boolean = true,
    val showOriginal: Boolean = true,
    val showTranslation: Boolean = true,
    val fontSizeSp: Float = 20f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.72f,
    val positionX: Int = 32,
    val positionY: Int = 220
)

/** Metin rengi için hazır seçenekler. */
data class OverlayColorPreset(val label: String, val argb: Int)

object OverlaySettings {
    private const val PREFS = "livesub_overlay_preferences"
    private const val ENABLED_KEY = "overlay_enabled"
    private const val SHOW_ORIGINAL_KEY = "show_original"
    private const val SHOW_TRANSLATION_KEY = "show_translation"
    private const val FONT_SIZE_KEY = "font_size_sp"
    private const val TEXT_COLOR_KEY = "text_color"
    private const val BACKGROUND_COLOR_KEY = "background_color"
    private const val BACKGROUND_OPACITY_KEY = "background_opacity"
    private const val POSITION_X_KEY = "position_x"
    private const val POSITION_Y_KEY = "position_y"

    val TEXT_COLOR_PRESETS = listOf(
        OverlayColorPreset("Buz beyazı", 0xFFFFFFFF.toInt()),
        OverlayColorPreset("Teal", 0xFF16A394.toInt()),
        OverlayColorPreset("Amber", 0xFFFFC857.toInt()),
        OverlayColorPreset("Sarı", 0xFFFFE066.toInt()),
        OverlayColorPreset("Açık gri", 0xFFD6E4EA.toInt()),
        OverlayColorPreset("Siyah", 0xFF000000.toInt())
    )

    fun load(context: Context): OverlayPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return OverlayPreferences(
            enabled = prefs.getBoolean(ENABLED_KEY, true),
            showOriginal = prefs.getBoolean(SHOW_ORIGINAL_KEY, true),
            showTranslation = prefs.getBoolean(SHOW_TRANSLATION_KEY, true),
            fontSizeSp = prefs.getFloat(FONT_SIZE_KEY, 20f).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
            textColorArgb = prefs.getInt(TEXT_COLOR_KEY, 0xFFFFFFFF.toInt()),
            backgroundColorArgb = prefs.getInt(BACKGROUND_COLOR_KEY, 0xFF000000.toInt()),
            backgroundOpacity = prefs.getFloat(BACKGROUND_OPACITY_KEY, 0.72f).coerceIn(MIN_OPACITY, MAX_OPACITY),
            positionX = prefs.getInt(POSITION_X_KEY, 32),
            positionY = prefs.getInt(POSITION_Y_KEY, 220)
        )
    }

    fun save(context: Context, preferences: OverlayPreferences) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED_KEY, preferences.enabled)
            .putBoolean(SHOW_ORIGINAL_KEY, preferences.showOriginal)
            .putBoolean(SHOW_TRANSLATION_KEY, preferences.showTranslation)
            .putFloat(FONT_SIZE_KEY, preferences.fontSizeSp)
            .putInt(TEXT_COLOR_KEY, preferences.textColorArgb)
            .putInt(BACKGROUND_COLOR_KEY, preferences.backgroundColorArgb)
            .putFloat(BACKGROUND_OPACITY_KEY, preferences.backgroundOpacity)
            .putInt(POSITION_X_KEY, preferences.positionX)
            .putInt(POSITION_Y_KEY, preferences.positionY)
            .apply()
    }

    /** Sürükleme bittiğinde yalnızca konumu yazar; diğer tercihler korunur. */
    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(POSITION_X_KEY, x)
            .putInt(POSITION_Y_KEY, y)
            .apply()
    }

    fun resetPosition(context: Context) {
        val current = load(context)
        save(context, current.copy(positionX = 32, positionY = 220))
    }

    const val MIN_FONT_SIZE = 12f
    const val MAX_FONT_SIZE = 34f
    const val MIN_OPACITY = 0.2f
    const val MAX_OPACITY = 0.95f
}
