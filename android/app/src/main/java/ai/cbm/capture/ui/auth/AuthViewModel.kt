package ai.cbm.capture.ui.auth

import ai.cbm.capture.data.auth.AuthRepository
import ai.cbm.capture.data.auth.AuthResult
import ai.cbm.capture.data.session.Session
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.domain.SiteCode
import ai.cbm.capture.domain.model.Membership
import ai.cbm.capture.domain.model.RequestableRole
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity-wide: the forms of the auth screens, and the actions that open or end a session. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val store: SessionStore
) : ViewModel() {

    private val _form = MutableStateFlow(AuthFormState(siteCode = store.siteCode))
    val form: StateFlow<AuthFormState> = _form.asStateFlow()

    fun onCode(value: String) = _form.update { it.copy(codeInput = value, error = null) }
    fun onEmail(value: String) {
        emailOfLastAccount = false
        _form.update { it.copy(email = value, error = null) }
    }
    fun onPassword(value: String) = _form.update { it.copy(password = value, error = null) }
    fun onRole(value: RequestableRole) = _form.update { it.copy(role = value, error = null) }
    fun clearError() = _form.update { it.copy(error = null) }

    /**
     * True while the email field still holds the address of an account that has signed in. It stays
     * after the session ends so Log in is one field shorter, but it can never create a new account
     * (it has one already), so sign-up must not start from it: typing a new address after it
     * produced "old@x.comnew@y.com".
     */
    private var emailOfLastAccount = false

    /** Called on the way to the sign-up screen. */
    fun startSignUp() {
        val clearEmail = emailOfLastAccount
        emailOfLastAccount = false
        _form.update { it.copy(email = if (clearEmail) "" else it.email, password = "", error = null) }
    }

    /** A typed code or a scanned join link. Returns false (with a message) if it is neither. */
    fun joinSite(input: String = _form.value.codeInput): Boolean {
        val code = SiteCode.parse(input)
        if (code == null) {
            _form.update { it.copy(error = "That is not a valid site code. Check the poster and try again.") }
            return false
        }
        store.siteCode = code
        _form.update { it.copy(siteCode = code, codeInput = code, error = null) }
        return true
    }

    fun login(onDone: (Session) -> Unit) = run(onDone) { auth.login(_form.value.email, _form.value.password) }

    fun signUp(onDone: (Session) -> Unit) {
        val site = _form.value.siteCode ?: return _form.update { it.copy(error = "Scan your site's QR code first.") }
        run(onDone) { auth.signUp(site, _form.value.role, _form.value.email, _form.value.password) }
    }

    fun chooseRole(membership: Membership, onDone: (Session) -> Unit) = run(onDone) { auth.selectRole(membership.id) }

    fun refresh(onDone: (Session) -> Unit) = run(onDone) { auth.refresh() }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            _form.update { it.copy(password = "", busy = false, error = null) }
            emailOfLastAccount = _form.value.email.isNotBlank()
            onDone()
        }
    }

    private fun run(onDone: (Session) -> Unit, call: suspend () -> AuthResult) {
        if (_form.value.busy) return
        _form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = call()) {
                is AuthResult.Ok -> {
                    // The password is not kept in memory once it has done its job. The email now
                    // names an account, whether the session later ends by log-out or by the hour.
                    _form.update { it.copy(busy = false, password = "") }
                    emailOfLastAccount = _form.value.email.isNotBlank()
                    onDone(r.session)
                }
                is AuthResult.Failed -> _form.update { it.copy(busy = false, error = r.message) }
            }
        }
    }
}
