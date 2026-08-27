package ai.cbm.capture.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "cbm_settings")

data class AppSettings(
    val baseUrl: String = "",
    val buildingId: String = "ROOM-POC",
    val reporterEmail: String = "",
    val uploadOnMetered: Boolean = true,
    val hasToken: Boolean = false
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && hasToken && buildingId.isNotBlank()

    /**
     * Flagged in the UI rather than blocked. A local n8n on plain HTTP is a normal PoC setup,
     * but the worker should be able to see that the token and the photographs are in the clear.
     */
    val isInsecureTransport: Boolean
        get() = baseUrl.startsWith("http://", ignoreCase = true)
}

/**
 * Device enrolment: which n8n endpoint, which building, who is reporting.
 *
 * Non-secret values live in DataStore; the bearer token lives in [EncryptedSharedPreferences],
 * backed by a hardware-bound master key. Plain `SharedPreferences` would leave it readable in
 * any file-system backup or by any process with root.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context
) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val BUILDING_ID = stringPreferencesKey("building_id")
        val REPORTER_EMAIL = stringPreferencesKey("reporter_email")
        val UPLOAD_ON_METERED = booleanPreferencesKey("upload_on_metered")
    }

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cbm_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            buildingId = prefs[Keys.BUILDING_ID] ?: "ROOM-POC",
            reporterEmail = prefs[Keys.REPORTER_EMAIL].orEmpty(),
            uploadOnMetered = prefs[Keys.UPLOAD_ON_METERED] ?: true,
            hasToken = !token().isNullOrBlank()
        )
    }

    suspend fun setBaseUrl(value: String) = edit(Keys.BASE_URL, value.trim())
    suspend fun setBuildingId(value: String) = edit(Keys.BUILDING_ID, value.trim())
    suspend fun setReporterEmail(value: String) = edit(Keys.REPORTER_EMAIL, value.trim())

    suspend fun setUploadOnMetered(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.UPLOAD_ON_METERED] = value }
    }

    private suspend fun edit(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.settingsDataStore.edit { it[key] = value }
    }

    // ---- Token ----

    fun token(): String? = securePrefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }

    fun setToken(value: String) {
        val trimmed = value.trim()
        securePrefs.edit().apply {
            if (trimmed.isEmpty()) remove(TOKEN_KEY) else putString(TOKEN_KEY, trimmed)
        }.apply()
    }

    fun clearToken() {
        securePrefs.edit().remove(TOKEN_KEY).apply()
    }

    private companion object {
        const val TOKEN_KEY = "bearer_token"
    }
}
