package ai.cbm.capture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `POST /cbm/capture`, mirroring `contract/capture-metadata.schema.json` and the
 * iOS `CaptureContract.swift` field for field.
 *
 * Inert data classes by design: the domain logic lives in [ai.cbm.capture.domain.imaging.ImageTransform]
 * and [ai.cbm.capture.domain.intrinsics.IntrinsicsGate], so a contract change is a diff in one file.
 */
object CaptureContract {
    const val SCHEMA_VERSION = "1.0.0"
}

@Serializable
enum class IntrinsicsSource {
    ARKIT, ARCORE, ANDROID_CAMERA2, EXIF, MANUAL_OVERRIDE;

    val displayName: String
        get() = when (this) {
            ARKIT -> "ARKit factory calibration"
            ARCORE -> "ARCore factory calibration"
            ANDROID_CAMERA2 -> "Camera2 factory calibration"
            EXIF -> "EXIF estimate"
            MANUAL_OVERRIDE -> "Manual override"
        }
}

@Serializable
enum class TrackingState { NORMAL, LIMITED, NOT_AVAILABLE }

@Serializable
data class CaptureMetadata(
    @SerialName("schema_version") val schemaVersion: String = CaptureContract.SCHEMA_VERSION,
    @SerialName("capture_id") val captureId: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("reporter_email") val reporterEmail: String? = null,
    val description: String? = null,
    /** RFC 3339, UTC, taken at shutter time - not at upload time. */
    @SerialName("captured_at") val capturedAt: String,
    val client: ClientInfo,
    val image: ImageDescriptor,
    val camera: CameraIntrinsics,
    val target: TargetDescriptor,
    val pose: PoseSample? = null
)

@Serializable
data class ClientInfo(
    val platform: String = "ANDROID",
    @SerialName("app_version") val appVersion: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_model") val deviceModel: String
)

@Serializable
data class ImageDescriptor(
    val width: Int,
    val height: Int,
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    val sha256: String,
    @SerialName("byte_length") val byteLength: Int,
    /** Quarter-turns clockwise already baked into the pixels. */
    @SerialName("orientation_applied") val orientationApplied: Int,
    @SerialName("source_width") val sourceWidth: Int? = null,
    @SerialName("source_height") val sourceHeight: Int? = null,
    val scale: Double? = null
)

@Serializable
data class CameraIntrinsics(
    val source: IntrinsicsSource,
    val trusted: Boolean,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double = 0.0,
    val width: Int,
    val height: Int,
    val lens: String? = null,
    val distortion: List<Double>? = null
)

@Serializable
data class TargetDescriptor(
    val pixel: PixelPoint,
    val source: String = "USER_TAP",
    val centrality: Double? = null
)

@Serializable
data class PixelPoint(val x: Double, val y: Double)

@Serializable
data class PoseSample(
    val source: IntrinsicsSource,
    val position: Vector3,
    val rotation: QuaternionValue,
    @SerialName("tracking_state") val trackingState: TrackingState,
    @SerialName("coordinate_space") val coordinateSpace: String = "AR_SESSION_WORLD"
)

@Serializable
data class Vector3(val x: Double, val y: Double, val z: Double)

@Serializable
data class QuaternionValue(val x: Double, val y: Double, val z: Double, val w: Double)

// MARK: - Responses

@Serializable
data class CaptureAcceptedResponse(
    val ok: Boolean = true,
    @SerialName("capture_id") val captureId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    val duplicate: Boolean = false
)

@Serializable
data class CaptureErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val detail: String? = null
)

@Serializable
data class CaptureHealthResponse(
    val ok: Boolean,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("schema_version") val schemaVersion: String? = null
)
