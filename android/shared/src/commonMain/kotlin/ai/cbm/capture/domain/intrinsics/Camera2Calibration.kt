package ai.cbm.capture.domain.intrinsics

import ai.cbm.capture.domain.imaging.PinholeCamera

/**
 * Camera2's factory calibration, carried into the pixels of one output image.
 *
 * `LENS_INTRINSIC_CALIBRATION` is `[fx, fy, cx, cy, s]` in the pre-correction active array. At no
 * zoom, an output stream is the largest centred crop of that array with the output's aspect
 * ratio, scaled to the output size, so
 *
 * ```
 * K_image = scale( crop( K_array, centredCrop(outputAspect) ), outputSize )
 * ```
 *
 * which is section 1 of `docs/INTRINSICS.md` with `SCALER_CROP_REGION` at its default (the whole
 * array). The capture screen offers no zoom on this path, which keeps that default true.
 *
 * One approximation remains, and it is small: where the camera corrects distortion, its output
 * covers the active array, a few pixels inside the pre-correction array. After the 1280 px
 * downscale that is under one pixel of principal point.
 */
object Camera2Calibration {

    /**
     * @return K for an [outputWidth] x [outputHeight] image in the array's own orientation, or
     *   `null` when the output is turned the other way: a camera that rotated the buffer itself
     *   has changed the frame the calibration describes, and guessing the turn back would be the
     *   silent repair this design refuses. The caller then falls back to the photo's own data.
     */
    fun toImage(
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        arrayWidth: Int,
        arrayHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): PinholeCamera? {
        if (arrayWidth <= 0 || arrayHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return null
        if ((arrayWidth >= arrayHeight) != (outputWidth >= outputHeight)) return null

        val arrayAspect = arrayWidth.toDouble() / arrayHeight
        val outputAspect = outputWidth.toDouble() / outputHeight
        val (regionWidth, regionHeight) = if (outputAspect >= arrayAspect) {
            arrayWidth.toDouble() to arrayWidth / outputAspect
        } else {
            arrayHeight * outputAspect to arrayHeight.toDouble()
        }
        val left = (arrayWidth - regionWidth) / 2
        val top = (arrayHeight - regionHeight) / 2
        val sx = outputWidth / regionWidth
        val sy = outputHeight / regionHeight

        return PinholeCamera(
            fx = fx * sx,
            fy = fy * sy,
            cx = (cx - left) * sx,
            cy = (cy - top) * sy,
            width = outputWidth,
            height = outputHeight
        )
    }
}
