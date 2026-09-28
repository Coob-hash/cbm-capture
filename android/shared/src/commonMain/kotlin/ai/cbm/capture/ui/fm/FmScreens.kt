package ai.cbm.capture.ui.fm

import ai.cbm.capture.domain.model.ChatLine
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    val error: String? = null,
    /** The conversation with the building assistant, oldest first. */
    val chat: List<ChatLine> = emptyList(),
    val chatDraft: String = "",
    val chatSending: Boolean = false
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
    onNotifications: () -> Unit,
    onChatDraft: (String) -> Unit,
    onAsk: () -> Unit,
    onProfile: () -> Unit
) {
    // While the FM writes to the assistant it gets most of the screen; the decisions stay in view.
    var writing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        CbmTopBar(
            title = "Building overview",
            siteCode = state.siteCode,
            sessionExpiresAt = state.expiresAtMillis,
            // The dot says something waits for a decision; the bell lists what.
            unread = state.queue.size,
            onBell = onNotifications,
            onProfile = onProfile
        )
        Box(Modifier.weight(if (writing) 0.3f else 0.58f)) {
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
        Box(Modifier.weight(if (writing) 0.7f else 0.42f)) {
            FmAssistantPanel(state, onChatDraft, onAsk, onWriting = { writing = it })
        }
    }
}

/**
 * What waits for the FM, one line each: jobs to authorize, then work to approve. A line opens the
 * home, where the decision is taken. Nothing waiting says so.
 */
@Composable
fun FmNotificationsScreen(
    state: FmUiState,
    onBack: () -> Unit,
    onOpen: (FmCard) -> Unit,
    onRefresh: () -> Unit,
    onProfile: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar(
            title = "Notifications",
            siteCode = state.siteCode,
            sessionExpiresAt = state.expiresAtMillis,
            unread = 0,
            onBack = onBack,
            onBell = onRefresh,
            onProfile = onProfile
        )
        when {
            state.loading && state.queue.isEmpty() -> CbmLoading("Checking")
            state.queue.isEmpty() && state.error != null -> Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CbmInlineAlert(AlertKind.CRITICAL, state.error)
                CbmOutlineButton("Try again", onRefresh, Modifier.fillMaxWidth())
            }
            state.queue.isEmpty() -> CbmEmpty(
                "No notification at the moment",
                "Jobs to authorize and work to approve show up here as soon as they arrive."
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.error?.let { item { CbmInlineAlert(AlertKind.CRITICAL, it) } }
                items(state.authorizations, key = { "a${it.ticketId}" }) { card ->
                    NotificationRow(card, "Authorize this job", CbmPalette.Amber, onOpen)
                }
                items(state.completions, key = { "c${it.ticketId}" }) { card ->
                    NotificationRow(card, "Approve the work", CbmPalette.Teal, onOpen)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(card: FmCard, what: String, color: androidx.compose.ui.graphics.Color, onOpen: (FmCard) -> Unit) {
    CbmPanel(Modifier.fillMaxWidth().clickable { onOpen(card) }, rail = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(what.uppercase(), style = LocalTechStyles.current.stencil, color = color)
            Spacer(Modifier.weight(1f))
            Text("#${card.ticketId}", style = LocalTechStyles.current.ticketId)
        }
        Spacer(Modifier.height(4.dp))
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        Text(
            card.technician?.let { "${card.location.detail} · ${it.name}" } ?: card.location.detail,
            style = LocalTechStyles.current.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
 * The lower half of the split screen: the building assistant, the same agent that answers in the
 * workflows' own chat. It reads the whole ticket history, and it carries out a decision the FM
 * states plainly ("approve the work on 42") through the same guarded action as the buttons above.
 */
@Composable
private fun FmAssistantPanel(
    state: FmUiState,
    onDraft: (String) -> Unit,
    onAsk: () -> Unit,
    onWriting: (Boolean) -> Unit
) {
    val accent = LocalAccent.current.color
    val list = rememberLazyListState()
    val lines = state.chat.size + (if (state.chatSending) 1 else 0)
    LaunchedEffect(lines) { if (lines > 0) list.animateScrollToItem(lines - 1) }
    val canSend = state.chatDraft.isNotBlank() && !state.chatSending

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp).navigationBarsPadding()
    ) {
        SectionHeader("Ask about the building")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.chat.isEmpty() && !state.chatSending) {
                item {
                    Text(
                        "Ask about any ticket, at any point in its history - \"which tickets are still " +
                            "open after a month?\" - or tell it what to do: \"approve the work on ticket 42\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.chat) { ChatBubble(it) }
            if (state.chatSending) item { ChatBubble(ChatLine(fromFm = false, text = "Looking it up…"), thinking = true) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.chatDraft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).onFocusChanged { onWriting(it.isFocused) },
                placeholder = { Text("Ask a question", color = CbmPalette.Steel300) },
                maxLines = 4,
                shape = RoundedCornerShape(3.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onAsk() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = CbmPalette.Steel200, cursorColor = accent
                )
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onAsk, enabled = canSend) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) accent else CbmPalette.Steel300
                )
            }
        }
    }
}

/** The FM's lines on the right, the assistant's on the left; an answer that did not come, in red. */
@Composable
private fun ChatBubble(line: ChatLine, thinking: Boolean = false) {
    val accent = LocalAccent.current.color
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromFm) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.fillMaxWidth(0.86f).wrapContentWidth(if (line.fromFm) Alignment.End else Alignment.Start)
                .background(
                    when {
                        line.fromFm -> accent.copy(alpha = 0.12f)
                        line.failed -> CbmPalette.RedSoft
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            when {
                thinking -> Text(line.text, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
                else -> SelectionContainer {
                    Text(
                        withBold(line.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.failed) CbmPalette.Red else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** The assistant writes **bold** for emphasis: shown bold, without the stars. */
internal fun withBold(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) { append(rest); break }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}
