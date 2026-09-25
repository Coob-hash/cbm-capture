package ai.cbm.capture.data.remote

import ai.cbm.capture.domain.model.ApiError
import ai.cbm.capture.domain.model.CaptureStoredResponse
import ai.cbm.capture.domain.model.DecisionRequest
import ai.cbm.capture.domain.model.DecisionResponse
import ai.cbm.capture.domain.model.FmQueueResponse
import ai.cbm.capture.domain.model.OfferAnswerRequest
import ai.cbm.capture.domain.model.OfferAnswerResponse
import ai.cbm.capture.domain.model.ReportLinkResponse
import ai.cbm.capture.domain.model.ReportSentResponse
import ai.cbm.capture.domain.model.SkillsRequest
import ai.cbm.capture.domain.model.SkillsResponse
import ai.cbm.capture.domain.model.TechnicianJobsResponse
import ai.cbm.capture.domain.model.LoginRequest
import ai.cbm.capture.domain.model.MeResponse
import ai.cbm.capture.domain.model.ReportsResponse
import ai.cbm.capture.domain.model.SelectRoleRequest
import ai.cbm.capture.domain.model.SelectRoleResponse
import ai.cbm.capture.domain.model.SessionResponse
import ai.cbm.capture.domain.model.SignUpRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.io.IOException
import java.lang.reflect.Type

/** The App API (backend/README.md). The phone talks to nothing else. */
interface AppApi {
    @POST("v1/auth/signup")
    suspend fun signUp(@Body body: SignUpRequest): Response<SessionResponse>

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<SessionResponse>

    @POST("v1/auth/role")
    suspend fun selectRole(@Header("Authorization") bearer: String, @Body body: SelectRoleRequest): Response<SelectRoleResponse>

    @GET("v1/me")
    suspend fun me(@Header("Authorization") bearer: String): Response<MeResponse>

    @POST("v1/auth/logout")
    suspend fun logout(@Header("Authorization") bearer: String): Response<Unit>

    @GET("v1/reports")
    suspend fun reports(@Header("Authorization") bearer: String): Response<ReportsResponse>

    // ---- The facility manager's decisions (backend/migrations/003_decisions.sql) ----------------

    @GET("v1/fm/queue")
    suspend fun fmQueue(@Header("Authorization") bearer: String): Response<FmQueueResponse>

    /** Approve or reject, through the workflows' own guarded action. 409 = the ticket moved on. */
    @POST("v1/fm/decisions")
    suspend fun fmDecide(@Header("Authorization") bearer: String, @Body body: DecisionRequest): Response<DecisionResponse>

    // ---- The technician's jobs -------------------------------------------------------------------

    @GET("v1/technician/jobs")
    suspend fun technicianJobs(@Header("Authorization") bearer: String): Response<TechnicianJobsResponse>

    /** Accept or decline an offer: the same record the offer email's link writes. */
    @POST("v1/technician/offers")
    suspend fun answerOffer(@Header("Authorization") bearer: String, @Body body: OfferAnswerRequest): Response<OfferAnswerResponse>

    @POST("v1/technician/skills")
    suspend fun setSkills(@Header("Authorization") bearer: String, @Body body: SkillsRequest): Response<SkillsResponse>

    /** The work report: the template's fields, and the AFTER photo when the technician took one. */
    @Multipart
    @POST("v1/technician/jobs/{ticketId}/report")
    suspend fun submitReport(
        @Header("Authorization") bearer: String,
        @Path("ticketId") ticketId: Int,
        @Part("report") report: RequestBody,
        @Part photo: MultipartBody.Part?
    ): Response<ReportSentResponse>

    @GET("v1/technician/jobs/{ticketId}/report-link")
    suspend fun reportLink(@Header("Authorization") bearer: String, @Path("ticketId") ticketId: Int): Response<ReportLinkResponse>

    @Multipart
    @POST("v1/captures")
    suspend fun submitCapture(
        @Header("Authorization") bearer: String,
        @Part("metadata") metadata: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<CaptureStoredResponse>
}

fun bearer(token: String) = "Bearer $token"

/** The API's error body ({"error","message"}), or a stand-in when there is none. */
fun Response<*>.apiError(json: Json): ApiError =
    errorBody()?.string()?.let { runCatching { json.decodeFromString(ApiError.serializer(), it) }.getOrNull() }
        ?: ApiError(error = "HTTP_${code()}", message = null)

const val NETWORK_MESSAGE = "Can't reach the server. Check your connection and try again."
const val UNREADABLE_MESSAGE =
    "The server's answer could not be read. If this Wi-Fi asks you to sign in, do that first, then try again."

/** What a screen says when a call ended without an answer it could use. */
fun networkMessage(e: IOException): String = if (e is UnreadableResponseException) UNREADABLE_MESSAGE else NETWORK_MESSAGE

/**
 * A success whose body is not the API's JSON: a Wi-Fi sign-in page, a proxy's error page, a body
 * cut short. Retrofit decodes it before any caller sees the response, and the decoder's exception
 * reached no handler: the app closed (third audit 2026-09-25, finding 3). As an [IOException] it is
 * what every caller already handles as "no usable answer": said on the screen, and retried.
 */
class UnreadableResponseException(cause: Throwable) : IOException("The server's answer could not be read", cause)

/** The App API's converter: its JSON, with an unreadable body turned into [UnreadableResponseException]. */
fun Json.appConverterFactory(): Converter.Factory = ReadableResponses(asConverterFactory("application/json".toMediaType()))

private class ReadableResponses(private val json: Converter.Factory) : Converter.Factory() {
    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? {
        val decode = json.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            try {
                decode.convert(body)
            } catch (e: IOException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // kotlinx.serialization's SerializationException (an IllegalArgumentException) and kin.
                throw UnreadableResponseException(e)
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? = json.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
}
