package ai.cbm.capture.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "cbm_settings")

data class AppSettings(val uploadOnMetered: Boolean = true)

/**
 * Per-phone preferences. The server address is a build setting (BuildConfig.API_BASE_URL); who is
 * reporting, for which site, comes from the session. What is left is how to upload.
 */
@Singleton
class SettingsRepository @Inject constructor(private val context: Context) {

    private val uploadOnMeteredKey = booleanPreferencesKey("upload_on_metered")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(uploadOnMetered = prefs[uploadOnMeteredKey] ?: true)
    }

    suspend fun setUploadOnMetered(value: Boolean) {
        context.settingsDataStore.edit { it[uploadOnMeteredKey] = value }
    }
}
