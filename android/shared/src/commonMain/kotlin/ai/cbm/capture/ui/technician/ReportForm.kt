package ai.cbm.capture.ui.technician

import ai.cbm.capture.domain.WorkDate
import ai.cbm.capture.ui.design.AlertKind
import ai.cbm.capture.ui.design.CapturePhoto
import ai.cbm.capture.ui.design.CbmField
import ai.cbm.capture.ui.design.CbmInlineAlert
import ai.cbm.capture.ui.design.CbmLockedField
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPanel
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.design.CbmRadioRow
import ai.cbm.capture.ui.design.CbmTextArea
import ai.cbm.capture.ui.design.CbmTopBar
import ai.cbm.capture.ui.design.SectionHeader
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The work report as the technician fills it in. The locked half is shown, never edited. */
data class ReportFormState(
    val ticketId: Int = 0,
    val siteCode: String = "",
    val expiresAtMillis: Long? = null,
    // Read from the job, shown so the technician can see what they are reporting on.
    val asset: String = "",
    val location: String = "",
    val reportedIssue: String = "",
    val technician: String = "",
    val reworkReason: String? = null,
    // Theirs to write.
    val workDate: String = "",
    val findings: String = "",
    val workPerformed: String = "",
    val materials: String = "",
    val checks: String = "",
    val checkResult: String? = null,
    val outcome: String? = null,
    val remainingIssues: String = "",
    val declaration: Boolean = false,
    val photoUri: String? = null,
    val photoCaption: String = "",
    /** Why the photo could not be taken (no camera permission, no camera app), shown by the photo. */
    val photoNotice: String? = null,
    val sending: Boolean = false,
    val sent: Boolean = false,
    /** Why there is nothing to send: this round's report was sent already, or the job has gone. */
    val closedNotice: String? = null,
    val error: String? = null
) {
    val workDateValid: Boolean get() = WorkDate.isValid(workDate.trim())

    /** The same rules the server applies, so the button is only offered when the form is complete. */
    val complete: Boolean get() = workDateValid && findings.isNotBlank() &&
        workPerformed.trim().length >= 20 && checks.isNotBlank() && checkResult != null &&
        outcome != null && remainingIssues.isNotBlank() && declaration &&
        (photoUri == null || photoCaption.isNotBlank())
}

/** What the technician can change on the form. */
class ReportFormActions(
    val onWorkDate: (String) -> Unit,
    val onFindings: (String) -> Unit,
    val onWorkPerformed: (String) -> Unit,
    val onMaterials: (String) -> Unit,
    val onChecks: (String) -> Unit,
    val onCheckResult: (String) -> Unit,
    val onOutcome: (String) -> Unit,
    val onRemainingIssues: (String) -> Unit,
    val onDeclaration: (Boolean) -> Unit,
    val onTakePhoto: () -> Unit,
    val onRemovePhoto: () -> Unit,
    val onCaption: (String) -> Unit,
    val onSend: () -> Unit,
    val onBack: () -> Unit
)

private val CHECK_RESULTS = listOf(
    "PASSED" to "The checks passed",
    "FAILED" to "The checks did not pass",
    "NOT_PERFORMED" to "No check was possible"
)
private val OUTCOMES = listOf(
    "COMPLETED" to "I declare the work completed",
    "PARTIAL" to "Partly done",
    "NOT_COMPLETED" to "Not done"
)

/**
 * The work report, written in the app. Nothing is uploaded by hand and no file is ever shown: the
 * fields go to the office, which turns them into the same report document as always.
 */
@Composable
fun ReportFormScreen(state: ReportFormState, actions: ReportFormActions) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(
            title = "Work report · #${state.ticketId}",
            siteCode = state.siteCode,
            sessionExpiresAt = state.expiresAtMillis,
            unread = 0,
            onBack = actions.onBack
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.closedNotice?.let {
                item { CbmInlineAlert(AlertKind.INFO, it, title = "Nothing to send") }
            }
            state.reworkReason?.takeIf { state.closedNotice == null }?.let {
                item { CbmInlineAlert(AlertKind.WARN, "“$it”", title = "Sent back by the facility manager") }
            }
            item {
                CbmPanel(header = "The job") {
                    CbmLockedField("Ticket", "#${state.ticketId}", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    CbmLockedField("Asset", state.asset, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    CbmLockedField("Where", state.location, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    CbmLockedField("Reported", state.reportedIssue, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    CbmLockedField("Technician", state.technician, Modifier.fillMaxWidth())
                }
            }
            item { SectionHeader("What you did") }
            item {
                Column {
                    CbmField("Work date", state.workDate, actions.onWorkDate, placeholder = "2026-09-22", mono = true)
                    if (state.workDate.isNotBlank() && !state.workDateValid) {
                        Text(
                            "Write a real date as YYYY-MM-DD, for example 2026-09-22.",
                            style = LocalTechStyles.current.meta,
                            color = CbmPalette.Red,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            item {
                CbmTextArea("What you found", state.findings, actions.onFindings, maxChars = 3000,
                    placeholder = "The seal was perished along the lower edge.")
            }
            item {
                CbmTextArea("What you did about it", state.workPerformed, actions.onWorkPerformed, maxChars = 6000,
                    placeholder = "Replaced the seal, refitted the frame and checked the sash closes flush.")
            }
            item {
                CbmTextArea("Materials used", state.materials, actions.onMaterials, maxChars = 2000,
                    placeholder = "1 x seal, 4 m")
            }
            item { SectionHeader("How you checked it") }
            item {
                CbmTextArea("What you checked", state.checks, actions.onChecks, maxChars = 4000,
                    placeholder = "Poured water along the sill and watched for ten minutes.")
            }
            item {
                CbmPanel {
                    CHECK_RESULTS.forEach { (value, label) ->
                        CbmRadioRow(label, state.checkResult == value, { actions.onCheckResult(value) })
                    }
                }
            }
            item { SectionHeader("Where it stands") }
            item {
                CbmPanel {
                    OUTCOMES.forEach { (value, label) ->
                        CbmRadioRow(label, state.outcome == value, { actions.onOutcome(value) })
                    }
                }
            }
            item {
                CbmTextArea("Anything still open", state.remainingIssues, actions.onRemainingIssues, maxChars = 4000,
                    placeholder = "None.")
            }
            item { SectionHeader("Photo of the finished work") }
            item { PhotoRow(state, actions) }
            item {
                CbmPanel {
                    CbmRadioRow(
                        text = "This report describes the work and the checks I actually did.",
                        selected = state.declaration,
                        onClick = { actions.onDeclaration(!state.declaration) }
                    )
                }
            }
            state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
            item { Spacer(Modifier.height(4.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CbmPrimaryButton(
                text = if (state.sending) "Sending…" else "Send the report",
                onClick = actions.onSend,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.complete && !state.sending && !state.sent && state.closedNotice == null,
                tall = true
            )
            Text(
                when {
                    state.closedNotice != null -> "There is no report to send for this job now."
                    state.complete -> "The facility manager reads it and either closes the job or sends it back."
                    else -> "Fill in the work, the checks, where it stands and the confirmation."
                },
                style = LocalTechStyles.current.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PhotoRow(state: ReportFormState, actions: ReportFormActions) {
    CbmPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapturePhoto(state.photoUri, "The photo of the finished work", Modifier.size(84.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.photoUri == null) "A photo is optional, and it helps: the office compares it with the one that was reported."
                    else "This photo goes into the report.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (state.photoUri == null) {
                    CbmOutlineButton("Take a photo", actions.onTakePhoto, Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbmOutlineButton("Retake", actions.onTakePhoto, Modifier.weight(1f))
                        CbmOutlineButton("Remove", actions.onRemovePhoto, Modifier.weight(1f), color = CbmPalette.Red)
                    }
                }
            }
        }
        state.photoNotice?.let {
            Spacer(Modifier.height(10.dp))
            CbmInlineAlert(AlertKind.WARN, it)
        }
        if (state.photoUri != null) {
            Spacer(Modifier.height(10.dp))
            CbmField("What the photo shows", state.photoCaption, actions.onCaption,
                placeholder = "The new seal in place")
        }
    }
}

/** Shown once the report has gone, so nobody writes it twice. */
@Composable
fun ReportSentScreen(ticketId: Int, siteCode: String, expiresAtMillis: Long?, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar("Work report · #$ticketId", siteCode, expiresAtMillis, 0, onBack = onDone)
        Box(Modifier.weight(1f).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Report sent", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "The facility manager reads it and either closes the job or sends it back with a reason.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CbmPrimaryButton("Back to my jobs", onDone, Modifier.fillMaxWidth(), tall = true)
            }
        }
    }
}
