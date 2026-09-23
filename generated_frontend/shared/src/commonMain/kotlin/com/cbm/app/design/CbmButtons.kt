package com.cbm.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cbm.app.theme.CbmPalette
import com.cbm.app.theme.LocalAccent
import com.cbm.app.theme.LocalDimens

@Composable
fun CbmPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tall: Boolean = false, color: Color = LocalAccent.current.color) {
    val d = LocalDimens.current
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(if (tall) 56.dp else d.buttonHeight),
        shape = RoundedCornerShape(d.buttonRadius),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White, disabledContainerColor = CbmPalette.Steel200, disabledContentColor = CbmPalette.Steel400),
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun CbmOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface) {
    val d = LocalDimens.current
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(d.buttonHeight),
        shape = RoundedCornerShape(d.buttonRadius),
        border = BorderStroke(1.5.dp, if (enabled) color else CbmPalette.Steel300),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = CbmPalette.Steel300),
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun CbmDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    CbmOutlineButton(text, onClick, modifier, enabled, CbmPalette.Red)

/** Compact 36dp action used on queue/offer cards. */
@Composable
fun CbmSmallAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalAccent.current.color, filled: Boolean = true) {
    if (filled) Button(onClick, modifier.height(36.dp), shape = RoundedCornerShape(3.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium) }
    else OutlinedButton(onClick, modifier.height(36.dp), shape = RoundedCornerShape(3.dp), border = BorderStroke(1.5.dp, color), colors = ButtonDefaults.outlinedButtonColors(contentColor = color), contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium) }
}
