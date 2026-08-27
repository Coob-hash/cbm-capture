import Observation
import SwiftUI

@MainActor
@Observable
final class ReportsViewModel {
    private(set) var items: [OutboxItem] = []
    private(set) var isLoading = false

    private let store: OutboxStore
    private let synchronizer: OutboxSynchronizer

    init(store: OutboxStore, synchronizer: OutboxSynchronizer) {
        self.store = store
        self.synchronizer = synchronizer
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        items = (try? await store.allItems()) ?? []
    }

    func retry(_ id: UUID) async {
        await synchronizer.retry(id)
        await load()
    }

    func delete(_ id: UUID) async {
        try? await store.delete(id)
        await load()
    }

    func sendAllNow() async {
        await synchronizer.drain()
        await load()
    }
}

/// History and queue in one list.
///
/// The worker's question is always "did my report get through?", so status is the most
/// prominent thing on each row, and a failed upload offers a retry rather than hiding behind a
/// log line.
struct ReportsView: View {

    @Environment(ReportsViewModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            Group {
                if model.items.isEmpty {
                    ContentUnavailableView(
                        "No reports yet",
                        systemImage: "camera.viewfinder",
                        description: Text("Photos you take will appear here until the office has received them.")
                    )
                } else {
                    List {
                        ForEach(model.items) { item in
                            ReportRow(item: item) {
                                Task { await model.retry(item.id) }
                            }
                        }
                        .onDelete { indexSet in
                            let ids = indexSet.map { model.items[$0].id }
                            Task { for id in ids { await model.delete(id) } }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("My reports")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Send all now", systemImage: "arrow.up.circle") {
                            Task { await model.sendAllNow() }
                        }
                        Button("Settings", systemImage: "gearshape") { showSettings = true }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .refreshable { await model.load() }
            .task { await model.load() }
            .sheet(isPresented: $showSettings) { SettingsView() }
        }
    }
}

private struct ReportRow: View {
    let item: OutboxItem
    let onRetry: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            thumbnail

            VStack(alignment: .leading, spacing: 4) {
                Text(item.summary)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(2)

                Text(item.createdAt, format: .dateTime.day().month().hour().minute())
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack(spacing: 6) {
                    StatusChip(status: item.status)
                    if item.intrinsicsTrusted == false {
                        Label("Manual location", systemImage: "questionmark.circle")
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }
                }

                if let error = item.lastError, item.status != .delivered {
                    Text(error)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                if item.status == .rejected {
                    Button("Try again", action: onRetry)
                        .font(.caption)
                        .buttonStyle(.bordered)
                        .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let data = item.thumbnailData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        } else {
            RoundedRectangle(cornerRadius: 8)
                .fill(.quaternary)
                .frame(width: 56, height: 56)
                .overlay(Image(systemName: "photo").foregroundStyle(.secondary))
        }
    }
}

private struct StatusChip: View {
    let status: OutboxStatus

    var body: some View {
        Label(status.displayName, systemImage: icon)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tint.opacity(0.15), in: Capsule())
            .foregroundStyle(tint)
    }

    private var icon: String {
        switch status {
        case .queued: "clock"
        case .uploading: "arrow.up.circle"
        case .delivered: "checkmark.circle.fill"
        case .rejected: "exclamationmark.circle.fill"
        }
    }

    private var tint: Color {
        switch status {
        case .queued: .secondary
        case .uploading: .blue
        case .delivered: .green
        case .rejected: .red
        }
    }
}
