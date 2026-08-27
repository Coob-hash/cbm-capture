package ai.cbm.capture.ui.reports

import ai.cbm.capture.data.local.OutboxStatus
import ai.cbm.capture.domain.repository.CaptureRepository
import ai.cbm.capture.domain.repository.ReportItem
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: CaptureRepository
) : ViewModel() {

    val reports: StateFlow<List<ReportItem>> = repository.observeReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(captureId: String) = viewModelScope.launch { repository.retry(captureId) }
    fun delete(captureId: String) = viewModelScope.launch { repository.delete(captureId) }
    suspend fun allowMetered(): Boolean = repository.uploadOnMeteredAllowed()
}

/**
 * History and queue in one list.
 *
 * The worker's question is always "did my report get through?", so status is the most prominent
 * thing on each row, and a rejected upload offers a retry rather than hiding behind a log line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendAll: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Send all now") },
                            onClick = { menuOpen = false; onSendAll() }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (reports.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Photos you take will appear here until the office has received them.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(reports, key = { it.captureId }) { item ->
                    ReportRow(
                        item = item,
                        onRetry = { viewModel.retry(item.captureId) },
                        onDelete = { viewModel.delete(item.captureId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReportRow(item: ReportItem, onRetry: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Thumbnail(item.thumbnailPath)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.summary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(item.status)
                if (!item.intrinsicsTrusted) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Manual location") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }
            }
            item.lastError?.takeIf { item.status != OutboxStatus.DELIVERED }?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.status == OutboxStatus.REJECTED) {
                Row {
                    TextButton(onClick = onRetry) { Text("Try again") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?) {
    val context = LocalContext.current
    val bitmap = remember(path) {
        path?.let { File(it) }?.takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {}
        }
    }
}

@Composable
private fun StatusChip(status: OutboxStatus) {
    val tint = when (status) {
        OutboxStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        OutboxStatus.UPLOADING -> MaterialTheme.colorScheme.primary
        OutboxStatus.DELIVERED -> Color(0xFF2E7D32)
        OutboxStatus.REJECTED -> MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(status.displayName) },
        colors = AssistChipDefaults.assistChipColors(labelColor = tint)
    )
}
