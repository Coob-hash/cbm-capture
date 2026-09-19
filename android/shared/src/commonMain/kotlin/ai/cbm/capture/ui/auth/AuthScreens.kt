package ai.cbm.capture.ui.auth

import ai.cbm.capture.domain.model.Membership
import ai.cbm.capture.domain.model.RequestableRole
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** What the auth screens show. Owned by the platform's view model; the screens only render it. */
data class AuthFormState(
    val siteCode: String? = null,
    val codeInput: String = "",
    val email: String = "",
    val password: String = "",
    val role: RequestableRole = RequestableRole.USER,
    val busy: Boolean = false,
    val error: String? = null
)

@Composable
private fun AuthScaffold(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(label)
    }
}

@Composable
private fun EmailAndPassword(state: AuthFormState, onEmail: (String) -> Unit, onPassword: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = state.email, onValueChange = onEmail, label = { Text("Email") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = state.password, onValueChange = onPassword, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

/** First launch without a site: the code comes from the site's QR poster, or is typed from it. */
@Composable
fun JoinSiteScreen(
    state: AuthFormState,
    onCodeChange: (String) -> Unit,
    onContinue: () -> Unit,
    onHaveAccount: () -> Unit
) = AuthScaffold("Join your site", "Scan the QR code on the site's poster with your phone's camera, or type the code printed under it.") {
    OutlinedTextField(
        value = state.codeInput, onValueChange = onCodeChange, label = { Text("Site code") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth()
    )
    ErrorText(state.error)
    PrimaryButton("Continue", state.busy, enabled = state.codeInput.isNotBlank(), onClick = onContinue)
    TextButton(onClick = onHaveAccount) { Text("I already have an account — log in") }
}

@Composable
fun LoginScreen(
    state: AuthFormState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onSignUp: () -> Unit
) = AuthScaffold("Log in", "Every session lasts one hour; after that you log in again.") {
    EmailAndPassword(state, onEmail, onPassword, onLogin)
    ErrorText(state.error)
    PrimaryButton("Log in", state.busy, enabled = state.email.isNotBlank() && state.password.isNotBlank(), onClick = onLogin)
    TextButton(onClick = onSignUp) { Text("New here? Create an account") }
}

/** Sign-up is three fields: email, password, role. The site comes from the QR code. */
@Composable
fun SignUpScreen(
    state: AuthFormState,
    siteLabel: String,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRole: (RequestableRole) -> Unit,
    onCreate: () -> Unit,
    onHaveAccount: () -> Unit
) = AuthScaffold("Create your account", "Site: $siteLabel") {
    EmailAndPassword(state, onEmail, onPassword, onCreate)
    Text("I am a", style = MaterialTheme.typography.titleMedium)
    Column {
        RequestableRole.entries.forEach { role ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = state.role == role, onClick = { onRole(role) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = state.role == role, onClick = null)
                Text(role.label, Modifier.padding(start = 8.dp))
            }
        }
    }
    if (state.role == RequestableRole.FM) {
        Text(
            "A facility manager account is active once the operator has approved it. You can log in meanwhile.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
    ErrorText(state.error)
    PrimaryButton("Create account", state.busy, enabled = state.email.isNotBlank() && state.password.length >= 8, onClick = onCreate)
    Text("At least 8 characters for the password.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onHaveAccount) { Text("I already have an account — log in") }
}

/** More than one role on this account: a session serves exactly one. */
@Composable
fun ChooseRoleScreen(memberships: List<Membership>, busy: Boolean, error: String?, onPick: (Membership) -> Unit, onLogout: () -> Unit) =
    AuthScaffold("Continue as", "You can change role by logging in again.") {
        memberships.forEach { m ->
            OutlinedButton(onClick = { onPick(m) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("${roleLabel(m.role)} · ${m.siteName}${if (m.isActive) "" else " (awaiting approval)"}")
            }
        }
        ErrorText(error)
        TextButton(onClick = onLogout) { Text("Log out") }
    }

/** A pending role, or a role whose screens are not built yet: nothing to do but wait or log out. */
@Composable
fun WaitingScreen(title: String, message: String, busy: Boolean, onRefresh: () -> Unit, onLogout: () -> Unit) =
    AuthScaffold(title, message) {
        PrimaryButton("Check again", busy, onClick = onRefresh)
        TextButton(onClick = onLogout) { Text("Log out") }
    }

fun roleLabel(wire: String): String = when (wire) {
    "USER" -> "User"
    "TECHNICIAN" -> "Technician"
    "FM" -> "Facility manager"
    "ADMIN" -> "Administrator"
    else -> wire
}
