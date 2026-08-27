import SwiftData
import SwiftUI

/// Composition root.
///
/// Dependencies are built once here and handed down through the SwiftUI environment. There is
/// no service locator and no singletons beyond the shared `URLSession`: every collaborator a
/// type needs arrives through its initialiser, which is what makes `CaptureAssembler` and the
/// transform layer testable without a device.
@MainActor
final class AppContainer {

    let settings: AppSettings
    let session: ARCaptureSession
    let store: OutboxStore
    let synchronizer: OutboxSynchronizer
    let modelContainer: ModelContainer

    private(set) lazy var captureViewModel = CaptureViewModel(
        session: session,
        assembler: CaptureAssembler(),
        store: store,
        synchronizer: synchronizer,
        settings: settings
    )

    private(set) lazy var reportsViewModel = ReportsViewModel(
        store: store,
        synchronizer: synchronizer
    )

    init() {
        let settings = AppSettings()
        self.settings = settings
        self.session = ARCaptureSession()

        do {
            modelContainer = try ModelContainer(for: OutboxRecord.self)
        } catch {
            // The outbox is the app's reason to exist offline; without it there is nothing
            // useful to degrade to, so fail loudly rather than silently dropping reports.
            fatalError("Could not open the local report store: \(error)")
        }

        let store = OutboxStore(modelContainer: modelContainer)
        self.store = store

        let client = CaptureAPIClient(settingsProvider: settings.configurationProvider())
        self.synchronizer = OutboxSynchronizer(store: store, client: client)
    }

    func start() {
        let allowsCellular = settings.uploadOnCellular
        Task {
            await synchronizer.start(allowsCellular: allowsCellular)
            try? await store.pruneOrphanedImages()
        }
    }
}

@main
struct CBMCaptureApp: App {

    @State private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CaptureView()
                .environment(container.captureViewModel)
                .environment(container.reportsViewModel)
                .environment(container.settings)
                .modelContainer(container.modelContainer)
                .preferredColorScheme(.dark)
                .task { container.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            // Returning to the app is the most common moment for connectivity to have changed.
            Task {
                await container.synchronizer.setAllowsCellular(container.settings.uploadOnCellular)
                await container.captureViewModel.refreshQueueCount()
            }
        }
    }
}
