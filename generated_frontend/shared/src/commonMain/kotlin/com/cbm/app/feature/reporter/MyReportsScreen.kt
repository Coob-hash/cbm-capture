package com.cbm.app.feature.reporter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.data.OutboxKind
import com.cbm.app.design.*
import com.cbm.app.domain.ReportItem
import com.cbm.app.domain.ReporterStatus
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReportsScreen(onCapture: () -> Unit, onRetake: (String, Int) -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<ReportItem>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    fun load() { scope.launch { refreshing = true; reports = app.api.myReports(session.token); app.refreshNotices(); refreshing = false } }
    LaunchedEffect(Unit) { load() }
    // Deliver queued captures for THIS account only — never under another one (acceptance test 8).
    LaunchedEffect(session.email) {
        app.outbox.forAccount(session.email).filter { it.kind == OutboxKind.CAPTURE }.forEach { item ->
            app.api.submitCapture(session.token, item.reportId ?: item.id, item.description, item.replacementOf)
            app.outbox.remove(item.id)
        }
        load()
    }
    Scaffold(
        topBar = { CbmTopBar("My reports", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBell = onNotifications, onProfile = onProfile) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    CbmPrimaryButton("Open a report", onCapture, Modifier.fillMaxWidth(), tall = true)
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { load() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val queued = app.outbox.forAccount(session.email).filter { it.kind == OutboxKind.CAPTURE }
                if (queued.isNotEmpty()) item { QueuedCard(queued.size) }
                if (reports.isEmpty() && !refreshing) item { CbmEmpty("No reports yet", "Tap “Open a report” and photograph the problem. Fifteen seconds, done.") }
                items(reports, key = { it.id }) { item -> ReportCard(item, onRetake) }
            }
        }
    }
}

@Composable
private fun QueuedCard(count: Int) {
    CbmPanel(rail = CbmPalette.Steel300, header = "Outbox") {
        CbmInlineAlert(AlertKind.INFO, "$count report(s) saved — will upload automatically. Queued items belong to this account and are never sent under another one.")
    }
}

@Composable
private fun ReportCard(item: ReportItem, onRetake: (String, Int) -> Unit) {
    val (label, color) = reporterStatusLabel(item.status)
    CbmPanel(rail = color) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                if (item.location != null) Text(item.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
            }
            StatusBadge(label, color, blink = item.status == ReporterStatus.PHOTO_NEEDED)
        }
        if (item.status == ReporterStatus.PHOTO_NEEDED) {
            Spacer(Modifier.height(8.dp))
            CbmInlineAlert(AlertKind.WARN, item.note ?: "The photo could not be located automatically.", title = "Photo needed")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttemptsChip(item.attemptsLeft ?: 0)
                Spacer(Modifier.width(10.dp))
                CbmSmallAction("Take another photo", { onRetake(item.id, item.attemptsLeft ?: 0) }, color = CbmPalette.Amber)
            }
        }
        if (item.status == ReporterStatus.NOT_SCHEDULED && item.note != null) {
            Spacer(Modifier.height(8.dp))
            CbmInlineAlert(AlertKind.INFO, item.note)
        }
        Spacer(Modifier.height(8.dp))
        Text("UPDATED ${item.updatedLabel}", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
    }
}

/** R-4: the backend returns a code; the app only translates it into plain language. */
private fun reporterStatusLabel(s: ReporterStatus): Pair<String, Color> = when (s) {
    ReporterStatus.QUEUED -> "Waiting to send" to CbmPalette.Steel400
    ReporterStatus.UPLOADING -> "Sending" to CbmPalette.Steel400
    ReporterStatus.RECEIVED, ReporterStatus.ANALYSING -> "Received — being analysed" to CbmPalette.Blueprint
    ReporterStatus.PHOTO_NEEDED -> "Please take another photo" to CbmPalette.Amber
    ReporterStatus.OFFICE_NOTIFIED -> "The office has been told" to CbmPalette.Steel400
    ReporterStatus.AWAITING_FM -> "Waiting for the facility manager" to CbmPalette.Amber
    ReporterStatus.NOT_SCHEDULED -> "Not scheduled" to CbmPalette.Steel500
    ReporterStatus.IN_PROGRESS -> "Being fixed" to CbmPalette.HiVis
    ReporterStatus.FIXED -> "Fixed ✓" to CbmPalette.Green
    ReporterStatus.ALREADY_REPORTED -> "Already reported — being handled" to CbmPalette.Blueprint
}
