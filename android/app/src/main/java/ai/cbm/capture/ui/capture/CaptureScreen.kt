package ai.cbm.capture.ui.capture

import ai.cbm.capture.data.capture.ArCameraController
import ai.cbm.capture.data.capture.StillCameraController
import ai.cbm.capture.domain.model.TrackingState
import ai.cbm.capture.ui.capture.CaptureViewModel.CameraMode
import ai.cbm.capture.ui.common.InstructionCard
import ai.cbm.capture.ui.common.Toast
import ai.cbm.capture.ui.review.ReviewSheet
import ai.cbm.capture.work.UploadWorker
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.CancellationException

/**
 * The camera screen. One instruction, one gesture.
 */
@Composable
fun CaptureScreen(
    /** Back to the reporter's home; also called once a photo is saved. */
    onDone: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val trackingState by viewModel.trackingState.collectAsStateWithLifecycle()
    val trackingAdvice by viewModel.trackingAdvice.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val standardReady by viewModel.standardCameraReady.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Every resume can end a wait: back from the permission dialog, or back from installing
    // ARCore. Counting resumes re-runs the decision below until it is made.
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumes++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hasCameraPermission, resumes) {
        if (hasCameraPermission) viewModel.resolveCameraMode(activity)
    }

    Box(Modifier.fillMaxSize()) {
        when {
            !hasCameraPermission -> PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
            cameraMode == CameraMode.AR -> ArCameraPreview(
                controller = viewModel.controller,
                onSessionReady = viewModel::onSessionReady,
                onSessionPaused = viewModel::onSessionPaused,
                onUnavailable = viewModel::onArUnavailable,
                onTap = { nx, ny, rotation -> viewModel.onTap(nx, ny, rotation) }
            )
            cameraMode == CameraMode.STANDARD -> StandardCameraPreview(
                controller = viewModel.stillCamera,
                onFailed = viewModel::onStandardCameraFailed,
                onTap = viewModel::onStandardTap
            )
            else -> Box(Modifier.fillMaxSize().background(Color.Black))
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CalibrationBadge(
                    when (cameraMode) {
                        CameraMode.AR -> trackingState
                        CameraMode.STANDARD -> if (standardReady) TrackingState.NORMAL else TrackingState.NOT_AVAILABLE
                        CameraMode.CHECKING -> TrackingState.NOT_AVAILABLE
                    }
                )
                BadgedBox(badge = { if (pendingCount > 0) Badge { Text("$pendingCount") } }) {
                    FilledTonalButton(onClick = onDone) {
                        Icon(Icons.Default.Inbox, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("My reports")
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    cameraMode == CameraMode.AR && trackingAdvice != null -> InstructionCard(trackingAdvice!!, isWarning = true)
                    phase is CaptureViewModel.Phase.Processing -> InstructionCard("Preparing the photo...")
                    viewModel.isReplacement -> InstructionCard("Another photo of the same problem: tap the damaged part")
                    else -> InstructionCard("Tap the damaged part")
                }
                toast?.let { message ->
                    Spacer(Modifier.size(12.dp))
                    Toast(message, Icons.Default.CheckCircle)
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(3_000)
                        viewModel.consumeToast()
                    }
                }
            }
        }
    }

    (phase as? CaptureViewModel.Phase.Reviewing)?.let { reviewing ->
        ReviewSheet(
            state = reviewing,
            onDescriptionChange = viewModel::updateDescription,
            onDiscard = viewModel::discard,
            onSend = {
                viewModel.send { allowMetered ->
                    UploadWorker.enqueue(activity, allowMetered)
                    onDone()
                }
            }
        )
    }

    (phase as? CaptureViewModel.Phase.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Could not take the photo") },
            text = { Text(failed.message) }
        )
    }
}

/**
 * Hosts the ARCore session in a [GLSurfaceView].
 *
 * ARCore requires a GL context to be pumping the camera texture, so a surface is unavoidable
 * even though nothing is drawn on top of the feed. The session follows the activity: created on
 * the first resume (ARCore refuses to resume a session whose camera permission was granted after
 * it was built), paused on every pause, resumed on every resume, closed when the screen goes. A
 * session that cannot be created or resumed is reported through [onUnavailable] - the screen then
 * falls back to the standard camera instead of staying black.
 */
@Composable
private fun ArCameraPreview(
    controller: ArCameraController,
    onSessionReady: (Session) -> Unit,
    onSessionPaused: () -> Unit,
    onUnavailable: (Throwable) -> Unit,
    onTap: (normalizedX: Float, normalizedY: Float, surfaceRotation: Int) -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(lifecycleOwner) {
        var session: Session? = null
        var failed = false

        fun resume() {
            if (failed) return
            val current = session ?: try {
                Session(activity).also {
                    session = it
                    onSessionReady(it)
                }
            } catch (e: Exception) {
                failed = true
                onUnavailable(e)
                return
            }
            try {
                current.resume()
                glView.onResume()
            } catch (e: CameraNotAvailableException) {
                failed = true
                onUnavailable(e)
            }
        }

        // Added while the screen is already resumed, the observer is brought up to date at once,
        // ON_RESUME included, so the first resume needs no separate call.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> {
                    glView.onPause()
                    session?.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
            onSessionPaused()
            session?.pause()
            session?.close()
            session = null
        }
    }

    AndroidView(
        factory = { _ ->
            glView.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                    val rotation = v.display?.rotation ?: 0
                    controller.setDisplayGeometry(rotation, v.width, v.height)
                    onTap(event.x / v.width, event.y / v.height, rotation)
                    v.performClick()
                }
                true
            }
            glView
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            val rotation = view.display?.rotation ?: 0
            if (view.width > 0 && view.height > 0) {
                controller.setDisplayGeometry(rotation, view.width, view.height)
            }
        }
    )
}

/**
 * The camera for phones without AR: the whole frame shown letterboxed ("fit centre"), so the
 * worker sees exactly what the photograph will contain and a tap maps onto it without guessing
 * at a crop. CameraX follows the screen's lifecycle on its own.
 */
@Composable
private fun StandardCameraPreview(
    controller: StillCameraController,
    onFailed: (Throwable) -> Unit,
    onTap: (x: Float, y: Float, viewWidth: Int, viewHeight: Int, surfaceRotation: Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        try {
            controller.bind(context, lifecycleOwner, previewView)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(e)
        }
    }
    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    AndroidView(
        factory = { _ ->
            previewView.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                    onTap(event.x, event.y, v.width, v.height, v.display?.rotation ?: 0)
                    v.performClick()
                }
                true
            }
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun CalibrationBadge(state: TrackingState) {
    val text = when (state) {
        TrackingState.NORMAL -> "Ready"
        TrackingState.LIMITED -> "Hold steady"
        TrackingState.NOT_AVAILABLE -> "Starting the camera"
    }
    Surface(shape = CircleShape, tonalElevation = 3.dp) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Camera access is needed to report damage", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRequest) { Text("Allow camera") }
        }
    }
}
