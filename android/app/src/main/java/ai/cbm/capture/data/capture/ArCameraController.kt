package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.imaging.PointF2
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.PoseSample
import ai.cbm.capture.domain.model.QuaternionValue
import ai.cbm.capture.domain.model.TrackingState
import ai.cbm.capture.domain.model.Vector3
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Everything read out of a single ARCore frame, captured atomically at the instant of the tap.
 *
 * The image arrives as NV21 rather than a `Bitmap`: the ARCore `Image` must be closed promptly
 * or the session starves, so the bytes are copied out on the GL thread and the expensive JPEG
 * conversion happens later, off it.
 */
class ArSnapshot(
    val nv21: ByteArray,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Intrinsics in the sensor-native frame, before any rotation or downscale. */
    val camera: PinholeCamera,
    /** The tap, already mapped into that same sensor-native frame. */
    val targetPixel: PointF2,
    val pose: PoseSample?,
    val trackingState: TrackingState
)

/**
 * Owns the ARCore [Session] and turns one instant of it into an [ArSnapshot].
 *
 * The reason this app runs ARCore rather than plain CameraX is `Frame.camera.imageIntrinsics`:
 * a factory calibration already expressed in the coordinate system of the CPU image the same
 * frame hands you. Camera2's `LENS_INTRINSIC_CALIBRATION` is the same data in a harder frame -
 * see [Camera2IntrinsicsReader] for the fallback that converts it.
 */
class ArCameraController : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session? = null
    private var textureAttached = false

    private val _trackingState = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _trackingAdvice = MutableStateFlow<String?>(null)
    val trackingAdvice: StateFlow<String?> = _trackingAdvice.asStateFlow()

    /** Set from the UI thread, consumed on the GL thread on the next frame. */
    private val pendingCapture = AtomicReference<PendingCapture?>(null)

    private class PendingCapture(
        val normalizedX: Float,
        val normalizedY: Float,
        val result: CompletableDeferred<Result<ArSnapshot>>
    )

    // ---- Lifecycle, driven by the hosting view ----

    fun attach(session: Session) {
        this.session = session
        textureAttached = false
    }

    fun detach() {
        session = null
        textureAttached = false
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
    }

    // ---- Capture ----

    /**
     * Request the next rendered frame, with the tap that triggered it.
     *
     * Coordinates are normalised (0..1) in view space. The snapshot is taken on the GL thread at
     * the moment the frame is drawn, so the pixels and the tap belong to the same instant - the
     * property the whole one-tap interaction exists to guarantee.
     */
    suspend fun capture(normalizedX: Float, normalizedY: Float): Result<ArSnapshot> {
        val deferred = CompletableDeferred<Result<ArSnapshot>>()
        pendingCapture.set(PendingCapture(normalizedX, normalizedY, deferred))
        return deferred.await()
    }

    // ---- GLSurfaceView.Renderer ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        textureAttached = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = this.session ?: return

        if (!textureAttached) {
            session.setCameraTextureName(backgroundRenderer.textureId)
            textureAttached = true
        }

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            _trackingAdvice.value = "The camera is not available."
            return
        }

        backgroundRenderer.draw(frame)
        publishTracking(frame)

        pendingCapture.getAndSet(null)?.let { request ->
            request.result.complete(runCatching { snapshot(frame, request) })
        }
    }

    // ---- Snapshot extraction ----

    private fun snapshot(frame: Frame, request: PendingCapture): ArSnapshot {
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength          // [fx, fy]
        val principal = intrinsics.principalPoint   // [cx, cy]
        val dimensions = intrinsics.imageDimensions // [width, height]

        val camera = PinholeCamera(
            fx = focal[0].toDouble(),
            fy = focal[1].toDouble(),
            cx = principal[0].toDouble(),
            cy = principal[1].toDouble(),
            width = dimensions[0],
            height = dimensions[1]
        )

        // ARCore performs the view -> image mapping itself, including the display rotation and
        // the aspect-fill crop. Reconstructing it by hand from the viewport is the usual way to
        // get a tap that is subtly wrong near the edges.
        val out = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW_NORMALIZED,
            floatArrayOf(request.normalizedX, request.normalizedY),
            Coordinates2d.IMAGE_PIXELS,
            out
        )

        val nv21 = frame.acquireCameraImage().use { image ->
            YuvConverter.toNv21(image)
        }

        return ArSnapshot(
            nv21 = nv21,
            imageWidth = camera.width,
            imageHeight = camera.height,
            camera = camera,
            targetPixel = PointF2(out[0].toDouble(), out[1].toDouble()),
            pose = poseOf(frame),
            trackingState = trackingStateOf(frame)
        )
    }

    private fun poseOf(frame: Frame): PoseSample? {
        if (frame.camera.trackingState != com.google.ar.core.TrackingState.TRACKING) return null
        val pose = frame.camera.pose
        return PoseSample(
            source = IntrinsicsSource.ARCORE,
            position = Vector3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()),
            rotation = QuaternionValue(
                pose.qx().toDouble(), pose.qy().toDouble(), pose.qz().toDouble(), pose.qw().toDouble()
            ),
            trackingState = TrackingState.NORMAL
        )
    }

    private fun trackingStateOf(frame: Frame): TrackingState =
        when (frame.camera.trackingState) {
            com.google.ar.core.TrackingState.TRACKING -> TrackingState.NORMAL
            com.google.ar.core.TrackingState.PAUSED -> TrackingState.LIMITED
            com.google.ar.core.TrackingState.STOPPED -> TrackingState.NOT_AVAILABLE
            else -> TrackingState.NOT_AVAILABLE
        }

    private fun publishTracking(frame: Frame) {
        _trackingState.value = trackingStateOf(frame)
        _trackingAdvice.value = when (frame.camera.trackingFailureReason) {
            com.google.ar.core.TrackingFailureReason.NONE -> null
            com.google.ar.core.TrackingFailureReason.BAD_STATE -> "Tracking lost - hold steady."
            com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark to track - add light."
            com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast - slow down."
            com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES -> "Not enough detail to track."
            com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is in use elsewhere."
            else -> null
        }
    }

    companion object {
        /** Focus and light estimation off: this app photographs surfaces, it does not light them. */
        fun configure(session: Session) {
            session.configure(
                Config(session).apply {
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
            )
        }
    }
}
