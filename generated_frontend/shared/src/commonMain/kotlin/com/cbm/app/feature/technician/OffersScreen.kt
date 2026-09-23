package com.cbm.app.feature.technician

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.design.*
import com.cbm.app.domain.Offer
import com.cbm.app.domain.OfferState
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalTechStyles

@Composable
fun OffersScreen(state: TechState, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf<Pair<Offer, Boolean>?>(null) }
    val offers = state.bundle?.offers.orEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (offers.isEmpty()) item { CbmEmpty("No offers", "When dispatch reserves a job for you it appears here with its expiry.") }
        items(offers, key = { it.id }) { offer -> OfferCard(offer) { accept -> confirm = offer to accept } }
    }
    confirm?.let { (offer, accept) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (accept) "Accept offer #${offer.ticketId}?" else "Decline offer #${offer.ticketId}?") },
            text = { Text(if (accept) "Slot ${offer.slot}. The first valid acceptance wins — expiry and eligibility rules are unchanged (T-2)." else "The offer goes to the next eligible technician.") },
            confirmButton = { CbmSmallAction(if (accept) "Accept" else "Decline", { state.respond(offer, accept); confirm = null }, color = if (accept) CbmPalette.Green else CbmPalette.Red) },
            dismissButton = { CbmSmallAction("Cancel", { confirm = null }, filled = false) },
        )
    }
}

@Composable
private fun OfferCard(offer: Offer, onRespond: (Boolean) -> Unit) {
    val open = offer.state == OfferState.OPEN
    val now = rememberNow()
    val left = offer.expiresAtMillis - now
    CbmPanel(rail = if (open) LocalAccent.current.color else CbmPalette.Steel300) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${offer.ticketId}", style = LocalTechStyles.current.ticketId)
            Spacer(Modifier.width(8.dp))
            SeverityChip(offer.severity)
            Spacer(Modifier.weight(1f))
            if (open) StatusBadge("New", LocalAccent.current.color, blink = true)
            else StatusBadge(offer.state.name.replace('_', ' '), CbmPalette.Steel400)
        }
        Spacer(Modifier.height(6.dp))
        Text(offer.asset, style = MaterialTheme.typography.titleMedium)
        Text(offer.location, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
        Spacer(Modifier.height(4.dp))
        Text(offer.issue, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CbmChip(offer.skill.label, selected = true, onClick = {})
            Spacer(Modifier.width(10.dp))
            Text("SLOT ${offer.slot}", style = LocalTechStyles.current.metaStrong)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(CbmPalette.Concrete100).border(1.dp, CbmPalette.Steel200), contentAlignment = Alignment.Center) {
                Text("PHOTO", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
            }
            Spacer(Modifier.width(12.dp))
            if (open) Text("EXPIRES ${formatCountdown(left)}", style = LocalTechStyles.current.metaStrong, color = if (left < 3_600_000) CbmPalette.Red else CbmPalette.Amber)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Row {
                CbmSmallAction("Decline", { onRespond(false) }, color = CbmPalette.Red, filled = false)
                Spacer(Modifier.width(10.dp))
                CbmSmallAction("Accept", { onRespond(true) }, color = CbmPalette.Green)
            }
        }
    }
}
