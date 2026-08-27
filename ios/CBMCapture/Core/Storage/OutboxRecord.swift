import Foundation
import SwiftData

/// Lifecycle of one capture from shutter press to acknowledged by the server.
enum OutboxStatus: String, Codable, Sendable, CaseIterable {
    /// Waiting for a network opportunity.
    case queued = "QUEUED"
    /// An upload attempt is in flight.
    case uploading = "UPLOADING"
    /// The server accepted it (or recognised it as a duplicate).
    case delivered = "DELIVERED"
    /// The server rejected it in a way that retrying cannot fix.
    case rejected = "REJECTED"

    var isTerminal: Bool { self == .delivered || self == .rejected }

    var displayName: String {
        switch self {
        case .queued: "Waiting to send"
        case .uploading: "Sending"
        case .delivered: "Sent"
        case .rejected: "Not accepted"
        }
    }
}

/// One capture package, durable across app launches.
///
/// A worker photographing a plant room in a basement will routinely have no signal, so a
/// capture is written to disk *before* any upload is attempted and only leaves the queue when
/// the server has acknowledged it. Losing a report because the upload failed would mean
/// walking back to the defect.
@Model
final class OutboxRecord {
    /// Also the server's idempotency key, which is what makes blind retries safe.
    ///
    /// No `#Unique` constraint: that macro requires iOS 18, and this app targets 17. Uniqueness
    /// holds by construction anyway - each record is created with a freshly generated UUID and
    /// the value is never rewritten - and the server enforces it independently through
    /// `UNIQUE (source_system, source_file_id)`.
    var captureID: UUID = UUID()

    var createdAt: Date = Date()
    var buildingID: String = ""
    var summary: String = ""

    /// Encoded `CaptureMetadata`. Stored as encoded bytes rather than as modelled properties so
    /// that the persisted form is exactly what will be transmitted, byte for byte.
    var metadataJSON: Data = Data()

    /// Filename inside the images directory. The JPEG itself is kept out of the store: SwiftData
    /// is a poor place for multi-megabyte blobs.
    var imageFilename: String = ""

    /// Small JPEG for the reports list, so browsing history does not touch the full images.
    @Attribute(.externalStorage) var thumbnailData: Data?

    var statusRaw: String = OutboxStatus.queued.rawValue
    var attemptCount: Int = 0
    var lastAttemptAt: Date?
    var nextAttemptAt: Date?
    var lastError: String?

    /// Set once the server responds.
    var serverRequestID: UUID?
    var serverStatus: String?

    var status: OutboxStatus {
        get { OutboxStatus(rawValue: statusRaw) ?? .queued }
        set { statusRaw = newValue.rawValue }
    }

    init(
        captureID: UUID,
        createdAt: Date,
        buildingID: String,
        summary: String,
        metadataJSON: Data,
        imageFilename: String,
        thumbnailData: Data?
    ) {
        self.captureID = captureID
        self.createdAt = createdAt
        self.buildingID = buildingID
        self.summary = summary
        self.metadataJSON = metadataJSON
        self.imageFilename = imageFilename
        self.thumbnailData = thumbnailData
        self.statusRaw = OutboxStatus.queued.rawValue
    }
}

/// A snapshot of an outbox row, safe to hand to the UI or across an actor boundary.
///
/// SwiftData models are not `Sendable`, and passing them out of the store actor is the usual
/// way to end up with a crash on a background context. The store returns these instead.
struct OutboxItem: Identifiable, Sendable, Equatable {
    var id: UUID
    var createdAt: Date
    var summary: String
    var buildingID: String
    var status: OutboxStatus
    var attemptCount: Int
    var lastError: String?
    var serverStatus: String?
    var thumbnailData: Data?
    var intrinsicsSource: IntrinsicsSource?
    var intrinsicsTrusted: Bool?
}
