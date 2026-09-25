package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.intrinsics.Camera2Calibration
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Rational
import android.util.Size

/**
 * Fallback intrinsics for devices where ARCore is unavailable.
 *
 * `LENS_INTRINSIC_CALIBRATION` returns `[fx, fy, cx, cy, s]` in the coordinate system of the
 * **pre-correction active array**, which is generally neither the active array nor the JPEG the
 * app saves. Using it without that conversion is the mistake the calibration note's appendix
 * warns about, so the conversion is done explicitly, by [Camera2Calibration.toImage]:
 *
 * ```
 * K_image = scale( crop( K_preCorrectionArray, centred crop of the output's aspect ), outputSize )
 * ```
 *
 * Two documented limitations, both of which the plausibility gate will catch if violated:
 *
 * - **Zoom is assumed absent.** Applying a non-default `SCALER_CROP_REGION` needs the capture
 *   result, and the capture screen does not offer zoom on this path, so the crop region stays the
 *   whole array.
 * - **The output is assumed to be in the array's orientation.** A camera that rotates the JPEG
 *   itself gets no Camera2 K; the photo's own EXIF is used instead.
 */
class Camera2IntrinsicsReader(private val context: Context) {

    data class Reading(
        val camera: PinholeCamera,
        val skew: Double,
        val lens: String?,
        val distortion: List<Double>?
    )

    /**
     * Read and convert the calibration for [cameraId], expressed for an output image of
     * [outputWidth] x [outputHeight]. Returns `null` when the device does not publish one -
     * the characteristic is optional and many shipping devices omit it - or when the output is
     * not in the array's orientation.
     */
    fun read(cameraId: String, outputWidth: Int, outputHeight: Int): Reading? {
        val characteristics = characteristics(cameraId) ?: return null

        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            ?: return null
        if (calibration.size < 4 || calibration.take(4).all { it == 0f }) return null

        val preCorrection = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
        ) ?: return null

        val camera = Camera2Calibration.toImage(
            fx = calibration[0].toDouble(),
            fy = calibration[1].toDouble(),
            cx = calibration[2].toDouble(),
            cy = calibration[3].toDouble(),
            arrayWidth = preCorrection.width(),
            arrayHeight = preCorrection.height(),
            outputWidth = outputWidth,
            outputHeight = outputHeight
        ) ?: return null

        return Reading(
            camera = camera,
            // Scaled with the x axis, like fx; carried for completeness, the ray math ignores it.
            skew = if (calibration.size >= 5 && calibration[0] != 0f) {
                calibration[4].toDouble() * camera.fx / calibration[0]
            } else 0.0,
            lens = describeLens(characteristics),
            distortion = distortionOf(characteristics)
        )
    }

    /**
     * Only reported when the device advertises a distortion model. Carried, never applied.
     * LENS_DISTORTION exists from Android 9 (API 28); on Android 8 the field itself is missing and
     * touching it throws NoSuchFieldError, which no `catch (Exception)` sees - so it is not touched.
     */
    private fun distortionOf(characteristics: CameraCharacteristics): List<Double>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.get(CameraCharacteristics.LENS_DISTORTION)?.map { it.toDouble() }
        } else {
            null
        }

    /** The sensor's active array, which every output stream of this camera is a crop of. */
    fun sensorArraySize(cameraId: String): Size? {
        val characteristics = characteristics(cameraId) ?: return null
        val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: return null
        return if (array.width() > 0 && array.height() > 0) Size(array.width(), array.height()) else null
    }

    /** The rear camera with the widest field of view is the one ARCore would have used. */
    fun defaultRearCameraId(): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
        }.getOrNull()
    }

    private fun characteristics(cameraId: String): CameraCharacteristics? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
    }

    private fun describeLens(characteristics: CameraCharacteristics): String? {
        val focalLengths = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        ) ?: return null
        val focal = focalLengths.firstOrNull() ?: return null
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        // 35 mm equivalent, used only for a human-readable label.
        val equivalent = Rational((focal * 36f / sensorSize.width).toInt(), 1)
        return when {
            equivalent.toDouble() < 20 -> "ultrawide"
            equivalent.toDouble() > 50 -> "telephoto"
            else -> "wide"
        }
    }
}
