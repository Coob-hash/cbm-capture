package ai.cbm.capture.data.capture

import ai.cbm.capture.BuildConfig
import ai.cbm.capture.domain.imaging.ImageTransform
import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.imaging.PointF2
import ai.cbm.capture.domain.imaging.QuarterTurn
import ai.cbm.capture.domain.intrinsics.IntrinsicsGate
import ai.cbm.capture.domain.model.CaptureMetadata
import ai.cbm.capture.domain.model.ClientInfo
import ai.cbm.capture.domain.model.ImageDescriptor
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.PixelPoint
import ai.cbm.capture.domain.model.PoseSample
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
        val reportId: String,
        val buildingId: String,
        val description: String?,
        val capturedAt: Instant,
        val turn: QuarterTurn
    )

    /** A frame of the AR session. */
    fun assemble(snapshot: ArSnapshot, input: Input, source: IntrinsicsSource = IntrinsicsSource.ARCORE): Package =
        build(snapshot.camera, snapshot.targetPixel, snapshot.pose, input, source) { transform ->
            renderJpeg(nv21ToJpeg(snapshot), snapshot.imageWidth, snapshot.imageHeight, crop = null, transform)
        }

    /**
     * A photograph from the camera path without AR. [Input.turn] must be the still's own turn
     * ([StillSnapshot.turn]): it is the camera, not the display, that knows how its JPEG lies.
     */
    fun assemble(still: StillSnapshot, input: Input): Package =
        build(
            still.camera, still.targetPixel, pose = null, input, still.source,
            skew = still.skew, lens = still.lens, distortion = still.distortion
        ) { transform ->
            renderJpeg(still.jpeg, still.bufferWidth, still.bufferHeight, still.crop, transform)
        }

    private fun build(
        camera: PinholeCamera,
        targetPixel: PointF2,
        pose: PoseSample?,
        input: Input,
        source: IntrinsicsSource,
        skew: Double = 0.0,
        lens: String? = null,
        distortion: List<Double>? = null,
        render: (ImageTransform.Result) -> Bitmap
    ): Package {
        // 1. One transform, computed once, applied to K and to the tap together.
        val transform = ImageTransform.apply(
            camera = camera,
            target = targetPixel,
            turn = input.turn
        )

        // 2. The same transform drives the pixels.
        val bitmap = render(transform)
        val imageBytes = bitmap.toJpeg(QUALITY)
        val thumbnail = bitmap.thumbnail()?.toJpeg(THUMBNAIL_QUALITY)
        bitmap.recycle()

        // 3. Trust is decided on the transmitted-frame K, not the sensor-frame K, because that
        //    is the one the server and MultiSet will actually use. Skew follows the x scale; it is
        //    carried for completeness and ignored by the ray math.
        val intrinsics = IntrinsicsGate.intrinsics(
            transform.camera, source, skew = skew * transform.scale, lens = lens, distortion = distortion
        )

        val centrality = ImageTransform.centrality(
            transform.target, transform.camera.width, transform.camera.height
        )

        val metadata = CaptureMetadata(
            captureId = input.captureId.toString(),
            reportId = input.reportId,
            buildingId = input.buildingId,
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
            pose = pose
        )

        assertFrameConsistency(metadata)
        return Package(metadata, imageBytes, thumbnail)
    }

    // ---- Pixels ----

    private fun nv21ToJpeg(snapshot: ArSnapshot): ByteArray {
        val yuv = YuvImage(snapshot.nv21, ImageFormat.NV21, snapshot.imageWidth, snapshot.imageHeight, null)
        val jpegStream = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, snapshot.imageWidth, snapshot.imageHeight), INTERMEDIATE_QUALITY, jpegStream)
        return jpegStream.toByteArray()
    }

    /**
     * JPEG -> subsampled bitmap -> crop -> rotate and scale to the exact transmitted size.
     *
     * The intermediate decode is subsampled so a 12 MP frame never becomes a 48 MB ARGB bitmap
     * on the heap; the final matrix pass then lands on the exact target dimensions, which is
     * what the transmitted K describes. [crop] is in the full-resolution buffer's pixels.
     */
    private fun renderJpeg(
        jpeg: ByteArray,
        bufferWidth: Int,
        bufferHeight: Int,
        crop: Rect?,
        transform: ImageTransform.Result
    ): Bitmap {
        val longestOut = maxOf(transform.camera.width, transform.camera.height)
        val longestIn = crop?.let { maxOf(it.width(), it.height()) } ?: maxOf(bufferWidth, bufferHeight)
        val sample = sampleSizeFor(longestIn, longestOut)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("The camera frame could not be decoded.")

        if (crop != null && (crop.width() != bufferWidth || crop.height() != bufferHeight)) {
            // The decoder's own scale, not the requested sample size: it may round.
            val sx = decoded.width.toDouble() / bufferWidth
            val sy = decoded.height.toDouble() / bufferHeight
            val left = (crop.left * sx).toInt().coerceIn(0, decoded.width - 1)
            val top = (crop.top * sy).toInt().coerceIn(0, decoded.height - 1)
            val width = (crop.width() * sx).toInt().coerceIn(1, decoded.width - left)
            val height = (crop.height() * sy).toInt().coerceIn(1, decoded.height - top)
            val cropped = Bitmap.createBitmap(decoded, left, top, width, height)
            if (cropped !== decoded) decoded.recycle()
            decoded = cropped
        }

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
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        )
    }
}
