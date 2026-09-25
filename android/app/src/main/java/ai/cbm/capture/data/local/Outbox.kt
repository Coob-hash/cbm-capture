package ai.cbm.capture.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle of one capture from shutter press to acknowledged by the server.
 * Mirrors `OutboxStatus` on iOS.
 */
enum class OutboxStatus {
    QUEUED, UPLOADING, DELIVERED, REJECTED;

    val displayName: String
        get() = when (this) {
            QUEUED -> "Waiting to send"
            UPLOADING -> "Sending"
            DELIVERED -> "Sent"
            REJECTED -> "Not accepted"
        }
}

/**
 * One capture package, durable across process death.
 *
 * A worker photographing a basement plant room routinely has no signal, so a capture is written
 * to disk *before* any upload is attempted and only leaves the queue once the server has
 * acknowledged it. Losing a report to a failed upload would mean walking back to the defect.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    /** Also the server's idempotency key, which is what makes blind retries safe. */
    @PrimaryKey @ColumnInfo(name = "capture_id") val captureId: String,
    /** The account that took the photo. Only that account's session ever sends it. */
    @ColumnInfo(name = "account_id", defaultValue = "") val accountId: String,
    /** Groups a report's photos: the first one and any replacement the workflows ask for. */
    @ColumnInfo(name = "report_id", defaultValue = "") val reportId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "building_id") val buildingId: String,
    val summary: String,
    /**
     * The encoded metadata document, stored exactly as it will be transmitted. Keeping the
     * serialised bytes rather than modelled columns means the thing that was validated is the
     * thing that gets sent.
     */
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
    /** Absolute path to the JPEG. Multi-megabyte blobs do not belong in SQLite. */
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "intrinsics_source") val intrinsicsSource: String,
    @ColumnInfo(name = "intrinsics_trusted") val intrinsicsTrusted: Boolean,
    val status: OutboxStatus = OutboxStatus.QUEUED,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "server_request_id") val serverRequestId: String? = null,
    @ColumnInfo(name = "server_status") val serverStatus: String? = null
)

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE account_id = :accountId ORDER BY created_at DESC")
    fun observeForAccount(accountId: String): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'QUEUED' AND account_id = :accountId")
    fun observePendingCount(accountId: String): Flow<Int>

    @Query("SELECT * FROM outbox WHERE capture_id = :captureId")
    suspend fun find(captureId: String): OutboxEntity?

    /**
     * Claim the next due package, marking it `UPLOADING` in the same transaction so two workers
     * cannot pick up the same row. Only a photo of [siteId]: the session sending it is that site's,
     * and the server refuses a photo of another (third audit 2026-09-25, finding 5).
     */
    @androidx.room.Transaction
    suspend fun claimNextDue(now: Long, accountId: String, siteId: String): OutboxEntity? {
        val candidate = nextDue(now, accountId, siteId) ?: return null
        setStatus(candidate.captureId, OutboxStatus.UPLOADING, now, candidate.attemptCount + 1)
        return candidate.copy(status = OutboxStatus.UPLOADING, attemptCount = candidate.attemptCount + 1)
    }

    @Query(
        "SELECT * FROM outbox WHERE status = 'QUEUED' AND next_attempt_at <= :now AND account_id = :accountId " +
            "AND building_id = :siteId ORDER BY created_at ASC LIMIT 1"
    )
    suspend fun nextDue(now: Long, accountId: String, siteId: String): OutboxEntity?

    @Query(
        "UPDATE outbox SET status = :status, next_attempt_at = :nextAttemptAt, " +
            "attempt_count = :attemptCount WHERE capture_id = :captureId"
    )
    suspend fun setStatus(captureId: String, status: OutboxStatus, nextAttemptAt: Long, attemptCount: Int)

    @Query(
        "UPDATE outbox SET status = 'DELIVERED', server_status = :serverStatus, last_error = NULL " +
            "WHERE capture_id = :captureId"
    )
    suspend fun markDelivered(captureId: String, serverStatus: String?)

    /** [code] is the server's reason (e.g. DESCRIPTION_TOO_LONG), kept to offer the right correction. */
    @Query("UPDATE outbox SET status = 'REJECTED', last_error = :reason, server_status = :code WHERE capture_id = :captureId")
    suspend fun markRejected(captureId: String, reason: String, code: String?)

    /**
     * A refused photo whose text was corrected: the new metadata replaces the old, and it goes back
     * in the queue. Only a REJECTED row: one being sent or already sent is never rewritten.
     */
    @Query(
        "UPDATE outbox SET metadata_json = :metadataJson, summary = :summary, status = 'QUEUED', " +
            "next_attempt_at = 0, last_error = NULL, server_status = NULL " +
            "WHERE capture_id = :captureId AND status = 'REJECTED'"
    )
    suspend fun replaceRejectedMetadata(captureId: String, metadataJson: String, summary: String): Int

    @Query(
        "UPDATE outbox SET status = 'QUEUED', last_error = :reason, next_attempt_at = :nextAttemptAt " +
            "WHERE capture_id = :captureId"
    )
    suspend fun markRetryable(captureId: String, reason: String, nextAttemptAt: Long)

    @Query(
        "UPDATE outbox SET status = 'QUEUED', next_attempt_at = 0, last_error = NULL " +
            "WHERE capture_id = :captureId"
    )
    suspend fun retryNow(captureId: String)

    /** Recover rows left `UPLOADING` by process death mid-request. */
    @Query("UPDATE outbox SET status = 'QUEUED', next_attempt_at = 0 WHERE status = 'UPLOADING'")
    suspend fun resetStuckUploads()

    @Query("DELETE FROM outbox WHERE capture_id = :captureId")
    suspend fun delete(captureId: String)

    @Query("SELECT * FROM outbox")
    suspend fun all(): List<OutboxEntity>
}

@Database(entities = [OutboxEntity::class], version = 2, exportSchema = false)
abstract class CbmDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        /**
         * 1 -> 2: photos now belong to an account and a report. A photo queued by version 1 has
         * neither, and sending it under whoever logs in next would misattribute it, so it is marked
         * not accepted with a request to take it again. Delivered history is kept.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN account_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE outbox ADD COLUMN report_id TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE outbox SET status = 'REJECTED', last_error = 'Saved before accounts existed. " +
                        "Please take the photo again.' WHERE status IN ('QUEUED', 'UPLOADING')"
                )
            }
        }
    }
}
