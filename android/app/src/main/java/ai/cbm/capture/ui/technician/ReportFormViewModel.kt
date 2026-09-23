package ai.cbm.capture.ui.technician

import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.data.work.WorkRepository
import ai.cbm.capture.data.work.WorkResult
import ai.cbm.capture.domain.model.JobCard
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * The work report, written in the app.
 *
 * The app sends only what the technician wrote. The ticket, the asset, the location and who they
 * are come from the job itself, on the server, so a report can never claim to be about something
 * else — and the document is made from those fields by the office, with the same renderer the
 * browser form uses.
 */
@HiltViewModel
class ReportFormViewModel @Inject constructor(
    private val work: WorkRepository,
    private val store: SessionStore,
    private val json: Json,
    savedState: SavedStateHandle
) : ViewModel() {

    private val ticketId: Int = savedState.get<Int>(TICKET_ARG) ?: 0

    private val _state = MutableStateFlow(
        ReportFormState(
            ticketId = ticketId,
            siteCode = store.current()?.membership?.siteId.orEmpty(),
            expiresAtMillis = store.current()?.expiresAt?.toEpochMilli(),
            technician = store.current()?.email.orEmpty(),
            workDate = LocalDate.now().toString()
        )
    )
    val state: StateFlow<ReportFormState> = _state.asStateFlow()

    /** The photo the camera has just written, kept until the report is sent. */
    private var photo: File? = null

    init { loadJob() }

    private fun loadJob() {
        viewModelScope.launch {
            when (val result = work.technicianJobs()) {
                is WorkResult.Ok -> {
                    val job: JobCard? = result.value.current.firstOrNull { it.ticketId == ticketId }
                    _state.update {
                        it.copy(
                            asset = job?.location?.asset ?: "Not identified",
                            location = job?.location?.detail.orEmpty(),
                            reportedIssue = job?.description ?: "Not described",
                            technician = result.value.me.name ?: it.technician,
                            reworkReason = job?.work?.reworkReason
                        )
                    }
                }
                is WorkResult.Failed -> _state.update { it.copy(error = result.message) }
                else -> Unit
            }
        }
    }

    fun onWorkDate(v: String) = _state.update { it.copy(workDate = v, error = null) }
    fun onFindings(v: String) = _state.update { it.copy(findings = v, error = null) }
    fun onWorkPerformed(v: String) = _state.update { it.copy(workPerformed = v, error = null) }
    fun onMaterials(v: String) = _state.update { it.copy(materials = v) }
    fun onChecks(v: String) = _state.update { it.copy(checks = v, error = null) }
    fun onCheckResult(v: String) = _state.update { it.copy(checkResult = v) }
    fun onOutcome(v: String) = _state.update { it.copy(outcome = v) }
    fun onRemainingIssues(v: String) = _state.update { it.copy(remainingIssues = v, error = null) }
    fun onDeclaration(v: Boolean) = _state.update { it.copy(declaration = v) }
    fun onCaption(v: String) = _state.update { it.copy(photoCaption = v) }

    fun onPhotoTaken(file: File, uri: String) {
        photo?.takeIf { it != file }?.delete()
        photo = file
        _state.update { it.copy(photoUri = uri) }
    }

    fun onRemovePhoto() {
        photo?.delete()
        photo = null
        _state.update { it.copy(photoUri = null, photoCaption = "") }
    }

    fun send() {
        val s = _state.value
        if (!s.complete || s.sending || s.sent) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val result = work.submitReport(ticketId, fieldsJson(s), photo)) {
                is WorkResult.Ok -> {
                    photo?.delete()
                    photo = null
                    _state.update { it.copy(sending = false, sent = true) }
                }
                is WorkResult.Stale -> _state.update { it.copy(sending = false, error = result.message) }
                is WorkResult.Failed -> _state.update { it.copy(sending = false, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(sending = false) }
            }
        }
    }

    /** Only the technician's own answers travel; the rest is the server's to fill in. */
    private fun fieldsJson(s: ReportFormState): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "work_date" to JsonPrimitive(s.workDate.trim()),
                "findings" to JsonPrimitive(s.findings.trim()),
                "work_performed" to JsonPrimitive(s.workPerformed.trim()),
                "materials" to JsonPrimitive(s.materials.trim()),
                "checks" to JsonPrimitive(s.checks.trim()),
                "check_result" to JsonPrimitive(s.checkResult.orEmpty()),
                "outcome" to JsonPrimitive(s.outcome.orEmpty()),
                "remaining_issues" to JsonPrimitive(s.remainingIssues.trim()),
                "declaration" to JsonPrimitive(s.declaration),
            ) + if (s.photoUri != null) mapOf("photo_caption" to JsonPrimitive(s.photoCaption.trim())) else emptyMap()
        )
    )

    override fun onCleared() {
        photo?.delete()
        super.onCleared()
    }

    companion object {
        const val TICKET_ARG = "ticket"
    }
}
