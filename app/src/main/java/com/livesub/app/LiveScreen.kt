package com.livesub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livesub.app.StatusChip
import com.livesub.app.StatusChipState
import com.livesub.app.ModelProgressBar
import com.livesub.app.SectionCard
import com.livesub.app.SettingSwitchRow
import com.livesub.app.InfoLine
import com.livesub.app.EmptyStateCard
import com.livesub.app.CaptionCard
import com.livesub.app.CARD_CORNER_RADIUS
import com.livesub.app.CONTENT_PADDING
import com.livesub.app.CenteredContent
import com.livesub.app.LiveSubAmber

// "Durdur" simgesi. material-icons-extended bağımlılığı APK'ya ~40 MB dex eklediği
// için yalnızca kullanılan simgeler tanımlanır; bu kare vektör Stop yerine geçer.
private val StopIcon: ImageVector = ImageVector.Builder(
    name = "Stop",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 6f)
        lineTo(18f, 6f)
        lineTo(18f, 18f)
        lineTo(6f, 18f)
        close()
    }
}.build()

@Composable
fun LiveScreen(
    ui: LiveSubUiState,
    overlayPreferences: OverlayPreferences,
    overlayPermissionGranted: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onPreviewOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onRetryModels: () -> Unit
) {
    val canStart = ui.modelReady &&
        (!ui.isTranslationEnabled || ui.translationModelReady) &&
        !ui.isRecording
    val waitingForModel = !ui.modelReady || (ui.isTranslationEnabled && !ui.translationModelReady)
    val modelProgress = when {
        !ui.modelReady -> ui.modelProgress
        ui.isTranslationEnabled && !ui.translationModelReady -> ui.translationModelProgress
        else -> 1f
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), // alt boşluk butonlar için
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tablet geniş ekranda içeriği ortala ve max genişlik uygula - Header
        item {
            CenteredContent {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("LiveSub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Yerel altyazı · Whisper + Hy-MT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onOpenSettings) { Text("Ayarlar") }
                }
            }
        }

        // Durum kartı
        item {
            CenteredContent {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(CARD_CORNER_RADIUS),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(CONTENT_PADDING),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Durum satırı + çip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Durum göstergesi (daire)
                            Box(
                                modifier = Modifier.size(12.dp)
                                    .background(
                                        if (ui.isRecording) LiveSubAmber else MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                            )
                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (ui.isRecording) "CANLI · sistem sesi dinleniyor" else "Hazır",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (ui.isRecording) LiveSubAmber else MaterialTheme.colorScheme.primary
                                )
                                Text(ui.status, style = MaterialTheme.typography.bodyMedium)
                            }
                            // Durum çipleri
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(
                                    if (ui.modelReady) "Whisper ✓" else "Whisper…",
                                    if (ui.modelReady) StatusChipState.Active else StatusChipState.Warning
                                )
                                StatusChip(
                                    when {
                                        !ui.isTranslationEnabled -> "Çeviri kapalı"
                                        ui.translationModelReady -> "Hy-MT ✓"
                                        else -> "Hy-MT…"
                                    },
                                    when {
                                        !ui.isTranslationEnabled -> StatusChipState.Inactive
                                        ui.translationModelReady -> StatusChipState.Active
                                        else -> StatusChipState.Warning
                                    }
                                )
                                StatusChip(
                                    if (ui.overlayActive) "Kutu aktif" else "Kutu kapalı",
                                    if (ui.overlayActive) StatusChipState.Active else StatusChipState.Inactive
                                )
                            }
                        }

                        // Model indirme ilerlemesi
                        if (waitingForModel) {
                            ModelProgressBar(modelProgress, "Model indiriliyor/doğrulanıyor")
                        }
                    }
                }
            }
        }

        // Hata kartı
        ui.error?.let { message ->
            item {
                CenteredContent {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(CARD_CORNER_RADIUS),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.padding(CONTENT_PADDING),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Sorun bildirildi",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                androidx.compose.material3.TextButton(onClick = onDismissError) { Text("Kapat") }
                            }
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            ui.errorDetail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            androidx.compose.material3.TextButton(onClick = onRetryModels) { Text("Modelleri yeniden dene") }
                        }
                    }
                }
            }
        }

        // Yüzen altyazı kontrol kartı
        item {
            CenteredContent {
                SectionCard(title = "Yüzen altyazı", subtitle = "Video oynatıcının üzerinde yarı saydam, sürüklenebilir kutu") {
                    SettingSwitchRow(
                        label = "Diğer uygulamaların üstünde göster",
                        description = "Dinleme başladığında kutu otomatik açılır",
                        checked = overlayPreferences.enabled,
                        onCheckedChange = onToggleOverlay
                    )
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            overlayPreferences.enabled && !overlayPermissionGranted -> {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "İzin verilmedi. Kutu görünmez; altyazılar yalnızca bu ekranda gösterilir.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LiveSubAmber
                                    )
                                    androidx.compose.material3.Button(onClick = onRequestOverlayPermission) { Text("İzin ver") }
                                }
                            }
                            overlayPreferences.enabled -> {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (ui.overlayActive) "Kutu şu anda ekranda" else "Dinleme başlayınca görünür",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    androidx.compose.material3.TextButton(onClick = onPreviewOverlay) { Text("Önizle") }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Performans kartı
        item {
            CenteredContent {
                SectionCard(title = "Performans ve kaynak kullanımı") {
                    InfoLine("Whisper gecikmesi", "${ui.latencyMillis} ms")
                    InfoLine(
                        "Hy-MT çeviri",
                        if (ui.isTranslationEnabled) "${ui.translationLatencyMillis} ms" else "Kapalı"
                    )
                    InfoLine("Süreç CPU", "%.1f%%".format(ui.processCpuPercent))
                    InfoLine("Toplam PSS", "${ui.memoryMb} MiB")
                    InfoLine("Bekleyen ses parçası", "${ui.queuedSegments}")
                    InfoLine("Örnekleme", "16 kHz mono PCM")
                }
            }
        }

        // Döküm başlığı
        item {
            CenteredContent {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Konuşma ve çeviri dökümü", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${ui.captions.size} satır",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Döküm listesi veya boş durum
        if (ui.captions.isEmpty()) {
            item {
                CenteredContent {
                    EmptyStateCard(
                        title = "Henüz altyazı yok",
                        message = "Video oynatılırken 'Canlı dinle'ye basın.\nAlgılanan konuşma ve çevirisi burada listelenir;\nkutu açıksa oynatıcının üzerinde de görünür."
                    )
                }
            }
        } else {
            items(ui.captions, key = { it.id }) { line ->
                CenteredContent {
                    CaptionCard(line, translationEnabled = ui.isTranslationEnabled)
                }
            }
        }
    }

    // BİRİNCİL BÜYÜK DÜĖME: Canlı dinle / Durdur (ekran altı, fixed)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        CenteredContent {
            Button(
                onClick = if (ui.isRecording) onStopCapture else onStartCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp) // 48dp+ dokunma hedefi
                    .padding(start = 16.dp, end = 16.dp),
                enabled = if (ui.isRecording) true else canStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ui.isRecording)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (ui.isRecording)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(CARD_CORNER_RADIUS),
                interactionSource = remember { MutableInteractionSource() }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ui.isRecording) StopIcon else Icons.Filled.PlayArrow,
                        contentDescription = if (ui.isRecording) "Durdur" else "Canlı dinle",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (ui.isRecording) "DURDUR" else "CANLI DINLE",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        fontSize = 20.sp
                    )
                }
            }
        }
    }
}