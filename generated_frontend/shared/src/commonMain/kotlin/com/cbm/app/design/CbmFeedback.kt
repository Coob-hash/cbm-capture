package com.cbm.app.design

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles

enum class AlertKind(val color: Color, val glyph: String) {
    INFO(CbmPalette.Blueprint, "i"), WARN(CbmPalette.Amber, "!"), CRITICAL(CbmPalette.Red, "▲"), OK(CbmPalette.Green, "✓")
}

@Composable
fun CbmInlineAlert(kind: AlertKind, text: String, modifier: Modifier = Modifier, title: String? = null) {
    Row(modifier.fillMaxWidth().border(1.dp, kind.color).padding(10.dp), verticalAlignment = Alignment.Top) {
        Text(kind.glyph, style = LocalTechStyles.current.metaStrong, color = kind.color)
        Spacer(Modifier.width(8.dp))
        Column {
            if (title != null) { Text(title.uppercase(), style = LocalTechStyles.current.stencil, color = kind.color); Spacer(Modifier.height(2.dp)) }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun CbmEmpty(title: String, body: String, modifier: Modifier = Modifier, glyph: String = "□") {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(glyph, style = LocalTechStyles.current.kpi, color = CbmPalette.Steel200)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
    }
}

@Composable
fun CbmLoading(label: String = "Loading") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        LedDot(CbmPalette.Amber, blink = true)
        Spacer(Modifier.width(8.dp))
        Text(label.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
    }
}
