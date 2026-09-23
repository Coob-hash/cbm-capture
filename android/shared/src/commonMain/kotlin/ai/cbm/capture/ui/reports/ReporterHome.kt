package ai.cbm.capture.ui.reports

import ai.cbm.capture.ui.design.AlertKind
import ai.cbm.capture.ui.design.CbmEmpty
import ai.cbm.capture.ui.design.CbmInlineAlert
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPanel
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.design.CbmTopBar
import ai.cbm.capture.ui.design.LedDot
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One line of the reporter's list: a report the server knows, or a photo still on the phone. */
data class HomeItem(
    val key: String,
    val title: String,
    val status: String,
    val detail: String? = null,
    /** The workflows asked for another photo of this report. */
    val photoNeededFor: String? = null,
    /** A photo the server refused; the reporter may retry or discard it. */
    val rejectedCapture: String? = null,
    val done: Boolean = false
)

data class ReporterHomeState(
    val siteName: String = "",
    val siteCode: String = "",
    val email: String = "",
    val expiresAtMillis: Long? = null,
    val items: List<HomeItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** The reporter's very small dashboard: their reports, and one button to open a new one. */
@Composable
fun ReporterHomeScreen(
    state: ReporterHomeState,
    onOpenReport: () -> Unit,
    onTakeAnotherPhoto: (reportId: String) -> Unit,
    onRetryUpload: (captureId: String) -> Unit,
    onDiscardUpload: (captureId: String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(
            title = "My reports",
            siteCode = state.siteCode.ifEmpty { state.siteName },
            sessionExpiresAt = state.expiresAtMillis,
            unread = 0,
            onProfile = onSettings
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
                if (state.items.isEmpty() && !state.loading) {
                    item {
                        CbmEmpty(
                            "No reports yet",
                            "When something is broken, tap Open a report and photograph it. " +
                                "You are told when it is scheduled and when it is fixed."
                        )
                    }
                }
                items(state.items, key = { it.key }) { item ->
                    ReportRow(item, onTakeAnotherPhoto, onRetryUpload, onDiscardUpload)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CbmPrimaryButton("Open a report", onOpenReport, Modifier.fillMaxWidth(), tall = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmOutlineButton(
                    text = if (state.loading) "Refreshing…" else "Refresh",
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading
                )
                CbmOutlineButton("Log out", onLogout, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReportRow(
    item: HomeItem,
    onTakeAnotherPhoto: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onDiscardUpload: (String) -> Unit
) {
    val action = item.photoNeededFor
    val rejected = item.rejectedCapture
    val rail = when {
        action != null -> CbmPalette.Amber
        rejected != null -> CbmPalette.Red
        item.done -> CbmPalette.Green
        else -> LocalAccent.current.color
    }
    CbmPanel(
        modifier = Modifier.fillMaxWidth().let { if (action != null) it.clickable { onTakeAnotherPhoto(action) } else it },
        rail = rail
    ) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LedDot(rail, blink = action != null)
            Spacer(Modifier.width(8.dp))
            Text(item.status.uppercase(), style = LocalTechStyles.current.stencil, color = rail)
        }
        item.detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            CbmInlineAlert(
                AlertKind.WARN,
                "The photo could not be located automatically. Tap this card to take another one.",
                title = "Photo needed"
            )
        }
        if (rejected != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmOutlineButton("Try again", { onRetryUpload(rejected) }, Modifier.weight(1f))
                CbmOutlineButton("Discard", { onDiscardUpload(rejected) }, Modifier.weight(1f), color = CbmPalette.Red)
            }
        }
    }
}
