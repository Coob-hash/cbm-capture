package com.cbm.app.feature.technician

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.design.*
import com.cbm.app.domain.JobStage
import com.cbm.app.domain.TechJob
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles

@Composable
fun TechDashboard(state: TechState, modifier: Modifier = Modifier) {
    val summary = state.summary
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("This month", "Last month", "90 days").forEach { p -> CbmChip(p, state.period == p) { state.setPeriod(p) } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CbmKpiTile(summary?.completed?.toString() ?: "–", "Completed", false, {}, Modifier.weight(1f), color = CbmPalette.Green)
                CbmKpiTile(summary?.awaitingFm?.toString() ?: "–", "Awaiting FM", false, {}, Modifier.weight(1f), color = CbmPalette.Amber)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CbmKpiTile(summary?.rework?.toString() ?: "–", "Rework", false, {}, Modifier.weight(1f), color = CbmPalette.HiVis)
                CbmKpiTile(summary?.avgDays?.toString() ?: "–", "Avg days", false, {}, Modifier.weight(1f))
            }
        }
        item { SectionHeader("Recent") }
        val recent = summary?.recent.orEmpty()
        if (recent.isEmpty()) item { CbmEmpty("No activity yet", "Accepted jobs and submitted reports appear here.") }
        items(recent, key = { it.ticketId }) { job -> RecentJobRow(job) }
    }
}

@Composable
private fun RecentJobRow(job: TechJob) {
    val (label, color) = when (job.stage) {
        JobStage.CLOSED -> "Closed" to CbmPalette.Green
        JobStage.AWAITING_FM -> "Awaiting FM" to CbmPalette.Amber
        JobStage.REWORK -> "Rework" to CbmPalette.HiVis
        JobStage.ASSIGNED -> "Assigned" to CbmPalette.Blueprint
    }
    CbmPanel(rail = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${job.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(job.asset, style = MaterialTheme.typography.bodyMedium)
                Text(job.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
            }
            StatusBadge(label, color)
        }
        Spacer(Modifier.height(4.dp))
        Text(job.updatedLabel, style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
    }
}
