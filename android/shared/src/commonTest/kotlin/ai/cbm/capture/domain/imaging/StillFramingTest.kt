package ai.cbm.capture.domain.imaging

import ai.cbm.capture.domain.intrinsics.Camera2Calibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The standard-camera path: a tap on a letterboxed preview, carried into the photograph, and the
 * factory calibration carried into the photograph's pixels. Both end in the same place as the AR
 * path - K and the tap in the buffer frame - so the last test checks the ray, as the transform
 * suite does.
 */
class StillFramingTest {

    private fun near(expected: PointF2, actual: PointF2?, tolerance: Double = 1e-6) {
        assertNotNull(actual)
        assertEquals(expected.x, actual.x, tolerance, "x")
        assertEquals(expected.y, actual.y, tolerance, "y")
    }

    // ---- unrotate ----

    @Test
    fun `unrotate undoes rotate for every quarter-turn`() {
        val p = PointF2(1204.0, 388.0)
        for (turn in QuarterTurn.entries) {
            val back = ImageTransform.unrotate(ImageTransform.rotate(p, 1920, 1440, turn), 1920, 1440, turn)
            near(p, back)
        }
    }

    @Test
    fun `degrees become quarter-turns, whatever the sign`() {
        assertEquals(QuarterTurn.NONE, QuarterTurn.ofDegrees(0))
        assertEquals(QuarterTurn.CW90, QuarterTurn.ofDegrees(90))
        assertEquals(QuarterTurn.CW180, QuarterTurn.ofDegrees(180))
        assertEquals(QuarterTurn.CW270, QuarterTurn.ofDegrees(270))
        assertEquals(QuarterTurn.CW270, QuarterTurn.ofDegrees(-90))
        assertEquals(QuarterTurn.NONE, QuarterTurn.ofDegrees(360))
    }

    // ---- tap -> photograph ----

    /** A 1080 x 2400 portrait screen showing a 3:4 preview: 1080 x 1440, centred, bands of 480. */
    private fun tap(x: Double, y: Double, previewW: Int = 1080, previewH: Int = 1440, stillW: Int = 3024, stillH: Int = 4032) =
        StillFraming.tapToUprightStill(
            tapX = x, tapY = y, viewWidth = 1080, viewHeight = 2400,
            previewWidth = previewW, previewHeight = previewH,
            sensorLong = 4032, sensorShort = 3024,
            stillWidth = stillW, stillHeight = stillH
        )

    @Test
    fun `same shape everywhere - only the letterbox is removed`() {
        near(PointF2(1512.0, 2016.0), tap(540.0, 1200.0))          // centre stays centre
        near(PointF2(0.0, 0.0), tap(0.0, 480.0))                   // top-left corner of the picture
        near(PointF2(3024.0, 4032.0), tap(1080.0, 1920.0))         // bottom-right corner
        near(PointF2(756.0, 1008.0), tap(270.0, 840.0))            // a quarter in from each side
    }

    @Test
    fun `a tap on the letterbox is not a tap on the photograph`() {
        assertNull(tap(540.0, 100.0))
        assertNull(tap(540.0, 2300.0))
    }

    @Test
    fun `a 16 by 9 preview of a 4 by 3 sensor shows a band of the photograph`() {
        // Upright, the 9:16 preview is narrower than the 3:4 sensor, so it covers the full height
        // and a centred 0.75 of the width. It is shown 1080 x 1920, with bands of 240.
        val centre = tap(540.0, 1200.0, previewW = 1080, previewH = 1920)
        near(PointF2(1512.0, 2016.0), centre)
        // The preview's left edge is 1/8 of the way into the photograph.
        near(PointF2(3024.0 / 8, 2016.0), tap(0.0, 1200.0, previewW = 1080, previewH = 1920))
    }

    @Test
    fun `a 16 by 9 photograph leaves out the top and bottom of a 4 by 3 preview`() {
        // Upright still 2268 x 4032 (9:16): full sensor height, centred 0.75 of the width.
        near(PointF2(1134.0, 2016.0), tap(540.0, 1200.0, stillW = 2268, stillH = 4032))
        // The preview's left eighth is not in the photograph at all.
        assertNull(tap(60.0, 1200.0, stillW = 2268, stillH = 4032))
    }

    @Test
    fun `crop mappings are inverse of each other`() {
        for ((crop, frame) in listOf(4.0 / 3 to 16.0 / 9, 16.0 / 9 to 4.0 / 3, 1.0 to 4.0 / 3)) {
            val (u, v) = StillFraming.fromCentredCrop(0.2, 0.7, crop, frame)
            val (u2, v2) = StillFraming.toCentredCrop(u, v, crop, frame)
            assertEquals(0.2, u2, 1e-12)
            assertEquals(0.7, v2, 1e-12)
        }
    }

    // ---- factory calibration -> photograph ----

    private val calibration = doubleArrayOf(3100.0, 3098.0, 2012.0, 1515.0)  // on a 4032 x 3024 array

    private fun k(outW: Int, outH: Int) = Camera2Calibration.toImage(
        calibration[0], calibration[1], calibration[2], calibration[3], 4032, 3024, outW, outH
    )

    @Test
    fun `a full-array output keeps the calibration, a smaller one scales it`() {
        val same = assertNotNull(k(4032, 3024))
        assertEquals(3100.0, same.fx, 1e-9)
        assertEquals(2012.0, same.cx, 1e-9)

        val half = assertNotNull(k(2016, 1512))
        assertEquals(1550.0, half.fx, 1e-9)
        assertEquals(1549.0, half.fy, 1e-9)
        assertEquals(1006.0, half.cx, 1e-9)
        assertEquals(757.5, half.cy, 1e-9)
    }

    @Test
    fun `a 16 by 9 output is a centred band of the array`() {
        // 4032 x 2268 band, 378 px down from the top; output 1920 x 1080.
        val wide = assertNotNull(k(1920, 1080))
        val s = 1920.0 / 4032
        assertEquals(3100.0 * s, wide.fx, 1e-9)
        assertEquals(2012.0 * s, wide.cx, 1e-9)
        assertEquals((1515.0 - 378.0) * s, wide.cy, 1e-9)
    }

    @Test
    fun `a buffer the camera turned itself gets no factory K`() {
        assertNull(k(3024, 4032))
    }

    @Test
    fun `the ray under the tap is the same whichever way it reaches the transmitted image`() {
        // A tap in the middle of the preview, carried into the buffer of a still that needs a
        // quarter-turn, then through the same ImageTransform.apply the AR path uses.
        val buffer = assertNotNull(k(4032, 3024))
        val upright = assertNotNull(tap(540.0, 1200.0))
        val inBuffer = ImageTransform.unrotate(upright, 4032, 3024, QuarterTurn.CW90)
        val out = ImageTransform.apply(buffer, inBuffer, QuarterTurn.CW90)

        // Upright centre of the photograph, transmitted at 960 x 1280.
        assertEquals(960, out.camera.width)
        assertEquals(1280, out.camera.height)
        near(PointF2(480.0, 640.0), out.target, 1e-6)

        // Same ray as in the buffer frame, rotated a quarter-turn: (x, y) -> (-y, x).
        val before = (inBuffer.x - buffer.cx) / buffer.fx to (inBuffer.y - buffer.cy) / buffer.fy
        val after = (out.target.x - out.camera.cx) / out.camera.fx to (out.target.y - out.camera.cy) / out.camera.fy
        assertEquals(-before.second, after.first, 1e-9)
        assertEquals(before.first, after.second, 1e-9)
    }
}
