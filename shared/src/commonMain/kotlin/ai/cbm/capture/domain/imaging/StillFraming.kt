package ai.cbm.capture.domain.imaging

import kotlin.math.min

/**
 * Where a tap on the camera preview lands in the photograph, on the camera path used when AR is
 * not available.
 *
 * There the preview and the photograph are two streams of the same sensor, not one frame, so the
 * platform cannot hand back "the pixel under the finger" as ARCore does. What is known is how each
 * stream relates to the sensor: at no zoom, a stream is the largest centred crop of the sensor that
 * has the stream's own aspect ratio (the Camera2 rule for output streams), and the preview is shown
 * whole inside the view (fit centre, letterboxed). The tap is carried view -> preview -> sensor ->
 * photograph through those three facts and nothing else. With the usual 4:3 preview and 4:3 still
 * on a 4:3 sensor, every step but the letterbox is the identity.
 *
 * All of it happens in the upright (display) orientation; the caller turns the result back into
 * the photograph's buffer frame with [ImageTransform.unrotate], so that it enters
 * [ImageTransform.apply] together with K, like a tap from the AR path.
 */
object StillFraming {

    /**
     * @param viewWidth size of the view showing the preview, in the same units as the tap
     * @param previewWidth upright size of the preview stream
     * @param sensorLong longer side of the sensor array (its orientation follows the photograph)
     * @param stillWidth upright size of the photograph
     * @return the tapped pixel of the upright photograph, or `null` when the tap is on the
     *   letterbox or on a part of the preview the photograph does not contain
     */
    fun tapToUprightStill(
        tapX: Double,
        tapY: Double,
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        sensorLong: Int,
        sensorShort: Int,
        stillWidth: Int,
        stillHeight: Int
    ): PointF2? {
        if (listOf(viewWidth, viewHeight, previewWidth, previewHeight, sensorLong, sensorShort, stillWidth, stillHeight)
                .any { it <= 0 }
        ) return null

        // 1. View -> preview: the preview is scaled to fit and centred.
        val fit = min(viewWidth.toDouble() / previewWidth, viewHeight.toDouble() / previewHeight)
        val shownWidth = previewWidth * fit
        val shownHeight = previewHeight * fit
        val u = (tapX - (viewWidth - shownWidth) / 2) / shownWidth
        val v = (tapY - (viewHeight - shownHeight) / 2) / shownHeight
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return null

        // 2. Preview -> sensor. The sensor is upright the same way the photograph is.
        val sensorAspect = if (stillHeight > stillWidth) {
            sensorShort.toDouble() / sensorLong
        } else {
            sensorLong.toDouble() / sensorShort
        }
        val (us, vs) = fromCentredCrop(u, v, previewWidth.toDouble() / previewHeight, sensorAspect)

        // 3. Sensor -> photograph.
        val (up, vp) = toCentredCrop(us, vs, stillWidth.toDouble() / stillHeight, sensorAspect)
        if (up !in 0.0..1.0 || vp !in 0.0..1.0) return null

        return PointF2(up * stillWidth, vp * stillHeight)
    }

    /**
     * A point normalised (0..1) in the largest centred crop of aspect [cropAspect], expressed
     * normalised in the whole frame of aspect [frameAspect]. Aspects are width / height.
     */
    fun fromCentredCrop(u: Double, v: Double, cropAspect: Double, frameAspect: Double): Pair<Double, Double> =
        if (cropAspect >= frameAspect) {
            // The crop spans the full width and a band of the height.
            u to 0.5 + (v - 0.5) * frameAspect / cropAspect
        } else {
            0.5 + (u - 0.5) * cropAspect / frameAspect to v
        }

    /** The inverse of [fromCentredCrop]. */
    fun toCentredCrop(u: Double, v: Double, cropAspect: Double, frameAspect: Double): Pair<Double, Double> =
        if (cropAspect >= frameAspect) {
            u to 0.5 + (v - 0.5) * cropAspect / frameAspect
        } else {
            0.5 + (u - 0.5) * frameAspect / cropAspect to v
        }
}
