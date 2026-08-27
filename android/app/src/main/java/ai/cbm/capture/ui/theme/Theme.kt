package ai.cbm.capture.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    val context = LocalContext.current
    // Material You where the platform offers it. The capture screen is mostly camera feed, so
    // the palette only has to keep the overlay chrome legible against arbitrary imagery.
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
