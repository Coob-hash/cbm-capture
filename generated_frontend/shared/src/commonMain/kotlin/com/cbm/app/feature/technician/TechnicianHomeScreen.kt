package com.cbm.app.feature.technician

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cbm.app.data.CbmApi
import com.cbm.app.data.LocalApp
import com.cbm.app.design.CbmSmallAction
import com.cbm.app.design.CbmTopBar
import com.cbm.app.domain.*
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TechState(private val api: CbmApi, private val token: String, private val scope: CoroutineScope) {
    var bundle by mutableStateOf<TechBundle?>(null); private set
    var summary by mutableStateOf<TechSummary?>(null); private set
    var period by mutableStateOf("This month"); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    fun load() { scope.launch { bundle = api.techBundle(token); summary = api.techSummary(token, period) } }
    fun setPeriod(p: String) { period = p; scope.launch { summary = api.techSummary(token, p) } }
    fun respond(offer: Offer, accept: Boolean) {
        scope.launch {
            busy = true
            val updated = api.respondToOffer(token, offer.id, accept)
            message = when (updated.state) {
                OfferState.ACCEPTED -> "Offer accepted — ticket #${offer.ticketId} is ASSIGNED to you. It is now under “To report”."
                OfferState.DECLINED -> "Offer #${offer.ticketId} declined."
                else -> "Offer #${offer.ticketId} is ${updated.state.name.lowercase().replace('_', ' ')} — the server's answer is authoritative (T-3)."
            }
            load(); busy = false
        }
    }

    init { load() }
}

@Composable
fun TechnicianHomeScreen(onOpenReport: (Int) -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    val state = remember { TechState(app.api, session.token, scope) }
    var tab by remember { mutableStateOf(0) }
    val bundle = state.bundle

    // T-0 / Q17: until the technician sets their own trades, dispatch offers nothing.
    if (bundle != null && bundle.skills.isEmpty()) {
        SkillsScreen(onDone = { state.load() }, blocking = true)
        return
    }

    Scaffold(
        topBar = { CbmTopBar("My jobs", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBell = onNotifications, onProfile = onProfile) },
        bottomBar = {
            NavigationBar(containerColor = CbmPalette.Ink900) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, "Dashboard") },
                    label = { Text("DASHBOARD", style = LocalTechStyles.current.stencil) },
                    colors = techNavColors(),
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(badge = {
                            val c = bundle?.offers?.count { it.state == OfferState.OPEN } ?: 0
                            if (c > 0) Badge { Text(c.toString()) }
                        }) { Icon(Icons.Default.Email, "Offers") }
                    },
                    label = { Text("OFFERS", style = LocalTechStyles.current.stencil) },
                    colors = techNavColors(),
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = {
                        BadgedBox(badge = {
                            val c = bundle?.toReport?.size ?: 0
                            if (c > 0) Badge { Text(c.toString()) }
                        }) { Icon(Icons.Default.Build, "To report") }
                    },
                    label = { Text("TO REPORT", style = LocalTechStyles.current.stencil) },
                    colors = techNavColors(),
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> TechDashboard(state, Modifier.padding(padding))
            1 -> OffersScreen(state, Modifier.padding(padding))
            else -> ToReportScreen(state, onOpenReport, Modifier.padding(padding))
        }
    }
    state.message?.let { msg ->
        AlertDialog(onDismissRequest = { state.message = null }, confirmButton = { CbmSmallAction("OK", { state.message = null }) }, text = { Text(msg) })
    }
}

@Composable
private fun techNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = CbmPalette.HiVis, selectedTextColor = Color.White,
    unselectedIconColor = CbmPalette.Steel300, unselectedTextColor = CbmPalette.Steel300,
    indicatorColor = CbmPalette.Steel700,
)
