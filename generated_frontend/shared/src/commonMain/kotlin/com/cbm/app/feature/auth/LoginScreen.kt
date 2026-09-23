package com.cbm.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.data.LocalApp
import com.cbm.app.design.*
import com.cbm.app.domain.Role
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import com.cbm.app.theme.accentFor
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var modeLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.REPORTER) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.background)) {
        // Site panel — the site is never typed; it comes from the QR code (PRD §4.1).
        Column(Modifier.fillMaxWidth().background(CbmPalette.Ink900).statusBarsPadding().padding(20.dp)) {
            Text("SITE // ROOM-POC", style = LocalTechStyles.current.stencil, color = CbmPalette.Steel300)
            Spacer(Modifier.height(8.dp))
            Text("Maddaloni Office", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("Community-Based Maintenance — report · fix · verify", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel300)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(CbmPalette.Green)
                Spacer(Modifier.width(6.dp))
                Text("SITE CODE FROM QR ✓", style = LocalTechStyles.current.stencil, color = CbmPalette.Green)
            }
            Spacer(Modifier.height(2.dp))
            Text("CBM APP · V2.0", style = LocalTechStyles.current.meta, color = CbmPalette.Steel500)
        }

        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth().border(1.dp, CbmPalette.Steel200)) {
                Segment("Log in", modeLogin) { modeLogin = true }
                Segment("Create account", !modeLogin) { modeLogin = false }
            }
            Spacer(Modifier.height(16.dp))
            CbmField("Email", email, { email = it }, placeholder = "name@company.com")
            Spacer(Modifier.height(12.dp))
            CbmField("Password", password, { password = it }, secret = true, placeholder = if (modeLogin) "••••••••" else "min. 8 characters")

            if (!modeLogin) {
                Spacer(Modifier.height(16.dp))
                FieldLabel("I am a")
                RoleOption(Role.REPORTER, "User", "Report problems with a photo", role) { role = it }
                RoleOption(Role.TECHNICIAN, "Technician", "Accept jobs, file work reports", role) { role = it }
                RoleOption(Role.FM, "Facility manager", "Authorize work, close tickets", role) { role = it }
                if (role == Role.FM) {
                    Spacer(Modifier.height(8.dp))
                    CbmInlineAlert(AlertKind.INFO, "Facility-manager accounts are approved by the operator. You can log in right away and will see a waiting screen until then.")
                }
            }

            Spacer(Modifier.height(20.dp))
            CbmPrimaryButton(
                if (modeLogin) "Log in" else "Create account",
                { scope.launch { if (modeLogin) app.signIn(email.trim(), password, "ROOM-POC") else app.signUp(email.trim(), password, role, "ROOM-POC") } },
                Modifier.fillMaxWidth(),
                enabled = !app.authBusy && email.isNotBlank() && password.isNotBlank(),
                tall = true,
            )
            Spacer(Modifier.height(10.dp))
            CbmOutlineButton("Continue with Google", { scope.launch { app.signInGoogle(if (modeLogin) null else role, "ROOM-POC") } }, Modifier.fillMaxWidth())

            app.authError?.let {
                Spacer(Modifier.height(12.dp))
                CbmInlineAlert(AlertKind.CRITICAL, it)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "One-hour sessions · five failed attempts lock the account for 15 minutes · an unknown email and a wrong password get the same answer.",
                style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400,
            )
            Spacer(Modifier.height(12.dp))
            CbmPanel(header = "Demo accounts") {
                Text("user@cbm.site · tech@cbm.site · fm@cbm.site · multi@cbm.site (two roles)\nAny password of 4+ characters.", style = LocalTechStyles.current.meta, color = CbmPalette.Steel500)
            }
        }
    }
}

@Composable
private fun RowScope.Segment(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).clickable(onClick = onClick).background(if (active) CbmPalette.Ink900 else Color.Transparent).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (active) Color.White else CbmPalette.Steel500) }
}

@Composable
private fun RoleOption(r: Role, title: String, sub: String, selected: Role, onSelect: (Role) -> Unit) {
    val color = accentFor(r).color
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.5.dp, if (selected == r) color else CbmPalette.Steel200, RoundedCornerShape(3.dp))
            .clickable { onSelect(r) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
        }
        RadioButton(selected == r, { onSelect(r) }, colors = RadioButtonDefaults.colors(selectedColor = color))
    }
}
