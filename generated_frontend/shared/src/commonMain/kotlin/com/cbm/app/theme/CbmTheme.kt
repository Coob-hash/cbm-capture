package com.cbm.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbm.app.domain.Role

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

fun accentFor(role: Role): RoleAccent = when (role) {
    Role.REPORTER -> RoleAccent.REPORTER
    Role.TECHNICIAN -> RoleAccent.TECHNICIAN
    Role.FM -> RoleAccent.FM
}

@Composable
fun CbmTheme(role: Role? = null, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDimens provides CbmDimens(),
        LocalTechStyles provides CbmTechStyles(),
        LocalAccent provides (role?.let(::accentFor) ?: RoleAccent.REPORTER),
    ) {
        MaterialTheme(
            colorScheme = if (dark) CbmDarkColors else CbmLightColors,
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
