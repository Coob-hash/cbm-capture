package ai.cbm.capture.data.capture

import android.media.Image

/**
 * Copies a YUV_420_888 [Image] into a packed NV21 buffer.
 *
 * ARCore's CPU image must be closed promptly - the session runs on a small pool of buffers and
 * holding one stalls tracking - so the bytes are copied out immediately and every later step
 * works from this array instead.
 *
 * NV21 is the target because it is the one layout `android.graphics.YuvImage` accepts, which
 * gives a JPEG encode with no third-party dependency and no colour-conversion of our own.
 */
object YuvConverter {

    fun toNv21(image: Image): ByteArray {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }

        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // --- Luma: copy row by row, because rowStride is frequently wider than the image. ---
        var offset = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(output, 0, width * height)
            offset = width * height
        } else {
            val row = ByteArray(yRowStride)
            for (y in 0 until height) {
                yBuffer.position(y * yRowStride)
                // The final row is often short: the plane is padded to the stride only between
                // rows, not after the last one.
                yBuffer.get(row, 0, minOf(yRowStride, yBuffer.remaining()))
                var index = 0
                for (x in 0 until width) {
                    output[offset++] = row[index]
                    index += yPixelStride
                }
            }
        }

        // --- Chroma: NV21 interleaves V then U at half resolution. ---
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        for (y in 0 until chromaHeight) {
            var uIndex = y * uRowStride
            var vIndex = y * vRowStride
            for (x in 0 until chromaWidth) {
                output[offset++] = vBuffer.get(vIndex)
                output[offset++] = uBuffer.get(uIndex)
                uIndex += uPixelStride
                vIndex += vPixelStride
            }
        }

        return output
    }
}
