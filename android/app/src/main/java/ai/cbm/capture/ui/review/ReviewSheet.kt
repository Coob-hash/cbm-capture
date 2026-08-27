package ai.cbm.capture.ui.review

import ai.cbm.capture.ui.capture.CaptureViewModel
import ai.cbm.capture.ui.common.GuidanceBanner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Confirm and describe the capture before it joins the queue.
 *
 * The marker is positioned from `metadata.target.pixel` over the transmitted JPEG - not from
 * the raw screen tap. That makes this screen a live end-to-end check of the transform chain: if
 * the rotation applied to the pixels ever disagreed with the rotation applied to K and to the
 * tap, the marker would visibly sit somewhere other than the damage the worker touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    state: CaptureViewModel.Phase.Reviewing,
    onDescriptionChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preview = state.preview

    ModalBottomSheet(onDismissRequest = onDiscard, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Check the photo", style = MaterialTheme.typography.headlineSmall)

            MarkedPhoto(preview)

            if (preview.isOffCentre) {
                GuidanceBanner(
                    icon = Icons.Default.WarningAmber,
                    tone = MaterialTheme.colorScheme.error,
                    title = "Move closer to centre",
                    message = "The damage is near the edge of the photo, where the lens bends " +
                        "straight lines. Retake it with the damage nearer the middle for a more " +
                        "reliable location."
                )
            }

            if (!preview.intrinsicsTrusted) {
                GuidanceBanner(
                    icon = Icons.Default.HelpOutline,
                    tone = MaterialTheme.colorScheme.tertiary,
                    title = "Location will be checked by hand",
                    message = "This phone did not report a usable camera calibration, so the office " +
                        "will place this report on the building model manually. You can still send it."
                )
            }

            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text("What is wrong?") },
                placeholder = { Text("For example: door handle detached, will not latch") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            TechnicalDetails(preview)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Retake") }
                Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MarkedPhoto(preview: CaptureViewModel.ReviewPreview) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(preview.width.toFloat() / preview.height.toFloat())
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.TopStart
    ) {
        Image(
            bitmap = preview.bitmap.asImageBitmap(),
            contentDescription = "Photo of the damage, with a marker on the part you tapped",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(
                x = (preview.targetX / preview.width * size.width).toFloat(),
                y = (preview.targetY / preview.height * size.height).toFloat()
            )
            drawCircle(Color.White, radius = 20.dp.toPx(), center = centre, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.White, radius = 3.dp.toPx(), center = centre)
        }
    }
}

@Composable
private fun TechnicalDetails(preview: CaptureViewModel.ReviewPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Technical details", style = MaterialTheme.typography.titleSmall)
        DetailRow("Calibration", preview.intrinsicsSource.displayName)
        DetailRow("Trusted", if (preview.intrinsicsTrusted) "Yes" else "No")
        DetailRow("Image", "${preview.width} x ${preview.height} px")
        DetailRow("Focal length", "%.1f px".format(preview.focalLength))
        DetailRow("Target pixel", "%.0f, %.0f".format(preview.targetX, preview.targetY))
        DetailRow("Distance from centre", "%.0f%%".format(preview.centrality * 100))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
