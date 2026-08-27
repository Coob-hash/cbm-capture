package ai.cbm.capture.ui.capture

import ai.cbm.capture.data.capture.ArCameraController
import ai.cbm.capture.domain.model.TrackingState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session

/**
 * The camera screen. One instruction, one gesture.
 */
@Composable
fun CaptureScreen(
    onOpenReports: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as Activity

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val trackingState by viewModel.trackingState.collectAsStateWithLifecycle()
    val trackingAdvice by viewModel.trackingAdvice.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

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

    Box(Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            ArCameraPreview(
                controller = viewModel.controller,
                onSessionReady = viewModel::onSessionReady,
                onSessionPaused = viewModel::onSessionPaused,
                onTap = { nx, ny, rotation -> viewModel.onTap(nx, ny, rotation) }
            )
        } else {
            PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CalibrationBadge(trackingState)
                BadgedBox(badge = { if (pendingCount > 0) Badge { Text("$pendingCount") } }) {
                    FilledTonalButton(onClick = onOpenReports) {
                        Icon(Icons.Default.Inbox, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("My reports")
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !settings.isConfigured -> SetupPrompt(onOpenSettings)
                    trackingAdvice != null -> InstructionCard(trackingAdvice!!, isWarning = true)
                    phase is CaptureViewModel.Phase.Processing -> InstructionCard("Preparing the photo...")
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
                viewModel.send { allowMetered -> UploadWorker.enqueue(activity, allowMetered) }
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
 * even though nothing is drawn on top of the feed. Session creation is deferred to
 * `onResume` semantics via [DisposableEffect]: ARCore refuses to resume a session whose camera
 * permission was granted after the session was built.
 */
@Composable
private fun ArCameraPreview(
    controller: ArCameraController,
    onSessionReady: (Session) -> Unit,
    onSessionPaused: () -> Unit,
    onTap: (normalizedX: Float, normalizedY: Float, surfaceRotation: Int) -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    var session by remember { mutableStateOf<Session?>(null) }

    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(Unit) {
        val created = runCatching {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> Session(activity)
                // The user was sent to install ARCore; the activity will be resumed afterwards
                // and this effect will run again.
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> null
                else -> null
            }
        }.getOrNull()

        if (created != null) {
            session = created
            onSessionReady(created)
            created.resume()
            glView.onResume()
        }

        onDispose {
            glView.onPause()
            onSessionPaused()
            session?.pause()
            session?.close()
            session = null
        }
    }

    AndroidView(
        factory = { view ->
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

@Composable
private fun CalibrationBadge(state: TrackingState) {
    val text = when (state) {
        TrackingState.NORMAL -> "Calibrated"
        TrackingState.LIMITED -> "Steadying"
        TrackingState.NOT_AVAILABLE -> "Starting"
    }
    Surface(shape = CircleShape, tonalElevation = 3.dp) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SetupPrompt(onOpenSettings: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("This phone is not set up yet", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        }
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
