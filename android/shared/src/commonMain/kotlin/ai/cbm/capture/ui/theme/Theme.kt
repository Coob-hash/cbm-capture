package ai.cbm.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Squared, instrument-like geometry: small radii, hairline borders, no blobs. */
class CbmDimens(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s8: Dp = 32.dp,
    val rail: Dp = 4.dp,
    val cardRadius: Dp = 4.dp,
    val buttonRadius: Dp = 3.dp,
    val buttonHeight: Dp = 48.dp,
)

val LocalDimens = staticCompositionLocalOf { CbmDimens() }
val LocalAccent = staticCompositionLocalOf { RoleAccent.REPORTER }

/** The accent of the role this session is bound to, from the API's wire values. */
fun accentFor(role: String?): RoleAccent = when (role) {
    "TECHNICIAN" -> RoleAccent.TECHNICIAN
    "FM", "ADMIN" -> RoleAccent.FM
    else -> RoleAccent.REPORTER
}

/**
 * The app's theme. One deliberate palette, so a screen looks the same on every phone: no Material
 * You dynamic colour. The role moves the accent only, never the layout.
 */
@Composable
fun CbmCaptureTheme(
    role: String? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalDimens provides CbmDimens(),
        LocalTechStyles provides CbmTechStyles(),
        LocalAccent provides accentFor(role),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) CbmDarkColors else CbmLightColors,
            typography = CbmTypography,
            shapes = Shapes(
                small = RoundedCornerShape(2.dp),
                medium = RoundedCornerShape(4.dp),
                large = RoundedCornerShape(6.dp),
            ),
            content = content,
        )
    }
}
