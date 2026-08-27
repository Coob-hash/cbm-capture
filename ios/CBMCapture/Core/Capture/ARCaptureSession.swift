import ARKit
import Observation
import UIKit
import simd

/// Owns the `ARSession` and converts one instant of it into a `FrameSnapshot`.
///
/// The reason this app runs ARKit at all, rather than `AVCapturePhotoOutput`, is
/// `ARFrame.camera.intrinsics`: a per-frame factory calibration expressed in the coordinate
/// system of `imageResolution`, which is the very frame `capturedImage` occupies. Nothing else
/// on iOS hands you the image and its calibration guaranteed to be the same frame.
@MainActor
@Observable
final class ARCaptureSession: NSObject {

    /// Everything read out of a single `ARFrame`, captured atomically at shutter time.
    ///
    /// Holding an `ARFrame` beyond the moment of capture starves the session's buffer pool, so
    /// the fields are copied out and the frame released immediately.
    struct FrameSnapshot: @unchecked Sendable {
        /// Sensor-native frame. Retained only for the duration of the render.
        let pixelBuffer: CVPixelBuffer
        /// Intrinsics in the sensor-native frame, before any rotation or downscale.
        let camera: PinholeCamera
        let pose: PoseSample?
        let trackingState: TrackingState
        /// Maps normalised image coordinates to normalised viewport coordinates.
        let displayTransform: CGAffineTransform
    }

    enum SessionError: Error, LocalizedError {
        case unsupportedDevice
        case noFrameAvailable
        case trackingUnavailable

        var errorDescription: String? {
            switch self {
            case .unsupportedDevice:
                "This device does not support the AR tracking required to read the camera calibration."
            case .noFrameAvailable:
                "The camera is not ready yet. Wait a moment and try again."
            case .trackingUnavailable:
                "The camera is still starting up."
            }
        }
    }

    // MARK: - Observable state

    private(set) var trackingState: TrackingState = .notAvailable
    private(set) var trackingAdvice: String?
    private(set) var isRunning = false

    let session = ARSession()

    static var isSupported: Bool { ARWorldTrackingConfiguration.isSupported }

    override init() {
        super.init()
        session.delegate = self
    }

    // MARK: - Lifecycle

    func start() {
        guard Self.isSupported else {
            trackingAdvice = SessionError.unsupportedDevice.errorDescription
            return
        }
        let configuration = ARWorldTrackingConfiguration()
        configuration.worldAlignment = .gravity
        configuration.planeDetection = []
        configuration.environmentTexturing = .none
        // A higher-resolution capture format gives the vision model and MultiSet more to work
        // with. The frame is downscaled before upload anyway, but downscaling from a larger
        // original is strictly better than upscaling a small one.
        if let format = ARWorldTrackingConfiguration.supportedVideoFormats
            .max(by: { $0.imageResolution.width * $0.imageResolution.height
                     < $1.imageResolution.width * $1.imageResolution.height }) {
            configuration.videoFormat = format
        }
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        isRunning = true
    }

    func pause() {
        session.pause()
        isRunning = false
    }

    // MARK: - Capture

    /// Read the current frame. Throws rather than returning a stale or invented value.
    func snapshot(for interfaceOrientation: UIInterfaceOrientation, viewportSize: CGSize) throws -> FrameSnapshot {
        guard let frame = session.currentFrame else { throw SessionError.noFrameAvailable }

        let resolution = frame.camera.imageResolution
        let k = frame.camera.intrinsics
        let camera = PinholeCamera(
            fx: Double(k.columns.0.x),
            fy: Double(k.columns.1.y),
            cx: Double(k.columns.2.x),
            cy: Double(k.columns.2.y),
            width: Int(resolution.width),
            height: Int(resolution.height)
        )

        return FrameSnapshot(
            pixelBuffer: frame.capturedImage,
            camera: camera,
            pose: Self.pose(from: frame),
            trackingState: Self.trackingState(from: frame.camera.trackingState),
            displayTransform: frame.displayTransform(for: interfaceOrientation, viewportSize: viewportSize)
        )
    }

    /// Convert a tap in view coordinates to a pixel in the sensor-native image frame.
    ///
    /// `displayTransform` maps normalised *image* space to normalised *view* space, including
    /// the aspect-fill crop and the interface rotation, so its inverse is exactly the mapping
    /// needed here. Doing this by hand from the viewport aspect ratio is the usual way to get a
    /// tap that is subtly wrong near the edges.
    static func imagePixel(
        forViewPoint point: CGPoint,
        viewportSize: CGSize,
        snapshot: FrameSnapshot
    ) -> CGPoint {
        guard viewportSize.width > 0, viewportSize.height > 0 else { return .zero }
        let normalizedView = CGPoint(x: point.x / viewportSize.width,
                                     y: point.y / viewportSize.height)
        let normalizedImage = normalizedView.applying(snapshot.displayTransform.inverted())
        let pixel = CGPoint(x: normalizedImage.x * Double(snapshot.camera.width),
                            y: normalizedImage.y * Double(snapshot.camera.height))
        return ImageTransform.clamp(pixel, toWidth: snapshot.camera.width, height: snapshot.camera.height)
    }

    // MARK: - Conversions

    private static func pose(from frame: ARFrame) -> PoseSample? {
        guard case .normal = frame.camera.trackingState else {
            // A limited-tracking pose is not worth transmitting even as advisory data.
            return nil
        }
        let transform = frame.camera.transform
        let translation = transform.columns.3
        let quaternion = simd_quatf(simd_float3x3(
            simd_make_float3(transform.columns.0),
            simd_make_float3(transform.columns.1),
            simd_make_float3(transform.columns.2)
        ))
        return PoseSample(
            source: .arkit,
            position: Vector3(x: Double(translation.x), y: Double(translation.y), z: Double(translation.z)),
            rotation: QuaternionValue(
                x: Double(quaternion.imag.x),
                y: Double(quaternion.imag.y),
                z: Double(quaternion.imag.z),
                w: Double(quaternion.real)
            ),
            trackingState: .normal
        )
    }

    private static func trackingState(from state: ARCamera.TrackingState) -> TrackingState {
        switch state {
        case .normal: .normal
        case .limited: .limited
        case .notAvailable: .notAvailable
        }
    }

    private static func advice(for state: ARCamera.TrackingState) -> String? {
        guard case let .limited(reason) = state else { return nil }
        return switch reason {
        case .initializing: "Starting up - move the phone slowly."
        case .excessiveMotion: "Moving too fast - slow down."
        case .insufficientFeatures: "Not enough detail to track - try a more textured surface."
        case .relocalizing: "Re-finding position - hold steady."
        @unknown default: "Tracking is limited - hold steady."
        }
    }
}

// MARK: - ARSessionDelegate

extension ARCaptureSession: ARSessionDelegate {
    nonisolated func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        let state = camera.trackingState
        Task { @MainActor in
            self.trackingState = Self.trackingState(from: state)
            self.trackingAdvice = Self.advice(for: state)
        }
    }

    nonisolated func session(_ session: ARSession, didFailWithError error: Error) {
        Task { @MainActor in
            self.isRunning = false
            self.trackingAdvice = error.localizedDescription
        }
    }

    nonisolated func sessionWasInterrupted(_ session: ARSession) {
        Task { @MainActor in self.trackingAdvice = "Camera interrupted." }
    }

    nonisolated func sessionInterruptionEnded(_ session: ARSession) {
        Task { @MainActor in
            self.trackingAdvice = nil
            self.start()
        }
    }
}
