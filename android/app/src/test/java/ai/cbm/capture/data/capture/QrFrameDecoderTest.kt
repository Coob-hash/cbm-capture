package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.SiteCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-09-28: the Join screen had no way to scan the site's QR poster - it left that to the phone's
 * camera app, which may show the join link as text and never open it. The app now reads the poster
 * itself, from the frames the camera hands it: the brightness plane, sideways, rows often padded.
 */
class QrFrameDecoderTest {

    private val link = "cbmapp://join?site=ExampleSite0001"
    private val decoder = QrFrameDecoder()

    @Test
    fun `reads the join link, and the site code comes out of it`() {
        val text = decoder.decode(frame(link), rowStride = 640, width = 640, height = 480)
        assertEquals(link, text)
        assertEquals("ExampleSite0001", SiteCode.parse(text!!))
    }

    @Test
    fun `reads a frame whose rows are padded past the picture`() {
        val stride = 640 + 64
        assertEquals(link, decoder.decode(frame(link, rowStride = stride), rowStride = stride, width = 640, height = 480))
    }

    @Test
    fun `reads the poster at any rotation - the portrait phone's frames arrive sideways`() {
        for (turns in 0..3) {
            assertEquals("quarter turns: $turns", link, decoder.decode(frame(link, quarterTurns = turns), 640, 640, 480))
        }
    }

    @Test
    fun `reads a poster that is small in the frame, as from a couple of metres`() {
        val text = decoder.decode(frame(link, width = 1280, height = 960, module = 3), 1280, 1280, 960)
        assertEquals(link, text)
    }

    @Test
    fun `a frame with no QR code gives nothing, and the next frame still reads`() {
        assertNull(decoder.decode(frame(null), 640, 640, 480))
        assertEquals(link, decoder.decode(frame(link), 640, 640, 480))
    }

    /**
     * A camera frame's brightness plane: a grey wall with the QR code of [text] in the middle,
     * [module] pixels a square, turned [quarterTurns] times. Padding past [width] is black.
     */
    private fun frame(
        text: String?,
        width: Int = 640,
        height: Int = 480,
        rowStride: Int = width,
        module: Int = 6,
        quarterTurns: Int = 0
    ): ByteArray {
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) bytes[y * rowStride + x] = 150.toByte()
        if (text == null) return bytes
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val n = matrix.width
        val size = n * module
        val left = (width - size) / 2
        val top = (height - size) / 2
        for (y in 0 until size) for (x in 0 until size) {
            val mx = x / module
            val my = y / module
            val (sx, sy) = when (quarterTurns % 4) {
                0 -> mx to my
                1 -> my to n - 1 - mx
                2 -> n - 1 - mx to n - 1 - my
                else -> n - 1 - my to mx
            }
            bytes[(top + y) * rowStride + left + x] = (if (matrix.get(sx, sy)) 20 else 235).toByte()
        }
        return bytes
    }
}
