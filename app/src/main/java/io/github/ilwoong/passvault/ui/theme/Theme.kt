package io.github.ilwoong.passvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 남색 계열의 고정 팔레트. 런처 아이콘과 같은 색이다.
// 기기 배경화면에서 색을 뽑는 동적 색상은 쓰지 않는다 — 금고 앱은 어느 기기에서나 같은 모습이어야 알아보기 쉽다.
// tertiary 는 호박색으로 두어 "변경 필요" 같은 경고에 쓴다 (오류는 error 의 빨강).

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F5EB2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF011B3C),
    inversePrimary = Color(0xFFB2C5FF),
    secondary = Color(0xFF565E78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2FF),
    onSecondaryContainer = Color(0xFF111B31),
    tertiary = Color(0xFF825515),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDBA),
    onTertiaryContainer = Color(0xFF281901),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFDEE2F3),
    onSurfaceVariant = Color(0xFF424654),
    surfaceTint = Color(0xFF0F5EB2),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFEFF0F8),
    outline = Color(0xFF737786),
    outlineVariant = Color(0xFFC2C6D7),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD8DAE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FB),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE6E8EF),
    surfaceContainerHighest = Color(0xFFE0E2EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF023061),
    primaryContainer = Color(0xFF034689),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF0F5EB2),
    secondary = Color(0xFFBEC6E3),
    onSecondary = Color(0xFF273047),
    secondaryContainer = Color(0xFF3E465F),
    onSecondaryContainer = Color(0xFFDAE2FF),
    tertiary = Color(0xFFF6BB79),
    onTertiary = Color(0xFF462A01),
    tertiaryContainer = Color(0xFF663E00),
    onTertiaryContainer = Color(0xFFFFDDBA),
    background = Color(0xFF121319),
    onBackground = Color(0xFFE0E2EA),
    surface = Color(0xFF121319),
    onSurface = Color(0xFFE0E2EA),
    surfaceVariant = Color(0xFF424654),
    onSurfaceVariant = Color(0xFFC2C6D7),
    surfaceTint = Color(0xFFB2C5FF),
    inverseSurface = Color(0xFFE0E2EA),
    inverseOnSurface = Color(0xFF2F3036),
    outline = Color(0xFF8C90A0),
    outlineVariant = Color(0xFF424654),
    surfaceBright = Color(0xFF37393F),
    surfaceDim = Color(0xFF121319),
    surfaceContainerLowest = Color(0xFF0C0E15),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A),
)

@Composable
fun PassVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
