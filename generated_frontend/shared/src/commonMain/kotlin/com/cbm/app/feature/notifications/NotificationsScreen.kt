package com.cbm.app.feature.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.design.*
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalTechStyles

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val session = app.session!!
    LaunchedEffect(Unit) { app.refreshNotices() }
    DisposableEffect(Unit) { onDispose { app.markNoticesRead() } }
    Scaffold(topBar = { CbmTopBar("Notifications", session.active.site.code, session.expiresAtMillis, 0, onBack = onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (app.notices.isEmpty()) item { CbmEmpty("No notifications", "Events from the workflows appear here (PRD §9.4).") }
            items(app.notices, key = { it.id }) { n ->
                CbmPanel(rail = if (n.read) CbmPalette.Steel200 else LocalAccent.current.color) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(n.title, style = MaterialTheme.typography.titleMedium)
                            Text(n.body, style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel500)
                        }
                        if (!n.read) LedDot(LocalAccent.current.color)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(n.at, style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
                }
            }
        }
    }
}
