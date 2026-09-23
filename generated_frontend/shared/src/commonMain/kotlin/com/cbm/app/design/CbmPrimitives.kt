package com.cbm.app.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/** Stencil section label + hairline rule — the drawing title-block motif. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(Modifier.weight(1f), color = CbmPalette.Steel200)
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun MetaRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label.uppercase(), style = LocalTechStyles.current.meta, color = CbmPalette.Steel400, modifier = Modifier.width(96.dp))
        Text(value, style = LocalTechStyles.current.metaStrong, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun pad2(v: Long): String = if (v < 10) "0$v" else v.toString()

fun formatCountdown(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(sec)}" else "${pad2(m)}:${pad2(sec)}"
}

@Composable
fun rememberNow(tickMs: Long = 1000): Long {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(tickMs); now = Clock.System.now().toEpochMilliseconds() } }
    return now
}
