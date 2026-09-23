package com.cbm.app.feature.reporter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.data.OutboxItem
import com.cbm.app.data.OutboxKind
import com.cbm.app.design.*
import com.cbm.app.theme.CbmPalette

@Composable
fun ReviewCaptureScreen(reportId: String?, x: Float, y: Float, onDone: () -> Unit, onRetake: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    var description by remember { mutableStateOf("") }
    Scaffold(
        topBar = { CbmTopBar(if (reportId == null) "Check the photo" else "Replacement photo", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBack = onRetake) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Box(Modifier.fillMaxWidth().height(280.dp).background(CbmPalette.Concrete200).border(1.dp, CbmPalette.Steel200)) {
                Canvas(Modifier.fillMaxSize()) {
                    val px = x * size.width; val py = y * size.height
                    drawCircle(CbmPalette.HiVis, radius = 30f, center = Offset(px, py), style = Stroke(width = 4f))
                    drawCircle(CbmPalette.HiVis, radius = 5f, center = Offset(px, py))
                    drawLine(CbmPalette.HiVis, Offset(px - 48f, py), Offset(px - 34f, py), 3f)
                    drawLine(CbmPalette.HiVis, Offset(px + 34f, py), Offset(px + 48f, py), 3f)
                    drawLine(CbmPalette.HiVis, Offset(px, py - 48f), Offset(px, py - 34f), 3f)
                    drawLine(CbmPalette.HiVis, Offset(px, py + 34f), Offset(px, py + 48f), 3f)
                }
                StatusBadge("PHOTO · MARKER SET", CbmPalette.Green, Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            Spacer(Modifier.height(14.dp))
            CbmTextArea("What is wrong? (optional)", description, { description = it }, maxChars = 500, placeholder = "e.g. Radiator leaking at the valve")
            Spacer(Modifier.height(18.dp))
            Row {
                CbmOutlineButton("Retake", onRetake, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                CbmPrimaryButton("Send", {
                    val id = reportId ?: "r-" + (100..999).random()
                    app.outbox.enqueue(
                        OutboxItem(
                            id = "ob-$id-${description.hashCode()}",
                            accountEmail = session.email,
                            kind = OutboxKind.CAPTURE,
                            label = description.ifBlank { "Photo report" },
                            reportId = id,
                            description = description.ifBlank { null },
                            replacementOf = reportId,
                        )
                    )
                    onDone()
                }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("Saved first, uploaded automatically — even from a basement (PRD 1.0 outbox).", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
        }
    }
}
