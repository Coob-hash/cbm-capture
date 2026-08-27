import SwiftUI

/// One-time enrolment: endpoint, token, building, reporter.
///
/// Intended to be filled in once by whoever hands the phone to the worker, then never opened
/// again. The "Test connection" button exists so that misconfiguration is discovered here,
/// in an office with signal, rather than in a plant room with a queue of failed uploads.
struct SettingsView: View {

    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    @State private var token = ""
    @State private var testResult: TestResult?
    @State private var isTesting = false
    @State private var saveError: String?

    private enum TestResult {
        case success(building: String?)
        case failure(String)
    }

    var body: some View {
        @Bindable var settings = settings

        NavigationStack {
            Form {
                Section {
                    TextField("https://n8n.example.com/webhook", text: $settings.baseURLString)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Server address")
                } footer: {
                    if settings.isInsecureTransport {
                        Label(
                            "This address is not encrypted. Photographs and the access token will "
                            + "be sent in the clear. Use https for anything beyond a local test.",
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .foregroundStyle(.orange)
                    } else {
                        Text("The base address of the n8n webhook, without /cbm/capture.")
                    }
                }

                Section {
                    SecureField(settings.hasToken ? "Stored - enter a new one to replace" : "Access token",
                                text: $token)
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if settings.hasToken {
                        Button("Remove stored token", role: .destructive) {
                            settings.clearToken()
                            token = ""
                            testResult = nil
                        }
                    }
                } header: {
                    Text("Access token")
                } footer: {
                    Text("Held in the device Keychain. It is never written to a backup or a log.")
                }

                Section("Reporting") {
                    TextField("Building ID", text: $settings.buildingID)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Your email", text: $settings.reporterEmail)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    Toggle("Upload over mobile data", isOn: $settings.uploadOnCellular)
                } footer: {
                    Text("Turn this off to hold reports until the phone is on Wi-Fi.")
                }

                Section {
                    Button {
                        Task { await test() }
                    } label: {
                        HStack {
                            Text("Test connection")
                            Spacer()
                            if isTesting { ProgressView() }
                        }
                    }
                    .disabled(isTesting)

                    switch testResult {
                    case let .success(building):
                        Label(
                            building.map { "Connected. Server is configured for \($0)." }
                                ?? "Connected.",
                            systemImage: "checkmark.circle.fill"
                        )
                        .foregroundStyle(.green)
                    case let .failure(message):
                        Label(message, systemImage: "xmark.circle.fill")
                            .foregroundStyle(.red)
                    case nil:
                        EmptyView()
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { save(); dismiss() }
                }
            }
            .alert("Could not save", isPresented: .constant(saveError != nil)) {
                Button("OK") { saveError = nil }
            } message: {
                Text(saveError ?? "")
            }
        }
    }

    private func save() {
        guard !token.isEmpty else { return }
        do {
            try settings.setToken(token)
            token = ""
        } catch {
            saveError = error.localizedDescription
        }
    }

    private func test() async {
        save()
        isTesting = true
        defer { isTesting = false }

        let client = CaptureAPIClient(settingsProvider: settings.configurationProvider())
        do {
            let health = try await client.checkHealth()
            if let serverBuilding = health.buildingID, serverBuilding != settings.buildingID {
                testResult = .failure(
                    "Connected, but the server expects building \(serverBuilding) "
                    + "and this phone is set to \(settings.buildingID)."
                )
            } else {
                testResult = .success(building: health.buildingID)
            }
        } catch {
            testResult = .failure(error.localizedDescription)
        }
    }
}
