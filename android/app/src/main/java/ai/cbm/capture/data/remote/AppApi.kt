package ai.cbm.capture.data.remote

import ai.cbm.capture.domain.model.ApiError
import ai.cbm.capture.domain.model.CaptureStoredResponse
import ai.cbm.capture.domain.model.LoginRequest
import ai.cbm.capture.domain.model.MeResponse
import ai.cbm.capture.domain.model.ReportsResponse
import ai.cbm.capture.domain.model.SelectRoleRequest
import ai.cbm.capture.domain.model.SelectRoleResponse
import ai.cbm.capture.domain.model.SessionResponse
import ai.cbm.capture.domain.model.SignUpRequest
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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
