package ai.cbm.capture.di

import ai.cbm.capture.data.capture.Camera2IntrinsicsReader
import ai.cbm.capture.data.capture.CaptureAssembler
import ai.cbm.capture.data.local.CbmDatabase
import ai.cbm.capture.data.local.OutboxDao
import ai.cbm.capture.data.remote.CaptureApi
import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // Every call supplies an absolute @Url, since the endpoint is set per handset at
        // enrolment. This placeholder only satisfies Retrofit's builder.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideCaptureApi(retrofit: Retrofit): CaptureApi = retrofit.create(CaptureApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CbmDatabase =
        Room.databaseBuilder(context, CbmDatabase::class.java, "cbm-capture.db").build()

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
