package com.cbm.app.feature.reporter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cbm.app.design.LedDot
import com.cbm.app.design.StatusBadge
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Viewfinder shell. The native ARCore preview binds into the marked area in
 * production (PRD §11: capture stays native). Tap = shutter (PRD 1.0): the
 * point touched is marked on the photo.
 */
@Composable
fun CaptureScreen(reportId: String?, attemptsLeft: Int, onBack: () -> Unit, onCaptured: (Float, Float) -> Unit) {
    var flash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(CbmPalette.Ink900)) {
        Box(
            Modifier.fillMaxSize().pointerInput(reportId) {
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                detectTapGestures { offset ->
                    scope.launch { flash = true; delay(160); flash = false }
                    onCaptured(offset.x / w, offset.y / h)
                }
            }
        ) { ViewfinderGrid() }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(CbmPalette.Green)
                Spacer(Modifier.width(6.dp))
                Text("CALIBRATED · K LOCKED", style = LocalTechStyles.current.stencil, color = CbmPalette.Green)
            }
            Spacer(Modifier.weight(1f))
            if (reportId != null) StatusBadge("REPLACEMENT · $attemptsLeft LEFT", CbmPalette.Amber)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CbmPalette.Ink800).navigationBarsPadding().padding(20.dp)) {
            Text("Tap the damaged part", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("The tap is the shutter — the point you touch is marked on the photo.", style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel300)
            if (reportId != null) {
                Spacer(Modifier.height(6.dp))
                Text("BOUND TO REPORT ${reportId.uppercase()} · COUNTS AS THE NEXT ATTEMPT", style = LocalTechStyles.current.stencil, color = CbmPalette.Amber)
            }
        }

        if (flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.85f)))
    }
}

@Composable
private fun ViewfinderGrid() {
    val line = CbmPalette.Steel700
    Canvas(Modifier.fillMaxSize()) {
        for (i in 1..2) {
            drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
            drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
        }
        drawLine(line, Offset(size.width / 2 - 16, size.height / 2), Offset(size.width / 2 + 16, size.height / 2), 2f)
        drawLine(line, Offset(size.width / 2, size.height / 2 - 16), Offset(size.width / 2, size.height / 2 + 16), 2f)
    }
}
