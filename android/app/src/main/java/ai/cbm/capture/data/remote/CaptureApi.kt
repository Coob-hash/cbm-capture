package ai.cbm.capture.data.remote

import ai.cbm.capture.domain.model.CaptureAcceptedResponse
import ai.cbm.capture.domain.model.CaptureHealthResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

/**
 * The n8n intake webhook. See `contract/openapi.yaml`.
 *
 * `@Url` takes an absolute address because the endpoint is configured per handset at enrolment
 * rather than baked into the build - one APK is handed to several sites.
 */
interface CaptureApi {

    @Multipart
    @POST
    suspend fun submitCapture(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("X-Capture-Id") captureId: String,
        @Header("X-Capture-Schema-Version") schemaVersion: String,
        @Part("metadata") metadata: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<CaptureAcceptedResponse>

    @GET
    suspend fun health(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<CaptureHealthResponse>
}
