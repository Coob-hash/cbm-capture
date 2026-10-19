package ai.cbm.capture.app

import ai.cbm.capture.BuildConfig
import ai.cbm.capture.data.session.SessionStore
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class CbmCaptureApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var http: OkHttpClient

    /**
     * WorkManager is initialised on demand rather than by its startup provider (removed in the
     * manifest), so that [ai.cbm.capture.work.UploadWorker] can be constructed with its
     * repository injected.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * A ticket's photo comes from the App API, so the loader adds the session's token — and only
     * for our own address, never for another host a URL might point at. Photos are cached in
     * memory but never written to disk: they belong to a ticket, not to this phone.
     */
    override fun newImageLoader(): ImageLoader {
        val api = BuildConfig.API_BASE_URL
        val client = http.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val token = sessions.current()?.token
            val authorized =
                if (token != null && request.url.toString().startsWith(api)) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    request
                }
            chain.proceed(authorized)
        }.build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
}
