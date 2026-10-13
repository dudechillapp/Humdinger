package com.kowalski.humdinger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kowalski.humdinger.data.AppPrefs
import com.kowalski.humdinger.data.ThemeMode
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kowalski.humdinger.R

val Coral = Color(0xFFFF5A36)
val Sunny = Color(0xFFFFC93C)
val Grape = Color(0xFF7B5CFF)
val Cream = Color(0xFFFFF8EE)
val Ink = Color(0xFF2A1B3D)
val Mint = Color(0xFF2EC4B6)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD1),
    onPrimaryContainer = Color(0xFF3D0700),
    secondary = Sunny,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFFFEFC2),
    onSecondaryContainer = Ink,
    tertiary = Grape,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9E2FF),
    onTertiaryContainer = Color(0xFF22135C),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF5E9DA),
    onSurfaceVariant = Color(0xFF5C4B6B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A6B),
    onPrimary = Color(0xFF3D0700),
    primaryContainer = Color(0xFF7A2410),
    onPrimaryContainer = Color(0xFFFFDAD1),
    secondary = Sunny,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF5A4500),
    onSecondaryContainer = Color(0xFFFFEFC2),
    tertiary = Color(0xFFB9A8FF),
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF3D2F7A),
    onTertiaryContainer = Color(0xFFE9E2FF),
    background = Color(0xFF1A1224),
    onBackground = Color(0xFFF3EAF9),
    surface = Color(0xFF1A1224),
    onSurface = Color(0xFFF3EAF9),
    surfaceVariant = Color(0xFF2E2340),
    onSurfaceVariant = Color(0xFFCFC0DD),
)

/** Fredoka: the rounded, friendly brand font (SIL Open Font License), used for headings. */
val Fredoka = FontFamily(
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

private val base = Typography()

private fun TextStyle.brand(weight: FontWeight = FontWeight.SemiBold) =
    copy(fontFamily = Fredoka, fontWeight = weight)

private val HumTypography = Typography(
    displayLarge = base.displayLarge.brand(),
    displayMedium = base.displayMedium.brand(),
    displaySmall = base.displaySmall.brand(),
    headlineLarge = base.headlineLarge.brand(),
    headlineMedium = base.headlineMedium.brand(),
    headlineSmall = base.headlineSmall.brand(),
    titleLarge = base.titleLarge.brand(),
    titleMedium = base.titleMedium.brand(FontWeight.Medium),
    titleSmall = base.titleSmall.brand(FontWeight.Medium),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
)

/** True between 8pm and 8am (phone's time). Checked every minute, so it switches over by itself. */
@Composable
private fun isEvening(): Boolean {
    fun now() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        .let { it >= ThemeMode.DARK_FROM || it < ThemeMode.DARK_UNTIL }
    val evening by produceState(initialValue = now()) {
        while (true) {
            value = now()
            delay(60_000)
        }
    }
    return evening
}

@Composable
fun HumdingerTheme(content: @Composable () -> Unit) {
    val mode by AppPrefs.theme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val evening = isEvening()
    val dark = when (mode) {
        ThemeMode.SCHEDULE -> evening
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Status bar and navigation bar icons: dark on light colours, light on dark colours.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = HumTypography,
        content = content,
    )
}
