package ai.cbm.capture.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalDimens
import ai.cbm.capture.ui.theme.LocalTechStyles

/** Bordered paper panel with an optional status rail on the left edge —
 *  the inspection-sheet card used across all three roles. */
@Composable
fun CbmPanel(
    modifier: Modifier = Modifier,
    rail: Color? = null,
    header: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalDimens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(d.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (rail != null) Box(Modifier.width(d.rail).fillMaxHeight().background(rail))
            Column(Modifier.padding(d.s4).fillMaxWidth()) {
                if (header != null) { SectionHeader(header, trailing = trailing); Spacer(Modifier.height(d.s3)) }
                content()
            }
        }
    }
}

/** KPI tile: big mono figure, stencil label, accent frame when selected. */
@Composable
fun CbmKpiTile(value: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val accent = LocalAccent.current
    Column(
        modifier
            .border(1.dp, if (selected) accent.color else MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .background(if (selected) accent.soft else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(value, style = LocalTechStyles.current.kpi, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), style = LocalTechStyles.current.stencil, color = if (selected) accent.color else CbmPalette.Steel400)
    }
}
