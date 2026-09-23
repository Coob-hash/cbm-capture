package com.cbm.app.feature.technician

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
import com.cbm.app.design.CbmChip
import com.cbm.app.design.CbmPrimaryButton
import com.cbm.app.design.CbmTopBar
import com.cbm.app.domain.Trade
import com.cbm.app.theme.CbmPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillsScreen(onDone: () -> Unit, blocking: Boolean = false) {
    val app = LocalApp.current
    val session = app.session!!
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Trade>()) }
    var saving by remember { mutableStateOf(false) }

    val body: @Composable (Modifier) -> Unit = { padding ->
        Column(padding.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("What do you work on?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Dispatch matches offers to these trades. Pick at least one. Only you can change this list — not the FM (PRD Q17). Until it is set, dispatch offers you nothing.",
                style = MaterialTheme.typography.bodyMedium, color = CbmPalette.Steel500,
            )
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Trade.entries.forEach { t ->
                    CbmChip(t.label, t in selected) { selected = if (t in selected) selected - t else selected + t }
                }
            }
            Spacer(Modifier.height(24.dp))
            CbmPrimaryButton("Save trades", {
                scope.launch { saving = true; app.api.setSkills(session.token, selected); saving = false; onDone() }
            }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && !saving)
        }
    }

    if (blocking) {
        Column(Modifier.fillMaxSize()) { CbmTopBar("First sign-in setup", session.active.site.code, session.expiresAtMillis, 0); body(Modifier) }
    } else {
        Scaffold(topBar = { CbmTopBar("My trades", session.active.site.code, session.expiresAtMillis, 0, onBack = onDone) }) { padding -> body(Modifier.padding(padding)) }
    }
}
