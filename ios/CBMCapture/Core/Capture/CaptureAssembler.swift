import CoreGraphics
import CryptoKit
import Foundation
import UIKit

/// Builds the capture package: the JPEG plus the metadata that describes it.
///
/// This is the one place in the app where the image, the intrinsics, and the target pixel are
/// brought into a single frame, and it is written so that they cannot be produced separately.
/// `ImageTransform.apply` computes the transform once; the same `Result` drives both the pixel
/// render and the metadata. There is no code path that renders an image with one size and
/// stamps K from another.
struct CaptureAssembler: Sendable {

    struct Package: Sendable {
        var metadata: CaptureMetadata
        var imageData: Data
    }

    struct Input: Sendable {
        var captureID: UUID
        var buildingID: String
        var reporterEmail: String?
        var description: String?
        var capturedAt: Date
        /// Tap in sensor-native image pixels, before rotation and downscale.
        var targetPixelInSensorFrame: CGPoint
        var turn: QuarterTurn
    }

    enum AssemblyError: Error, LocalizedError {
        case frameMismatch(String)

        var errorDescription: String? {
            switch self {
            case let .frameMismatch(detail): "Internal consistency check failed: \(detail)"
            }
        }
    }

    private let renderer: PixelBufferRenderer
    private let client: ClientInfo

    init(renderer: PixelBufferRenderer = PixelBufferRenderer(), client: ClientInfo = .current) {
        self.renderer = renderer
        self.client = client
    }

    func assemble(snapshot: ARCaptureSession.FrameSnapshot, input: Input) throws -> Package {
        // 1. One transform, computed once, applied to K and to the tap together.
        let transform = ImageTransform.apply(
            camera: snapshot.camera,
            target: input.targetPixelInSensorFrame,
            turn: input.turn
        )

        // 2. The same transform drives the pixels.
        let imageData = try renderer.encodeJPEG(from: snapshot.pixelBuffer, applying: transform)

        // 3. Trust is decided on the transmitted-frame K, not the sensor-frame K, because that
        //    is the one the server and MultiSet will actually use.
        let intrinsics = IntrinsicsGate.intrinsics(
            from: transform.camera,
            source: .arkit,
            lens: nil
        )

        let target = TargetDescriptor(
            pixel: PixelPoint(x: transform.target.x, y: transform.target.y),
            source: .userTap,
            centrality: ImageTransform.centrality(
                of: transform.target,
                inWidth: transform.camera.width,
                height: transform.camera.height
            )
        )

        let image = ImageDescriptor(
            width: transform.camera.width,
            height: transform.camera.height,
            sha256: Self.sha256Hex(imageData),
            byteLength: imageData.count,
            orientationApplied: transform.turn.rawValue,
            sourceWidth: transform.sourceWidth,
            sourceHeight: transform.sourceHeight,
            scale: transform.scale
        )

        let metadata = CaptureMetadata(
            captureID: input.captureID,
            buildingID: input.buildingID,
            reporterEmail: input.reporterEmail,
            description: input.description,
            capturedAt: input.capturedAt,
            client: client,
            image: image,
            camera: intrinsics,
            target: target,
            pose: snapshot.pose
        )

        try Self.assertFrameConsistency(metadata)
        return Package(metadata: metadata, imageData: imageData)
    }

    /// The invariant from `docs/INTRINSICS.md` section 2.3, checked before anything is persisted.
    ///
    /// This should be unreachable given the construction above. It is here because the cost of
    /// the check is a few comparisons and the cost of it being wrong is a confidently incorrect
    /// IFC GlobalId attached to a real work order.
    static func assertFrameConsistency(_ metadata: CaptureMetadata) throws {
        guard metadata.camera.width == metadata.image.width,
              metadata.camera.height == metadata.image.height else {
            throw AssemblyError.frameMismatch(
                "K describes \(metadata.camera.width)x\(metadata.camera.height) "
                + "but the image is \(metadata.image.width)x\(metadata.image.height)"
            )
        }
        guard (0..<Double(metadata.image.width)).contains(metadata.target.pixel.x),
              (0..<Double(metadata.image.height)).contains(metadata.target.pixel.y) else {
            throw AssemblyError.frameMismatch("the target pixel lies outside the image")
        }
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

extension ClientInfo {
    static var current: ClientInfo {
        let bundle = Bundle.main
        let short = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        // `ProcessInfo` rather than `UIDevice.current.systemVersion`: the latter is
        // main-actor isolated under Swift 6, and this is read from whichever context is
        // assembling a package. Isolating it to the main actor to satisfy the compiler would
        // put a UI dependency in the middle of the capture path for no benefit.
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return ClientInfo(
            appVersion: "\(short) (\(build))",
            osVersion: "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)",
            deviceModel: Self.hardwareIdentifier
        )
    }

    /// `iPhone15,2` rather than the marketing name: the ablation in section 8 of the calibration
    /// note needs to distinguish camera hardware, and marketing names do not always do that.
    private static var hardwareIdentifier: String {
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
    }
}
