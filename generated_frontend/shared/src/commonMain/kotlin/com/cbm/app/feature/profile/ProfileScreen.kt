package com.cbm.app.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.design.*
import com.cbm.app.domain.MembershipStatus
import com.cbm.app.domain.Role
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onBack: () -> Unit, onSkills: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { CbmTopBar("Profile", session.active.site.code, session.expiresAtMillis, app.unreadCount, onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbmPanel(header = "Account") {
                MetaRow("Email", session.email)
                MetaRow("Name", session.displayName)
            }
            CbmPanel(header = "Membership") {
                MetaRow("Site", "${session.active.site.name} (${session.active.site.code})")
                Spacer(Modifier.height(6.dp))
                Row {
                    StatusBadge(session.active.role.name, LocalAccent.current.color)
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(session.active.status.name, if (session.active.status == MembershipStatus.ACTIVE) CbmPalette.Green else CbmPalette.Amber)
                }
            }
            CbmPanel(header = "Session") {
                val now = rememberNow()
                MetaRow("Expires in", formatCountdown(session.expiresAtMillis - now))
                Text("Sessions last exactly one hour and are never extended (PRD §4.1).", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
            }
            CbmPanel(header = "Device") {
                MetaRow("Model", "Pixel 8 · Android 15")
                MetaRow("App", "2.0.0 (1)")
                if (session.newDevice) { Spacer(Modifier.height(6.dp)); CbmInlineAlert(AlertKind.INFO, "First sign-in from this device.") }
            }
            if (session.active.role == Role.TECHNICIAN) {
                CbmOutlineButton("Edit my trades", onSkills, Modifier.fillMaxWidth())
            }
            val queued = app.outbox.forAccount(session.email)
            if (queued.isNotEmpty()) {
                CbmInlineAlert(AlertKind.INFO, "${queued.size} item(s) queued in this account's outbox — they upload automatically and never under another account.")
            }
            CbmDangerButton("Sign out", { scope.launch { app.signOut() } }, Modifier.fillMaxWidth())
        }
    }
}
