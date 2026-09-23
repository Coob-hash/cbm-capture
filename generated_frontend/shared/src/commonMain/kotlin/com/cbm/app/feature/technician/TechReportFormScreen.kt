package com.cbm.app.feature.technician

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.data.OutboxItem
import com.cbm.app.data.OutboxKind
import com.cbm.app.design.*
import com.cbm.app.domain.Outcome
import com.cbm.app.domain.TechReport
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.launch

@Composable
fun TechReportFormScreen(ticketId: Int, onDone: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf<TechReport?>(null) }
    var afterPhoto by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ticketId) { form = app.api.reportPrefill(session.token, ticketId) }
    val f = form

    Scaffold(
        topBar = { CbmTopBar("Work report · #$ticketId", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBack = onDone) },
        bottomBar = {
            if (f != null) Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
                Row(Modifier.navigationBarsPadding().padding(12.dp)) {
                    CbmOutlineButton("Save draft", {
                        app.outbox.enqueue(OutboxItem("draft-${f.ticketId}", session.email, OutboxKind.TECH_REPORT, "Draft · report #${f.ticketId}", techReport = f))
                        info = "Draft saved on this device — it survives restarts (T-6)."
                    }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    val valid = f.declaration && f.outcome != null && f.workDate.isNotBlank() && f.findings.isNotBlank() && f.workPerformed.isNotBlank()
                    CbmPrimaryButton("Submit", { confirmSubmit = true }, Modifier.weight(1f), enabled = valid)
                }
            }
        },
    ) { padding ->
        if (f == null) {
            CbmLoading("Loading template")
        } else Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbmPanel(header = "Prefilled · locked") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbmLockedField("Ticket", "#${f.ticketId}", Modifier.weight(1f))
                    CbmLockedField("Asset", f.asset, Modifier.weight(2f))
                }
                Spacer(Modifier.height(8.dp))
                CbmLockedField("Location", f.location, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbmLockedField("Technician", f.technicianName, Modifier.weight(1f))
                    CbmLockedField("Email", f.technicianEmail, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                CbmLockedField("Reported issue", f.reportedIssue, Modifier.fillMaxWidth())
            }
            CbmField("Work date", f.workDate, { form = f.copy(workDate = it) }, placeholder = "2026-09-22", mono = true)
            CbmTextArea("Findings", f.findings, { form = f.copy(findings = it) }, maxChars = 500)
            CbmTextArea("Work performed", f.workPerformed, { form = f.copy(workPerformed = it) }, maxChars = 500)
            CbmField("Materials", f.materials, { form = f.copy(materials = it) }, placeholder = "e.g. 1× handle set, 8 mm spindle")
            CbmField("Checks", f.checks, { form = f.copy(checks = it) }, placeholder = "e.g. 20 open/close cycles")
            CbmField("Check result", f.checkResult, { form = f.copy(checkResult = it) })
            Column {
                FieldLabel("Outcome")
                Outcome.entries.forEach { o ->
                    CbmRadioRow(o.label, f.outcome == o, { form = f.copy(outcome = o) }, sub = if (o == Outcome.COMPLETED) "A declaration — the FM still approves closure (T-8)." else null)
                    Spacer(Modifier.height(6.dp))
                }
            }
            CbmTextArea("Remaining issues", f.remainingIssues, { form = f.copy(remainingIssues = it) }, maxChars = 500, placeholder = "Optional")
            Column {
                FieldLabel("After photo")
                if (afterPhoto) {
                    Box(Modifier.fillMaxWidth().height(120.dp).background(CbmPalette.Concrete200).border(1.dp, CbmPalette.Steel200), contentAlignment = Alignment.Center) {
                        Text("AFTER PHOTO · CAPTURED", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
                    }
                    Spacer(Modifier.height(8.dp))
                    CbmField("Caption", f.afterPhotoCaption, { form = f.copy(afterPhotoCaption = it) })
                } else CbmOutlineButton("Take AFTER photo", { afterPhoto = true }, Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.declaration, { form = f.copy(declaration = it) }, colors = CheckboxDefaults.colors(checkedColor = LocalAccent.current.color))
                Text("I declare the information above is correct and the work is as described.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(72.dp))
        }
    }

    if (confirmSubmit && f != null) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("Submit report for #${f.ticketId}?") },
            text = { Text("The server renders the PDF and WF2 reviews it. After submission the job shows “Awaiting FM approval”.") },
            confirmButton = {
                CbmSmallAction("Submit", {
                    confirmSubmit = false
                    app.outbox.enqueue(OutboxItem("tr-${f.ticketId}", session.email, OutboxKind.TECH_REPORT, "Report #${f.ticketId}", techReport = f))
                    scope.launch { app.api.submitTechReport(session.token, f); app.outbox.remove("tr-${f.ticketId}") }
                    onDone()
                }, color = CbmPalette.Green)
            },
            dismissButton = { CbmSmallAction("Cancel", { confirmSubmit = false }, filled = false) },
        )
    }
    info?.let {
        AlertDialog(onDismissRequest = { info = null }, confirmButton = { CbmSmallAction("OK", { info = null }) }, text = { Text(it) })
    }
}
