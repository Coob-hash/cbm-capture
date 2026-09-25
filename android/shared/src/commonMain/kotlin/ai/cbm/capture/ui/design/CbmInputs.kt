package ai.cbm.capture.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles

@Composable
fun FieldLabel(text: String) {
    Text(text.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
    Spacer(Modifier.height(6.dp))
}

@Composable
fun CbmField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", secret: Boolean = false, mono: Boolean = false) {
    val accent = LocalAccent.current.color
    Column(modifier) {
        FieldLabel(label)
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = CbmPalette.Steel300) },
            textStyle = if (mono) LocalTechStyles.current.ticketId else MaterialTheme.typography.bodyLarge,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            // Masking the characters is not enough: the keyboard must also be told it is a password,
            // or it treats it as ordinary text - autocorrect, suggestions, learning what is typed
            // (third audit 2026-09-25, finding 8).
            keyboardOptions = if (secret) SECRET_KEYBOARD else KeyboardOptions.Default,
            singleLine = true,
            shape = RoundedCornerShape(3.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent, unfocusedBorderColor = CbmPalette.Steel200,
                cursorColor = accent,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

private val SECRET_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

@Composable
fun CbmTextArea(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, maxChars: Int = 500, placeholder: String = "") {
    val accent = LocalAccent.current.color
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
            Text("${value.length}/$maxChars", style = LocalTechStyles.current.meta, color = if (value.length > maxChars) CbmPalette.Red else CbmPalette.Steel300)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= maxChars) onChange(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text(placeholder, color = CbmPalette.Steel300) },
            shape = RoundedCornerShape(3.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = CbmPalette.Steel200, cursorColor = accent),
        )
    }
}

/** Read-only title-block cell for server-prefilled, locked data (T-5). */
@Composable
fun CbmLockedField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.border(1.dp, CbmPalette.Steel200).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label.uppercase(), style = LocalTechStyles.current.stencil, color = CbmPalette.Steel400)
        Spacer(Modifier.height(3.dp))
        Text(value, style = LocalTechStyles.current.metaStrong, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun CbmChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalAccent.current.color) {
    val bg = if (selected) color else Color.Transparent
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .border(1.5.dp, if (selected) color else CbmPalette.Steel200, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = fg) }
}

@Composable
fun CbmRadioRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    val accent = LocalAccent.current.color
    Row(
        modifier.fillMaxWidth()
            .border(1.dp, if (selected) accent else CbmPalette.Steel200, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = CbmPalette.Steel400)
        }
    }
}
