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
fun ToReportScreen(state: TechState, onOpenReport: (Int) -> Unit, modifier: Modifier = Modifier) {
    val jobs = state.bundle?.toReport.orEmpty()
    val sent = state.bundle?.recent.orEmpty().filter { it.stage == JobStage.AWAITING_FM }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (jobs.isEmpty()) item { CbmEmpty("Nothing to report", "Accepted jobs waiting on a work report appear here.") }
        items(jobs, key = { it.ticketId }) { job -> ToReportCard(job, onOpenReport) }
        if (sent.isNotEmpty()) {
            item { SectionHeader("Sent — awaiting FM") }
            items(sent, key = { "sent-${it.ticketId}" }) { job ->
                CbmPanel(rail = CbmPalette.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${job.ticketId}", style = LocalTechStyles.current.ticketId)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(job.asset, style = MaterialTheme.typography.bodyMedium)
                            Text("Awaiting FM approval", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Amber)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToReportCard(job: TechJob, onOpenReport: (Int) -> Unit) {
    val rework = job.stage == JobStage.REWORK
    CbmPanel(rail = if (rework) CbmPalette.HiVis else CbmPalette.Blueprint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${job.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(8.dp))
            StatusBadge(if (rework) "Rework" else "Assigned", if (rework) CbmPalette.HiVis else CbmPalette.Blueprint, blink = rework)
            Spacer(Modifier.weight(1f))
            job.dueLabel?.let { Text(it.uppercase(), style = LocalTechStyles.current.metaStrong, color = CbmPalette.Steel400) }
        }
        Spacer(Modifier.height(6.dp))
        Text(job.asset, style = MaterialTheme.typography.titleMedium)
        Text(job.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
        if (rework && job.reworkReason != null) {
            Spacer(Modifier.height(8.dp))
            CbmInlineAlert(AlertKind.WARN, "FM: \"${job.reworkReason}\"", title = "Rework requested")
        }
        Spacer(Modifier.height(10.dp))
        CbmPrimaryButton("Fill report", { onOpenReport(job.ticketId) }, Modifier.fillMaxWidth())
    }
}
