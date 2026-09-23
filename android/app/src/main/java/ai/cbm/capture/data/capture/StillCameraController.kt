package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.ImageTransform
import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.imaging.PointF2
import ai.cbm.capture.domain.imaging.QuarterTurn
import ai.cbm.capture.domain.imaging.StillFraming
import ai.cbm.capture.domain.model.IntrinsicsSource
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * One photograph from the camera path used when AR is not available on the phone.
 *
 * The same promise as [ArSnapshot]: the pixels, K and the tap are all in one frame - here the
 * part of the JPEG buffer that is the photograph ([crop]) - and [turn] is what brings it upright.
 * There is no pose on this path.
 */
class StillSnapshot(
    val jpeg: ByteArray,
    val bufferWidth: Int,
    val bufferHeight: Int,
    /** The photograph within the buffer: the whole buffer unless the camera cropped it. */
    val crop: Rect,
    /** K in the frame of [crop], before any rotation or downscale. */
    val camera: PinholeCamera,
    /** The tap, in that same frame. */
    val targetPixel: PointF2,
    val turn: QuarterTurn,
    val source: IntrinsicsSource,
    val skew: Double = 0.0,
    val lens: String? = null,
    val distortion: List<Double>? = null
)

/**
 * The camera path for phones without ARCore (or where it was declined): CameraX, a letterboxed
 * preview, and one gesture that both takes the photograph and marks the damage, as on the AR path.
 *
 * What it gives up against ARCore, and says so in the package: no pose, and a still that is
 * taken a moment after the tap rather than being the frame under the finger. K is the factory
 * calibration ([IntrinsicsSource.ANDROID_CAMERA2]) where the phone publishes one, else the EXIF
 * estimate ([IntrinsicsSource.EXIF]); where neither exists, the capture is refused rather than
 * given an invented K (`docs/INTRINSICS.md` section 1).
 */
class StillCameraController(private val intrinsics: Camera2IntrinsicsReader) {

    class TapOutsidePhoto : IllegalStateException("The tap is outside the photograph.")
    class NoCalibration : IllegalStateException("The camera reports neither a factory calibration nor a focal length.")

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var cameraId: String? = null

    /** Main thread. CameraX follows [owner]'s lifecycle from here on: paused in the background. */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bind(context: Context, owner: LifecycleOwner, view: PreviewView) {
        val provider = awaitProvider(context)
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        // 4:3 for both streams: the sensor's own shape on nearly every phone, so neither stream is
        // cropped and the preview shows exactly what the photograph will contain.
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()
        val capture = ImageCapture.Builder()
            .setResolutionSelector(resolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)

        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        this.provider = provider
        this.preview = preview
        this.imageCapture = capture
        this.cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
        _ready.value = true
    }

    fun unbind() {
        _ready.value = false
        provider?.unbindAll()
        provider = null
        preview = null
        imageCapture = null
        cameraId = null
    }

    /**
     * Take the photograph for a tap at ([tapX], [tapY]) on a view of [viewWidth] x [viewHeight]
     * that shows the preview letterboxed.
     *
     * @throws TapOutsidePhoto when the tap is on the letterbox
     * @throws NoCalibration when the phone offers no way to know its focal length
     */
    suspend fun capture(tapX: Float, tapY: Float, viewWidth: Int, viewHeight: Int, displayRotation: Int): StillSnapshot {
        val capture = imageCapture ?: throw IllegalStateException("The camera is not ready.")
        capture.targetRotation = displayRotation
        val shot = capture.shoot()

        // The buffer as stored in the JPEG, which is how BitmapFactory will decode it: the EXIF
        // orientation tag is not applied, and the reported rotation says how to bring it upright.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, bounds)
        val bufferWidth = bounds.outWidth
        val bufferHeight = bounds.outHeight
        if (bufferWidth <= 0 || bufferHeight <= 0) throw IllegalStateException("The photograph could not be read.")

        val whole = Rect(0, 0, bufferWidth, bufferHeight)
        val crop = if (shot.width == bufferWidth && shot.height == bufferHeight &&
            !shot.cropRect.isEmpty && whole.contains(shot.cropRect)
        ) Rect(shot.cropRect) else whole
        val turn = QuarterTurn.ofDegrees(shot.rotationDegrees)

        // The tap, carried into the photograph (StillFraming explains the three steps).
        val (uprightWidth, uprightHeight) = ImageTransform.rotatedSize(crop.width(), crop.height(), turn)
        val (previewWidth, previewHeight) = previewUprightSize() ?: (uprightWidth to uprightHeight)
        val id = cameraId
        val sensor = id?.let(intrinsics::sensorArraySize)
        val upright = StillFraming.tapToUprightStill(
            tapX = tapX.toDouble(),
            tapY = tapY.toDouble(),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            sensorLong = sensor?.let { max(it.width, it.height) } ?: max(uprightWidth, uprightHeight),
            sensorShort = sensor?.let { min(it.width, it.height) } ?: min(uprightWidth, uprightHeight),
            stillWidth = uprightWidth,
            stillHeight = uprightHeight
        ) ?: throw TapOutsidePhoto()
        val target = ImageTransform.unrotate(upright, crop.width(), crop.height(), turn)

        // K for the whole buffer: the factory calibration first, then the photograph's own EXIF -
        // never a guess. Both describe the buffer, so both are then moved into the crop.
        val factory = id?.let { intrinsics.read(it, bufferWidth, bufferHeight) }
        val (k, source) = when {
            factory != null -> factory.camera to IntrinsicsSource.ANDROID_CAMERA2
            else -> (ExifIntrinsicsReader.read(shot.jpeg, bufferWidth, bufferHeight) ?: throw NoCalibration()) to
                IntrinsicsSource.EXIF
        }
        return StillSnapshot(
            jpeg = shot.jpeg,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            crop = crop,
            camera = PinholeCamera(k.fx, k.fy, k.cx - crop.left, k.cy - crop.top, crop.width(), crop.height()),
            targetPixel = target,
            turn = turn,
            source = source,
            skew = factory?.skew ?: 0.0,
            lens = factory?.lens,
            distortion = factory?.distortion
        )
    }

    /** The preview stream's size once upright, as the view shows it. */
    private fun previewUprightSize(): Pair<Int, Int>? {
        val info = preview?.resolutionInfo ?: return null
        val rect = info.cropRect
        val width = if (rect.isEmpty) info.resolution.width else rect.width()
        val height = if (rect.isEmpty) info.resolution.height else rect.height()
        return ImageTransform.rotatedSize(width, height, QuarterTurn.ofDegrees(info.rotationDegrees))
    }

    private class Shot(val jpeg: ByteArray, val rotationDegrees: Int, val cropRect: Rect, val width: Int, val height: Int)

    private suspend fun ImageCapture.shoot(): Shot = suspendCancellableCoroutine { continuation ->
        takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val shot = runCatching {
                    image.use { proxy ->
                        check(proxy.format == ImageFormat.JPEG) { "The camera returned format ${proxy.format}, not JPEG." }
                        val buffer = proxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        Shot(bytes, proxy.imageInfo.rotationDegrees, Rect(proxy.cropRect), proxy.width, proxy.height)
                    }
                }
                if (continuation.isActive) {
                    shot.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            }

            override fun onError(exception: ImageCaptureException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        })
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }.fold(
                    { if (continuation.isActive) continuation.resume(it) },
                    { if (continuation.isActive) continuation.resumeWithException(it) }
                )
            }, ContextCompat.getMainExecutor(context))
        }
}
