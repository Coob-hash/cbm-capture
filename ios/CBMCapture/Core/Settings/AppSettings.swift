import Foundation
import Observation

/// Device enrolment: which n8n endpoint, which building, who is reporting.
///
/// Non-secret values live in `UserDefaults`; the bearer token lives in the Keychain and is
/// never mirrored here. `isConfigured` is the single check the rest of the app uses, so there
/// is one definition of "this handset is set up".
@MainActor
@Observable
final class AppSettings {

    private enum Key {
        static let baseURL = "cbm.settings.baseURL"
        static let buildingID = "cbm.settings.buildingID"
        static let reporterEmail = "cbm.settings.reporterEmail"
        static let uploadOnCellular = "cbm.settings.uploadOnCellular"
    }

    private let defaults: UserDefaults
    private let credentials: CredentialStore

    var baseURLString: String {
        didSet { defaults.set(baseURLString, forKey: Key.baseURL) }
    }

    var buildingID: String {
        didSet { defaults.set(buildingID, forKey: Key.buildingID) }
    }

    var reporterEmail: String {
        didSet { defaults.set(reporterEmail, forKey: Key.reporterEmail) }
    }

    /// Site connections are often metered. Defaulting to Wi-Fi-only would silently strand
    /// reports, so this defaults on and is presented as a plain switch.
    var uploadOnCellular: Bool {
        didSet { defaults.set(uploadOnCellular, forKey: Key.uploadOnCellular) }
    }

    private(set) var hasToken: Bool

    init(defaults: UserDefaults = .standard, credentials: CredentialStore = CredentialStore()) {
        self.defaults = defaults
        self.credentials = credentials
        self.baseURLString = defaults.string(forKey: Key.baseURL) ?? ""
        self.buildingID = defaults.string(forKey: Key.buildingID) ?? "ROOM-POC"
        self.reporterEmail = defaults.string(forKey: Key.reporterEmail) ?? ""
        self.uploadOnCellular = defaults.object(forKey: Key.uploadOnCellular) as? Bool ?? true
        self.hasToken = credentials.token() != nil
    }

    var baseURL: URL? {
        let trimmed = baseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed), url.scheme != nil else { return nil }
        return url
    }

    var isConfigured: Bool { baseURL != nil && hasToken && !buildingID.isEmpty }

    /// Flagged in Settings rather than blocked. A local n8n on plain HTTP is a normal PoC setup,
    /// but the worker should be able to see that the token and photographs are travelling in
    /// the clear.
    var isInsecureTransport: Bool { baseURL?.scheme?.lowercased() == "http" }

    func setToken(_ token: String) throws {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            credentials.deleteToken()
            hasToken = false
        } else {
            try credentials.save(token: trimmed)
            hasToken = true
        }
    }

    func clearToken() {
        credentials.deleteToken()
        hasToken = false
    }

    /// Snapshot for the networking layer, which runs off the main actor.
    nonisolated func configurationProvider() -> @Sendable () async -> (baseURL: URL, token: String)? {
        { [credentials] in
            guard let url = await MainActor.run(body: { self.baseURL }),
                  let token = credentials.token() else { return nil }
            return (url, token)
        }
    }
}
