package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.ImageTransform
import ai.cbm.capture.domain.imaging.QuarterTurn
import ai.cbm.capture.domain.intrinsics.IntrinsicsGate
import ai.cbm.capture.domain.model.CaptureMetadata
import ai.cbm.capture.domain.model.ClientInfo
import ai.cbm.capture.domain.model.ImageDescriptor
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.PixelPoint
import ai.cbm.capture.domain.model.TargetDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Builds the capture package: the JPEG plus the metadata that describes it.
 *
 * The single place where the image, the intrinsics, and the target pixel are brought into one
 * frame, written so they cannot be produced separately. [ImageTransform.apply] computes the
 * transform once, and the same result drives both the pixel render and the metadata; there is
 * no path that renders an image at one size and stamps K from another.
 *
 * Kotlin twin of `ios/CBMCapture/Core/Capture/CaptureAssembler.swift`.
 */
class CaptureAssembler(
    private val client: ClientInfo = currentClient()
) {

    class Package(
        val metadata: CaptureMetadata,
        val imageBytes: ByteArray,
        val thumbnailBytes: ByteArray?
    )

    class FrameMismatchException(message: String) : IllegalStateException(message)

    data class Input(
        val captureId: UUID,
        val buildingId: String,
        val reporterEmail: String?,
        val description: String?,
        val capturedAt: Instant,
        val turn: QuarterTurn
    )

    fun assemble(snapshot: ArSnapshot, input: Input, source: IntrinsicsSource = IntrinsicsSource.ARCORE): Package {
        // 1. One transform, computed once, applied to K and to the tap together.
        val transform = ImageTransform.apply(
            camera = snapshot.camera,
            target = snapshot.targetPixel,
            turn = input.turn
        )

        // 2. The same transform drives the pixels.
        val bitmap = renderBitmap(snapshot, transform)
        val imageBytes = bitmap.toJpeg(QUALITY)
        val thumbnail = bitmap.thumbnail()?.toJpeg(THUMBNAIL_QUALITY)
        bitmap.recycle()

        // 3. Trust is decided on the transmitted-frame K, not the sensor-frame K, because that
        //    is the one the server and MultiSet will actually use.
        val intrinsics = IntrinsicsGate.intrinsics(transform.camera, source)

        val centrality = ImageTransform.centrality(
            transform.target, transform.camera.width, transform.camera.height
        )

        val metadata = CaptureMetadata(
            captureId = input.captureId.toString(),
            buildingId = input.buildingId,
            reporterEmail = input.reporterEmail,
            description = input.description,
            capturedAt = DateTimeFormatter.ISO_INSTANT.format(input.capturedAt),
            client = client,
            image = ImageDescriptor(
                width = transform.camera.width,
                height = transform.camera.height,
                sha256 = sha256Hex(imageBytes),
                byteLength = imageBytes.size,
                orientationApplied = transform.turn.turns,
                sourceWidth = transform.sourceWidth,
                sourceHeight = transform.sourceHeight,
                scale = transform.scale
            ),
            camera = intrinsics,
            target = TargetDescriptor(
                pixel = PixelPoint(transform.target.x, transform.target.y),
                centrality = centrality
            ),
            pose = snapshot.pose
        )

        assertFrameConsistency(metadata)
        return Package(metadata, imageBytes, thumbnail)
    }

    // ---- Pixels ----

    /**
     * NV21 -> JPEG -> subsampled bitmap -> rotate and scale to the exact transmitted size.
     *
     * The intermediate decode is subsampled so a 12 MP frame never becomes a 48 MB ARGB bitmap
     * on the heap; the final matrix pass then lands on the exact target dimensions, which is
     * what the transmitted K describes.
     */
    private fun renderBitmap(snapshot: ArSnapshot, transform: ImageTransform.Result): Bitmap {
        val yuv = YuvImage(snapshot.nv21, ImageFormat.NV21, snapshot.imageWidth, snapshot.imageHeight, null)
        val jpegStream = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, snapshot.imageWidth, snapshot.imageHeight), INTERMEDIATE_QUALITY, jpegStream)
        val jpeg = jpegStream.toByteArray()

        val longestOut = maxOf(transform.camera.width, transform.camera.height)
        val longestIn = maxOf(snapshot.imageWidth, snapshot.imageHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(longestIn, longestOut)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("The camera frame could not be decoded.")

        val matrix = Matrix().apply {
            postRotate(transform.turn.turns * 90f)
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()

        if (rotated.width == transform.camera.width && rotated.height == transform.camera.height) {
            return rotated
        }
        val scaled = Bitmap.createScaledBitmap(
            rotated, transform.camera.width, transform.camera.height, true
        )
        if (scaled !== rotated) rotated.recycle()
        return scaled
    }

    /** Largest power of two that keeps the decode at or above the final size. */
    private fun sampleSizeFor(sourceLongest: Int, targetLongest: Int): Int {
        var sample = 1
        while (targetLongest > 0 && sourceLongest / (sample * 2) >= targetLongest) {
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun Bitmap.thumbnail(maxSide: Int = 240): Bitmap? {
        val longest = maxOf(width, height)
        if (longest <= 0) return null
        val factor = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        return Bitmap.createScaledBitmap(
            this, (width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), true
        )
    }

    companion object {
        private const val QUALITY = 85
        private const val INTERMEDIATE_QUALITY = 95
        private const val THUMBNAIL_QUALITY = 70

        /**
         * The invariant from `docs/INTRINSICS.md` section 2.3, checked before anything is
         * persisted. Unreachable given the construction above; present because the check costs
         * a few comparisons and being wrong costs a confidently incorrect IFC GlobalId on a
         * real work order.
         */
        fun assertFrameConsistency(metadata: CaptureMetadata) {
            if (metadata.camera.width != metadata.image.width ||
                metadata.camera.height != metadata.image.height
            ) {
                throw FrameMismatchException(
                    "K describes ${metadata.camera.width}x${metadata.camera.height} " +
                        "but the image is ${metadata.image.width}x${metadata.image.height}"
                )
            }
            val x = metadata.target.pixel.x
            val y = metadata.target.pixel.y
            if (x < 0 || x >= metadata.image.width || y < 0 || y >= metadata.image.height) {
                throw FrameMismatchException("the target pixel lies outside the image")
            }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * `Pixel 8 Pro` rather than a marketing string: the ablation in section 8 of the
         * calibration note needs to distinguish camera hardware.
         */
        fun currentClient(): ClientInfo = ClientInfo(
            appVersion = "1.0.0 (1)",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        )
    }
}
