package ai.cbm.capture.domain.imaging

import ai.cbm.capture.domain.intrinsics.IntrinsicsGate
import ai.cbm.capture.domain.model.IntrinsicsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Kotlin half of the cross-platform transform suite. Every assertion here has a twin in
 * `ios/CBMCaptureTests/ImageTransformTests.swift`, so a divergence between the two platforms
 * shows up as a failing test rather than as two subtly different rays.
 */
class ImageTransformTest {

    private val reference = PinholeCamera(
        fx = 1432.61, fy = 1431.95, cx = 959.48, cy = 719.71, width = 1920, height = 1440
    )

    /**
     * The normalised camera ray a pixel unprojects to.
     *
     * This is what `/elements/resolve-ray` actually builds its ray from, so it is what the tests
     * assert on. Checking fx and cx individually would pass for a transform that scaled the
     * focal length and the principal point inconsistently; checking the ray cannot.
     */
    private fun ray(point: PointF2, camera: PinholeCamera): Pair<Double, Double> =
        (point.x - camera.cx) / camera.fx to (point.y - camera.cy) / camera.fy

    @Test
    fun `downscaling preserves the unprojected ray exactly`() {
        val target = PointF2(1204.0, 388.0)
        val before = ray(target, reference)

        val (outW, outH) = ImageTransform.downscaledSize(1920, 1440, 1280)
        val scaled = ImageTransform.scale(reference, outW, outH)
        val scaledTarget = ImageTransform.scale(target, 1920, 1440, outW, outH)
        val after = ray(scaledTarget, scaled)

        assertEquals(before.first, after.first, 1e-9)
        assertEquals(before.second, after.second, 1e-9)
    }

    @Test
    fun `downscaling never enlarges an image already below the limit`() {
        assertEquals(640 to 480, ImageTransform.downscaledSize(640, 480, 1280))
    }

    @Test
    fun `both principal-point components scale, each by its own axis`() {
        val scaled = ImageTransform.scale(reference, 960, 480)
        assertEquals(reference.cx * 0.5, scaled.cx, 1e-9)
        assertEquals(reference.cy / 3.0, scaled.cy, 1e-9)
        assertEquals(reference.fx * 0.5, scaled.fx, 1e-9)
        assertEquals(reference.fy / 3.0, scaled.fy, 1e-9)
    }

    @Test
    fun `rotation maps the ray by the matching rotation of the camera frame`() {
        val target = PointF2(1204.0, 388.0)
        val before = ray(target, reference)

        for (turn in QuarterTurn.entries) {
            val rotated = ImageTransform.rotate(reference, turn)
            val rotatedTarget = ImageTransform.rotate(target, 1920, 1440, turn)
            val after = ray(rotatedTarget, rotated)

            // Clockwise image rotation sends camera-space (x, y) to (-y, x), applied `turns` times.
            var expected = before
            repeat(turn.turns) { expected = -expected.second to expected.first }

            assertEquals("turn=$turn", expected.first, after.first, 1e-9)
            assertEquals("turn=$turn", expected.second, after.second, 1e-9)
        }
    }

    @Test
    fun `four quarter-turns return the original intrinsics`() {
        var camera = reference
        repeat(4) { camera = ImageTransform.rotate(camera, QuarterTurn.CW90) }
        assertTrue(abs(camera.fx - reference.fx) < 1e-9)
        assertTrue(abs(camera.cy - reference.cy) < 1e-9)
        assertEquals(reference.width, camera.width)
        assertEquals(reference.height, camera.height)
    }

    @Test
    fun `a quarter-turn transposes the frame`() {
        val rotated = ImageTransform.rotate(reference, QuarterTurn.CW90)
        assertEquals(1440, rotated.width)
        assertEquals(1920, rotated.height)
    }

    @Test
    fun `the full pipeline keeps K, the frame, and the tap in one coordinate system`() {
        val result = ImageTransform.apply(reference, PointF2(1204.0, 388.0), QuarterTurn.CW90)

        assertEquals(960, result.camera.width)
        assertEquals(1280, result.camera.height)
        assertTrue(result.target.x >= 0 && result.target.x < 960)
        assertTrue(result.target.y >= 0 && result.target.y < 1280)
        assertEquals(960.0 / 1440.0, result.scale, 1e-9)
    }

    @Test
    fun `the tap is clamped inside the frame`() {
        val result = ImageTransform.apply(reference, PointF2(1920.0, 1440.0), QuarterTurn.NONE)
        assertTrue(result.target.x <= result.camera.width - 1.0)
        assertTrue(result.target.y <= result.camera.height - 1.0)
    }

    @Test
    fun `centrality is zero at the centre and one at the edge`() {
        assertEquals(0.0, ImageTransform.centrality(PointF2(480.0, 640.0), 960, 1280), 1e-9)
        assertTrue(ImageTransform.centrality(PointF2(959.0, 640.0), 960, 1280) > 0.99)
    }

    // ---- Gate ----

    @Test
    fun `a real ARCore calibration passes the gate`() {
        assertTrue(IntrinsicsGate.evaluate(reference).trusted)
    }

    @Test
    fun `the mismatched-frame defect is caught`() {
        // The live defect from section 3 of the calibration note: K describes a 1920x1440 frame
        // while the pixels are a stock 4032x3024 photograph.
        val mismatched = reference.copy(width = 4032, height = 3024)
        val verdict = IntrinsicsGate.evaluate(mismatched)
        assertFalse(verdict.trusted)
        assertTrue(IntrinsicsGate.Rejection.PRINCIPAL_POINT_OFF_CENTRE in verdict.rejections)
        assertTrue(IntrinsicsGate.Rejection.FOCAL_OUT_OF_RANGE in verdict.rejections)
    }

    @Test
    fun `the hardcoded environment default is rejected for a modern frame`() {
        // fx = 1200 against a 4032-wide image is a 118-degree field of view: no phone lens.
        val fabricated = PinholeCamera(1200.0, 1200.0, 960.0, 540.0, 4032, 3024)
        assertFalse(IntrinsicsGate.evaluate(fabricated).trusted)
    }

    @Test
    fun `wildly non-square pixels are rejected`() {
        val skewed = PinholeCamera(1400.0, 1000.0, 960.0, 720.0, 1920, 1440)
        assertTrue(IntrinsicsGate.Rejection.NON_SQUARE_PIXELS in IntrinsicsGate.evaluate(skewed).rejections)
    }

    @Test
    fun `a manual override is never marked trusted, however plausible`() {
        val intrinsics = IntrinsicsGate.intrinsics(reference, IntrinsicsSource.MANUAL_OVERRIDE)
        assertFalse(intrinsics.trusted)
    }

    @Test
    fun `EXIF derivation follows f35 times W over 36 with a centred principal point`() {
        val camera = IntrinsicsGate.fromExif(26.0, 4032, 3024)!!
        assertEquals(26.0 * 4032.0 / 36.0, camera.fx, 1e-9)
        assertEquals(camera.fx, camera.fy, 1e-9)
        assertEquals(2016.0, camera.cx, 1e-9)
        assertEquals(1512.0, camera.cy, 1e-9)
        assertTrue(IntrinsicsGate.evaluate(camera).trusted)
    }

    @Test
    fun `a zero focal length yields no camera at all, rather than a guess`() {
        assertNull(IntrinsicsGate.fromExif(0.0, 4032, 3024))
    }
}
