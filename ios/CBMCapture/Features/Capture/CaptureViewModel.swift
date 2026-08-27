import CoreGraphics
import Foundation
import Observation
import UIKit

/// Drives the capture screen.
///
/// The screen has exactly one gesture: the worker taps the damage. That single tap is both the
/// shutter and the target designation, which is not just a UI simplification - it is what
/// guarantees the tap and the frame are the same instant. A separate "aim, shoot, then mark"
/// flow would let the phone move between the two, and the marked pixel would belong to a
/// different view of the room than the photograph.
@MainActor
@Observable
final class CaptureViewModel {

    enum Phase: Equatable {
        case aiming
        case processing
        case reviewing(ReviewState)
        case failed(String)
    }

    /// Everything the review screen needs, already in the transmitted frame.
    struct ReviewState: Equatable {
        var package: PackagePreview
        var description: String = ""
        /// True when the defect sits too near the frame edge for the pinhole model to hold.
        var isOffCentre: Bool
    }

    /// A value-type view of the assembled package. The `CaptureAssembler.Package` itself is not
    /// `Equatable`, and the review screen only needs these fields.
    struct PackagePreview: Equatable {
        var captureID: UUID
        var image: UIImage
        var targetPixel: CGPoint
        var imageSize: CGSize
        var intrinsicsSource: IntrinsicsSource
        var intrinsicsTrusted: Bool
        var focalLength: Double
        var centrality: Double
    }

    private(set) var phase: Phase = .aiming
    private(set) var pendingCount = 0
    var lastSentMessage: String?

    private let session: ARCaptureSession
    private let assembler: CaptureAssembler
    private let store: OutboxStore
    private let synchronizer: OutboxSynchronizer
    private let settings: AppSettings

    /// The package awaiting the worker's confirmation. Held outside `Phase` because it carries
    /// the JPEG bytes, which have no business being compared for equality on every state change.
    private var stagedPackage: CaptureAssembler.Package?

    init(
        session: ARCaptureSession,
        assembler: CaptureAssembler,
        store: OutboxStore,
        synchronizer: OutboxSynchronizer,
        settings: AppSettings
    ) {
        self.session = session
        self.assembler = assembler
        self.store = store
        self.synchronizer = synchronizer
        self.settings = settings
    }

    var arSession: ARCaptureSession { session }

    var canCapture: Bool {
        if case .aiming = phase { return session.isRunning && settings.isConfigured }
        return false
    }

    // MARK: - Lifecycle

    func onAppear() {
        session.start()
        Task { await refreshQueueCount() }
    }

    func onDisappear() {
        session.pause()
    }

    // MARK: - The single gesture

    /// Capture the frame at the instant of the tap and assemble the package.
    func captureTapped(at point: CGPoint, viewportSize: CGSize, interfaceOrientation: UIInterfaceOrientation) {
        guard canCapture else { return }
        phase = .processing

        // Read the frame synchronously, on this run loop turn, so it is the frame the worker was
        // looking at when they tapped.
        let snapshot: ARCaptureSession.FrameSnapshot
        do {
            snapshot = try session.snapshot(for: interfaceOrientation, viewportSize: viewportSize)
        } catch {
            phase = .failed(error.localizedDescription)
            return
        }

        let targetPixel = ARCaptureSession.imagePixel(
            forViewPoint: point, viewportSize: viewportSize, snapshot: snapshot
        )
        let turn = QuarterTurn.uprightRotation(forDeviceOrientation: Self.orientationInput(interfaceOrientation))
            ?? .cw90

        let input = CaptureAssembler.Input(
            captureID: UUID(),
            buildingID: settings.buildingID,
            reporterEmail: settings.reporterEmail.isEmpty ? nil : settings.reporterEmail,
            description: nil,
            capturedAt: Date(),
            targetPixelInSensorFrame: targetPixel,
            turn: turn
        )

        let assembler = self.assembler
        Task {
            do {
                // JPEG encoding of a 12 MP frame is not main-thread work.
                let package = try await Task.detached(priority: .userInitiated) {
                    try assembler.assemble(snapshot: snapshot, input: input)
                }.value
                await self.stage(package)
            } catch {
                self.phase = .failed(error.localizedDescription)
            }
        }
    }

    private func stage(_ package: CaptureAssembler.Package) {
        stagedPackage = package

        guard let image = UIImage(data: package.imageData) else {
            phase = .failed("The captured image could not be displayed.")
            return
        }
        let centrality = package.metadata.target.centrality ?? 0
        let preview = PackagePreview(
            captureID: package.metadata.captureID,
            image: image,
            targetPixel: CGPoint(x: package.metadata.target.pixel.x, y: package.metadata.target.pixel.y),
            imageSize: CGSize(width: package.metadata.image.width, height: package.metadata.image.height),
            intrinsicsSource: package.metadata.camera.source,
            intrinsicsTrusted: package.metadata.camera.trusted,
            focalLength: package.metadata.camera.fx,
            centrality: centrality
        )
        phase = .reviewing(ReviewState(
            package: preview,
            isOffCentre: centrality > ImageTransform.centralityWarningThreshold
        ))
    }

    // MARK: - Review actions

    func updateDescription(_ text: String) {
        guard case var .reviewing(state) = phase else { return }
        state.description = text
        phase = .reviewing(state)
    }

    func discard() {
        stagedPackage = nil
        phase = .aiming
    }

    /// Persist to the outbox, then let the synchroniser deal with the network.
    ///
    /// The worker is told "saved", not "sent": the report is durable at this point, and whether
    /// it has reached n8n yet is a separate fact shown on the Reports screen. Promising delivery
    /// the app cannot yet guarantee is how a queue silently loses trust.
    func send() {
        guard case let .reviewing(state) = phase, var package = stagedPackage else { return }

        let trimmed = state.description.trimmingCharacters(in: .whitespacesAndNewlines)
        package.metadata.description = trimmed.isEmpty ? nil : trimmed

        let thumbnail = Self.thumbnail(from: state.package.image)
        let store = self.store
        let synchronizer = self.synchronizer

        phase = .processing
        Task {
            do {
                _ = try await store.enqueue(package, thumbnail: thumbnail)
                self.stagedPackage = nil
                self.lastSentMessage = "Report saved. It will upload automatically."
                self.phase = .aiming
                await self.refreshQueueCount()
                await synchronizer.drain()
            } catch {
                self.phase = .failed("Could not save the report: \(error.localizedDescription)")
            }
        }
    }

    func dismissError() {
        phase = .aiming
    }

    func refreshQueueCount() async {
        pendingCount = (try? await store.pendingCount()) ?? 0
    }

    // MARK: - Helpers

    private static func orientationInput(_ orientation: UIInterfaceOrientation) -> DeviceOrientationInput {
        switch orientation {
        case .portrait: .portrait
        case .portraitUpsideDown: .portraitUpsideDown
        // UIInterfaceOrientation names the *interface* rotation, which is the opposite of the
        // device rotation of the same name. The mapping is inverted here deliberately.
        case .landscapeLeft: .landscapeRight
        case .landscapeRight: .landscapeLeft
        default: .portrait
        }
    }

    private static func thumbnail(from image: UIImage, maxSide: CGFloat = 240) -> Data? {
        let longest = max(image.size.width, image.size.height)
        let factor = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: image.size.width * factor, height: image.size.height * factor)
        let renderer = UIGraphicsImageRenderer(size: size)
        let scaled = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return scaled.jpegData(compressionQuality: 0.7)
    }
}
