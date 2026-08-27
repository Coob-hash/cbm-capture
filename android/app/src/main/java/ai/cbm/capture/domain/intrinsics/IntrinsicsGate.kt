package ai.cbm.capture.domain.intrinsics

import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.model.CameraIntrinsics
import ai.cbm.capture.domain.model.IntrinsicsSource
import kotlin.math.abs
import kotlin.math.max

/**
 * Plausibility check applied to the *final* transmitted-frame intrinsics.
 *
 * Kotlin twin of `ios/CBMCapture/Core/Intrinsics/IntrinsicsGate.swift`, implementing section 3
 * of `docs/INTRINSICS.md`. The contract is narrow and important: it decides whether K is
 * trustworthy, and it never repairs or substitutes a value. A silently substituted fallback is
 * what produces confidently wrong GlobalIds.
 */
object IntrinsicsGate {

    enum class Rejection(val explanation: String) {
        FOCAL_OUT_OF_RANGE("The focal length is outside the range any phone lens produces for this image size."),
        PRINCIPAL_POINT_OFF_CENTRE("The principal point is far from the image centre, which usually means the calibration describes a different frame than the photo."),
        NON_SQUARE_PIXELS("Horizontal and vertical focal lengths disagree by more than 5%."),
        DEGENERATE_FRAME("The image dimensions are invalid."),
        NON_FINITE("The calibration contains a non-finite value.")
    }

    data class Verdict(val trusted: Boolean, val rejections: List<Rejection>)

    // `fx` between 0.5W and 2.5W spans roughly a 23-90 degree horizontal field of view, which
    // brackets every phone lens from ultra-wide to 3x telephoto with margin.
    private const val MIN_FOCAL_RATIO = 0.5
    private const val MAX_FOCAL_RATIO = 2.5
    private const val MIN_PRINCIPAL_RATIO = 0.30
    private const val MAX_PRINCIPAL_RATIO = 0.70
    private const val MAX_ASPECT_DISAGREEMENT = 0.05

    fun evaluate(camera: PinholeCamera): Verdict {
        if (camera.width <= 0 || camera.height <= 0) {
            return Verdict(false, listOf(Rejection.DEGENERATE_FRAME))
        }
        val values = listOf(camera.fx, camera.fy, camera.cx, camera.cy)
        if (values.any { it.isNaN() || it.isInfinite() }) {
            return Verdict(false, listOf(Rejection.NON_FINITE))
        }

        val rejections = mutableListOf<Rejection>()
        val w = camera.width.toDouble()
        val h = camera.height.toDouble()

        if (camera.fx < MIN_FOCAL_RATIO * w || camera.fx > MAX_FOCAL_RATIO * w ||
            camera.fy < MIN_FOCAL_RATIO * h || camera.fy > MAX_FOCAL_RATIO * h
        ) {
            rejections += Rejection.FOCAL_OUT_OF_RANGE
        }

        if (camera.cx < MIN_PRINCIPAL_RATIO * w || camera.cx > MAX_PRINCIPAL_RATIO * w ||
            camera.cy < MIN_PRINCIPAL_RATIO * h || camera.cy > MAX_PRINCIPAL_RATIO * h
        ) {
            rejections += Rejection.PRINCIPAL_POINT_OFF_CENTRE
        }

        val largest = max(camera.fx, camera.fy)
        if (largest > 0 && abs(camera.fx - camera.fy) / largest > MAX_ASPECT_DISAGREEMENT) {
            rejections += Rejection.NON_SQUARE_PIXELS
        }

        return Verdict(rejections.isEmpty(), rejections)
    }

    /**
     * Assemble the wire type, stamping the verdict onto it.
     *
     * `MANUAL_OVERRIDE` is forced untrusted regardless of the numbers: a hand-entered K is a
     * debugging aid, and letting it look authoritative defeats the audit trail.
     */
    fun intrinsics(
        camera: PinholeCamera,
        source: IntrinsicsSource,
        skew: Double = 0.0,
        lens: String? = null,
        distortion: List<Double>? = null
    ): CameraIntrinsics {
        val verdict = evaluate(camera)
        return CameraIntrinsics(
            source = source,
            trusted = verdict.trusted && source != IntrinsicsSource.MANUAL_OVERRIDE,
            fx = camera.fx,
            fy = camera.fy,
            cx = camera.cx,
            cy = camera.cy,
            skew = skew,
            width = camera.width,
            height = camera.height,
            lens = lens,
            distortion = distortion
        )
    }

    /**
     * EXIF fallback, section 4 of the calibration note: `fx = f35 * W / 36`, square pixels,
     * centred principal point. 36 mm is the full-frame sensor width that the 35 mm-equivalent
     * focal length is defined against.
     */
    fun fromExif(focalLengthIn35mmFilm: Double, width: Int, height: Int): PinholeCamera? {
        if (focalLengthIn35mmFilm <= 0 || width <= 0 || height <= 0) return null
        val fx = focalLengthIn35mmFilm * width / 36.0
        return PinholeCamera(fx, fx, width / 2.0, height / 2.0, width, height)
    }
}
