import Foundation
import SwiftData

/// Durable queue of capture packages awaiting delivery.
///
/// A `@ModelActor` so that every read and write happens on one isolated context: the UI, the
/// synchroniser, and the background refresh task all touch this queue, and SwiftData contexts
/// are not safe to share across them.
@ModelActor
actor OutboxStore {

    // MARK: - Image files

    /// JPEGs live on disk beside the store, excluded from backup: they are large, and they are
    /// reproducible only in the sense that the defect is still there to photograph again.
    static func imagesDirectory() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ).appendingPathComponent("CaptureImages", isDirectory: true)

        if !FileManager.default.fileExists(atPath: base.path) {
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            var url = base
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        }
        return base
    }

    static func imageURL(for filename: String) throws -> URL {
        try imagesDirectory().appendingPathComponent(filename, isDirectory: false)
    }

    // MARK: - Enqueue

    /// Persist a package. Returns the queued item.
    ///
    /// The image is written to disk before the row is inserted, so a crash between the two
    /// leaves an orphan file (harmless, swept by `pruneOrphanedImages`) rather than a row that
    /// points at nothing.
    func enqueue(_ package: CaptureAssembler.Package, thumbnail: Data?) throws -> OutboxItem {
        let filename = "\(package.metadata.captureID.uuidString).jpg"
        try package.imageData.write(to: Self.imageURL(for: filename), options: .atomic)

        let metadataJSON = try JSONEncoder.captureContract.encode(package.metadata)
        let record = OutboxRecord(
            captureID: package.metadata.captureID,
            createdAt: package.metadata.capturedAt,
            buildingID: package.metadata.buildingID,
            summary: Self.summary(for: package.metadata),
            metadataJSON: metadataJSON,
            imageFilename: filename,
            thumbnailData: thumbnail
        )
        modelContext.insert(record)
        try modelContext.save()
        return Self.item(from: record, metadata: package.metadata)
    }

    // MARK: - Reads

    func allItems() throws -> [OutboxItem] {
        let descriptor = FetchDescriptor<OutboxRecord>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        return try modelContext.fetch(descriptor).map { Self.item(from: $0, metadata: try? Self.metadata(of: $0)) }
    }

    func pendingCount() throws -> Int {
        try modelContext.fetchCount(FetchDescriptor<OutboxRecord>(
            predicate: #Predicate { $0.statusRaw == "QUEUED" }
        ))
    }

    /// The next package that is due for an attempt, marked `uploading` in the same transaction
    /// so two synchronisers cannot claim the same row.
    func claimNextDue(now: Date = .now) throws -> (item: OutboxItem, metadata: Data, imageURL: URL)? {
        var descriptor = FetchDescriptor<OutboxRecord>(
            predicate: #Predicate { $0.statusRaw == "QUEUED" },
            sortBy: [SortDescriptor(\.createdAt, order: .forward)]
        )
        descriptor.fetchLimit = 20

        let candidates = try modelContext.fetch(descriptor)
        guard let record = candidates.first(where: { ($0.nextAttemptAt ?? .distantPast) <= now }) else {
            return nil
        }

        record.status = .uploading
        record.lastAttemptAt = now
        record.attemptCount += 1
        try modelContext.save()

        return (
            Self.item(from: record, metadata: try? Self.metadata(of: record)),
            record.metadataJSON,
            try Self.imageURL(for: record.imageFilename)
        )
    }

    // MARK: - Outcome recording

    func markDelivered(_ captureID: UUID, requestID: UUID?, serverStatus: String?) throws {
        guard let record = try find(captureID) else { return }
        record.status = .delivered
        record.serverRequestID = requestID
        record.serverStatus = serverStatus
        record.lastError = nil
        try modelContext.save()
        // The full-size JPEG has served its purpose; the thumbnail keeps the history browsable.
        try? FileManager.default.removeItem(at: Self.imageURL(for: record.imageFilename))
    }

    func markRejected(_ captureID: UUID, reason: String) throws {
        guard let record = try find(captureID) else { return }
        record.status = .rejected
        record.lastError = reason
        try modelContext.save()
    }

    /// Return the package to the queue with exponential backoff.
    ///
    /// Backoff is capped at 15 minutes: this is a worker walking a building, not a batch job,
    /// and an hour-long wait after a few failures would feel like the app had lost the report.
    func markRetryable(_ captureID: UUID, reason: String, now: Date = .now) throws {
        guard let record = try find(captureID) else { return }
        record.status = .queued
        record.lastError = reason
        let delay = min(pow(2.0, Double(min(record.attemptCount, 8))) * 5.0, 900.0)
        record.nextAttemptAt = now.addingTimeInterval(delay)
        try modelContext.save()
    }

    func retryNow(_ captureID: UUID) throws {
        guard let record = try find(captureID) else { return }
        record.status = .queued
        record.nextAttemptAt = nil
        record.lastError = nil
        try modelContext.save()
    }

    func delete(_ captureID: UUID) throws {
        guard let record = try find(captureID) else { return }
        try? FileManager.default.removeItem(at: Self.imageURL(for: record.imageFilename))
        modelContext.delete(record)
        try modelContext.save()
    }

    /// Recover rows left `uploading` by a crash or a force-quit mid-request.
    func resetStuckUploads() throws {
        let descriptor = FetchDescriptor<OutboxRecord>(
            predicate: #Predicate { $0.statusRaw == "UPLOADING" }
        )
        for record in try modelContext.fetch(descriptor) {
            record.status = .queued
            record.nextAttemptAt = nil
        }
        try modelContext.save()
    }

    /// Delete image files with no surviving row.
    func pruneOrphanedImages() throws {
        let directory = try Self.imagesDirectory()
        let known = Set(try modelContext.fetch(FetchDescriptor<OutboxRecord>()).map(\.imageFilename))
        let onDisk = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        for filename in onDisk where !known.contains(filename) {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(filename))
        }
    }

    // MARK: - Helpers

    private func find(_ captureID: UUID) throws -> OutboxRecord? {
        var descriptor = FetchDescriptor<OutboxRecord>(
            predicate: #Predicate { $0.captureID == captureID }
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first
    }

    private static func metadata(of record: OutboxRecord) throws -> CaptureMetadata {
        try JSONDecoder.captureContract.decode(CaptureMetadata.self, from: record.metadataJSON)
    }

    private static func item(from record: OutboxRecord, metadata: CaptureMetadata?) -> OutboxItem {
        OutboxItem(
            id: record.captureID,
            createdAt: record.createdAt,
            summary: record.summary,
            buildingID: record.buildingID,
            status: record.status,
            attemptCount: record.attemptCount,
            lastError: record.lastError,
            serverStatus: record.serverStatus,
            thumbnailData: record.thumbnailData,
            intrinsicsSource: metadata?.camera.source,
            intrinsicsTrusted: metadata?.camera.trusted
        )
    }

    private static func summary(for metadata: CaptureMetadata) -> String {
        let text = metadata.description?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.isEmpty ? "Untitled report" : text
    }
}
