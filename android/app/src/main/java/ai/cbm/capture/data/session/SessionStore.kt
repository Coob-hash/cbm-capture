package ai.cbm.capture.data.session

import ai.cbm.capture.domain.model.Membership
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The logged-in person on this phone. A session lasts exactly one hour (set by the server); after
 * that the app asks for the password again. Several people may use one phone, one at a time.
 */
@Serializable
data class Session(
    val token: String,
    val expiresAtEpochSecond: Long,
    val accountId: String,
    val email: String,
    val membershipId: String? = null,
    val memberships: List<Membership> = emptyList()
) {
    val expiresAt: Instant get() = Instant.ofEpochSecond(expiresAtEpochSecond)
    fun isValid(now: Instant = Instant.now()): Boolean = now.isBefore(expiresAt)
    val membership: Membership? get() = memberships.firstOrNull { it.id == membershipId }
}

/**
 * Keeps the session token, the device's install id and the site code in encrypted preferences.
 *
 * The token lives in the platform keystore-backed store (PRD 1.0 FR-14), is readable only while
 * the session is valid, and is removed on log-out or expiry. The account id of the last session is
 * kept after expiry: a photo queued under that account is sent after the same person logs in again,
 * never under someone else.
 */
@Singleton
class SessionStore @Inject constructor(context: Context, private val json: Json) {

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "cbm_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    private val _session = MutableStateFlow(load())
    /** The current session, or null. May hold an expired one until [expireIfNeeded] runs. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** The session if it is still valid; clears an expired one. */
    fun current(now: Instant = Instant.now()): Session? {
        val s = _session.value ?: return null
        if (s.isValid(now)) return s
        clear()
        return null
    }

    fun expireIfNeeded() { current() }

    fun save(session: Session) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(Session.serializer(), session))
            .putString(KEY_LAST_ACCOUNT, session.accountId).apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    /** Account of the most recent session, kept after log-out/expiry for its queued photos. */
    val lastAccountId: String? get() = prefs.getString(KEY_LAST_ACCOUNT, null)

    /** A random id for this installation, created once. Not tied to any person. */
    val installId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

    /** The site this phone was joined to (from the QR code), used by sign-up. */
    var siteCode: String?
        get() = prefs.getString(KEY_SITE_CODE, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_SITE_CODE) else putString(KEY_SITE_CODE, value) }.apply() }

    private fun load(): Session? = prefs.getString(KEY_SESSION, null)?.let {
        runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_LAST_ACCOUNT = "last_account_id"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_SITE_CODE = "site_code"
    }
}
