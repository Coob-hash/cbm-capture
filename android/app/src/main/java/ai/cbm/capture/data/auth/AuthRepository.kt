package ai.cbm.capture.data.auth

import ai.cbm.capture.BuildConfig
import ai.cbm.capture.data.remote.AppApi
import ai.cbm.capture.data.remote.NETWORK_MESSAGE
import ai.cbm.capture.data.remote.apiError
import ai.cbm.capture.data.remote.bearer
import ai.cbm.capture.data.session.Session
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.domain.model.DeviceInfo
import ai.cbm.capture.domain.model.LoginRequest
import ai.cbm.capture.domain.model.RequestableRole
import ai.cbm.capture.domain.model.SelectRoleRequest
import ai.cbm.capture.domain.model.SessionResponse
import ai.cbm.capture.domain.model.SignUpRequest
import android.os.Build
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthResult {
    data class Ok(val session: Session) : AuthResult
    data class Failed(val message: String, val code: String? = null) : AuthResult
}

/** Sign-up, login, role choice and log-out against the App API; the session goes to [SessionStore]. */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AppApi,
    private val store: SessionStore,
    private val json: Json
) {

    private fun device() = DeviceInfo(
        id = store.installId,
        model = "${Build.MANUFACTURER} ${Build.MODEL}".take(100),
        osVersion = "Android ${Build.VERSION.RELEASE}",
        appVersion = BuildConfig.VERSION_NAME
    )

    suspend fun signUp(siteCode: String, role: RequestableRole, email: String, password: String): AuthResult =
        call { api.signUp(SignUpRequest(siteCode, role.wire, email.trim(), password, device())) }

    suspend fun login(email: String, password: String): AuthResult =
        call { api.login(LoginRequest(email.trim(), password, device())) }

    /** Binds this session to one of the person's roles (only when they hold several). */
    suspend fun selectRole(membershipId: String): AuthResult {
        val s = store.current() ?: return AuthResult.Failed(EXPIRED, "UNAUTHENTICATED")
        return try {
            val r = api.selectRole(bearer(s.token), SelectRoleRequest(membershipId))
            if (r.isSuccessful) AuthResult.Ok(s.copy(membershipId = membershipId).also(store::save))
            else failed(r)
        } catch (e: IOException) {
            AuthResult.Failed(NETWORK_MESSAGE)
        }
    }

    /** Re-reads the session's memberships, e.g. to see whether a pending role was approved. */
    suspend fun refresh(): AuthResult {
        val s = store.current() ?: return AuthResult.Failed(EXPIRED, "UNAUTHENTICATED")
        return try {
            val r = api.me(bearer(s.token))
            val body = r.body()
            if (r.isSuccessful && body != null) {
                AuthResult.Ok(s.copy(membershipId = body.membership?.id ?: s.membershipId, memberships =
                    (body.memberships + listOfNotNull(body.membership)).distinctBy { it.id }).also(store::save))
            } else {
                if (r.code() == 401) store.clear()
                failed(r)
            }
        } catch (e: IOException) {
            AuthResult.Failed(NETWORK_MESSAGE)
        }
    }

    suspend fun logout() {
        val s = store.session.value
        store.clear()
        if (s != null) runCatching { api.logout(bearer(s.token)) }
    }

    private suspend fun call(request: suspend () -> Response<SessionResponse>): AuthResult = try {
        val r = request()
        val body = r.body()
        if (r.isSuccessful && body != null) AuthResult.Ok(toSession(body).also(store::save)) else failed(r)
    } catch (e: IOException) {
        AuthResult.Failed(NETWORK_MESSAGE)
    }

    private fun failed(r: Response<*>): AuthResult.Failed {
        val err = r.apiError(json)
        val message = when {
            r.code() == 422 && err.error == "INVALID_REQUEST" -> "Please check the fields and try again."
            err.message != null -> err.message
            r.code() >= 500 -> "The server is not available right now. Try again in a moment."
            else -> "Something went wrong (${err.error})."
        }
        return AuthResult.Failed(message!!, err.error)
    }

    private fun toSession(r: SessionResponse) = Session(
        token = r.token,
        expiresAtEpochSecond = OffsetDateTime.parse(r.expiresAt).toEpochSecond(),
        accountId = r.user.id,
        email = r.user.email,
        membershipId = r.membershipId,
        memberships = r.memberships
    )

    companion object {
        const val EXPIRED = "Your session has ended. Log in again."
    }
}
