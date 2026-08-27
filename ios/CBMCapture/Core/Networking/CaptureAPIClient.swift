import Foundation

/// Outcome of one upload attempt, in the only two categories the outbox cares about.
enum UploadOutcome: Sendable {
    case delivered(requestID: UUID?, status: String?, duplicate: Bool)
    /// The server will never accept this package. Stop retrying and tell the worker.
    case permanentFailure(reason: String)
    /// Worth trying again later.
    case transientFailure(reason: String)
}

/// Errors surfaced to the UI for interactive operations (health check, credential test).
enum APIError: Error, LocalizedError {
    case notConfigured
    case unauthorized
    case server(status: Int, detail: String?)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: "Set the server address and access token in Settings first."
        case .unauthorized: "The access token was rejected."
        case let .server(status, detail): detail ?? "The server returned HTTP \(status)."
        case let .transport(message): message
        }
    }
}

protocol CaptureUploading: Sendable {
    func upload(metadataJSON: Data, imageURL: URL, captureID: UUID) async -> UploadOutcome
    func checkHealth() async throws -> CaptureHealthResponse
}

/// Talks to the n8n webhook.
///
/// Every failure is classified transient or permanent before it reaches the outbox. That
/// classification is the whole contract of this type: retrying a 422 forever would keep a
/// broken capture in the queue indefinitely, and giving up on a 503 would lose a good one.
struct CaptureAPIClient: CaptureUploading {

    private let session: URLSession
    private let settings: @Sendable () async -> (baseURL: URL, token: String)?

    init(
        session: URLSession = .captureSession,
        settingsProvider: @escaping @Sendable () async -> (baseURL: URL, token: String)?
    ) {
        self.session = session
        self.settings = settingsProvider
    }

    // MARK: - Upload

    func upload(metadataJSON: Data, imageURL: URL, captureID: UUID) async -> UploadOutcome {
        guard let config = await settings() else {
            return .permanentFailure(reason: APIError.notConfigured.localizedDescription)
        }
        guard FileManager.default.fileExists(atPath: imageURL.path) else {
            return .permanentFailure(reason: "The stored image for this report is missing.")
        }

        var form = MultipartFormData()
        form.addField(name: "metadata", json: metadataJSON)
        form.addFile(name: "image", filename: "\(captureID.uuidString).jpg",
                     url: imageURL, contentType: "image/jpeg")

        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("upload-\(captureID.uuidString).multipart")
        defer { try? FileManager.default.removeItem(at: bodyURL) }

        do {
            try form.writeBody(to: bodyURL)
        } catch {
            return .permanentFailure(reason: "Could not prepare the upload: \(error.localizedDescription)")
        }

        var request = URLRequest(url: config.baseURL.appendingPathComponent("cbm/capture"))
        request.httpMethod = "POST"
        request.setValue(form.contentType, forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        request.setValue(captureID.uuidString, forHTTPHeaderField: "X-Capture-Id")
        request.setValue(CaptureContract.schemaVersion, forHTTPHeaderField: "X-Capture-Schema-Version")

        do {
            let (data, response) = try await session.upload(for: request, fromFile: bodyURL)
            guard let http = response as? HTTPURLResponse else {
                return .transientFailure(reason: "Unexpected response from the server.")
            }
            return Self.classify(status: http.statusCode, data: data)
        } catch let error as URLError {
            // Offline, timed out, or the connection dropped mid-body: all worth retrying.
            return .transientFailure(reason: error.localizedDescription)
        } catch {
            return .transientFailure(reason: error.localizedDescription)
        }
    }

    static func classify(status: Int, data: Data) -> UploadOutcome {
        switch status {
        case 200...299:
            if let accepted = try? JSONDecoder.captureContract.decode(CaptureAcceptedResponse.self, from: data) {
                return .delivered(requestID: accepted.requestID,
                                  status: accepted.status,
                                  duplicate: accepted.duplicate ?? false)
            }
            // A 2xx with a body we cannot parse still means the server took it. Treating that
            // as a failure would re-upload a report the FM can already see.
            return .delivered(requestID: nil, status: nil, duplicate: false)

        case 401, 403:
            return .permanentFailure(reason: "The access token was rejected. Re-enter it in Settings.")

        case 409:
            // Already staged under this capture_id. The first attempt got through and the
            // response was lost; this is success.
            return .delivered(requestID: nil, status: nil, duplicate: true)

        case 413:
            return .permanentFailure(reason: "The image is larger than the server accepts.")

        case 400, 422:
            let detail = (try? JSONDecoder().decode(CaptureErrorResponse.self, from: data))?.error
            return .permanentFailure(reason: Self.message(forServerError: detail))

        case 408, 429, 500...599:
            return .transientFailure(reason: "The server is unavailable (HTTP \(status)).")

        default:
            return .transientFailure(reason: "Unexpected server response (HTTP \(status)).")
        }
    }

    private static func message(forServerError code: String?) -> String {
        switch code {
        case "UNTRUSTED_INTRINSICS":
            "The camera calibration was not trusted, so this photo was sent for manual location instead."
        case "FRAME_MISMATCH":
            "The photo and its calibration did not match. Please retake it."
        case "CHECKSUM_MISMATCH":
            "The image was corrupted in transit and could not be recovered."
        case "UNSUPPORTED_SCHEMA_VERSION":
            "This app version is too old for the server. Please update."
        case let code?:
            "The server rejected this report (\(code))."
        case nil:
            "The server rejected this report."
        }
    }

    // MARK: - Health

    func checkHealth() async throws -> CaptureHealthResponse {
        guard let config = await settings() else { throw APIError.notConfigured }

        var request = URLRequest(url: config.baseURL.appendingPathComponent("cbm/capture/health"))
        request.httpMethod = "GET"
        request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 15

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw APIError.transport("Unexpected response.")
            }
            switch http.statusCode {
            case 200...299:
                return (try? JSONDecoder.captureContract.decode(CaptureHealthResponse.self, from: data))
                    ?? CaptureHealthResponse(ok: true, buildingID: nil, schemaVersion: nil)
            case 401, 403:
                throw APIError.unauthorized
            default:
                throw APIError.server(status: http.statusCode, detail: nil)
            }
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
    }
}

extension URLSession {
    /// Waits for connectivity rather than failing instantly when offline, and allows a generous
    /// window for a large upload over a weak site connection.
    static let captureSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 300
        configuration.allowsConstrainedNetworkAccess = true
        return URLSession(configuration: configuration)
    }()
}
