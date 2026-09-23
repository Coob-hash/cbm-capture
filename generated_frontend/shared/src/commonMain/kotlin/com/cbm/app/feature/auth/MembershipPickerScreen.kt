package com.cbm.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.design.*
import com.cbm.app.domain.MembershipStatus
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import com.cbm.app.theme.accentFor
import kotlinx.coroutines.launch

@Composable
fun MembershipPickerScreen() {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar("Choose a workspace", session.active.site.code, session.expiresAtMillis, 0)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "One account, several memberships. Each session is bound to exactly one site and one role — switching role means logging in again (PRD §3).",
                style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel500,
            )
            session.memberships.forEach { m ->
                val accent = accentFor(m.role)
                CbmPanel(
                    rail = accent.color,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = m.status == MembershipStatus.ACTIVE) { scope.launch { app.chooseMembership(m.id) } },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.site.name, style = MaterialTheme.typography.titleMedium)
                            Text(m.site.code, style = LocalTechStyles.current.meta, color = CbmPalette.Steel400)
                        }
                        StatusBadge(m.role.name, accent.color)
                        if (m.status == MembershipStatus.PENDING) { Spacer(Modifier.width(6.dp)); StatusBadge("Pending", CbmPalette.Amber, blink = true) }
                    }
                }
            }
            CbmOutlineButton("Sign out", { scope.launch { app.signOut() } }, Modifier.fillMaxWidth())
        }
    }
}
