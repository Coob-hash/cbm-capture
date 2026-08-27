package ai.cbm.capture.ui.capture

import ai.cbm.capture.data.capture.ArCameraController
import ai.cbm.capture.data.capture.CaptureAssembler
import ai.cbm.capture.data.settings.AppSettings
import ai.cbm.capture.data.settings.SettingsRepository
import ai.cbm.capture.domain.imaging.ImageTransform
import ai.cbm.capture.domain.imaging.QuarterTurn
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.TrackingState
import ai.cbm.capture.domain.repository.CaptureRepository
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: CaptureRepository,
    private val assembler: CaptureAssembler,
    settingsRepository: SettingsRepository
) : ViewModel() {

    sealed interface Phase {
        data object Aiming : Phase
        data object Processing : Phase
        data class Reviewing(val preview: ReviewPreview, val description: String = "") : Phase
        data class Failed(val message: String) : Phase
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

    private val _phase = MutableStateFlow<Phase>(Phase.Aiming)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val trackingState: StateFlow<TrackingState> = controller.trackingState
    val trackingAdvice: StateFlow<String?> = controller.trackingAdvice

    val pendingCount: StateFlow<Int> = repository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Held outside [Phase] because it carries the JPEG bytes. */
    private var stagedPackage: CaptureAssembler.Package? = null

    fun onSessionReady(session: Session) {
        ArCameraController.configure(session)
        controller.attach(session)
    }

    fun onSessionPaused() = controller.detach()

    // ---- The single gesture ----

    /**
     * @param normalizedX tap position in view space, 0..1
     * @param surfaceRotation `Surface.ROTATION_*` of the display at the moment of the tap
     */
    fun onTap(normalizedX: Float, normalizedY: Float, surfaceRotation: Int) {
        if (_phase.value !is Phase.Aiming) return
        val current = settings.value
        if (!current.isConfigured) return

        _phase.value = Phase.Processing
        viewModelScope.launch {
            val snapshotResult = controller.capture(normalizedX, normalizedY)
            val snapshot = snapshotResult.getOrElse { error ->
                _phase.value = Phase.Failed(error.message ?: "The camera frame could not be read.")
                return@launch
            }

            val input = CaptureAssembler.Input(
                captureId = UUID.randomUUID(),
                buildingId = current.buildingId,
                reporterEmail = current.reporterEmail.ifBlank { null },
                description = null,
                capturedAt = Instant.now(),
                turn = QuarterTurn.forDisplayRotation(surfaceRotation)
            )

            runCatching {
                // JPEG encoding of a multi-megapixel frame is not main-thread work.
                withContext(Dispatchers.Default) { assembler.assemble(snapshot, input) }
            }.onSuccess { pkg ->
                stagedPackage = pkg
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(pkg.imageBytes, 0, pkg.imageBytes.size)
                }
                if (bitmap == null) {
                    _phase.value = Phase.Failed("The captured image could not be displayed.")
                    return@onSuccess
                }
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
                _phase.value = Phase.Failed(error.message ?: "The photo could not be prepared.")
            }
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

        val description = reviewing.description.trim().ifBlank { null }
        val pkg = CaptureAssembler.Package(
            metadata = staged.metadata.copy(description = description),
            imageBytes = staged.imageBytes,
            thumbnailBytes = staged.thumbnailBytes
        )

        _phase.value = Phase.Processing
        viewModelScope.launch {
            runCatching { repository.enqueue(pkg) }
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
}
