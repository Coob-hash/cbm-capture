package ai.cbm.capture.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The two things a screen needs from the platform: an image, and a page.
 *
 * Both are kept behind `expect` so the screens stay platform-free and an iOS target can supply its
 * own. The app provides [LocalPhotoUrl] and [LocalReportPage]; nothing here reads a session.
 */

/** Turns a capture id into the URL that serves it, or null when the app has no address yet. */
val LocalPhotoUrl = staticCompositionLocalOf<(String) -> String?> { { null } }

/** The reporter's photo behind a card. Draws a labelled placeholder while it loads or if it fails. */
@Composable
expect fun CapturePhoto(url: String?, contentDescription: String?, modifier: Modifier)
