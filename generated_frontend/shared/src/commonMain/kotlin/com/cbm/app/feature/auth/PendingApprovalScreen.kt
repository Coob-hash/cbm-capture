package com.cbm.app.feature.auth

import androidx.compose.foundation.background
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
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.launch

@Composable
fun PendingApprovalScreen() {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CbmTopBar("Building overview", session.active.site.code, session.expiresAtMillis, 0)
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LedDot(CbmPalette.Amber, blink = true, size = 14)
            Spacer(Modifier.height(16.dp))
            Text("WAITING FOR APPROVAL", style = LocalTechStyles.current.stencil, color = CbmPalette.Amber)
            Spacer(Modifier.height(10.dp))
            Text(
                "A facility manager can authorize work and close tickets, so the operator approves FM accounts first. You can already log in — this screen shows until then. Nobody approves their own request.",
                style = MaterialTheme.typography.bodyMedium, color = CbmPalette.Steel500,
            )
            Spacer(Modifier.height(24.dp))
            CbmPrimaryButton("Check again", { scope.launch { app.refreshMe() } })
            Spacer(Modifier.height(10.dp))
            CbmOutlineButton("Sign out", { scope.launch { app.signOut() } })
        }
    }
}
