package ai.cbm.capture.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val email: String = "",
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
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My reports", style = MaterialTheme.typography.headlineSmall)
                    Text(state.siteName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(if (state.loading) "…" else "Refresh") }
                TextButton(onClick = onSettings) { Text("Settings") }
                TextButton(onClick = onLogout) { Text("Log out") }
            }
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty() && !state.loading) {
                    Text(
                        "No reports yet. When something is broken, tap Open a report and photograph it.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.items, key = { it.key }) { item ->
                            ReportRow(item, onTakeAnotherPhoto, onRetryUpload, onDiscardUpload)
                        }
                    }
                }
            }
            Button(onClick = onOpenReport, modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp)) {
                Text("Open a report", style = MaterialTheme.typography.titleMedium)
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
    Card(
        modifier = Modifier.fillMaxWidth().let { if (action != null) it.clickable { onTakeAnotherPhoto(action) } else it },
        colors = if (action != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.detail != null) Text(item.detail, style = MaterialTheme.typography.bodySmall)
            if (action != null) Text("Tap to take another photo", style = MaterialTheme.typography.labelLarge)
            val rejected = item.rejectedCapture
            if (rejected != null) {
                Row {
                    TextButton(onClick = { onRetryUpload(rejected) }) { Text("Try again") }
                    TextButton(onClick = { onDiscardUpload(rejected) }) { Text("Discard") }
                }
            }
        }
    }
}
