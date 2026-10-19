package ai.cbm.capture.data.capture

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in one camera frame and returns its text, or null. The frame is its brightness
 * plane - the Y of YUV, one byte a pixel, [rowStride] bytes a row - exactly as the camera hands it
 * over, so no Android type is involved and the tests feed it frames without a camera.
 *
 * The frame need not be upright: a QR code reads the same at any rotation. One decoder per thread.
 */
class QrFrameDecoder {

    private val reader = QRCodeReader()

    // Looks at every row rather than every few: a poster seen from a couple of metres is small in
    // the frame. Still a few tens of milliseconds a frame on a phone.
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        require(width in 1..rowStride && height > 0) { "A frame of $width x $height with rows of $rowStride bytes." }
        require(luminance.size >= rowStride * (height - 1) + width) { "The frame is shorter than $height rows." }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null   // no QR code in this frame, or one too blurred or cut off to read
        } finally {
            reader.reset()
        }
    }
}
