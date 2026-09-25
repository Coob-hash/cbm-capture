package ai.cbm.capture.ui.technician

import ai.cbm.capture.domain.model.JobCard
import ai.cbm.capture.domain.model.TechnicianProfile
import ai.cbm.capture.domain.model.skillLabel
import ai.cbm.capture.ui.design.AlertKind
import ai.cbm.capture.ui.design.CbmChip
import ai.cbm.capture.ui.design.CbmEmpty
import ai.cbm.capture.ui.design.CbmInlineAlert
import ai.cbm.capture.ui.design.CbmKpiTile
import ai.cbm.capture.ui.design.CbmLoading
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPanel
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.design.CapturePhoto
import ai.cbm.capture.ui.design.CbmTopBar
import ai.cbm.capture.ui.design.LocalPhotoUrl
import ai.cbm.capture.ui.design.MetaRow
import ai.cbm.capture.ui.design.SectionHeader
import ai.cbm.capture.ui.design.SeverityChip
import ai.cbm.capture.ui.design.StatusBadge
import ai.cbm.capture.ui.design.formatCountdown
import ai.cbm.capture.ui.design.rememberNow
import ai.cbm.capture.ui.design.ticketStatusColor
import ai.cbm.capture.ui.design.ticketStatusShort
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
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

data class TechUiState(
    val siteCode: String = "",
    val expiresAtMillis: Long? = null,
    val me: TechnicianProfile = TechnicianProfile(),
    val catalog: List<String> = emptyList(),
    val offers: List<JobCard> = emptyList(),
    val current: List<JobCard> = emptyList(),
    val completed: List<JobCard> = emptyList(),
    val loading: Boolean = true,
    val busyTicket: Int? = null,
    /** The trades picker is open to change trades already set (the first time it opens by itself). */
    val editingSkills: Boolean = false,
    val notice: String? = null,
    val error: String? = null
) {
    val toReport: List<JobCard> get() = current.filter { it.reportNeeded }
    val withFm: List<JobCard> get() = current.filter { !it.reportNeeded }
}

/**
 * The technician's three sections: the work they have done, the offers waiting for an answer, and
 * the jobs whose report is still to be written (PRD § 6).
 */
@Composable
fun TechnicianHomeScreen(
    state: TechUiState,
    onRefresh: () -> Unit,
    onAnswerOffer: (JobCard, Boolean) -> Unit,
    onSaveSkills: (List<String>) -> Unit,
    onOpenReport: (JobCard) -> Unit,
    onProfile: () -> Unit,
    onEditSkills: () -> Unit = {},
    onCancelEditSkills: () -> Unit = {}
) {
    // Q17: until the technician says what they work on, dispatch offers them nothing — so ask first.
    if (!state.loading && state.me.needsSkills) {
        SkillsScreen(state, onSave = onSaveSkills, onProfile = onProfile)
        return
    }
    // Q17 again: the trades are theirs to change later, and only theirs.
    if (state.editingSkills) {
        SkillsScreen(state, onSave = onSaveSkills, onProfile = onProfile, onCancel = onCancelEditSkills)
        return
    }
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(
            title = when (tab) { 1 -> "Offers"; 2 -> "To report"; else -> "My jobs" },
            siteCode = state.siteCode,
            sessionExpiresAt = state.expiresAtMillis,
            unread = 0,
            onProfile = onProfile
        )
        Box(Modifier.weight(1f)) {
            when {
                state.loading && state.offers.isEmpty() && state.current.isEmpty() -> CbmLoading("Reading your jobs")
                tab == 1 -> OffersTab(state, onAnswerOffer)
                tab == 2 -> ToReportTab(state, onOpenReport)
                else -> WorkTab(state, onRefresh, onEditSkills)
            }
        }
        TechnicianTabs(
            selected = tab,
            offers = state.offers.size,
            toReport = state.toReport.size,
            onSelect = { tab = it }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkTab(state: TechUiState, onRefresh: () -> Unit, onEditSkills: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
        state.notice?.let { item { CbmInlineAlert(AlertKind.INFO, it) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmKpiTile(state.me.jobsCompleted.toString(), "Completed", false, onRefresh, Modifier.weight(1f), CbmPalette.Green)
                CbmKpiTile(state.withFm.size.toString(), "With the FM", false, onRefresh, Modifier.weight(1f), CbmPalette.Teal)
                CbmKpiTile(state.toReport.size.toString(), "To report", false, onRefresh, Modifier.weight(1f), CbmPalette.HiVis)
            }
        }
        item { SectionHeader("Your trades") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.me.skills.forEach { CbmChip(skillLabel(it), selected = true, onClick = onEditSkills) }
            }
        }
        item { CbmOutlineButton("Change your trades", onEditSkills, Modifier.fillMaxWidth()) }
        item { SectionHeader("Done") }
        if (state.completed.isEmpty()) {
            item { CbmEmpty("No closed job yet", "A job appears here once the facility manager has approved your report.") }
        }
        items(state.completed, key = { it.ticketId }) { job -> JobRow(job) }
    }
}

@Composable
private fun OffersTab(state: TechUiState, onAnswer: (JobCard, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
        state.notice?.let { item { CbmInlineAlert(AlertKind.INFO, it) } }
        if (state.offers.isEmpty()) {
            item {
                CbmEmpty(
                    "No offer waiting",
                    if (state.me.skills.isEmpty()) "Set your trades first: jobs are offered by trade."
                    else "You are offered jobs that match ${state.me.skills.joinToString { skillLabel(it) }}."
                )
            }
        }
        items(state.offers, key = { it.ticketId }) { offer ->
            OfferCard(offer, busy = state.busyTicket == offer.ticketId, onAnswer = onAnswer)
        }
    }
}

@Composable
private fun OfferCard(card: JobCard, busy: Boolean, onAnswer: (JobCard, Boolean) -> Unit) {
    CbmPanel(rail = LocalAccent.current.color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${card.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(8.dp))
            card.severity?.let { SeverityChip(it) }
            Spacer(Modifier.weight(1f))
            card.requiredSkill?.let { Text(it.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400) }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        Text(card.location.detail, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row {
            CapturePhoto(
                url = card.photo.captureId?.let { LocalPhotoUrl.current(it) },
                contentDescription = "The photo of ticket ${card.ticketId}",
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.width(10.dp))
            card.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                MetaRow("Proposed", listOfNotNull(card.offer?.date, card.offer?.slot).joinToString(" · ").ifEmpty { "—" })
                OfferCountdown(card.offer?.expiresAt)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Fixed slot, no rescheduling. Opening an offer is not an answer.",
            style = LocalTechStyles.current.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (busy) {
            CbmLoading("Sending your answer")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbmOutlineButton("Decline", { onAnswer(card, false) }, Modifier.weight(1f))
                CbmPrimaryButton("Accept", { onAnswer(card, true) }, Modifier.weight(1f))
            }
        }
    }
}

/** How long is left to answer, from the offer's own expiry. */
@Composable
private fun OfferCountdown(expiresAt: String?) {
    val millis = remember(expiresAt) { expiresAt?.let(::parseIsoMillis) } ?: return
    val now = rememberNow()
    val left = millis - now
    MetaRow(
        label = "Answer within",
        value = if (left > 0) formatCountdown(left) else "expired",
        valueColor = if (left in 1..(3 * 3_600_000L)) CbmPalette.Red else MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ToReportTab(state: TechUiState, onOpenReport: (JobCard) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
        state.notice?.let { item { CbmInlineAlert(AlertKind.INFO, it) } }
        if (state.current.isEmpty()) {
            item { CbmEmpty("Nothing to report", "Jobs you have accepted appear here until their report is approved.") }
        }
        items(state.current, key = { it.ticketId }) { job ->
            CbmPanel(rail = ticketStatusColor(job.status)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${job.ticketId}", style = LocalTechStyles.current.ticketId)
                    Spacer(Modifier.weight(1f))
                    StatusBadge(ticketStatusShort(job.status), ticketStatusColor(job.status))
                }
                Spacer(Modifier.height(6.dp))
                Text(job.title, style = MaterialTheme.typography.titleMedium)
                Text(job.location.detail, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                job.work.slotLine?.let { MetaRow("Scheduled", it) }
                // The reason stays on the ticket after the new report is sent; it is news only until then.
                job.work.reworkReason?.takeIf { job.reportState == "REWORK" }?.let {
                    Spacer(Modifier.height(8.dp))
                    CbmInlineAlert(AlertKind.WARN, "“$it”", title = "The facility manager sent it back")
                }
                Spacer(Modifier.height(10.dp))
                when (job.reportState) {
                    "TO_DO", "REWORK" -> CbmPrimaryButton(
                        if (job.reportState == "REWORK") "Fill the report again" else "Fill the report",
                        { onOpenReport(job) },
                        Modifier.fillMaxWidth()
                    )
                    // Sent: the office turns it into the report document, then it goes to the manager.
                    "PROCESSING" -> Text(
                        "Report sent. It reaches the facility manager in a few minutes.",
                        style = LocalTechStyles.current.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    "WITH_FM" -> Text(
                        "Report sent. Waiting for the facility manager.",
                        style = LocalTechStyles.current.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Text(
                        "Nothing to do on this one.",
                        style = LocalTechStyles.current.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: JobCard) {
    CbmPanel(rail = ticketStatusColor(job.status)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${job.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge)
                Text(job.location.detail, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(ticketStatusShort(job.status), ticketStatusColor(job.status))
        }
    }
}

/**
 * "What do you work on?" — the technician's own trades, nobody else's (Q17). Opens by itself at the
 * first sign-in; later, [onCancel] is given and the same screen changes trades already set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillsScreen(state: TechUiState, onSave: (List<String>) -> Unit, onProfile: () -> Unit, onCancel: (() -> Unit)? = null) {
    var picked by remember(state.me.skills) { mutableStateOf(state.me.skills.toSet()) }
    val firstTime = onCancel == null
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(if (firstTime) "First sign-in" else "Your trades", state.siteCode, state.expiresAtMillis, 0,
            onProfile = onProfile, onBack = onCancel)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What do you work on?", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (firstTime) "Jobs are offered to you only in the trades you pick. You can change them " +
                    "later from My jobs — nobody else can, not the facility manager."
                else "Jobs are offered to you only in the trades you pick. Nobody else can change them, " +
                    "not the facility manager.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error?.let { CbmInlineAlert(AlertKind.CRITICAL, it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.catalog.forEach { skill ->
                    CbmChip(
                        text = skillLabel(skill),
                        selected = skill in picked,
                        onClick = { picked = if (skill in picked) picked - skill else picked + skill }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (picked.isEmpty()) "Pick at least one to receive job offers."
                else "${picked.size} selected — dispatch will match these.",
                style = LocalTechStyles.current.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CbmPrimaryButton(
                text = if (firstTime) "Save and start" else "Save",
                onClick = { onSave(state.catalog.filter { it in picked }) },
                modifier = Modifier.fillMaxWidth().then(if (firstTime) Modifier.navigationBarsPadding() else Modifier),
                enabled = picked.isNotEmpty() && !state.loading,
                tall = true
            )
            if (onCancel != null) {
                CbmOutlineButton("Cancel", onCancel, Modifier.fillMaxWidth().navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun TechnicianTabs(selected: Int, offers: Int, toReport: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CbmPalette.Ink900).navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TechnicianTab("Work", null, selected == 0) { onSelect(0) }
        TechnicianTab("Offers", offers.takeIf { it > 0 }, selected == 1) { onSelect(1) }
        TechnicianTab("To report", toReport.takeIf { it > 0 }, selected == 2) { onSelect(2) }
    }
}

@Composable
private fun TechnicianTab(label: String, badge: Int?, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                style = LocalTechStyles.current.stencil,
                color = if (selected) accent.color else CbmPalette.Steel300
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    badge.toString(),
                    style = LocalTechStyles.current.stencil,
                    color = CbmPalette.Ink900,
                    modifier = Modifier.background(accent.color).padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(28.dp)
                .background(if (selected) accent.color else CbmPalette.Ink900)
        )
    }
}

/** Offer expiry as sent by the API: an ISO-8601 instant. */
internal fun parseIsoMillis(iso: String): Long? = runCatching {
    kotlinx.datetime.Instant.parse(iso).toEpochMilliseconds()
}.getOrNull()
