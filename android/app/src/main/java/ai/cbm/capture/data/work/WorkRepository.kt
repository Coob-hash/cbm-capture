package ai.cbm.capture.data.work

import ai.cbm.capture.data.remote.AppApi
import ai.cbm.capture.data.remote.NETWORK_MESSAGE
import ai.cbm.capture.data.remote.apiError
import ai.cbm.capture.data.remote.bearer
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.domain.model.DecisionRequest
import ai.cbm.capture.domain.model.DecisionResponse
import ai.cbm.capture.domain.model.FmAction
import ai.cbm.capture.domain.model.FmCard
import ai.cbm.capture.domain.model.FmQueueResponse
import ai.cbm.capture.domain.model.OfferAnswerRequest
import ai.cbm.capture.domain.model.OfferAnswerResponse
import ai.cbm.capture.domain.model.ReportLinkResponse
import ai.cbm.capture.domain.model.ReportSentResponse
import ai.cbm.capture.domain.model.SkillsRequest
import ai.cbm.capture.domain.model.SkillsResponse
import ai.cbm.capture.domain.model.TechnicianJobsResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a call to the App API can end as, in terms a screen can show. */
sealed interface WorkResult<out T> {
    data class Ok<T>(val value: T) : WorkResult<T>
    /** The ticket or the offer moved on: reload and show what it is now. */
    data class Stale(val message: String) : WorkResult<Nothing>
    data class Failed(val message: String) : WorkResult<Nothing>
    /** The hour is over; the session was cleared and the app returns to the login screen. */
    data object LoggedOut : WorkResult<Nothing>
}

/**
 * The facility manager's decisions and the technician's jobs.
 *
 * Every call here is one endpoint of the App API, which in turn calls the workflows' own guarded
 * functions: this app never decides anything by itself, it only carries the person's decision.
 */
@Singleton
class WorkRepository @Inject constructor(
    private val api: AppApi,
    private val store: SessionStore,
    private val json: Json
) {

    suspend fun fmQueue(): WorkResult<FmQueueResponse> = call { api.fmQueue(bearer(it)) }

    /**
     * One decision. [card] carries the approval cycle and the revision it was drawn from, so the
     * workflows refuse a decision on a ticket that changed meanwhile. [requestId] makes a repeat
     * of the same tap harmless.
     */
    suspend fun decide(
        card: FmCard,
        action: FmAction,
        reason: String?,
        requestId: String = UUID.randomUUID().toString().take(24)
    ): WorkResult<DecisionResponse> {
        val approval = card.action.approvalId
        val revision = card.action.expectedUpdatedAt
        if (approval == null || revision == null) return WorkResult.Stale(MOVED_ON)
        return call {
            api.fmDecide(
                bearer(it),
                DecisionRequest(
                    ticketId = card.ticketId,
                    action = action.wire,
                    reason = reason?.trim()?.ifEmpty { null },
                    approvalId = approval,
                    expectedUpdatedAt = revision,
                    requestId = requestId
                )
            )
        }
    }

    suspend fun technicianJobs(): WorkResult<TechnicianJobsResponse> = call { api.technicianJobs(bearer(it)) }

    suspend fun answerOffer(ticketId: Int, offerId: String, accept: Boolean): WorkResult<OfferAnswerResponse> =
        call { api.answerOffer(bearer(it), OfferAnswerRequest(ticketId, offerId, if (accept) "accept" else "deny")) }

    suspend fun setSkills(skills: List<String>): WorkResult<SkillsResponse> =
        call { api.setSkills(bearer(it), SkillsRequest(skills)) }

    suspend fun reportLink(ticketId: Int): WorkResult<ReportLinkResponse> = call { api.reportLink(bearer(it), ticketId) }

    /**
     * The work report. The app sends the fields and, if there is one, the photo: the document
     * itself is made by the office from exactly these fields, so both routes produce one report.
     */
    suspend fun submitReport(ticketId: Int, fieldsJson: String, photo: File?): WorkResult<ReportSentResponse> =
        call { token ->
            val part = photo?.let {
                MultipartBody.Part.createFormData("photo", "after.jpg", it.asRequestBody("image/jpeg".toMediaType()))
            }
            api.submitReport(bearer(token), ticketId, fieldsJson.toRequestBody("application/json".toMediaType()), part)
        }

    private suspend fun <T> call(block: suspend (token: String) -> Response<T>): WorkResult<T> {
        val session = store.current() ?: return WorkResult.LoggedOut
        return try {
            val response = block(session.token)
            val body = response.body()
            when {
                response.isSuccessful && body != null -> WorkResult.Ok(body)
                response.isSuccessful -> WorkResult.Failed(UNEXPECTED)
                response.code() == 401 -> { store.clear(); WorkResult.LoggedOut }
                // 409: decided elsewhere, or the offer expired. 404: not this person's to act on.
                response.code() == 409 || response.code() == 404 ->
                    WorkResult.Stale(response.apiError(json).message ?: MOVED_ON)
                else -> WorkResult.Failed(response.apiError(json).message ?: "Something went wrong. Try again in a moment.")
            }
        } catch (e: IOException) {
            WorkResult.Failed(NETWORK_MESSAGE)
        }
    }

    private companion object {
        const val MOVED_ON = "Someone got there first. Showing you where it stands now."
        const val UNEXPECTED = "Something went wrong. Try again in a moment."
    }
}
