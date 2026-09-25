package ai.cbm.capture.ui.home

import ai.cbm.capture.data.local.OutboxStatus
import ai.cbm.capture.data.remote.AppApi
import ai.cbm.capture.data.remote.bearer
import ai.cbm.capture.data.remote.networkMessage
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.domain.ReporterStatus
import ai.cbm.capture.domain.model.CaptureContract
import ai.cbm.capture.domain.model.ReportSummary
import ai.cbm.capture.domain.repository.CaptureRepository
import ai.cbm.capture.domain.repository.ReportItem
import ai.cbm.capture.ui.reports.HomeItem
import ai.cbm.capture.ui.reports.ReporterHomeState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * The reporter's home: their reports as the server sees them, plus the photos still on the phone.
 */
@HiltViewModel
class ReporterHomeViewModel @Inject constructor(
    private val api: AppApi,
    private val repository: CaptureRepository,
    private val store: SessionStore
) : ViewModel() {

    private val server = MutableStateFlow<List<ReportSummary>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val session = store.current()
    private val local = session?.let { repository.observeReports(it.accountId) } ?: flowOf(emptyList())

    val state: StateFlow<ReporterHomeState> = combine(local, server, loading, error) { l, s, busy, err ->
        ReporterHomeState(
            siteName = session?.membership?.siteName.orEmpty(),
            siteCode = session?.membership?.siteId.orEmpty(),
            email = session?.email.orEmpty(),
            expiresAtMillis = session?.expiresAt?.toEpochMilli(),
            items = buildHomeItems(l, s, siteId = session?.membership?.siteId,
                siteNames = session?.memberships.orEmpty().associate { it.siteId to it.siteName }),
            loading = busy,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReporterHomeState(loading = true))

    init {
        refresh()
        // Each time a photo finishes uploading, the server has news about its report.
        viewModelScope.launch {
            local.map { rows -> rows.count { it.status == OutboxStatus.DELIVERED } }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        val s = store.current() ?: return
        viewModelScope.launch {
            loading.value = true
            try {
                val r = api.reports(bearer(s.token))
                when {
                    r.isSuccessful -> { server.value = r.body()?.reports.orEmpty(); error.value = null }
                    r.code() == 401 -> store.clear()   // the navigation returns to the login screen
                    else -> error.value = "Could not load your reports. Pull down to try again."
                }
            } catch (e: IOException) {
                error.value = networkMessage(e)
            } finally {
                loading.value = false
            }
        }
    }

    fun retry(captureId: String) = viewModelScope.launch { repository.retry(captureId) }

    /** Correct the text of a refused photo; [onQueued] runs once it is back in the queue. */
    fun editDescription(captureId: String, text: String, onQueued: () -> Unit) = viewModelScope.launch {
        if (repository.editDescription(captureId, text)) onQueued()
    }
    fun discard(captureId: String) = viewModelScope.launch { repository.delete(captureId) }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/**
 * Photos still on the phone first (waiting, sending, refused), then the server's reports, newest
 * first. A delivered photo disappears into its report once the server lists it.
 *
 * [siteId] is the session's site. A photo taken at another of the person's sites is sent only under
 * a session there, so while it waits it says where ([siteNames] names the sites); once sent, it
 * belongs to that site's list, not this one.
 */
internal fun buildHomeItems(
    local: List<ReportItem>,
    server: List<ReportSummary>,
    zone: ZoneId = ZoneId.systemDefault(),
    siteId: String? = null,
    siteNames: Map<String, String> = emptyMap()
): List<HomeItem> {
    val serverIds = server.map { it.reportId }.toSet()
    val pendingReports = local.filter { it.status == OutboxStatus.QUEUED || it.status == OutboxStatus.UPLOADING }.map { it.reportId }.toSet()
    val onPhone = local.filter { row ->
        if (siteId != null && row.siteId != siteId) row.status != OutboxStatus.DELIVERED
        else row.status != OutboxStatus.DELIVERED || row.reportId !in serverIds
    }.map { row ->
        val elsewhere = siteId != null && row.siteId != siteId
        HomeItem(
            key = "local-${row.captureId}",
            title = row.summary,
            status = when {
                elsewhere && row.status != OutboxStatus.REJECTED ->
                    "Saved on this phone — sent when you log in to ${siteNames[row.siteId] ?: "the site it was taken at"}"
                else -> when (row.status) {
                    OutboxStatus.QUEUED -> "Saved on this phone — waiting to send"
                    OutboxStatus.UPLOADING -> "Sending…"
                    OutboxStatus.REJECTED -> "Not accepted"
                    OutboxStatus.DELIVERED -> "Sent"
                }
            },
            detail = if (elsewhere && row.status != OutboxStatus.REJECTED) null else row.lastError,
            rejectedCapture = row.captureId.takeIf { row.status == OutboxStatus.REJECTED },
            editableDescription = (row.description ?: "").takeIf { row.status == OutboxStatus.REJECTED && refusedForItsText(row) }
        )
    }
    val reports = server.sortedByDescending { it.createdAt }.map { r ->
        val status = ReporterStatus.fromCode(r.status)
        val asksForPhoto = status.needsAction && r.attemptsLeft > 0 && r.reportId !in pendingReports
        HomeItem(
            key = "server-${r.reportId}",
            title = r.description ?: "Report of ${formatDate(r.createdAt, zone)}",
            status = status.label,
            detail = if (asksForPhoto) "${r.attemptsLeft} more photo${if (r.attemptsLeft == 1) "" else "s"} possible" else formatDate(r.createdAt, zone),
            photoNeededFor = r.reportId.takeIf { asksForPhoto },
            done = status.done
        )
    }
    return onPhone + reports
}

/**
 * Refused because of its text: the server said so (DESCRIPTION_TOO_LONG), or - from a server that
 * only answered "not valid" - the text is over the limit, which is the one thing wrong with it the
 * reporter can put right without a new photo.
 */
private fun refusedForItsText(row: ReportItem): Boolean =
    row.serverStatus == "DESCRIPTION_TOO_LONG" ||
        (row.description?.length ?: 0) > CaptureContract.DESCRIPTION_MAX_LENGTH

private fun formatDate(iso: String, zone: ZoneId): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DATE) }.getOrDefault(iso)
