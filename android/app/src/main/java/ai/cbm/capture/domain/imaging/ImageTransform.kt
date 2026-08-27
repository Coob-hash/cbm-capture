package ai.cbm.capture.domain.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A pinhole camera in the pixel frame of a specific image.
 *
 * The [width]/[height] fields are what make this type meaningful: an intrinsics matrix without
 * the frame it belongs to is exactly the defect described in section 3 of the calibration note,
 * so the two never travel separately here.
 */
data class PinholeCamera(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int
)

data class PointF2(val x: Double, val y: Double)

/** Clockwise rotation in quarter-turns; the ordinal is transmitted as `image.orientation_applied`. */
enum class QuarterTurn(val turns: Int) {
    NONE(0), CW90(1), CW180(2), CW270(3);

    companion object {
        /**
         * Rotation that brings a sensor-native landscape frame upright, given the display
         * rotation reported by the window manager (`Surface.ROTATION_*`).
         */
        fun forDisplayRotation(surfaceRotation: Int): QuarterTurn = when (surfaceRotation) {
            0 -> CW90     // Surface.ROTATION_0, portrait
            1 -> NONE     // Surface.ROTATION_90
            2 -> CW270    // Surface.ROTATION_180
            3 -> CW180    // Surface.ROTATION_270
            else -> CW90
        }
    }
}

/**
 * Rotation and resampling of an image *together with* its intrinsics and the worker's tap.
 *
 * Every function here is pure and total, and each is the Kotlin twin of the Swift implementation
 * in `ios/CBMCapture/Core/Imaging/ImageTransform.swift`. Both are implementations of section 2
 * of `docs/INTRINSICS.md`, and both test suites assert the same identities, so the two platforms
 * cannot drift apart silently.
 */
object ImageTransform {

    /**
     * Longest side of the transmitted image. The 2026_07_08 package advises <= 1280 px for the
     * MultiSet query, and K is derived from what is actually sent, never from the original.
     */
    const val MAX_UPLOAD_LONG_SIDE = 1280

    /** Above this, the worker is asked to re-frame with the defect nearer the centre. */
    const val CENTRALITY_WARNING_THRESHOLD = 0.6

    // ---- Rotation ----

    fun rotatedSize(width: Int, height: Int, turn: QuarterTurn): Pair<Int, Int> =
        when (turn) {
            QuarterTurn.NONE, QuarterTurn.CW180 -> width to height
            QuarterTurn.CW90, QuarterTurn.CW270 -> height to width
        }

    /**
     * Rotate intrinsics by [turn] quarter-turns clockwise.
     *
     * | k | size  | fx' | fy' | cx'    | cy'    |
     * |---|-------|-----|-----|--------|--------|
     * | 1 | H x W | fy  | fx  | H - cy | cx     |
     * | 2 | W x H | fx  | fy  | W - cx | H - cy |
     * | 3 | H x W | fy  | fx  | cy     | W - cx |
     */
    fun rotate(camera: PinholeCamera, turn: QuarterTurn): PinholeCamera {
        val w = camera.width.toDouble()
        val h = camera.height.toDouble()
        val (outW, outH) = rotatedSize(camera.width, camera.height, turn)

        return when (turn) {
            QuarterTurn.NONE -> camera
            QuarterTurn.CW90 -> PinholeCamera(camera.fy, camera.fx, h - camera.cy, camera.cx, outW, outH)
            QuarterTurn.CW180 -> PinholeCamera(camera.fx, camera.fy, w - camera.cx, h - camera.cy, outW, outH)
            QuarterTurn.CW270 -> PinholeCamera(camera.fy, camera.fx, camera.cy, w - camera.cx, outW, outH)
        }
    }

    fun rotate(point: PointF2, width: Int, height: Int, turn: QuarterTurn): PointF2 {
        val w = width.toDouble()
        val h = height.toDouble()
        return when (turn) {
            QuarterTurn.NONE -> point
            QuarterTurn.CW90 -> PointF2(h - point.y, point.x)
            QuarterTurn.CW180 -> PointF2(w - point.x, h - point.y)
            QuarterTurn.CW270 -> PointF2(point.y, w - point.x)
        }
    }

    // ---- Scaling ----

    /** Never upscales: an image already below the limit is transmitted untouched. */
    fun downscaledSize(width: Int, height: Int, longSide: Int = MAX_UPLOAD_LONG_SIDE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= longSide) return width to height
        val factor = longSide.toDouble() / longest
        return max(1, (width * factor).roundToInt()) to max(1, (height * factor).roundToInt())
    }

    /**
     * Scale intrinsics to a new frame size using the **realised** per-axis ratios.
     *
     * Rounding the output dimensions to integers makes the two axes differ slightly, and each of
     * fx, fy, cx, cy is linear in the resolution of the axis it belongs to. Applying one factor
     * to all four, or scaling only the focal lengths, is the classic form of this bug.
     */
    fun scale(camera: PinholeCamera, outWidth: Int, outHeight: Int): PinholeCamera {
        val sx = outWidth.toDouble() / camera.width
        val sy = outHeight.toDouble() / camera.height
        return PinholeCamera(camera.fx * sx, camera.fy * sy, camera.cx * sx, camera.cy * sy, outWidth, outHeight)
    }

    fun scale(point: PointF2, inWidth: Int, inHeight: Int, outWidth: Int, outHeight: Int): PointF2 =
        PointF2(point.x * outWidth / inWidth, point.y * outHeight / inHeight)

    // ---- The composed pipeline ----

    data class Result(
        val camera: PinholeCamera,
        val target: PointF2,
        val turn: QuarterTurn,
        val sourceWidth: Int,
        val sourceHeight: Int,
        /** Realised uniform scale, for provenance. K is already scaled. */
        val scale: Double
    )

    /**
     * Apply rotation then downscale to intrinsics and the tap point together.
     *
     * The caller pushes the returned [Result] through the pixel pipeline as well, so the pixels,
     * K, and the tap cannot end up in different coordinate systems.
     */
    fun apply(
        camera: PinholeCamera,
        target: PointF2,
        turn: QuarterTurn,
        longSide: Int = MAX_UPLOAD_LONG_SIDE
    ): Result {
        val sourceWidth = camera.width
        val sourceHeight = camera.height

        val rotatedCamera = rotate(camera, turn)
        val rotatedTarget = rotate(target, sourceWidth, sourceHeight, turn)

        val (outW, outH) = downscaledSize(rotatedCamera.width, rotatedCamera.height, longSide)
        val finalCamera = scale(rotatedCamera, outW, outH)
        val finalTarget = scale(rotatedTarget, rotatedCamera.width, rotatedCamera.height, outW, outH)

        return Result(
            camera = finalCamera,
            target = clamp(finalTarget, outW, outH),
            turn = turn,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scale = outW.toDouble() / rotatedCamera.width
        )
    }

    /** A tap exactly on the right or bottom edge would produce an out-of-range pixel index. */
    fun clamp(point: PointF2, width: Int, height: Int): PointF2 =
        PointF2(
            min(max(point.x, 0.0), width - 1.0),
            min(max(point.y, 0.0), height - 1.0)
        )

    /**
     * 0 at the frame centre, 1 at the edge. Section 6.2 of the calibration note uses this to
     * reject captures where lens distortion makes the pinhole model untenable.
     */
    fun centrality(point: PointF2, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) return 1.0
        val dx = abs(point.x / width - 0.5)
        val dy = abs(point.y / height - 0.5)
        return min(1.0, max(dx, dy) / 0.5)
    }
}
