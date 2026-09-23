package ai.cbm.capture.ui.auth

import ai.cbm.capture.domain.model.Membership
import ai.cbm.capture.domain.model.RequestableRole
import ai.cbm.capture.ui.design.AlertKind
import ai.cbm.capture.ui.design.CbmField
import ai.cbm.capture.ui.design.CbmInlineAlert
import ai.cbm.capture.ui.design.CbmOutlineButton
import ai.cbm.capture.ui.design.CbmPanel
import ai.cbm.capture.ui.design.CbmPrimaryButton
import ai.cbm.capture.ui.design.CbmRadioRow
import ai.cbm.capture.ui.design.SectionHeader
import ai.cbm.capture.ui.theme.CbmPalette
import ai.cbm.capture.ui.theme.LocalAccent
import ai.cbm.capture.ui.theme.LocalTechStyles
import ai.cbm.capture.ui.theme.accentFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * The ink header shared by every screen before a session opens: it says where you are before it
 * asks who you are. The form sits below it on paper.
 */
@Composable
private fun AuthScaffold(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxWidth().background(CbmPalette.Ink900).statusBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("CBM APP", style = LocalTechStyles.current.stencil, color = LocalAccent.current.color)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CbmPalette.Steel300)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) { content() }
    }
}

/** First launch without a site: the code comes from the site's QR poster, or is typed from it. */
@Composable
fun JoinSiteScreen(
    state: AuthFormState,
    onCodeChange: (String) -> Unit,
    onContinue: () -> Unit,
    onHaveAccount: () -> Unit
) = AuthScaffold(
    title = "Join your site",
    subtitle = "Scan the QR code on the site's poster with your phone's camera, or type the code printed under it."
) {
    CbmField("Site code", state.codeInput, onCodeChange, placeholder = "the code under the QR", mono = true)
    state.error?.let { CbmInlineAlert(AlertKind.CRITICAL, it) }
    CbmPrimaryButton(
        text = "Continue",
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.codeInput.isNotBlank() && !state.busy,
        tall = true
    )
    CbmOutlineButton("I already have an account", onHaveAccount, Modifier.fillMaxWidth())
}

@Composable
fun LoginScreen(
    state: AuthFormState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onSignUp: () -> Unit
) = AuthScaffold(title = "Log in", subtitle = "A session lasts one hour; after that you log in again.") {
    CbmField("Email", state.email, onEmail, placeholder = "name@example.com")
    CbmField("Password", state.password, onPassword, secret = true)
    state.error?.let { CbmInlineAlert(AlertKind.CRITICAL, it) }
    CbmPrimaryButton(
        text = if (state.busy) "Logging in…" else "Log in",
        onClick = onLogin,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.email.isNotBlank() && state.password.isNotBlank() && !state.busy,
        tall = true
    )
    Text(
        "Five wrong passwords lock the account for fifteen minutes. An unknown email and a wrong " +
            "password get the same answer.",
        style = LocalTechStyles.current.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    CbmOutlineButton("New here? Create an account", onSignUp, Modifier.fillMaxWidth())
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
) = AuthScaffold(title = "Create your account", subtitle = siteLabel) {
    CbmField("Email", state.email, onEmail, placeholder = "name@example.com")
    CbmField("Password", state.password, onPassword, secret = true)
    Text("At least 8 characters.", style = LocalTechStyles.current.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionHeader("I am a")
    CbmPanel(Modifier.fillMaxWidth()) {
        RequestableRole.entries.forEach { role ->
            CbmRadioRow(
                text = role.label,
                selected = state.role == role,
                onClick = { onRole(role) },
                sub = when (role) {
                    RequestableRole.USER -> "Report what is broken, with a photo"
                    RequestableRole.TECHNICIAN -> "Accept jobs and write the reports"
                    RequestableRole.FM -> "Authorize work and approve what is done"
                }
            )
        }
    }
    if (state.role == RequestableRole.FM) {
        CbmInlineAlert(
            AlertKind.INFO,
            "A facility manager can authorize work, so the account is active once the operator has " +
                "approved it. You can log in meanwhile.",
            title = "Approved by the operator"
        )
    }
    state.error?.let { CbmInlineAlert(AlertKind.CRITICAL, it) }
    CbmPrimaryButton(
        text = if (state.busy) "Creating…" else "Create account",
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.email.isNotBlank() && state.password.length >= 8 && !state.busy,
        tall = true
    )
    CbmOutlineButton("I already have an account", onHaveAccount, Modifier.fillMaxWidth())
}

/** More than one role on this account: a session serves exactly one. */
@Composable
fun ChooseRoleScreen(
    memberships: List<Membership>,
    busy: Boolean,
    error: String?,
    onPick: (Membership) -> Unit,
    onLogout: () -> Unit
) = AuthScaffold(title = "Continue as", subtitle = "You can change role by logging in again.") {
    memberships.forEach { m ->
        CbmPanel(Modifier.fillMaxWidth(), rail = accentFor(m.role).color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(roleLabel(m.role), style = MaterialTheme.typography.titleMedium)
                    Text(
                        m.siteName + if (m.isActive) "" else " · waiting for approval",
                        style = LocalTechStyles.current.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CbmPrimaryButton("Continue", { onPick(m) }, enabled = !busy, color = accentFor(m.role).color)
            }
        }
    }
    error?.let { CbmInlineAlert(AlertKind.CRITICAL, it) }
    CbmOutlineButton("Log out", onLogout, Modifier.fillMaxWidth())
}

/** A pending role, or an account with nothing to do: wait, or log out. */
@Composable
fun WaitingScreen(
    title: String,
    message: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) = AuthScaffold(title = title, subtitle = null) {
    CbmInlineAlert(AlertKind.INFO, message)
    Spacer(Modifier.height(4.dp))
    CbmPrimaryButton(
        text = if (busy) "Checking…" else "Check again",
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        tall = true
    )
    CbmOutlineButton("Log out", onLogout, Modifier.fillMaxWidth())
}

fun roleLabel(wire: String): String = when (wire) {
    "USER" -> "User"
    "TECHNICIAN" -> "Technician"
    "FM" -> "Facility manager"
    "ADMIN" -> "Administrator"
    else -> wire
}
