package ai.cbm.capture.ui.design

import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage

/**
 * The image comes from the App API, so it needs the session's token: the application's own
 * Coil loader adds it (CbmCaptureApplication). Here there is only a URL.
 */
@Composable
actual fun CapturePhoto(url: String?, contentDescription: String?, modifier: Modifier) {
    if (url == null) {
        PhotoPlaceholder("NO PHOTO", modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { PhotoPlaceholder("PHOTO", Modifier.fillMaxSize()) },
        error = { PhotoPlaceholder("NO PHOTO", Modifier.fillMaxSize()) }
    )
}

@Composable
private fun PhotoPlaceholder(label: String, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(label, style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
    }
}
