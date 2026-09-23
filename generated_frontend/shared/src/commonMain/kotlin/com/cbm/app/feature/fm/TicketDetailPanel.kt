package com.cbm.app.feature.fm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.design.*
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles

@Composable
fun TicketDetailPanel(state: FmState, modifier: Modifier = Modifier) {
    val d = state.detail
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TICKET DETAIL", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.detail = null }) { Text("CLOSE", style = MaterialTheme.typography.labelLarge, color = CbmPalette.Red) }
        }
        HorizontalDivider(color = CbmPalette.Steel200)
        if (d == null) {
            CbmLoading("Loading ticket")
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${d.ticketId}", style = LocalTechStyles.current.kpi)
                Spacer(Modifier.width(12.dp))
                StatusBadge(d.status.label, ticketStatusColor(d.status))
            }
            Column {
                Text(d.asset, style = MaterialTheme.typography.headlineMedium)
                Text(d.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SeverityChip(d.severity) }
            CbmInlineAlert(AlertKind.INFO, d.issue, title = "Reported issue")
            SectionHeader("Photos")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoBox("BEFORE", d.beforePhotoId != null, Modifier.weight(1f))
                PhotoBox("AFTER", d.afterPhotoId != null, Modifier.weight(1f))
            }
            SectionHeader("Technician report")
            Text(d.techReport ?: "No report submitted yet.", style = MaterialTheme.typography.bodyMedium)
            SectionHeader("AI assessment")
            Text(d.aiAssessment ?: "—", style = MaterialTheme.typography.bodyMedium)
            SectionHeader("History")
            d.history.forEach { e ->
                Row {
                    Text(e.at, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400, modifier = Modifier.width(110.dp))
                    Column {
                        Text(e.actor, style = LocalTechStyles.current.metaStrong)
                        Text(e.text, style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel500)
                    }
                }
            }
            CbmOutlineButton("Ask the agent about #${d.ticketId}", { state.askAbout(d.ticketId); state.detail = null }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhotoBox(label: String, present: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.height(110.dp).background(if (present) CbmPalette.Concrete200 else CbmPalette.Concrete100).border(1.dp, CbmPalette.Steel200), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
            Text(if (present) "CAPTURED" else "—", style = LocalTechStyles.current.meta, color = CbmPalette.Steel300)
        }
    }
}
