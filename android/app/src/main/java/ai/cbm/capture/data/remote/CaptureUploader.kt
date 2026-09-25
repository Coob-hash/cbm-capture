package ai.cbm.capture.data.remote

import kotlinx.coroutines.Dispatchers
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

/** Outcome of one upload attempt, in the categories the outbox acts on. */
sealed interface UploadOutcome {
    data class Delivered(val status: String?) : UploadOutcome
    /** The server will never accept this photo as it is. Stop retrying and tell the reporter. */
    data class PermanentFailure(val reason: String, val code: String? = null) : UploadOutcome
    /** Worth trying again later. */
    data class TransientFailure(val reason: String) : UploadOutcome
    /** The session ended. Keep the photo queued; it goes out after the same person logs in again. */
    data object NeedsLogin : UploadOutcome
}

/**
 * Sends a stored capture to `POST /v1/captures`.
 *
 * Every response is classified before it reaches the outbox, and that classification is this
 * class's whole contract: retrying a 422 forever keeps a broken photo in the queue, giving up on a
 * 503 loses a good one, and treating an expired session as a rejection would lose a report that
 * only needs its owner to log in again.
 */
@Singleton
class CaptureUploader @Inject constructor(
    private val api: AppApi,
    private val json: Json
) {

    suspend fun upload(token: String, captureId: String, metadataJson: String, imageFile: File): UploadOutcome =
        withContext(Dispatchers.IO) {
            if (!imageFile.exists()) return@withContext UploadOutcome.PermanentFailure("The stored photo is missing.")
            val metadata = metadataJson.toRequestBody("application/json".toMediaType())
            val image = MultipartBody.Part.createFormData("image", "$captureId.jpg", imageFile.asRequestBody("image/jpeg".toMediaType()))
            try {
                val r = api.submitCapture(bearer(token), metadata, image)
                when (r.code()) {
                    200, 201, 202 -> UploadOutcome.Delivered(r.body()?.status)
                    401 -> UploadOutcome.NeedsLogin
                    409 -> UploadOutcome.PermanentFailure(r.apiError(json).message ?: "This photo was already sent differently.")
                    413 -> UploadOutcome.PermanentFailure("The photo is larger than the server accepts.")
                    400, 422 -> r.apiError(json).let { UploadOutcome.PermanentFailure(rejection(it.error, it.message), it.error) }
                    else -> UploadOutcome.TransientFailure("The server is unavailable (HTTP ${r.code()}).")
                }
            } catch (e: IOException) {
                // Offline, timed out, or dropped mid-body: all worth retrying.
                UploadOutcome.TransientFailure(NETWORK_MESSAGE)
            }
        }

    private fun rejection(code: String, message: String?): String = when (code) {
        "FRAME_MISMATCH", "INVALID_CAPTURE" -> "The photo and its camera data did not match. Please take it again."
        "CHECKSUM_MISMATCH" -> "The photo was damaged on the way. Please take it again."
        "SITE_MISMATCH" -> "This photo belongs to another site than your account."
        "NOT_A_JPEG" -> "The photo could not be read. Please take it again."
        "DESCRIPTION_TOO_LONG" -> "The description is longer than 500 characters. Edit it and send it again."
        else -> message ?: "The server did not accept this photo ($code)."
    }
}
