package ai.cbm.capture.ui.fm

import ai.cbm.capture.domain.model.FmAction
import ai.cbm.capture.domain.model.FmCard
import ai.cbm.capture.domain.model.FmCounts
import ai.cbm.capture.ui.design.AlertKind
import ai.cbm.capture.ui.design.CbmDangerButton
import ai.cbm.capture.ui.design.CbmEmpty
import ai.cbm.capture.ui.design.CbmInlineAlert
import ai.cbm.capture.ui.design.CbmKpiTile
import ai.cbm.capture.ui.design.CbmLoading
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPanel
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.design.CbmTextArea
import ai.cbm.capture.ui.design.CapturePhoto
import ai.cbm.capture.ui.design.CbmTopBar
import ai.cbm.capture.ui.design.FirstJobTag
import ai.cbm.capture.ui.design.LocalPhotoUrl
import ai.cbm.capture.ui.design.SectionHeader
import ai.cbm.capture.ui.design.SeverityChip
import ai.cbm.capture.ui.design.StatusBadge
import ai.cbm.capture.ui.design.ticketStatusColor
import ai.cbm.capture.ui.design.ticketStatusShort
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Everything the facility manager's home draws. The view model owns it; the screen only renders. */
data class FmUiState(
    val siteName: String = "",
    val siteCode: String = "",
    val expiresAtMillis: Long? = null,
    val counts: FmCounts = FmCounts(),
    val authorizations: List<FmCard> = emptyList(),
    val completions: List<FmCard> = emptyList(),
    val loading: Boolean = true,
    val busyTicket: Int? = null,
    val notice: String? = null,
    val error: String? = null
) {
    val queue: List<FmCard> get() = authorizations + completions
}

/**
 * The FM's home: the decisions on top, the assistant below, one screen (PRD § 7.1).
 *
 * A decision here is the same decision as the email link and the chat — the app records it through
 * the workflows' own guarded action and they carry it out. Whoever is first wins.
 */
@Composable
fun FmHomeScreen(
    state: FmUiState,
    onRefresh: () -> Unit,
    onDecide: (FmCard, FmAction, String?) -> Unit,
    onProfile: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(
            title = "Building overview",
            siteCode = state.siteCode,
            sessionExpiresAt = state.expiresAtMillis,
            unread = 0,
            onProfile = onProfile
        )
        Box(Modifier.weight(0.58f)) {
            when {
                state.loading && state.queue.isEmpty() -> CbmLoading("Reading the site")
                else -> FmQueue(state, onRefresh, onDecide)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(14.dp).background(CbmPalette.Ink900),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { Box(Modifier.size(width = 10.dp, height = 2.dp).background(CbmPalette.Steel500)) }
            }
        }
        Box(Modifier.weight(0.42f)) { FmAssistantPanel() }
    }
}

@Composable
private fun FmQueue(state: FmUiState, onRefresh: () -> Unit, onDecide: (FmCard, FmAction, String?) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmKpiTile(state.counts.awaitingAuthorization.toString(), "Authorize", false, onRefresh, color = CbmPalette.Amber)
                CbmKpiTile(state.counts.awaitingApproval.toString(), "Approve", false, onRefresh, color = CbmPalette.Teal)
                CbmKpiTile(state.counts.inProgress.toString(), "In progress", false, onRefresh)
                CbmKpiTile(state.counts.closed7d.toString(), "Closed 7d", false, onRefresh, color = CbmPalette.Green)
            }
        }
        state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
        state.notice?.let { item { CbmInlineAlert(AlertKind.INFO, it) } }
        item { SectionHeader("Needs you") }
        if (state.queue.isEmpty()) {
            item {
                CbmEmpty(
                    "Nothing needs you right now",
                    "You are told as soon as a job needs authorizing or a report arrives."
                )
            }
        }
        items(state.queue, key = { it.ticketId }) { card ->
            FmDecisionCard(card, busy = state.busyTicket == card.ticketId, onDecide = onDecide)
        }
    }
}

/** One ticket waiting for a decision, with the two buttons of its stage (F-2a). */
@Composable
private fun FmDecisionCard(card: FmCard, busy: Boolean, onDecide: (FmCard, FmAction, String?) -> Unit) {
    var asking by remember(card.ticketId, card.status) { mutableStateOf<FmAction?>(null) }
    var reason by remember(card.ticketId, card.status) { mutableStateOf("") }
    val completion = card.action.canApproveCompletion || card.action.canRequestRework

    CbmPanel(rail = ticketStatusColor(card.status)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${card.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(8.dp))
            card.severity?.let { SeverityChip(it) }
            Spacer(Modifier.weight(1f))
            StatusBadge(ticketStatusShort(card.status), ticketStatusColor(card.status))
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        Text(card.location.detail, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.technician?.let { tech ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TECH ${tech.name}", style = LocalTechStyles.current.meta)
                if (tech.firstJob) { Spacer(Modifier.width(8.dp)); FirstJobTag() }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            CapturePhoto(
                url = card.photo.captureId?.let { LocalPhotoUrl.current(it) },
                contentDescription = "The photo of ticket ${card.ticketId}",
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                card.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (card.reporter.fromApp) {
                    Text(
                        "Reported by ${card.reporter.label}",
                        style = LocalTechStyles.current.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        card.work.reportText?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "“$it”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
            )
        }
        Spacer(Modifier.height(10.dp))

        val pending = asking
        when {
            busy -> CbmLoading("Sending your decision")
            pending != null -> ReasonBox(
                action = pending,
                reason = reason,
                onReason = { reason = it },
                onCancel = { asking = null; reason = "" },
                onSend = { onDecide(card, pending, reason); asking = null }
            )
            !card.action.decidable -> Text(
                "Nothing to decide on this one yet.",
                style = LocalTechStyles.current.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmOutlineButton(
                    text = if (completion) FmAction.REWORK.label else FmAction.REJECT.label,
                    onClick = { asking = if (completion) FmAction.REWORK else FmAction.REJECT },
                    modifier = Modifier.weight(1f),
                    color = CbmPalette.Red
                )
                CbmPrimaryButton(
                    text = if (completion) FmAction.APPROVE.label else FmAction.AUTHORIZE.label,
                    onClick = { onDecide(card, if (completion) FmAction.APPROVE else FmAction.AUTHORIZE, null) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Rejecting and sending back need a reason: the reporter or the technician reads it. */
@Composable
private fun ReasonBox(
    action: FmAction,
    reason: String,
    onReason: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbmTextArea(
            label = if (action == FmAction.REJECT) "Why are you rejecting it?" else "What still has to be done?",
            value = reason,
            onChange = onReason,
            maxChars = 2000,
            placeholder = if (action == FmAction.REJECT) "Not our building — it belongs to the landlord." else "The handle is still loose."
        )
        Text(
            if (action == FmAction.REJECT) "The person who reported it is told." else "The technician sees this and does the work again.",
            style = LocalTechStyles.current.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CbmOutlineButton("Cancel", onCancel, Modifier.weight(1f))
            CbmDangerButton(action.label, onSend, Modifier.weight(1f), enabled = reason.trim().length >= 2)
        }
    }
}

/**
 * The lower half of the split screen. The WF3 agent already answers by email and in n8n's own chat;
 * the app endpoint for it is the next piece of backend work, so the panel states that plainly
 * instead of pretending to answer.
 */
@Composable
private fun FmAssistantPanel() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(14.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionHeader("Ask about the building")
        CbmInlineAlert(
            AlertKind.INFO,
            "Asking questions here is not ready yet. The decisions above are: whoever answers first, " +
                "here or from the email, is the one that counts.",
            title = "Coming soon"
        )
        Spacer(Modifier.weight(1f))
    }
}
