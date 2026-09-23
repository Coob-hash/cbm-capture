package com.cbm.app.feature.fm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cbm.app.data.CbmApi
import com.cbm.app.data.LocalApp
import com.cbm.app.design.CbmSmallAction
import com.cbm.app.design.CbmTopBar
import com.cbm.app.domain.*
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class FmState(private val api: CbmApi, private val token: String, private val scope: CoroutineScope) {
    var board by mutableStateOf<FmBoard?>(null); private set
    var refreshing by mutableStateOf(false); private set
    var filter by mutableStateOf<FmFilter?>(null)
    var detail by mutableStateOf<TicketDetail?>(null)
    var messages by mutableStateOf(listOf(ChatMsg("m0", true, "Good morning. Interventions are waiting for your authorization — tap a card or ask me anything."))); private set
    var chatInput by mutableStateOf("")
    var sending by mutableStateOf(false); private set
    var toast by mutableStateOf<String?>(null)
    private var seq = 0
    private fun nid() = "m${++seq}"

    fun refresh() { scope.launch { refreshing = true; board = api.fmBoard(token); refreshing = false } }
    fun openDetail(id: Int) { scope.launch { detail = api.fmTicket(token, id) } }
    fun decide(item: QueueItem, action: Decision, reason: String?) {
        scope.launch {
            val r = api.fmDecide(token, item.ticketId, action, reason, item.cycle, item.revision)
            toast = "${r.outcome} — ${r.message}"
            refresh()
        }
    }
    fun askAbout(ticketId: Int) { chatInput = "Ticket #$ticketId — " } // F-6: inserts a reference, never sends
    fun send() {
        val text = chatInput.trim(); if (text.isEmpty()) return
        chatInput = ""
        messages = messages + ChatMsg(nid(), false, text)
        sending = true
        scope.launch { val reply = api.fmChat(token, text); messages = messages + reply; sending = false }
    }
    fun confirmProposal(msgId: String, confirm: Boolean) {
        val msg = messages.firstOrNull { it.id == msgId } ?: return
        val p = msg.proposal ?: return
        if (!confirm) { updateProposal(msgId, p.copy(state = ProposalState.CANCELLED, outcome = "UNCONFIRMED")); return }
        scope.launch {
            val r = api.fmConfirmProposal(token, p)
            updateProposal(msgId, p.copy(state = if (r.outcome == DecisionOutcome.APPLIED) ProposalState.CONFIRMED else ProposalState.BLOCKED, outcome = r.outcome.name))
            messages = messages + ChatMsg(nid(), true, "${r.outcome} — ${r.message}")
            refresh()
        }
    }
    private fun updateProposal(msgId: String, p: Proposal) { messages = messages.map { if (it.id == msgId) it.copy(proposal = p) else it } }
    init { refresh() }
}

@Composable
fun FmHomeScreen(onNotifications: () -> Unit, onProfile: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    val state = remember { FmState(app.api, session.token, scope) }

    Scaffold(topBar = { CbmTopBar("Building overview", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBell = onNotifications, onProfile = onProfile) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val widthClass = when { maxWidth < 600.dp -> 0; maxWidth < 840.dp -> 1; else -> 2 }
            val density = LocalDensity.current
            val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
            when (widthClass) {
                // Compact: horizontal split, default 55/45, draggable divider (F-9).
                0 -> {
                    var fraction by rememberSaveable(widthClass) { mutableStateOf(0.55f) }
                    val heightPx = with(density) { maxHeight.toPx() }
                    Column(Modifier.fillMaxSize()) {
                        if (keyboardOpen) {
                            FmDashboard(state, kpiOnly = true) // keyboard open → dashboard collapses to the KPI row
                        } else {
                            Box(Modifier.weight(fraction)) { FmDashboard(state) }
                            SplitHandle { delta -> fraction = (fraction + delta / heightPx).coerceIn(0.25f, 0.78f) }
                        }
                        Box(Modifier.weight(if (keyboardOpen) 1f else 1f - fraction)) { FmChat(state) }
                    }
                }
                // Medium: side by side 50/50.
                1 -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.5f)) { FmDashboard(state) }
                    VerticalHandle()
                    Box(Modifier.weight(0.5f)) { FmChat(state) }
                }
                // Expanded: 60/40 with room for the ticket detail sheet next to the list.
                else -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(if (state.detail != null) 0.38f else 0.6f)) { FmDashboard(state) }
                    if (state.detail != null) { VerticalHandle(); Box(Modifier.weight(0.27f)) { TicketDetailPanel(state) } }
                    VerticalHandle()
                    Box(Modifier.weight(if (state.detail != null) 0.35f else 0.4f)) { FmChat(state) }
                }
            }
            if (widthClass != 2 && state.detail != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { state.detail = null })
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f).align(Alignment.BottomCenter)) { TicketDetailPanel(state) }
            }
        }
    }
    state.toast?.let {
        AlertDialog(onDismissRequest = { state.toast = null }, confirmButton = { CbmSmallAction("OK", { state.toast = null }) }, text = { Text(it) })
    }
}

@Composable
private fun SplitHandle(onDrag: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(22.dp).background(CbmPalette.Ink800).pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> onDrag(dragAmount) } },
        contentAlignment = Alignment.Center,
    ) { Text("═ ═ ═", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel500) }
}

@Composable
private fun VerticalHandle() {
    Box(Modifier.fillMaxHeight().width(10.dp).background(CbmPalette.Ink800), contentAlignment = Alignment.Center) {
        Text("║", color = CbmPalette.Steel500)
    }
}
