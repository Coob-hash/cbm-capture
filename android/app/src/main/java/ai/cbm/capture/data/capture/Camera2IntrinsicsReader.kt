package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.PinholeCamera
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Rational

/**
 * Fallback intrinsics for devices where ARCore is unavailable.
 *
 * `LENS_INTRINSIC_CALIBRATION` returns `[fx, fy, cx, cy, s]` in the coordinate system of the
 * **pre-correction active array**, which is generally neither the active array nor the JPEG the
 * app saves. Using it without that conversion is the mistake the calibration note's appendix
 * warns about, so the conversion is done here explicitly:
 *
 * ```
 * K_image = scale( K_preCorrectionArray , preCorrectionSize -> outputSize )
 * ```
 *
 * Two documented limitations, both of which the plausibility gate will catch if violated:
 *
 * - **Zoom is assumed absent.** Applying `SCALER_CROP_REGION` needs a live capture request, and
 *   this reader runs before one exists. The capture screen therefore does not offer zoom on the
 *   Camera2 path.
 * - **The output is assumed to cover the full array.** A capture configured with a different
 *   aspect ratio to the sensor is cropped, not letterboxed, which would shift the principal
 *   point. The app requests a sensor-aspect output size to avoid this.
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
     * the characteristic is optional and many shipping devices omit it.
     */
    fun read(cameraId: String, outputWidth: Int, outputHeight: Int): Reading? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null

        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            ?: return null
        if (calibration.size < 4) return null

        val preCorrection = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
        ) ?: return null
        if (preCorrection.width() <= 0 || preCorrection.height() <= 0) return null

        val sx = outputWidth.toDouble() / preCorrection.width()
        val sy = outputHeight.toDouble() / preCorrection.height()

        val camera = PinholeCamera(
            fx = calibration[0].toDouble() * sx,
            fy = calibration[1].toDouble() * sy,
            cx = calibration[2].toDouble() * sx,
            cy = calibration[3].toDouble() * sy,
            width = outputWidth,
            height = outputHeight
        )

        return Reading(
            camera = camera,
            skew = if (calibration.size >= 5) calibration[4].toDouble() * sx else 0.0,
            lens = describeLens(characteristics),
            // Only reported when the device advertises a distortion model. Carried, never applied.
            distortion = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                ?.map { it.toDouble() }
        )
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
