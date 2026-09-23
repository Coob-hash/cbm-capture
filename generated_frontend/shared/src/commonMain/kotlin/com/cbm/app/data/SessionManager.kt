package com.cbm.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cbm.app.domain.MembershipStatus
import com.cbm.app.domain.Notice
import com.cbm.app.domain.Role
import com.cbm.app.domain.Session

/** Root application state: session, auth flow state, outbox, notices. */
class CbmAppState(val api: CbmApi) {
    var session by mutableStateOf<Session?>(null); private set
    var authBusy by mutableStateOf(false); private set
    var authError by mutableStateOf<String?>(null); private set
    var membershipChosen by mutableStateOf(false); private set
    var notices by mutableStateOf<List<Notice>>(emptyList()); private set
    val outbox = Outbox()

    val role: Role? get() = session?.active?.role
    val pendingApproval: Boolean get() = session?.active?.status == MembershipStatus.PENDING
    val unreadCount: Int get() = notices.count { !it.read }

    private suspend fun runAuth(block: suspend () -> Session): Boolean {
        authBusy = true; authError = null
        return try {
            val s = block()
            session = s
            membershipChosen = s.memberships.size <= 1
            true
        } catch (e: Exception) {
            authError = e.message ?: "Authentication failed"
            false
        } finally {
            authBusy = false
        }
    }

    suspend fun signIn(email: String, password: String, siteCode: String) = runAuth { api.login(email, password, siteCode) }
    suspend fun signUp(email: String, password: String, role: Role, siteCode: String) = runAuth { api.signUp(email, password, role, siteCode) }
    suspend fun signInGoogle(role: Role?, siteCode: String) = runAuth { api.loginGoogle("demo-id-token", role, siteCode) }
    suspend fun chooseMembership(id: String) { session?.let { session = api.selectMembership(it.token, id) }; membershipChosen = true }
    suspend fun refreshMe() { session?.let { session = api.me(it.token) } }
    suspend fun refreshNotices() { session?.let { notices = api.notifications(it.token) } }
    fun markNoticesRead() { notices = notices.map { it.copy(read = true) } }
    suspend fun signOut() {
        session?.let { api.logout(it.token) }
        session = null; notices = emptyList(); membershipChosen = false
    }
}

val LocalApp = staticCompositionLocalOf<CbmAppState> { error("CbmAppState not provided") }
