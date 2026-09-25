package ai.cbm.capture.di

import ai.cbm.capture.data.capture.Camera2IntrinsicsReader
import ai.cbm.capture.data.capture.CaptureAssembler
import ai.cbm.capture.data.local.CbmDatabase
import ai.cbm.capture.data.local.OutboxDao
import ai.cbm.capture.BuildConfig
import ai.cbm.capture.data.remote.AppApi
import ai.cbm.capture.data.remote.appConverterFactory
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The server may add fields ahead of an app rollout; refusing to parse a response that
        // gained a key would strand every handset that had not updated yet.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        // Generous, because these are multi-megabyte uploads over site Wi-Fi, not API calls.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // ngrok's free domains may answer a request with a browser warning page instead of
        // forwarding it; this header tells ngrok the caller is an app, not a browser.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("ngrok-skip-browser-warning", "1").build())
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // The App API; set per build in android/local.properties (cbm.apiBaseUrl).
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        // An unreadable 2xx body becomes an IOException the screens handle, not a crash.
        .addConverterFactory(json.appConverterFactory())
        .build()

    @Provides
    @Singleton
    fun provideAppApi(retrofit: Retrofit): AppApi = retrofit.create(AppApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CbmDatabase =
        Room.databaseBuilder(context, CbmDatabase::class.java, "cbm-capture.db")
            .addMigrations(CbmDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideOutboxDao(database: CbmDatabase): OutboxDao = database.outboxDao()

    @Provides
    @Singleton
    fun provideCaptureAssembler(): CaptureAssembler = CaptureAssembler()

    @Provides
    @Singleton
    fun provideCamera2IntrinsicsReader(@ApplicationContext context: Context): Camera2IntrinsicsReader =
        Camera2IntrinsicsReader(context)
}
