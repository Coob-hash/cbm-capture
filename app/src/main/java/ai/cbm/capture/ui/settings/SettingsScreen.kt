package ai.cbm.capture.ui.settings

import ai.cbm.capture.BuildConfig
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.data.settings.AppSettings
import ai.cbm.capture.data.settings.SettingsRepository
import ai.cbm.capture.ui.auth.roleLabel
import ai.cbm.capture.work.UploadWorker
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val sessions: SessionStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val session = sessions.current()

    /**
     * The preference applies to photos already waiting too: saving it alone left them waiting for
     * Wi-Fi after mobile data was allowed, because their send was queued for Wi-Fi.
     */
    fun setUploadOnMetered(value: Boolean) = viewModelScope.launch {
        repository.setUploadOnMetered(value)
        if (sessions.current() != null) UploadWorker.enqueueForPreference(context, value)
    }
}

/** Who is logged in, where the app sends reports, and the one upload preference. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = viewModel.session
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Text(s?.email ?: "—")
            Text(s?.membership?.let { "${roleLabel(it.role)} · ${it.siteName}" } ?: "—")
            Text(
                s?.let { "Session ends at " + it.expiresAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Uploads", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Send photos over mobile data", Modifier.weight(1f))
                Switch(checked = settings.uploadOnMetered, onCheckedChange = viewModel::setUploadOnMetered)
            }
            Text("Off: photos wait for Wi-Fi.", style = MaterialTheme.typography.bodySmall)
            Text("Connected to", style = MaterialTheme.typography.titleMedium)
            Text(BuildConfig.API_BASE_URL, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
        }
    }
}
