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
 *
 * What the technician writes, the photo attached and the file the camera is writing into live in
 * [savedState] as well as in memory: Android may reclaim the app while the camera app is in front,
 * and the form must come back as it was, with the new photo on it.
 */
@HiltViewModel
class ReportFormViewModel @Inject constructor(
    private val work: WorkRepository,
    private val store: SessionStore,
    private val json: Json,
    private val savedState: SavedStateHandle
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

    init {
        restoreDraft()
        loadJob()
    }

    private fun restoreDraft() {
        val text = savedState.get<String>(DRAFT) ?: return
        val draft = runCatching { json.decodeFromString(ReportDraft.serializer(), text) }.getOrNull() ?: return
        photo = draft.photoPath?.let(::File)?.takeIf { it.isFile }
        _state.update { draft.applyTo(it, photoExists = photo != null) }
    }

    /** Every change the technician makes goes through here, so the saved draft is never behind. */
    private fun edit(change: (ReportFormState) -> ReportFormState) {
        _state.update(change)
        savedState[DRAFT] = json.encodeToString(ReportDraft.serializer(), ReportDraft.of(_state.value, photo?.path))
    }

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
                            reworkReason = job?.work?.reworkReason,
                            closedNotice = closedNotice(job)
                        )
                    }
                }
                is WorkResult.Failed -> _state.update { it.copy(error = result.message) }
                else -> Unit
            }
        }
    }

    /**
     * Why this form cannot be sent, when the server says there is no report to write: it has been
     * sent already this round (the job stays assigned until the office has taken it), or the job
     * is no longer this technician's. The job list says the same; this covers a form opened before.
     */
    private fun closedNotice(job: JobCard?): String? = when {
        job == null -> "This job is no longer in your list, so there is no report to write for it."
        job.reportNeeded -> null
        else -> "The report for this job has already been sent. If something needs changing, the " +
            "facility manager can send the job back to you."
    }

    fun onWorkDate(v: String) = edit { it.copy(workDate = v, error = null) }
    fun onFindings(v: String) = edit { it.copy(findings = v, error = null) }
    fun onWorkPerformed(v: String) = edit { it.copy(workPerformed = v, error = null) }
    fun onMaterials(v: String) = edit { it.copy(materials = v) }
    fun onChecks(v: String) = edit { it.copy(checks = v, error = null) }
    fun onCheckResult(v: String) = edit { it.copy(checkResult = v) }
    fun onOutcome(v: String) = edit { it.copy(outcome = v) }
    fun onRemainingIssues(v: String) = edit { it.copy(remainingIssues = v, error = null) }
    fun onDeclaration(v: Boolean) = edit { it.copy(declaration = v) }
    fun onCaption(v: String) = edit { it.copy(photoCaption = v) }

    /**
     * The camera app is about to write the photo into [file]. Remembered in the saved state, not
     * only in memory: if Android reclaims this app meanwhile, the answer arrives in a new one, which
     * must still know where the photo is.
     */
    fun onCameraOpening(file: File) {
        savedState[PENDING_PHOTO] = file.path
    }

    /** The camera app is back. [uriOf] turns the file into the address the form shows it from. */
    fun onCameraResult(ok: Boolean, uriOf: (File) -> String) {
        val file = savedState.get<String>(PENDING_PHOTO)?.let(::File) ?: return
        savedState.remove<String>(PENDING_PHOTO)
        if (ok && file.isFile && file.length() > 0) onPhotoTaken(file, uriOf(file)) else file.delete()
    }

    private fun onPhotoTaken(file: File, uri: String) {
        photo?.takeIf { it != file }?.delete()
        photo = file
        edit { it.copy(photoUri = uri, photoNotice = null) }
    }

    fun onRemovePhoto() {
        photo?.delete()
        photo = null
        edit { it.copy(photoUri = null, photoCaption = "", photoNotice = null) }
    }

    /** The camera could not be opened: permission refused, or no camera app. The report stays. */
    fun onCameraUnavailable(noCameraApp: Boolean) {
        savedState.get<String>(PENDING_PHOTO)?.let { File(it).delete() }
        savedState.remove<String>(PENDING_PHOTO)
        _state.update {
            it.copy(
                photoNotice = if (noCameraApp) {
                    "This phone has no camera app to take the photo. You can send the report without one."
                } else {
                    "The photo needs the camera. Allow it when the phone asks, or in the phone's settings " +
                        "for CBM Capture - or send the report without a photo."
                }
            )
        }
    }

    fun send() {
        val s = _state.value
        if (!s.complete || s.sending || s.sent || s.closedNotice != null) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val result = work.submitReport(ticketId, fieldsJson(s), photo)) {
                is WorkResult.Ok -> {
                    photo?.delete()
                    photo = null
                    savedState.remove<String>(DRAFT)
                    _state.update { it.copy(sending = false, sent = true) }
                }
                // Sent already, or the job moved on: say so, and read the job again so the form
                // stops offering to send.
                is WorkResult.Stale -> {
                    _state.update { it.copy(sending = false, error = result.message) }
                    loadJob()
                }
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
        // The form is closed for good (not reclaimed: then this is not called). Its files go with it.
        photo?.delete()
        savedState.get<String>(PENDING_PHOTO)?.let { File(it).delete() }
        super.onCleared()
    }

    companion object {
        const val TICKET_ARG = "ticket"
        private const val DRAFT = "report_draft"
        private const val PENDING_PHOTO = "report_pending_photo"
    }
}
