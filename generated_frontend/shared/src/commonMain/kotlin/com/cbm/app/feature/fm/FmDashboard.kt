package com.cbm.app.feature.fm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.design.*
import com.cbm.app.domain.*
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalTechStyles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FmDashboard(state: FmState, modifier: Modifier = Modifier, kpiOnly: Boolean = false) {
    var reasonTarget by remember { mutableStateOf<Pair<QueueItem, Decision>?>(null) }
    val board = state.board
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { state.refresh() }, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { KpiRow(board?.kpis, state.filter) { state.filter = it } }
            if (!kpiOnly) {
                item {
                    SectionHeader("Needs you", trailing = {
                        if (state.filter != null) Text("CLEAR", style = LocalTechStyles.current.stencil, color = CbmPalette.Red, modifier = Modifier.clickable { state.filter = null })
                    })
                }
                val queue = board?.queue?.filter { matchesFilter(state.filter, it) }.orEmpty()
                if (queue.isEmpty()) item { CbmEmpty("Nothing waiting", "No ticket needs an FM decision right now.") }
                items(queue, key = { it.ticketId }) { item -> QueueCard(item, state) { i, a -> reasonTarget = i to a } }
                board?.let { b ->
                    item { CbmPanel(header = "Open by status") { StatusBars(b.openByStatus.map { ticketStatusShort(it.first) to it.second }, b.openByStatus.map { ticketStatusColor(it.first) }) } }
                    item { CbmPanel(header = "Technician load") { WorkloadBars(b.workload) } }
                }
            }
        }
    }
    reasonTarget?.let { (item, action) ->
        var reason by remember(item.ticketId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reasonTarget = null },
            title = { Text("${action.label} #${item.ticketId}") },
            text = {
                Column {
                    Text("A reason is required. It is recorded with the decision and can be shared with the requester.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    CbmTextArea("Reason", reason, { reason = it }, maxChars = 500)
                }
            },
            confirmButton = { CbmSmallAction(action.label, { state.decide(item, action, reason); reasonTarget = null }, color = CbmPalette.Red) },
            dismissButton = { CbmSmallAction("Cancel", { reasonTarget = null }, filled = false) },
        )
    }
}

private fun matchesFilter(filter: FmFilter?, item: QueueItem) = when (filter) {
    null -> true
    FmFilter.AUTHORIZE -> item.status == TicketStatus.PENDING_AUTHORIZATION
    FmFilter.APPROVE -> item.status == TicketStatus.PENDING_APPROVAL
    FmFilter.ESCALATED -> item.status == TicketStatus.ESCALATED
    FmFilter.OVERDUE -> item.ageDays >= 30
}

@Composable
private fun KpiRow(kpis: FmKpis?, filter: FmFilter?, onSelect: (FmFilter?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CbmKpiTile(kpis?.toAuthorize?.toString() ?: "–", "Authorize", filter == FmFilter.AUTHORIZE, { onSelect(if (filter == FmFilter.AUTHORIZE) null else FmFilter.AUTHORIZE) }, color = CbmPalette.Amber)
        CbmKpiTile(kpis?.toApprove?.toString() ?: "–", "Approve", filter == FmFilter.APPROVE, { onSelect(if (filter == FmFilter.APPROVE) null else FmFilter.APPROVE) }, color = CbmPalette.Amber)
        CbmKpiTile(kpis?.escalated?.toString() ?: "–", "Escalated", filter == FmFilter.ESCALATED, { onSelect(if (filter == FmFilter.ESCALATED) null else FmFilter.ESCALATED) }, color = CbmPalette.Red)
        CbmKpiTile(kpis?.overdue30?.toString() ?: "–", ">30 days", filter == FmFilter.OVERDUE, { onSelect(if (filter == FmFilter.OVERDUE) null else FmFilter.OVERDUE) }, color = CbmPalette.Red)
        CbmKpiTile(kpis?.openTotal?.toString() ?: "–", "Open", filter == null, { onSelect(null) })
    }
}

@Composable
private fun QueueCard(item: QueueItem, state: FmState, onReason: (QueueItem, Decision) -> Unit) {
    val color = ticketStatusColor(item.status)
    CbmPanel(rail = color, modifier = Modifier.clickable { state.openDetail(item.ticketId) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${item.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(8.dp))
            SeverityChip(item.severity)
            Spacer(Modifier.weight(1f))
            Text(item.ageLabel.uppercase(), style = LocalTechStyles.current.metaStrong, color = CbmPalette.Steel400)
        }
        Spacer(Modifier.height(4.dp))
        Text(item.asset, style = MaterialTheme.typography.titleMedium)
        Text(item.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
        if (item.technician != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TECH ${item.technician}", style = LocalTechStyles.current.meta, color = CbmPalette.Steel500)
                if (item.techFirstJob) { Spacer(Modifier.width(8.dp)); FirstJobTag() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.summary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(CbmPalette.Concrete100).border(1.dp, CbmPalette.Steel200), contentAlignment = Alignment.Center) {
                Text("PHOTO", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row {
                    when (item.status) {
                        TicketStatus.PENDING_APPROVAL -> {
                            CbmSmallAction("Approve", { state.decide(item, Decision.APPROVE, null) }, color = CbmPalette.Green)
                            Spacer(Modifier.width(8.dp))
                            CbmSmallAction("Send back", { onReason(item, Decision.REWORK) }, color = CbmPalette.Red, filled = false)
                        }
                        else -> {
                            CbmSmallAction("Authorize", { state.decide(item, Decision.AUTHORIZE, null) })
                            Spacer(Modifier.width(8.dp))
                            CbmSmallAction("Reject", { onReason(item, Decision.REJECT) }, color = CbmPalette.Red, filled = false)
                        }
                    }
                }
                TextButton(onClick = { state.askAbout(item.ticketId) }) {
                    Text("Ask the agent →", style = MaterialTheme.typography.labelMedium, color = LocalAccent.current.color)
                }
            }
        }
    }
}
