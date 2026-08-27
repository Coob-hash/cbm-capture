package ai.cbm.capture.work

import ai.cbm.capture.domain.repository.CaptureRepository
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the outbox in the background.
 *
 * WorkManager rather than a foreground coroutine because the guarantee that matters is
 * "eventually, even if the worker closes the app in the lift". The retry schedule lives in
 * [CaptureRepository] so it stays the same whether the drain was triggered by the UI or by the
 * system; this worker only asks WorkManager to try again when the repository says the network
 * let it down.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CaptureRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.resetStuckUploads()
        return if (repository.drain()) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "cbm-capture-upload"

        /**
         * Queue a drain. Uses [ExistingWorkPolicy.KEEP] so several triggers arriving together -
         * a saved report, a regained network, a foregrounded app - produce one drain, not three.
         */
        fun enqueue(context: Context, allowMetered: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }

        /** Force an immediate attempt, replacing any pending one. Used by "Send all now". */
        fun enqueueNow(context: Context, allowMetered: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
