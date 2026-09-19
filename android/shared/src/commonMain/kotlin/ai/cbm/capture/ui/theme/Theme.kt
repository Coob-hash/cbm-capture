package ai.cbm.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C5A8A),
    onPrimary = Color.White,
    secondary = Color(0xFF4A6572),
    tertiary = Color(0xFF9A6A00),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8EC),
    onPrimary = Color(0xFF10314F),
    secondary = Color(0xFFB6CAD6),
    tertiary = Color(0xFFF2C14E),
    error = Color(0xFFF2B8B5)
)

@Composable
fun CbmCaptureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Material You where the platform offers it. The capture screen is mostly camera feed, so
    // the palette only has to keep the overlay chrome legible against arbitrary imagery.
    val colorScheme = platformColorScheme(darkTheme) ?: if (darkTheme) DarkColors else LightColors

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** The platform's own palette (Android 12+ dynamic colour), or null to use the CBM palette. */
@Composable
internal expect fun platformColorScheme(darkTheme: Boolean): ColorScheme?
