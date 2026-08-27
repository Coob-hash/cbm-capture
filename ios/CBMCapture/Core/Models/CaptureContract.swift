import Foundation

/// Wire types for `POST /cbm/capture`, mirroring `contract/capture-metadata.schema.json`.
///
/// These are deliberately dumb `Codable` value types with no behaviour. Domain logic lives in
/// `ImageTransform` and `IntrinsicsGate`; keeping the wire shape inert means a contract change
/// is a diff in one file rather than a hunt through the feature layer.
enum CaptureContract {
    static let schemaVersion = "1.0.0"
}

// MARK: - Provenance

enum IntrinsicsSource: String, Codable, Sendable, CaseIterable {
    case arkit = "ARKIT"
    case arcore = "ARCORE"
    case androidCamera2 = "ANDROID_CAMERA2"
    case exif = "EXIF"
    case manualOverride = "MANUAL_OVERRIDE"

    /// Human-facing label for the capture screen badge.
    var displayName: String {
        switch self {
        case .arkit: "ARKit factory calibration"
        case .arcore: "ARCore factory calibration"
        case .androidCamera2: "Camera2 factory calibration"
        case .exif: "EXIF estimate"
        case .manualOverride: "Manual override"
        }
    }
}

enum TargetSource: String, Codable, Sendable {
    case userTap = "USER_TAP"
}

enum TrackingState: String, Codable, Sendable {
    case normal = "NORMAL"
    case limited = "LIMITED"
    case notAvailable = "NOT_AVAILABLE"
}

// MARK: - Metadata document

struct CaptureMetadata: Codable, Sendable, Equatable {
    var schemaVersion: String = CaptureContract.schemaVersion
    var captureID: UUID
    var buildingID: String
    var reporterEmail: String?
    var description: String?
    var capturedAt: Date
    var client: ClientInfo
    var image: ImageDescriptor
    var camera: CameraIntrinsics
    var target: TargetDescriptor
    var pose: PoseSample?

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case captureID = "capture_id"
        case buildingID = "building_id"
        case reporterEmail = "reporter_email"
        case description
        case capturedAt = "captured_at"
        case client, image, camera, target, pose
    }
}

struct ClientInfo: Codable, Sendable, Equatable {
    var platform: String = "IOS"
    var appVersion: String
    var osVersion: String
    var deviceModel: String

    enum CodingKeys: String, CodingKey {
        case platform
        case appVersion = "app_version"
        case osVersion = "os_version"
        case deviceModel = "device_model"
    }
}

struct ImageDescriptor: Codable, Sendable, Equatable {
    var width: Int
    var height: Int
    var mimeType: String = "image/jpeg"
    var sha256: String
    var byteLength: Int
    /// Quarter-turns clockwise already baked into the pixel data.
    var orientationApplied: Int
    var sourceWidth: Int?
    var sourceHeight: Int?
    var scale: Double?

    enum CodingKeys: String, CodingKey {
        case width, height, sha256, scale
        case mimeType = "mime_type"
        case byteLength = "byte_length"
        case orientationApplied = "orientation_applied"
        case sourceWidth = "source_width"
        case sourceHeight = "source_height"
    }
}

struct CameraIntrinsics: Codable, Sendable, Equatable {
    var source: IntrinsicsSource
    var trusted: Bool
    var fx: Double
    var fy: Double
    var cx: Double
    var cy: Double
    var skew: Double = 0
    var width: Int
    var height: Int
    var lens: String?
    var distortion: [Double]?
}

struct TargetDescriptor: Codable, Sendable, Equatable {
    var pixel: PixelPoint
    var source: TargetSource = .userTap
    var centrality: Double?
}

struct PixelPoint: Codable, Sendable, Equatable {
    var x: Double
    var y: Double
}

struct PoseSample: Codable, Sendable, Equatable {
    var source: IntrinsicsSource
    var position: Vector3
    var rotation: QuaternionValue
    var trackingState: TrackingState
    var coordinateSpace: String = "AR_SESSION_WORLD"

    enum CodingKeys: String, CodingKey {
        case source, position, rotation
        case trackingState = "tracking_state"
        case coordinateSpace = "coordinate_space"
    }
}

struct Vector3: Codable, Sendable, Equatable {
    var x: Double, y: Double, z: Double
}

struct QuaternionValue: Codable, Sendable, Equatable {
    var x: Double, y: Double, z: Double, w: Double
}

// MARK: - Responses

struct CaptureAcceptedResponse: Codable, Sendable {
    var ok: Bool
    var captureID: UUID?
    var requestID: UUID?
    var status: String?
    var duplicate: Bool?

    enum CodingKeys: String, CodingKey {
        case ok, status, duplicate
        case captureID = "capture_id"
        case requestID = "request_id"
    }
}

struct CaptureErrorResponse: Codable, Sendable {
    var ok: Bool
    var error: String
    var detail: String?
}

struct CaptureHealthResponse: Codable, Sendable {
    var ok: Bool
    var buildingID: String?
    var schemaVersion: String?

    enum CodingKeys: String, CodingKey {
        case ok
        case buildingID = "building_id"
        case schemaVersion = "schema_version"
    }
}

// MARK: - JSON coders

extension JSONEncoder {
    /// The single encoder used for the metadata part. RFC 3339 with fractional seconds, in UTC.
    static var captureContract: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(ISO8601DateFormatter.captureContract.string(from: date))
        }
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

extension JSONDecoder {
    static var captureContract: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let raw = try decoder.singleValueContainer().decode(String.self)
            guard let date = ISO8601DateFormatter.captureContract.date(from: raw) else {
                throw DecodingError.dataCorruptedError(
                    in: try decoder.singleValueContainer(),
                    debugDescription: "Expected RFC 3339 timestamp, got \(raw)"
                )
            }
            return date
        }
        return decoder
    }
}

extension ISO8601DateFormatter {
    static let captureContract: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()
}
