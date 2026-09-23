package ai.cbm.capture.ui.design

import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The review target (screenshots) has no network: it draws the placeholder a phone shows before an
 * image arrives, so a rendered screen has the real layout without inventing content.
 */
@Composable
actual fun CapturePhoto(url: String?, contentDescription: String?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("PHOTO", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
    }
}
