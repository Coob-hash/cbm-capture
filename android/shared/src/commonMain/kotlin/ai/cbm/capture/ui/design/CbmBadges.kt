package ai.cbm.capture.ui.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalTechStyles

/** Small square "panel LED" — used for live states and the first-job tag (F-4a). */
@Composable
fun LedDot(color: Color, modifier: Modifier = Modifier, blink: Boolean = false, size: Int = 8) {
    val a = if (blink) {
        val t = rememberInfiniteTransition(label = "led")
        val v by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse), label = "ledA")
        v
    } else 1f
    Box(modifier.size(size.dp).alpha(a).background(color))
}

@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier, blink: Boolean = false) {
    Row(modifier.border(1.dp, color).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LedDot(color, blink = blink, size = 6)
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = LocalTechStyles.current.stencil, color = color)
    }
}

@Composable
fun SeverityChip(severity: Int, modifier: Modifier = Modifier) {
    val color = when { severity >= 4 -> CbmPalette.Red; severity == 3 -> CbmPalette.Amber; else -> CbmPalette.Steel400 }
    Text("SEV $severity", style = LocalTechStyles.current.metaStrong, color = color, modifier = modifier.border(1.dp, color).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Blinking tag for technicians with no completed job yet (PRD F-4a). */
@Composable
fun FirstJobTag(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LedDot(CbmPalette.HiVis, blink = true, size = 6)
        Spacer(Modifier.width(4.dp))
        Text("FIRST JOB", style = LocalTechStyles.current.stencil, color = CbmPalette.HiVis)
    }
}

@Composable
fun AttemptsChip(left: Int, modifier: Modifier = Modifier) {
    Text("$left OF 3 LEFT", style = LocalTechStyles.current.stencil, color = CbmPalette.Amber, modifier = modifier.border(1.dp, CbmPalette.Amber).padding(horizontal = 7.dp, vertical = 3.dp))
}

/** The workflows' ticket statuses, as the API sends them. */
fun ticketStatusColor(status: String): Color = when (status) {
    "PENDING_AUTHORIZATION", "PENDING_APPROVAL" -> CbmPalette.Amber
    "LOCALIZED", "DISPATCHING", "ASSIGNED", "WORK_DONE" -> CbmPalette.Blueprint
    "REWORK" -> CbmPalette.HiVis
    "ESCALATED", "NEEDS_TRIAGE" -> CbmPalette.Red
    "CLOSED" -> CbmPalette.Green
    else -> CbmPalette.Steel400
}

fun ticketStatusShort(status: String): String = when (status) {
    "PENDING_AUTHORIZATION" -> "TO AUTHORIZE"
    "LOCALIZED" -> "TO ASSIGN"
    "DISPATCHING" -> "FINDING A TECHNICIAN"
    "ASSIGNED" -> "ASSIGNED"
    "WORK_DONE" -> "WORK DONE"
    "REWORK" -> "SENT BACK"
    "PENDING_APPROVAL" -> "TO APPROVE"
    "ESCALATED" -> "NEEDS ATTENTION"
    "CLOSED" -> "CLOSED"
    "REJECTED" -> "REJECTED"
    "DUPLICATE" -> "ALREADY REPORTED"
    else -> "IN HAND"
}

fun ticketStatusLabel(status: String): String = when (status) {
    "PENDING_AUTHORIZATION" -> "Waiting for your authorization"
    "LOCALIZED" -> "Ready to be assigned"
    "DISPATCHING" -> "Finding a technician"
    "ASSIGNED" -> "Assigned"
    "WORK_DONE" -> "Work done"
    "REWORK" -> "Sent back for rework"
    "PENDING_APPROVAL" -> "Waiting for your approval"
    "ESCALATED" -> "Escalated"
    "CLOSED" -> "Closed"
    "REJECTED" -> "Rejected"
    "DUPLICATE" -> "Already reported"
    else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
