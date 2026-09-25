package ai.cbm.capture.work

import ai.cbm.capture.data.session.SessionStore
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
import kotlinx.coroutines.flow.first

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
    private val repository: CaptureRepository,
    private val sessions: SessionStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.resetStuckUploads()
        // Photos go out only under their owner's valid session; without one, they wait for the
        // next login, which enqueues this worker again.
        val session = sessions.current() ?: return Result.success()
        return if (repository.drain(session)) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "cbm-capture-upload"

        /**
         * Queue a drain. Uses [ExistingWorkPolicy.KEEP] so several triggers arriving together -
         * a saved report, a regained network, a foregrounded app - produce one drain, not three.
         */
        fun enqueue(context: Context, allowMetered: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraintsFor(allowMetered))
                    .build()
            )
        }

        /**
         * Queue a drain on the network the upload preference allows now. [enqueue] keeps a drain
         * already waiting, and with it the network it was queued for: after "Send photos over mobile
         * data" was switched on, photos queued before stayed waiting for Wi-Fi. A waiting drain
         * queued for the other network is updated in place instead - its photos are not touched,
         * and a drain already running is not interrupted (the change applies to its next attempt).
         */
        suspend fun enqueueForPreference(context: Context, allowMetered: Boolean) {
            val workManager = WorkManager.getInstance(context)
            val waiting = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first()
                .firstOrNull { !it.state.isFinished }
            if (waiting != null && waiting.constraints.requiredNetworkType != networkFor(allowMetered)) {
                workManager.updateWork(
                    OneTimeWorkRequestBuilder<UploadWorker>()
                        .setId(waiting.id)
                        .setConstraints(constraintsFor(allowMetered))
                        .build()
                )
            }
            enqueue(context, allowMetered)
        }

        private fun networkFor(allowMetered: Boolean) =
            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED

        private fun constraintsFor(allowMetered: Boolean) =
            Constraints.Builder().setRequiredNetworkType(networkFor(allowMetered)).build()

        /** Force an immediate attempt, replacing any pending one. Used by "Send all now". */
        fun enqueueNow(context: Context, allowMetered: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraintsFor(allowMetered))
                    .build()
            )
        }
    }
}
