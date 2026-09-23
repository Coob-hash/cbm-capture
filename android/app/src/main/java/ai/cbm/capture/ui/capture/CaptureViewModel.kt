package ai.cbm.capture.ui.capture

import ai.cbm.capture.data.capture.ArCameraController
import ai.cbm.capture.data.capture.Camera2IntrinsicsReader
import ai.cbm.capture.data.capture.CaptureAssembler
import ai.cbm.capture.data.capture.StillCameraController
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.domain.imaging.ImageTransform
import ai.cbm.capture.domain.imaging.QuarterTurn
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.TrackingState
import ai.cbm.capture.domain.repository.CaptureRepository
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Drives the capture screen.
 *
 * The screen has exactly one gesture: the worker taps the damage. That single tap is both the
 * shutter and the target designation, which is not only a UI simplification - it is what
 * guarantees the tap and the frame are the same instant. An "aim, shoot, then mark" flow would
 * let the phone move in between, and the marked pixel would belong to a different view of the
 * room than the photograph.
 *
 * Two cameras sit behind that gesture. ARCore is the primary one (its frame, its K and its pose
 * are one instant); the standard camera ([StillCameraController]) serves phones that cannot run
 * ARCore or where it was declined, with K from the factory calibration or the photo's EXIF and
 * the source recorded truthfully (`docs/INTRINSICS.md` section 1).
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: CaptureRepository,
    private val assembler: CaptureAssembler,
    private val sessions: SessionStore,
    camera2: Camera2IntrinsicsReader,
    savedState: SavedStateHandle
) : ViewModel() {

    /**
     * The report this camera session adds a photo to: an existing one when the workflows asked for
     * a replacement (navigation argument), otherwise a new one, created here once.
     */
    val reportId: String = savedState.get<String>(REPORT_ID_ARG)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    val isReplacement: Boolean = !savedState.get<String>(REPORT_ID_ARG).isNullOrBlank()

    sealed interface Phase {
        data object Aiming : Phase
        data object Processing : Phase
        data class Reviewing(val preview: ReviewPreview, val description: String = "") : Phase
        data class Failed(val message: String) : Phase
    }

    /** Which camera the screen runs. Decided once per screen, on the first resume with permission. */
    enum class CameraMode {
        /** Finding out whether ARCore can run, or waiting for the worker to come back from installing it. */
        CHECKING,
        AR,
        STANDARD
    }

    data class ReviewPreview(
        val captureId: String,
        val bitmap: Bitmap,
        val targetX: Double,
        val targetY: Double,
        val width: Int,
        val height: Int,
        val intrinsicsSource: IntrinsicsSource,
        val intrinsicsTrusted: Boolean,
        val focalLength: Double,
        val centrality: Double
    ) {
        val isOffCentre: Boolean get() = centrality > ImageTransform.CENTRALITY_WARNING_THRESHOLD
    }

    val controller = ArCameraController()
    val stillCamera = StillCameraController(camera2)

    private val _cameraMode = MutableStateFlow(CameraMode.CHECKING)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    /** How many times the ARCore install flow has been started from this screen. */
    private var installRequests = 0

    private val _phase = MutableStateFlow<Phase>(Phase.Aiming)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val trackingState: StateFlow<TrackingState> = controller.trackingState
    val trackingAdvice: StateFlow<String?> = controller.trackingAdvice
    val standardCameraReady: StateFlow<Boolean> = stillCamera.ready

    val pendingCount: StateFlow<Int> = (sessions.current()?.let { repository.observePendingCount(it.accountId) } ?: flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Held outside [Phase] because it carries the JPEG bytes. */
    private var stagedPackage: CaptureAssembler.Package? = null

    // ---- Which camera ----

    /**
     * Decide between the AR camera and the standard one. Called on every resume while the mode is
     * still [CameraMode.CHECKING]; a no-op once decided. Main thread: the install prompt is an
     * Activity.
     *
     * ARCore's own install flow is followed (`requestInstall` with `userRequestedInstall = true`
     * the first time, `false` on the resume that follows), with one guard it lacks: a phone where
     * the install cannot complete - no Play Store, say - would otherwise be sent to the prompt on
     * every resume. After [MAX_INSTALL_REQUESTS] the standard camera is used instead.
     */
    suspend fun resolveCameraMode(activity: Activity) {
        if (_cameraMode.value != CameraMode.CHECKING) return
        val arCore = ArCoreApk.getInstance()

        var availability = arCore.checkAvailability(activity)
        var waited = 0
        while (availability.isTransient && waited < AVAILABILITY_WAIT_MS) {
            delay(AVAILABILITY_POLL_MS)
            waited += AVAILABILITY_POLL_MS.toInt()
            availability = arCore.checkAvailability(activity)
        }
        if (_cameraMode.value != CameraMode.CHECKING) return

        if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            _cameraMode.value = CameraMode.AR
            return
        }
        // Still checking after the wait is not a "no": the install flow answers it for sure.
        if (!availability.isSupported && !availability.isTransient) {
            Log.i(TAG, "AR is not available on this phone ($availability): standard camera")
            _cameraMode.value = CameraMode.STANDARD
            return
        }
        if (installRequests >= MAX_INSTALL_REQUESTS) {
            Log.i(TAG, "AR still missing after $installRequests install requests: standard camera")
            _cameraMode.value = CameraMode.STANDARD
            return
        }
        try {
            when (arCore.requestInstall(activity, installRequests == 0)) {
                ArCoreApk.InstallStatus.INSTALLED -> _cameraMode.value = CameraMode.AR
                // The worker is in the install flow; the next resume decides.
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> installRequests++
            }
        } catch (e: UnavailableException) {
            // Declined, or not compatible after all.
            Log.i(TAG, "AR cannot be installed (${e.javaClass.simpleName}): standard camera")
            _cameraMode.value = CameraMode.STANDARD
        }
    }

    /** The AR session could not be created or resumed: fall back rather than show a black screen. */
    fun onArUnavailable(error: Throwable) {
        Log.w(TAG, "The AR camera could not start; using the standard camera", error)
        controller.detach()
        _cameraMode.value = CameraMode.STANDARD
    }

    fun onStandardCameraFailed(error: Throwable) {
        Log.w(TAG, "The standard camera could not start", error)
        _phase.value = Phase.Failed("The camera could not start. Close any other app that uses the camera, then try again.")
    }

    fun onSessionReady(session: Session) {
        ArCameraController.configure(session)
        controller.attach(session)
    }

    fun onSessionPaused() = controller.detach()

    // ---- The single gesture ----

    /**
     * A tap on the AR view.
     *
     * @param normalizedX tap position in view space, 0..1
     * @param surfaceRotation `Surface.ROTATION_*` of the display at the moment of the tap
     */
    fun onTap(normalizedX: Float, normalizedY: Float, surfaceRotation: Int) {
        if (_phase.value !is Phase.Aiming) return
        val siteId = siteOrFail() ?: return

        _phase.value = Phase.Processing
        viewModelScope.launch {
            val snapshot = controller.capture(normalizedX, normalizedY).getOrElse { error ->
                Log.w(TAG, "The camera frame could not be read", error)
                _phase.value = Phase.Failed("The camera did not give a usable picture. Try again.")
                return@launch
            }
            prepare(input(siteId, QuarterTurn.forDisplayRotation(surfaceRotation))) { assembler.assemble(snapshot, it) }
        }
    }

    /** A tap on the standard camera's preview, in view pixels. */
    fun onStandardTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int, surfaceRotation: Int) {
        if (_phase.value !is Phase.Aiming) return
        val siteId = siteOrFail() ?: return

        _phase.value = Phase.Processing
        viewModelScope.launch {
            val still = try {
                stillCamera.capture(x, y, viewWidth, viewHeight, surfaceRotation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StillCameraController.TapOutsidePhoto) {
                _phase.value = Phase.Failed("Tap on the picture itself, not on the dark band around it.")
                return@launch
            } catch (e: StillCameraController.NoCalibration) {
                Log.w(TAG, "No factory calibration and no focal length in the photo", e)
                _phase.value = Phase.Failed(
                    "This phone does not tell the app enough about its camera to place a photo on the " +
                        "building. Please report from another phone."
                )
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "The standard camera could not take the photo", e)
                _phase.value = Phase.Failed("The camera did not give a usable picture. Try again.")
                return@launch
            }
            prepare(input(siteId, still.turn)) { assembler.assemble(still, it) }
        }
    }

    private fun siteOrFail(): String? {
        val siteId = sessions.current()?.membership?.siteId
        if (siteId == null) _phase.value = Phase.Failed("Your session has ended. Log in again to report.")
        return siteId
    }

    private fun input(siteId: String, turn: QuarterTurn) = CaptureAssembler.Input(
        captureId = UUID.randomUUID(),
        reportId = reportId,
        buildingId = siteId,
        description = null,
        capturedAt = Instant.now(),
        turn = turn
    )

    /** Assemble the package off the main thread and open the review sheet on it. */
    private suspend fun prepare(input: CaptureAssembler.Input, assemble: (CaptureAssembler.Input) -> CaptureAssembler.Package) {
        runCatching {
            // JPEG encoding of a multi-megapixel frame is not main-thread work.
            withContext(Dispatchers.Default) { assemble(input) }
        }.onSuccess { pkg ->
            stagedPackage = pkg
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(pkg.imageBytes, 0, pkg.imageBytes.size)
            }
            if (bitmap == null) {
                _phase.value = Phase.Failed("The captured image could not be displayed.")
                return@onSuccess
            }
            Log.i(TAG, "Prepared ${pkg.metadata.image.width}x${pkg.metadata.image.height}, K from " +
                "${pkg.metadata.camera.source} (fx=${"%.1f".format(pkg.metadata.camera.fx)}, trusted=${pkg.metadata.camera.trusted}), " +
                "tap at ${pkg.metadata.target.pixel}")
            _phase.value = Phase.Reviewing(
                ReviewPreview(
                    captureId = pkg.metadata.captureId,
                    bitmap = bitmap,
                    targetX = pkg.metadata.target.pixel.x,
                    targetY = pkg.metadata.target.pixel.y,
                    width = pkg.metadata.image.width,
                    height = pkg.metadata.image.height,
                    intrinsicsSource = pkg.metadata.camera.source,
                    intrinsicsTrusted = pkg.metadata.camera.trusted,
                    focalLength = pkg.metadata.camera.fx,
                    centrality = pkg.metadata.target.centrality ?: 0.0
                )
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "The photo could not be prepared", error)
            _phase.value = Phase.Failed("That photo could not be prepared. Take it again.")
        }
    }

    // ---- Review actions ----

    fun updateDescription(text: String) {
        val reviewing = _phase.value as? Phase.Reviewing ?: return
        _phase.value = reviewing.copy(description = text)
    }

    fun discard() {
        stagedPackage = null
        _phase.value = Phase.Aiming
    }

    fun dismissError() {
        _phase.value = Phase.Aiming
    }

    fun consumeToast() {
        _toast.value = null
    }

    /**
     * Persist to the outbox, then let WorkManager deal with the network.
     *
     * The worker is told "saved", not "sent": the report is durable at this point, and whether
     * it has reached n8n yet is a separate fact, shown on the Reports screen. Promising delivery
     * the app cannot yet guarantee is how a queue silently loses trust.
     */
    fun send(onQueued: (allowMetered: Boolean) -> Unit) {
        val reviewing = _phase.value as? Phase.Reviewing ?: return
        val staged = stagedPackage ?: return
        val session = sessions.current() ?: run {
            _phase.value = Phase.Failed("Your session has ended. Log in again, then take the photo again.")
            return
        }

        val description = reviewing.description.trim().ifBlank { null }
        val pkg = CaptureAssembler.Package(
            metadata = staged.metadata.copy(description = description),
            imageBytes = staged.imageBytes,
            thumbnailBytes = staged.thumbnailBytes
        )

        _phase.value = Phase.Processing
        viewModelScope.launch {
            runCatching { repository.enqueue(pkg, session.accountId) }
                .onSuccess {
                    stagedPackage = null
                    _toast.value = "Report saved. It will upload automatically."
                    _phase.value = Phase.Aiming
                    onQueued(repository.uploadOnMeteredAllowed())
                }
                .onFailure { error ->
                    _phase.value = Phase.Failed("Could not save the report: ${error.message}")
                }
        }
    }

    override fun onCleared() {
        stillCamera.unbind()
    }

    companion object {
        const val REPORT_ID_ARG = "reportId"
        private const val MAX_INSTALL_REQUESTS = 2
        private const val AVAILABILITY_POLL_MS = 200L
        private const val AVAILABILITY_WAIT_MS = 3_000
    }
}

private const val TAG = "CbmCapture"
