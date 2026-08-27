import Foundation
import Network

/// Drains the outbox whenever there is a network path and something to send.
///
/// Deliberately a serial actor with a single in-flight upload. Parallel uploads from a phone on
/// a weak site connection make every one of them slower and more likely to time out, and the
/// worker's report queue is measured in units, not thousands.
actor OutboxSynchronizer {

    private let store: OutboxStore
    private let client: any CaptureUploading
    private let monitor = NWPathMonitor()

    private var isDraining = false
    private var isOnline = true
    private var allowsCellular = true
    private var isExpensivePath = false

    /// Set by the owner so the UI can reflect queue depth without polling the store.
    var onQueueChanged: (@Sendable (Int) async -> Void)?

    init(store: OutboxStore, client: any CaptureUploading) {
        self.store = store
        self.client = client
    }

    func start(allowsCellular: Bool) {
        self.allowsCellular = allowsCellular
        monitor.pathUpdateHandler = { [weak self] path in
            Task { await self?.pathChanged(path) }
        }
        monitor.start(queue: DispatchQueue(label: "ai.cbm.capture.network"))

        Task {
            // A force-quit mid-upload leaves a row claimed but not delivered.
            try? await store.resetStuckUploads()
            await drain()
        }
    }

    func setAllowsCellular(_ allowed: Bool) {
        allowsCellular = allowed
        Task { await drain() }
    }

    private func pathChanged(_ path: NWPath) async {
        isOnline = path.status == .satisfied
        isExpensivePath = path.isExpensive
        if isOnline { await drain() }
    }

    /// Send everything that is due, one package at a time, then stop.
    ///
    /// Re-entrant calls are collapsed: several triggers arriving together (app foregrounded on a
    /// new network while the user saves a report) should produce one drain, not three.
    func drain() async {
        guard !isDraining else { return }
        guard isOnline else { return }
        guard allowsCellular || !isExpensivePath else { return }

        isDraining = true
        defer { isDraining = false }

        while true {
            guard let claimed = try? await store.claimNextDue(), let next = claimed else { break }

            let outcome = await client.upload(
                metadataJSON: next.metadata,
                imageURL: next.imageURL,
                captureID: next.item.id
            )

            switch outcome {
            case let .delivered(requestID, status, _):
                try? await store.markDelivered(next.item.id, requestID: requestID, serverStatus: status)
            case let .permanentFailure(reason):
                try? await store.markRejected(next.item.id, reason: reason)
            case let .transientFailure(reason):
                try? await store.markRetryable(next.item.id, reason: reason)
                await notifyQueueChanged()
                // Backing off applies to the whole queue: if the network just failed, the next
                // package will fail the same way.
                return
            }
            await notifyQueueChanged()
        }
        await notifyQueueChanged()
    }

    func retry(_ captureID: UUID) async {
        try? await store.retryNow(captureID)
        await drain()
    }

    private func notifyQueueChanged() async {
        guard let handler = onQueueChanged else { return }
        let count = (try? await store.pendingCount()) ?? 0
        await handler(count)
    }
}
