package ai.cbm.capture.ui.settings

import ai.cbm.capture.data.remote.CaptureUploader
import ai.cbm.capture.data.settings.AppSettings
import ai.cbm.capture.data.settings.SettingsRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val uploader: CaptureUploader
) : ViewModel() {

    sealed interface TestResult {
        data class Success(val building: String?) : TestResult
        data class Failure(val message: String) : TestResult
    }

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun setBaseUrl(value: String) = viewModelScope.launch { repository.setBaseUrl(value) }
    fun setBuildingId(value: String) = viewModelScope.launch { repository.setBuildingId(value) }
    fun setReporterEmail(value: String) = viewModelScope.launch { repository.setReporterEmail(value) }
    fun setUploadOnMetered(value: Boolean) = viewModelScope.launch { repository.setUploadOnMetered(value) }

    fun setToken(value: String) = repository.setToken(value)
    fun clearToken() = repository.clearToken()

    fun test() = viewModelScope.launch {
        _isTesting.value = true
        _testResult.value = runCatching { uploader.checkHealth() }.fold(
            onSuccess = { health ->
                val expected = settings.value.buildingId
                if (health.buildingId != null && health.buildingId != expected) {
                    TestResult.Failure(
                        "Connected, but the server expects building ${health.buildingId} " +
                            "and this phone is set to $expected."
                    )
                } else {
                    TestResult.Success(health.buildingId)
                }
            },
            onFailure = { TestResult.Failure(it.message ?: "Could not reach the server.") }
        )
        _isTesting.value = false
    }
}

/**
 * One-time enrolment: endpoint, token, building, reporter.
 *
 * Meant to be filled in once by whoever hands the phone to the worker, then never opened again.
 * "Test connection" exists so misconfiguration is discovered here, in an office with signal,
 * rather than in a plant room with a queue of failed uploads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = { Text("Server address") },
                    placeholder = { Text("https://n8n.example.com/webhook") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                if (settings.isInsecureTransport) {
                    Text(
                        "This address is not encrypted. Photographs and the access token will be " +
                            "sent in the clear. Use https for anything beyond a local test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "The base address of the n8n webhook, without /cbm/capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    placeholder = {
                        Text(if (settings.hasToken) "Stored - enter a new one to replace" else "Access token")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Held in encrypted device storage. It is never written to a backup or a log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setToken(token); token = "" },
                        enabled = token.isNotBlank()
                    ) { Text("Save token") }
                    if (settings.hasToken) {
                        TextButton(onClick = { viewModel.clearToken(); token = "" }) { Text("Remove") }
                    }
                }
            }

            OutlinedTextField(
                value = settings.buildingId,
                onValueChange = viewModel::setBuildingId,
                label = { Text("Building ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = settings.reporterEmail,
                onValueChange = viewModel::setReporterEmail,
                label = { Text("Your email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Upload over mobile data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turn this off to hold reports until the phone is on Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.uploadOnMetered, onCheckedChange = viewModel::setUploadOnMetered)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.test() }, enabled = !isTesting) {
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Test connection")
                }
                when (val result = testResult) {
                    is SettingsViewModel.TestResult.Success -> Text(
                        result.building?.let { "Connected. Server is configured for $it." } ?: "Connected.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is SettingsViewModel.TestResult.Failure -> Text(
                        result.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    null -> Unit
                }
            }
        }
    }
}
