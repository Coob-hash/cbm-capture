package ai.cbm.capture.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles

/**
 * Role-branded instrument header (PRD §4.2): ink bar, accent strip, role tag,
 * site code and the one-hour session countdown — a shared device can never be
 * mistaken for another role's session.
 */
@Composable
fun CbmTopBar(
    title: String,
    siteCode: String,
    sessionExpiresAt: Long?,
    unread: Int,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBell: () -> Unit = {},
    onProfile: () -> Unit = {},
) {
    val accent = LocalAccent.current
    val now = rememberNow()
    Column(modifier.fillMaxWidth().background(CbmPalette.Ink900)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            } else {
                Box(Modifier.padding(horizontal = 14.dp).size(10.dp).background(accent.color))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("SITE // $siteCode", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
            }
            if (sessionExpiresAt != null) {
                val left = sessionExpiresAt - now
                Text("SESSION ${formatCountdown(left)}", style = LocalTechStyles.current.metaStrong, color = if (left < 5 * 60_000) CbmPalette.Amber else CbmPalette.Steel300)
                Spacer(Modifier.width(6.dp))
            }
            Box {
                IconButton(onClick = onBell) { Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = Color.White) }
                if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(11.dp).size(7.dp).background(CbmPalette.HiVis))
            }
            IconButton(onClick = onProfile) { Icon(Icons.Default.Person, contentDescription = "Profile", tint = Color.White) }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(accent.color))
    }
}
