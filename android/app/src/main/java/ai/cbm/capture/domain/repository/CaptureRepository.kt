package ai.cbm.capture.domain.repository

import ai.cbm.capture.data.capture.CaptureAssembler
import ai.cbm.capture.data.local.OutboxDao
import ai.cbm.capture.data.local.OutboxEntity
import ai.cbm.capture.data.local.OutboxStatus
import ai.cbm.capture.data.remote.CaptureUploader
import ai.cbm.capture.data.remote.UploadOutcome
import ai.cbm.capture.data.settings.SettingsRepository
import ai.cbm.capture.domain.model.CaptureMetadata
import ai.cbm.capture.domain.model.IntrinsicsSource
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/** A snapshot of one outbox row, shaped for the UI. */
data class ReportItem(
    val captureId: String,
    val createdAt: Long,
    val summary: String,
    val status: OutboxStatus,
    val attemptCount: Int,
    val lastError: String?,
    val serverStatus: String?,
    val thumbnailPath: String?,
    val intrinsicsSource: IntrinsicsSource,
    val intrinsicsTrusted: Boolean
)

/**
 * The single door between the capture flow, durable storage, and the network.
 *
 * Deliberately the only place that knows a capture package becomes three things - a JPEG on
 * disk, a thumbnail, and a row - so that enqueue and delete stay symmetric and nothing leaks.
 */
@Singleton
class CaptureRepository @Inject constructor(
    private val context: Context,
    private val dao: OutboxDao,
    private val uploader: CaptureUploader,
    private val settings: SettingsRepository,
    private val json: Json
) {

    fun observeReports(): Flow<List<ReportItem>> =
        dao.observeAll().map { entities -> entities.map(::toReportItem) }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    // ---- Enqueue ----

    /**
     * Persist a package. The image is written before the row is inserted, so a crash between
     * the two leaves an orphan file (swept by [pruneOrphans]) rather than a row pointing at
     * nothing.
     */
    suspend fun enqueue(pkg: CaptureAssembler.Package): ReportItem = withContext(Dispatchers.IO) {
        val captureId = pkg.metadata.captureId
        val imageFile = imageFile(captureId)
        imageFile.parentFile?.mkdirs()
        imageFile.writeBytes(pkg.imageBytes)

        val thumbnailFile = pkg.thumbnailBytes?.let { bytes ->
            thumbnailFile(captureId).also { it.writeBytes(bytes) }
        }

        val entity = OutboxEntity(
            captureId = captureId,
            createdAt = System.currentTimeMillis(),
            buildingId = pkg.metadata.buildingId,
            summary = pkg.metadata.description?.takeIf { it.isNotBlank() } ?: "Untitled report",
            metadataJson = json.encodeToString(CaptureMetadata.serializer(), pkg.metadata),
            imagePath = imageFile.absolutePath,
            thumbnailPath = thumbnailFile?.absolutePath,
            intrinsicsSource = pkg.metadata.camera.source.name,
            intrinsicsTrusted = pkg.metadata.camera.trusted
        )
        dao.insert(entity)
        toReportItem(entity)
    }

    // ---- Draining ----

    /**
     * Send everything that is due, one package at a time.
     *
     * Serial by design: parallel uploads from a phone on a weak site connection make every one
     * of them slower and more likely to time out, and the queue is measured in units, not
     * thousands. Returns false when a transient failure means the caller should back off.
     */
    suspend fun drain(): Boolean {
        while (true) {
            val claimed = dao.claimNextDue(System.currentTimeMillis()) ?: return true

            val outcome = uploader.upload(
                captureId = claimed.captureId,
                metadataJson = claimed.metadataJson,
                imageFile = File(claimed.imagePath)
            )

            when (outcome) {
                is UploadOutcome.Delivered -> {
                    dao.markDelivered(claimed.captureId, outcome.requestId, outcome.status)
                    // The full-size JPEG has served its purpose; the thumbnail keeps the
                    // history browsable without holding megabytes per report.
                    File(claimed.imagePath).delete()
                }

                is UploadOutcome.PermanentFailure ->
                    dao.markRejected(claimed.captureId, outcome.reason)

                is UploadOutcome.TransientFailure -> {
                    dao.markRetryable(
                        claimed.captureId,
                        outcome.reason,
                        System.currentTimeMillis() + backoffMillis(claimed.attemptCount)
                    )
                    // If the network just failed, the next package will fail the same way.
                    return false
                }
            }
        }
    }

    /**
     * Exponential, capped at 15 minutes. This is a worker walking a building, not a batch job:
     * an hour-long wait after a few failures would feel like the app had lost the report.
     */
    private fun backoffMillis(attemptCount: Int): Long =
        min(2.0.pow(min(attemptCount, 8)) * 5_000, 900_000.0).toLong()

    suspend fun retry(captureId: String) = dao.retryNow(captureId)

    suspend fun resetStuckUploads() = dao.resetStuckUploads()

    suspend fun delete(captureId: String) = withContext(Dispatchers.IO) {
        dao.find(captureId)?.let { entity ->
            File(entity.imagePath).delete()
            entity.thumbnailPath?.let { File(it).delete() }
        }
        dao.delete(captureId)
    }

    /** Remove image files with no surviving row. */
    suspend fun pruneOrphans() = withContext(Dispatchers.IO) {
        val known = dao.all().flatMap { listOfNotNull(it.imagePath, it.thumbnailPath) }.toSet()
        imagesDir().listFiles()?.forEach { file ->
            if (file.absolutePath !in known) file.delete()
        }
        Unit
    }

    suspend fun uploadOnMeteredAllowed(): Boolean = settings.settings.first().uploadOnMetered

    // ---- Files ----

    private fun imagesDir() = File(context.filesDir, "captures")
    private fun imageFile(captureId: String) = File(imagesDir(), "$captureId.jpg")
    private fun thumbnailFile(captureId: String) = File(imagesDir(), "$captureId-thumb.jpg")

    private fun toReportItem(entity: OutboxEntity) = ReportItem(
        captureId = entity.captureId,
        createdAt = entity.createdAt,
        summary = entity.summary,
        status = entity.status,
        attemptCount = entity.attemptCount,
        lastError = entity.lastError,
        serverStatus = entity.serverStatus,
        thumbnailPath = entity.thumbnailPath,
        intrinsicsSource = runCatching { IntrinsicsSource.valueOf(entity.intrinsicsSource) }
            .getOrDefault(IntrinsicsSource.ARCORE),
        intrinsicsTrusted = entity.intrinsicsTrusted
    )
}
