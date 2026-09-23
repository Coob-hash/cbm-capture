package com.cbm.app.feature.fm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.design.*
import com.cbm.app.domain.ChatMsg
import com.cbm.app.domain.Proposal
import com.cbm.app.domain.ProposalState
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalTechStyles

@Composable
fun FmChat(state: FmState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1) }
    Column(modifier.fillMaxSize()) {
        SectionHeader("WF3 agent", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), trailing = { Text("MEM 50 · LOGGED", style = LocalTechStyles.current.meta, color = CbmPalette.Steel300) })
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages, key = { it.id }) { ChatBubble(it, state) }
            if (state.sending) item { CbmLoading("Agent is thinking") }
        }
        SuggestedPrompts(state)
        ChatInput(state)
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg, state: FmState) {
    if (msg.fromAgent) {
        Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
            Text("AGENT · WF3", style = LocalTechStyles.current.stencil, color = CbmPalette.Teal)
            Spacer(Modifier.height(3.dp))
            CbmPanel(rail = CbmPalette.Teal) {
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                msg.proposal?.let { Spacer(Modifier.height(8.dp)); ProposalCard(msg.id, it, state) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalAlignment = Alignment.End) {
            Text("YOU", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
            Spacer(Modifier.height(3.dp))
            Surface(color = CbmPalette.Ink800, shape = RoundedCornerShape(3.dp)) {
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

/** F-7: a state-changing action is rendered as a confirmation card; only
 *  Confirm runs the guarded action. Outcomes are shown verbatim (F-8). */
@Composable
private fun ProposalCard(msgId: String, p: Proposal, state: FmState) {
    Column(Modifier.fillMaxWidth().border(1.dp, CbmPalette.Amber).padding(10.dp)) {
        Text("ACTION PROPOSED", style = LocalTechStyles.current.stencil, color = CbmPalette.Amber)
        Spacer(Modifier.height(4.dp))
        Text("${p.action.label} · ticket #${p.ticketId}", style = MaterialTheme.typography.titleMedium)
        if (p.action.needsReason) Text("A reason will be required on confirm.", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
        Spacer(Modifier.height(8.dp))
        when (p.state) {
            ProposalState.PENDING -> Row {
                CbmSmallAction("Confirm", { state.confirmProposal(msgId, true) }, color = CbmPalette.Green)
                Spacer(Modifier.width(8.dp))
                CbmSmallAction("Cancel", { state.confirmProposal(msgId, false) }, filled = false)
            }
            ProposalState.CONFIRMED -> CbmInlineAlert(AlertKind.OK, "${p.outcome} — decision recorded; the dashboard above refreshed.")
            ProposalState.CANCELLED -> CbmInlineAlert(AlertKind.INFO, "${p.outcome ?: "UNCONFIRMED"} — cancelled; nothing changed.")
            ProposalState.BLOCKED -> CbmInlineAlert(AlertKind.CRITICAL, "${p.outcome ?: "BLOCKED"} — the ticket moved on; nothing changed.")
        }
    }
}

@Composable
private fun SuggestedPrompts(state: FmState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("What's wrong with #57?", "How many tickets open?", "Approve #42").forEach { p ->
            CbmChip(p, false) { state.chatInput = p }
        }
    }
}

@Composable
private fun ChatInput(state: FmState) {
    val accent = LocalAccent.current.color
    Column {
        HorizontalDivider(color = CbmPalette.Steel200)
        Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.chatInput,
                onValueChange = { if (it.length <= 1500) state.chatInput = it },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                placeholder = { Text("Ask about the building…") },
                shape = RoundedCornerShape(3.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = CbmPalette.Steel200, cursorColor = accent),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).background(accent, RoundedCornerShape(3.dp)).clickable { state.send() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
            }
        }
        Text("${state.chatInput.length}/1500", style = LocalTechStyles.current.meta, color = CbmPalette.Steel300, modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp))
    }
}
