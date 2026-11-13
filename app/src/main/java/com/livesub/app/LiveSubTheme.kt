package com.livesub.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * LiveSub Material 3 Tema
 *
 * - Açık ve koyu tema, sistem ayarına uyar (isSystemInDarkTheme)
 * - Tek vurgu rengi: Teal (#16A394) - tutarlı birincil aksan
 * - Kayıt/uyarı durumu: Amber (#FFC857)
 * - Tipografiyi tek yerden (Typography) tanımla
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF16A394),      // Teal - tek vurgu rengi
    onPrimary = Color(0xFF04110F),
    primaryContainer = Color(0xFF004E47),
    onPrimaryContainer = Color(0xFFA8F8EF),
    secondary = Color(0xFF16A394),
    onSecondary = Color(0xFF04110F),
    tertiary = Color(0xFF8FD8D0),
    background = Color(0xFF0B1318),
    onBackground = Color(0xFFE8F2F4),
    surface = Color(0xFF132029),
    onSurface = Color(0xFFE8F2F4),
    surfaceVariant = Color(0xFF1B2C36),
    onSurfaceVariant = Color(0xFFA9C0C8),
    outline = Color(0xFF3A4E59),
    outlineVariant = Color(0xFF2A3E49),
    inverseSurface = Color(0xFFE8F2F4),
    inverseOnSurface = Color(0xFF0B1318),
    inversePrimary = Color(0xFF006A61),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006A61),      // Teal (darker for light theme)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F8EF),
    onPrimaryContainer = Color(0xFF00211D),
    secondary = Color(0xFF006A61),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF006A61),
    background = Color(0xFFF8FDFD),
    onBackground = Color(0xFF0B1318),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1318),
    surfaceVariant = Color(0xFFDAE5E3),
    onSurfaceVariant = Color(0xFF3A4E59),
    outline = Color(0xFF6E8280),
    outlineVariant = Color(0xFFBEC9C7),
    inverseSurface = Color(0xFF0B1318),
    inverseOnSurface = Color(0xFFE8F2F4),
    inversePrimary = Color(0xFF8FD8D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000)
)

/** Kayıt/uyarı durumu için sabit renk (tema bağımsız) */
val LiveSubAmber = Color(0xFFFFC857)
val LiveSubAmberDark = Color(0xFFFF8F00)

/** Uygulama tipografisi - tek merkezli tanım */
val LiveSubTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = -0.25.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Tema bileşeni - sistem koyu/açık tema ayarını okur.
 * MaterialTheme.colorScheme ve MaterialTheme.typography kullanılır.
 */
@Composable
fun LiveSubTheme(
    darkTheme: Boolean = false, // Varsayılan açık tema; sistem teması için çağıran yer isSystemInDarkTheme() kullanabilir
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LiveSubTypography,
        content = content
    )
}