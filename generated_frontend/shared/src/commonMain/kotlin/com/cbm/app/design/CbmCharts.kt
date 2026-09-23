package com.cbm.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.domain.WorkloadEntry
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalTechStyles

/** Open tickets by status — flat bar chart, drawing-sheet style. */
@Composable
fun StatusBars(data: List<Pair<String, Int>>, colors: List<Color>, modifier: Modifier = Modifier) {
    val max = (data.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEachIndexed { i, (_, v) ->
                val h = 72.dp * (v.toFloat() / max)
                Box(Modifier.weight(1f).height(h.coerceAtLeast(3.dp)).background(colors.getOrElse(i) { CbmPalette.Steel400 }))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CbmPalette.Steel200))
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEach { (label, v) ->
                Column(Modifier.weight(1f)) {
                    Text(v.toString(), style = LocalTechStyles.current.metaStrong, color = MaterialTheme.colorScheme.onSurface)
                    Text(label.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400, maxLines = 1)
                }
            }
        }
    }
}

/** Open workload per technician, with the blinking first-job LED (F-4a). */
@Composable
fun WorkloadBars(entries: List<WorkloadEntry>, modifier: Modifier = Modifier) {
    val max = (entries.maxOfOrNull { it.openJobs } ?: 1).coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(86.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(10.dp).background(CbmPalette.Steel100)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(e.openJobs.toFloat() / max).background(CbmPalette.Steel500))
                }
                Spacer(Modifier.width(8.dp))
                Text(e.openJobs.toString(), style = LocalTechStyles.current.metaStrong)
                if (e.firstJob) { Spacer(Modifier.width(6.dp)); LedDot(CbmPalette.HiVis, blink = true, size = 6) }
            }
        }
    }
}
