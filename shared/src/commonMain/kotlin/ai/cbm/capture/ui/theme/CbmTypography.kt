package ai.cbm.capture.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Grotesque system sans for prose; monospace for every technical value
 *  (ticket ids, codes, timestamps, KPI figures) like a drawing title block. */
val CbmFont: FontFamily = FontFamily.Default
val CbmMono: FontFamily = FontFamily.Monospace

val CbmTypography = Typography(
    headlineMedium = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 1.1.sp),
)

/** Technical styles: figures, codes and stencil micro-labels. */
class CbmTechStyles(
    val kpi: TextStyle = TextStyle(fontFamily = CbmMono, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
    val ticketId: TextStyle = TextStyle(fontFamily = CbmMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    val meta: TextStyle = TextStyle(fontFamily = CbmMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val metaStrong: TextStyle = TextStyle(fontFamily = CbmMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    val stencil: TextStyle = TextStyle(fontFamily = CbmFont, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.6.sp),
)

val LocalTechStyles = staticCompositionLocalOf { CbmTechStyles() }
