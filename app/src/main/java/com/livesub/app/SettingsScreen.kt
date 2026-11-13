package com.livesub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livesub.translation.TranslationEngine
import com.livesub.translation.TranslationModelRepository
import com.livesub.app.SectionCard
import com.livesub.app.SettingSwitchRow
import com.livesub.app.LanguageDropdown
import com.livesub.app.InfoLine
import com.livesub.app.CONTENT_PADDING
import com.livesub.app.CenteredContent

private val SOURCE_LANGUAGES = listOf(
    "auto" to "Otomatik algıla",
    "tr" to "Türkçe",
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "ru" to "Русский",
    "ja" to "日本語",
    "zh" to "中文",
    "ko" to "한국어",
    "ar" to "العربية",
    "pt" to "Português",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "cs" to "Čeština"
)

@Composable
fun SettingsScreen(
    ui: LiveSubUiState,
    overlayPreferences: OverlayPreferences,
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    stopOnScreenLock: Boolean,
    onBack: () -> Unit,
    onReloadModels: () -> Unit,
    onWhisperModelSelected: (String) -> Unit,
    onTranslationToggled: (Boolean) -> Unit,
    onSourceLanguageSelected: (String) -> Unit,
    onTargetLanguageSelected: (String) -> Unit,
    onOverlayChanged: (OverlayPreferences) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onPreviewOverlay: () -> Unit,
    onStopOnScreenLockChanged: (Boolean) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onTranslationModelSelected: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = (16.dp),
            vertical = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CenteredContent {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Ayarlar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Modeller, dil, yüzen altyazı ve pil davranışı",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onBack) { Text("Canlı ekran") }
                }
            }
        }

        // ------------------------------------------------------------------
        // Modeller
        // ------------------------------------------------------------------
        item {
            CenteredContent {
                SectionCard(
                    title = "Modeller",
                    subtitle = "Model ağırlıkları APK'ya gömülmez; ilk kullanımda indirilir ve cihazda kalır."
                ) {
                    Text(
                        "Whisper modeli",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    WhisperModelRepository.availableModels().forEach { spec ->
                        val selected = ui.selectedModel == spec.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .clickable(enabled = !ui.isRecording && !selected) {
                                    onWhisperModelSelected(spec.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (!ui.isRecording && !selected) onWhisperModelSelected(spec.id)
                                },
                                enabled = !ui.isRecording
                            )
                            Column(Modifier.weight(1f)) {
                                Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${spec.fileName} · ${spec.expectedBytes / (1024 * 1024)} MiB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Text(
                                    if (ui.modelReady) "Hazır" else "Yükleniyor",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (ui.isRecording) {
                        Text(
                            "Model değiştirmek için önce dinlemeyi durdurun.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LiveSubAmber
                        )
                    }
                    if (!ui.modelReady) {
                        LinearProgressIndicator(
                            progress = { ui.modelProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "İndirme/doğrulama: ${(ui.modelProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Hy-MT çeviri modeli",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    TranslationModelRepository.availableModels().forEach { spec ->
                        val selected = ui.selectedTranslationModel == spec.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .clickable(enabled = !ui.isRecording && !selected) {
                                    onTranslationModelSelected(spec.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (!ui.isRecording && !selected) onTranslationModelSelected(spec.id)
                                },
                                enabled = !ui.isRecording
                            )
                            Column(Modifier.weight(1f)) {
                                Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${spec.fileName} · ${spec.expectedBytes / (1024 * 1024)} MiB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Text(
                                    if (ui.translationModelReady) "Hazır" else "Yükleniyor",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (ui.isRecording) {
                        Text(
                            "Model değiştirmek için önce dinlemeyi durdurun.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LiveSubAmber
                        )
                    }
                    if (ui.isTranslationEnabled && !ui.translationModelReady) {
                        LinearProgressIndicator(
                            progress = { ui.translationModelProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "İndirme/doğrulama: ${(ui.translationModelProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onReloadModels, enabled = !ui.isRecording) {
                        Text("İndirmeyi sürdür / yeniden doğrula")
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Dil ve çeviri
        // ------------------------------------------------------------------
        item {
            CenteredContent {
                SectionCard(
                    title = "Dil ve çeviri",
                    subtitle = "Whisper kaynak dili otomatik algılar; Hy-MT seçilen hedefe çevirir."
                ) {
                    SettingSwitchRow(
                        label = "Yerel çeviri (Tencent Hy-MT)",
                        description = "Kapalıyken yalnızca orijinal konuşma metni gösterilir.",
                        checked = ui.isTranslationEnabled,
                        onCheckedChange = onTranslationToggled
                    )
                    Text("Kaynak dil (Whisper)", style = MaterialTheme.typography.bodySmall)
                    LanguageDropdown(
                        selected = ui.selectedLanguage,
                        options = SOURCE_LANGUAGES,
                        onSelected = onSourceLanguageSelected
                    )
                    if (ui.isTranslationEnabled) {
                        Text("Hedef dil (Hy-MT)", style = MaterialTheme.typography.bodySmall)
                        LanguageDropdown(
                            selected = ui.targetLanguage,
                            options = TranslationEngine.SUPPORTED_LANGUAGES.map { (code, _) ->
                                code to TranslationEngine.getLanguageDisplayName(code)
                            },
                            onSelected = onTargetLanguageSelected
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Yüzen altyazı
        // ------------------------------------------------------------------
        item {
            CenteredContent {
                SectionCard(
                    title = "Yüzen altyazı",
                    subtitle = "En fazla iki satır; yeni satır gelince eski satır yukarı kayarak silinir."
                ) {
                    SettingSwitchRow(
                        label = "Kutuyu göster",
                        checked = overlayPreferences.enabled,
                        onCheckedChange = { onOverlayChanged(overlayPreferences.copy(enabled = it)) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (overlayPermissionGranted) "Üstte gösterme izni verildi" else "Üstte gösterme izni yok",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overlayPermissionGranted) MaterialTheme.colorScheme.primary else LiveSubAmber
                        )
                        if (!overlayPermissionGranted) {
                            TextButton(onClick = onRequestOverlayPermission) { Text("İzin ver") }
                        }
                    }
                    SettingSwitchRow(
                        label = "Orijinal metni göster",
                        description = "Çeviriyle birlikte kaynak cümleyi de gösterir.",
                        checked = overlayPreferences.showOriginal,
                        onCheckedChange = { onOverlayChanged(overlayPreferences.copy(showOriginal = it)) }
                    )
                    SettingSwitchRow(
                        label = "Çeviriyi göster",
                        description = "Kapatılırsa yalnızca orijinal metin gösterilir.",
                        checked = overlayPreferences.showTranslation,
                        enabled = ui.isTranslationEnabled,
                        onCheckedChange = { onOverlayChanged(overlayPreferences.copy(showTranslation = it)) }
                    )

                    FontSizeSetting(overlayPreferences, onOverlayChanged)
                    TextColorSetting(overlayPreferences, onOverlayChanged)
                    BackgroundOpacitySetting(overlayPreferences, onOverlayChanged)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPreviewOverlay) { Text("Önizle") }
                        TextButton(
                            onClick = {
                                onOverlayChanged(overlayPreferences.copy(positionX = 32, positionY = 220))
                            }
                        ) { Text("Konumu sıfırla") }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Pil ve davranış
        // ------------------------------------------------------------------
        item {
            CenteredContent {
                SectionCard(
                    title = "Pil ve davranış",
                    subtitle = "Ekran kilitliyken ve uygulama arka plandayken kararlı çalışma."
                ) {
                    SettingSwitchRow(
                        label = "Ekran kilitlenince dinlemeyi durdur",
                        description = "Kapalıyken ekran kapansa da ses yakalama sürer.",
                        checked = stopOnScreenLock,
                        onCheckedChange = onStopOnScreenLockChanged
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Pil optimizasyonu", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (batteryOptimizationIgnored) "LiveSub muaf; arka planda kesilme riski düşük."
                                else "Sistem uygulamayı arka planda kısıtlayabilir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (batteryOptimizationIgnored) MaterialTheme.colorScheme.primary else LiveSubAmber
                            )
                        }
                        if (!batteryOptimizationIgnored) {
                            TextButton(onClick = onRequestBatteryExemption) { Text("Muaf tut") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Bildirim izni", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (notificationPermissionGranted) "Verildi; dinleme bildirimi görünür."
                                else "Verilmedi; arka plan bildirimleri görünmeyebilir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationPermissionGranted) MaterialTheme.colorScheme.primary else LiveSubAmber
                            )
                        }
                        if (!notificationPermissionGranted) {
                            TextButton(onClick = onRequestNotificationPermission) { Text("İzin ver") }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Hakkında
        // ------------------------------------------------------------------
        item {
            CenteredContent {
                SectionCard(title = "Hakkında") {
                    InfoLine("Sürüm", "0.4.0 · Aşama 4/5")
                    InfoLine("Hedef cihaz", "Huawei MatePad 11 DBY-W09")
                    InfoLine("Mimari", "arm64-v8a · minSdk 29")
                    InfoLine("Çalışma biçimi", "Tamamen cihaz üzerinde")
                    Text(
                        "Ağ yalnızca model ağırlıklarının ilk indirmesinde kullanılır. Ses, transkript ve çeviri cihazdan çıkmaz. Google Play Services, ML Kit, Firebase veya bulut çeviri kullanılmaz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Hata ayrıntıları logcat'te LiveSubCapture / LiveSubWhisper / LiveSubTranslation / LiveSubOverlay etiketleriyle izlenebilir.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FontSizeSetting(
    preferences: OverlayPreferences,
    onChanged: (OverlayPreferences) -> Unit
) {
    var value by remember(preferences.fontSizeSp) { mutableFloatStateOf(preferences.fontSizeSp) }
    Text("Yazı boyutu: ${value.toInt()} sp", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChanged(preferences.copy(fontSizeSp = value)) },
        valueRange = OverlaySettings.MIN_FONT_SIZE..OverlaySettings.MAX_FONT_SIZE,
        steps = 21
    )
    OverlaySample(
        preferences.copy(fontSizeSp = value),
        text = "Örnek altyazı · subtitle sample"
    )
}

@Composable
private fun TextColorSetting(
    preferences: OverlayPreferences,
    onChanged: (OverlayPreferences) -> Unit
) {
    Text("Yazı rengi", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OverlaySettings.TEXT_COLOR_PRESETS.forEach { preset ->
            val selected = preferences.textColorArgb == preset.argb
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(preset.argb), CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onChanged(preferences.copy(textColorArgb = preset.argb)) }
            )
        }
    }
    OverlaySample(preferences, text = "Renk önizlemesi")
}

@Composable
private fun BackgroundOpacitySetting(
    preferences: OverlayPreferences,
    onChanged: (OverlayPreferences) -> Unit
) {
    var value by remember(preferences.backgroundOpacity) { mutableFloatStateOf(preferences.backgroundOpacity) }
    Text("Kutu arka planı: %${(value * 100).toInt()} opak", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChanged(preferences.copy(backgroundOpacity = value)) },
        valueRange = OverlaySettings.MIN_OPACITY..OverlaySettings.MAX_OPACITY
    )
    OverlaySample(preferences.copy(backgroundOpacity = value), text = "Arka plan önizlemesi")
}

/** Ayarlar ekranında gerçek kutu stilini gösteren küçük önizleme. */
@Composable
private fun OverlaySample(preferences: OverlayPreferences, text: String) {
    val background = Color(
        red = Color(preferences.backgroundColorArgb).red,
        green = Color(preferences.backgroundColorArgb).green,
        blue = Color(preferences.backgroundColorArgb).blue,
        alpha = preferences.backgroundOpacity.coerceIn(0f, 1f)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = Color(preferences.textColorArgb),
            fontSize = preferences.fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}