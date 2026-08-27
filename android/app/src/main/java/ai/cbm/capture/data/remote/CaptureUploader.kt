package ai.cbm.capture.data.remote

import ai.cbm.capture.data.settings.SettingsRepository
import ai.cbm.capture.domain.model.CaptureContract
import ai.cbm.capture.domain.model.CaptureErrorResponse
import ai.cbm.capture.domain.model.CaptureHealthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one upload attempt, in the only two categories the outbox cares about. */
sealed interface UploadOutcome {
    data class Delivered(val requestId: String?, val status: String?, val duplicate: Boolean) : UploadOutcome
    /** The server will never accept this package. Stop retrying and tell the worker. */
    data class PermanentFailure(val reason: String) : UploadOutcome
    /** Worth trying again later. */
    data class TransientFailure(val reason: String) : UploadOutcome
}

class NotConfiguredException : IllegalStateException("Set the server address and access token in Settings first.")
class UnauthorizedException : IllegalStateException("The access token was rejected.")

/**
 * Sends capture packages to n8n.
 *
 * Every failure is classified transient or permanent before it reaches the outbox, and that
 * classification is this class's entire contract: retrying a 422 forever keeps a broken capture
 * in the queue indefinitely, and giving up on a 503 loses a good one.
 */
@Singleton
class CaptureUploader @Inject constructor(
    private val api: CaptureApi,
    private val settings: SettingsRepository,
    private val json: Json
) {

    suspend fun upload(captureId: String, metadataJson: String, imageFile: File): UploadOutcome =
        withContext(Dispatchers.IO) {
            val baseUrl = currentBaseUrl() ?: return@withContext UploadOutcome.PermanentFailure(
                NotConfiguredException().message!!
            )
            val token = settings.token() ?: return@withContext UploadOutcome.PermanentFailure(
                NotConfiguredException().message!!
            )
            if (!imageFile.exists()) {
                return@withContext UploadOutcome.PermanentFailure("The stored image for this report is missing.")
            }

            val metadataPart = metadataJson.toRequestBody("application/json".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "image",
                "$captureId.jpg",
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )

            try {
                val response = api.submitCapture(
                    url = "$baseUrl/cbm/capture",
                    authorization = "Bearer $token",
                    captureId = captureId,
                    schemaVersion = CaptureContract.SCHEMA_VERSION,
                    metadata = metadataPart,
                    image = imagePart
                )
                classify(response.code(), response.body(), response.errorBody()?.string())
            } catch (e: IOException) {
                // Offline, timed out, or the connection dropped mid-body: all worth retrying.
                UploadOutcome.TransientFailure(e.message ?: "The network is unavailable.")
            } catch (e: Exception) {
                UploadOutcome.TransientFailure(e.message ?: "The upload failed.")
            }
        }

    suspend fun checkHealth(): CaptureHealthResponse = withContext(Dispatchers.IO) {
        val baseUrl = currentBaseUrl() ?: throw NotConfiguredException()
        val token = settings.token() ?: throw NotConfiguredException()

        val response = api.health("$baseUrl/cbm/capture/health", "Bearer $token")
        when {
            response.isSuccessful -> response.body() ?: CaptureHealthResponse(ok = true)
            response.code() == 401 || response.code() == 403 -> throw UnauthorizedException()
            else -> throw IOException("The server returned HTTP ${response.code()}.")
        }
    }

    private fun classify(
        code: Int,
        body: ai.cbm.capture.domain.model.CaptureAcceptedResponse?,
        errorBody: String?
    ): UploadOutcome = when (code) {
        in 200..299 -> UploadOutcome.Delivered(
            requestId = body?.requestId,
            status = body?.status,
            duplicate = body?.duplicate ?: false
        )

        401, 403 -> UploadOutcome.PermanentFailure("The access token was rejected. Re-enter it in Settings.")

        // Already staged under this capture_id: the first attempt got through and the response
        // was lost. That is success, not a conflict to resolve.
        409 -> UploadOutcome.Delivered(requestId = null, status = null, duplicate = true)

        413 -> UploadOutcome.PermanentFailure("The image is larger than the server accepts.")

        400, 422 -> UploadOutcome.PermanentFailure(messageForServerError(errorBody))

        408, 429, in 500..599 -> UploadOutcome.TransientFailure("The server is unavailable (HTTP $code).")

        else -> UploadOutcome.TransientFailure("Unexpected server response (HTTP $code).")
    }

    private fun messageForServerError(errorBody: String?): String {
        val code = errorBody
            ?.let { runCatching { json.decodeFromString<CaptureErrorResponse>(it) }.getOrNull() }
            ?.error
        return when (code) {
            "UNTRUSTED_INTRINSICS" ->
                "The camera calibration was not trusted, so this photo was sent for manual location instead."
            "FRAME_MISMATCH" -> "The photo and its calibration did not match. Please retake it."
            "CHECKSUM_MISMATCH" -> "The image was corrupted in transit and could not be recovered."
            "UNSUPPORTED_SCHEMA_VERSION" -> "This app version is too old for the server. Please update."
            null -> "The server rejected this report."
            else -> "The server rejected this report ($code)."
        }
    }

    private suspend fun currentBaseUrl(): String? =
        settings.settings.first().baseUrl.trimEnd('/').ifBlank { null }
}
