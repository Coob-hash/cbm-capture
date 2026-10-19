package ai.cbm.capture.ui.auth

import ai.cbm.capture.data.capture.QrFrameDecoder
import ai.cbm.capture.domain.SiteCode
import ai.cbm.capture.ui.common.InstructionCard
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The site's QR poster, read in the app. The first QR code that holds a site code - the join link
 * or a bare code, whatever [SiteCode.parse] accepts - goes to [onCode], once. Any other QR code
 * gets a line saying so, and the camera keeps looking. Nothing is photographed or kept.
 */
@Composable
fun ScanSiteScreen(onCode: (String) -> Unit, onTypeInstead: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    fun cameraAllowed() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(cameraAllowed()) }
    var refused by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        refused = !granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }
    // Back from the phone's settings, where the camera may have been allowed.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && cameraAllowed()) hasPermission = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var cameraFailed by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    // When a QR code that is not a site's was last in view; the line goes 3 s after it leaves.
    var otherCodeSeenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(otherCodeSeenAt) {
        if (otherCodeSeenAt != 0L) { delay(3_000); otherCodeSeenAt = 0L }
    }
    val currentOnCode by rememberUpdatedState(onCode)
    val onValue: (String) -> Unit = { value ->
        if (!accepted) {
            val code = SiteCode.parse(value)
            if (code != null) {
                accepted = true
                currentOnCode(code)
            } else {
                otherCodeSeenAt = SystemClock.uptimeMillis()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(CbmPalette.Ink900)) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("CBM APP", style = LocalTechStyles.current.stencil, color = LocalAccent.current.color)
            Text("Scan the QR code", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                "Point the camera at the QR code on the site's poster.",
                style = MaterialTheme.typography.bodyMedium,
                color = CbmPalette.Steel300
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                cameraFailed -> Message("The camera could not start. Type the code printed under the QR instead.")
                hasPermission -> {
                    QrCameraPreview(onValue, onFailed = { cameraFailed = true })
                    Box(Modifier.size(240.dp).border(3.dp, LocalAccent.current.color, RoundedCornerShape(16.dp)))
                    if (otherCodeSeenAt != 0L) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                            InstructionCard("This QR code is not a site code. Scan the one on the site's poster.", isWarning = true)
                        }
                    }
                }
                else -> Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Refused with "don't ask again", Android no longer shows the question: only the
                    // phone's settings can allow the camera then.
                    val askAgain = !refused || activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    Message(
                        if (askAgain) "The app needs the camera to read the QR code. Nothing is photographed or kept."
                        else "The camera is off for this app. Allow it in the phone's settings, or type the code instead."
                    )
                    CbmPrimaryButton(
                        text = if (askAgain) "Allow camera" else "Open settings",
                        onClick = {
                            if (askAgain) permission.launch(Manifest.permission.CAMERA)
                            else context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            CbmOutlineButton("Type the code instead", onTypeInstead, Modifier.fillMaxWidth(), color = Color.White)
        }
    }
}

@Composable
private fun Message(text: String) = Text(
    text,
    modifier = Modifier.padding(horizontal = 20.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = Color.White,
    textAlign = TextAlign.Center
)

/**
 * The camera, filling its box, with every frame offered to the QR reader. CameraX follows the
 * screen's lifecycle: it stops when the screen is left and is released when the screen goes.
 */
@Composable
private fun QrCameraPreview(onValue: (String) -> Unit, onFailed: (Throwable) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnFailed by rememberUpdatedState(onFailed)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(lifecycleOwner) {
        // 4:3 for both streams, so the reader sees what the preview shows. 1280 x 960 reads a
        // poster from a couple of metres without slowing the reader down.
        val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val reader = QrReader(ContextCompat.getMainExecutor(context)) { currentOnValue(it) }
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(fourThree)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, reader) }

        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                provider = future.get().also {
                    it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            } catch (e: Exception) {
                currentOnFailed(e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Reads QR codes from camera frames, on the camera's analysis thread, entirely offline. [onValue]
 * runs on [main] with the text of a QR code, for every frame that has one.
 */
private class QrReader(private val main: Executor, private val onValue: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val decoder = QrFrameDecoder()
    private var luminance = ByteArray(0)

    override fun analyze(frame: ImageProxy) {
        // Until this frame is closed no newer one arrives, so a slow phone reads fewer frames
        // rather than falling behind.
        val text = frame.use {
            val plane = it.planes[0]   // brightness: all a QR code needs
            val size = plane.rowStride * it.height
            if (luminance.size != size) luminance = ByteArray(size)
            val buffer = plane.buffer.apply { rewind() }
            buffer.get(luminance, 0, minOf(buffer.remaining(), size))
            decoder.decode(luminance, plane.rowStride, it.width, it.height)
        }
        if (text != null) main.execute { onValue(text) }
    }
}
