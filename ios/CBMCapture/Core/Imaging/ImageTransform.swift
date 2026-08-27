import CoreGraphics
import Foundation

/// A pinhole camera in the pixel frame of a specific image.
///
/// The `width`/`height` fields are not decoration: they are what make this type meaningful.
/// An intrinsics matrix without the frame it belongs to is the defect described in section 3
/// of the calibration note, so the two never travel separately here.
struct PinholeCamera: Equatable, Sendable {
    var fx: Double
    var fy: Double
    var cx: Double
    var cy: Double
    var width: Int
    var height: Int
}

/// Clockwise rotation in quarter-turns. The raw value is transmitted as `image.orientation_applied`.
enum QuarterTurn: Int, Sendable, CaseIterable {
    case none = 0
    case cw90 = 1
    case cw180 = 2
    case cw270 = 3

    /// Rotation needed to bring a sensor-native landscape frame upright for the given device
    /// orientation. `nil` for face-up/face-down/unknown, where the caller should reuse the last
    /// known good value rather than guessing.
    static func uprightRotation(forDeviceOrientation orientation: DeviceOrientationInput) -> QuarterTurn? {
        switch orientation {
        case .portrait: .cw90
        case .portraitUpsideDown: .cw270
        case .landscapeLeft: .none
        case .landscapeRight: .cw180
        case .unknown: nil
        }
    }
}

/// Deliberately not `UIDeviceOrientation`, so the transform layer stays free of UIKit and
/// remains testable on any platform.
enum DeviceOrientationInput: Sendable {
    case portrait
    case portraitUpsideDown
    case landscapeLeft
    case landscapeRight
    case unknown
}

/// Rotation and resampling of an image *together with* its intrinsics and the worker's tap.
///
/// Every function here is pure and total. The whole point of this type is that the image, K,
/// and the target pixel are transformed in one call and cannot drift apart; see
/// `docs/INTRINSICS.md` section 2 for the derivations these implement.
enum ImageTransform {

    /// Longest side of the transmitted image. The 2026_07_08 package advises <= 1280 px for the
    /// MultiSet query, and K is derived from what is actually sent, never from the original.
    static let maxUploadLongSide = 1280

    // MARK: - Rotation

    /// Size of the frame after `turn` is applied.
    static func rotatedSize(width: Int, height: Int, by turn: QuarterTurn) -> (width: Int, height: Int) {
        switch turn {
        case .none, .cw180: (width, height)
        case .cw90, .cw270: (height, width)
        }
    }

    /// Rotate intrinsics by `turn` quarter-turns clockwise.
    ///
    /// | k | size    | fx' | fy' | cx'    | cy'    |
    /// |---|---------|-----|-----|--------|--------|
    /// | 1 | H x W   | fy  | fx  | H - cy | cx     |
    /// | 2 | W x H   | fx  | fy  | W - cx | H - cy |
    /// | 3 | H x W   | fy  | fx  | cy     | W - cx |
    static func rotate(_ camera: PinholeCamera, by turn: QuarterTurn) -> PinholeCamera {
        let w = Double(camera.width)
        let h = Double(camera.height)
        let size = rotatedSize(width: camera.width, height: camera.height, by: turn)

        switch turn {
        case .none:
            return camera
        case .cw90:
            return PinholeCamera(fx: camera.fy, fy: camera.fx,
                                 cx: h - camera.cy, cy: camera.cx,
                                 width: size.width, height: size.height)
        case .cw180:
            return PinholeCamera(fx: camera.fx, fy: camera.fy,
                                 cx: w - camera.cx, cy: h - camera.cy,
                                 width: size.width, height: size.height)
        case .cw270:
            return PinholeCamera(fx: camera.fy, fy: camera.fx,
                                 cx: camera.cy, cy: w - camera.cx,
                                 width: size.width, height: size.height)
        }
    }

    /// Rotate a pixel coordinate by the same `turn`, in a frame of size `width` x `height`.
    static func rotate(point: CGPoint, in width: Int, _ height: Int, by turn: QuarterTurn) -> CGPoint {
        let w = Double(width)
        let h = Double(height)
        switch turn {
        case .none: return point
        case .cw90: return CGPoint(x: h - point.y, y: point.x)
        case .cw180: return CGPoint(x: w - point.x, y: h - point.y)
        case .cw270: return CGPoint(x: point.y, y: w - point.x)
        }
    }

    // MARK: - Scaling

    /// Output size that puts the longest side at `longSide`, preserving aspect ratio.
    /// Never upscales - an image smaller than the target is transmitted untouched.
    static func downscaledSize(width: Int, height: Int, longSide: Int = maxUploadLongSide) -> (width: Int, height: Int) {
        let longest = max(width, height)
        guard longest > longSide else { return (width, height) }
        let factor = Double(longSide) / Double(longest)
        return (max(1, Int((Double(width) * factor).rounded())),
                max(1, Int((Double(height) * factor).rounded())))
    }

    /// Scale intrinsics to a new frame size.
    ///
    /// Uses the realised per-axis ratios `outW/inW` and `outH/inH` rather than the requested
    /// factor: rounding the output dimensions to integers makes the two axes differ slightly,
    /// and each of fx, fy, cx, cy is linear in the resolution of the axis it belongs to.
    static func scale(_ camera: PinholeCamera, toWidth outWidth: Int, height outHeight: Int) -> PinholeCamera {
        let sx = Double(outWidth) / Double(camera.width)
        let sy = Double(outHeight) / Double(camera.height)
        return PinholeCamera(fx: camera.fx * sx, fy: camera.fy * sy,
                             cx: camera.cx * sx, cy: camera.cy * sy,
                             width: outWidth, height: outHeight)
    }

    /// Scale a pixel coordinate by the same per-axis ratios.
    static func scale(point: CGPoint, from input: (width: Int, height: Int), to output: (width: Int, height: Int)) -> CGPoint {
        CGPoint(x: point.x * Double(output.width) / Double(input.width),
                y: point.y * Double(output.height) / Double(input.height))
    }

    // MARK: - The composed pipeline

    struct Result: Equatable, Sendable {
        var camera: PinholeCamera
        var target: CGPoint
        var turn: QuarterTurn
        var sourceWidth: Int
        var sourceHeight: Int
        /// Realised uniform scale, for provenance. K is already scaled.
        var scale: Double
    }

    /// Apply `rotate` then `downscale` to intrinsics and the tap point together.
    ///
    /// The caller is responsible for pushing the same `turn` and the same output size through
    /// the pixel pipeline - `PixelBufferRenderer` takes this `Result` so the two cannot diverge.
    static func apply(
        camera: PinholeCamera,
        target: CGPoint,
        turn: QuarterTurn,
        longSide: Int = maxUploadLongSide
    ) -> Result {
        let sourceWidth = camera.width
        let sourceHeight = camera.height

        let rotatedCamera = rotate(camera, by: turn)
        let rotatedTarget = rotate(point: target, in: sourceWidth, sourceHeight, by: turn)

        let outSize = downscaledSize(width: rotatedCamera.width, height: rotatedCamera.height, longSide: longSide)
        let finalCamera = scale(rotatedCamera, toWidth: outSize.width, height: outSize.height)
        let finalTarget = scale(point: rotatedTarget,
                                from: (rotatedCamera.width, rotatedCamera.height),
                                to: outSize)

        return Result(
            camera: finalCamera,
            target: clamp(finalTarget, toWidth: outSize.width, height: outSize.height),
            turn: turn,
            sourceWidth: sourceWidth,
            sourceHeight: sourceHeight,
            scale: Double(outSize.width) / Double(rotatedCamera.width)
        )
    }

    /// Keep a tap strictly inside the frame. A tap exactly on the right or bottom edge would
    /// otherwise produce an out-of-range pixel index downstream.
    static func clamp(_ point: CGPoint, toWidth width: Int, height: Int) -> CGPoint {
        CGPoint(x: min(max(point.x, 0), Double(width) - 1),
                y: min(max(point.y, 0), Double(height) - 1))
    }

    /// 0 at the frame centre, 1 at the edge. Section 6.2 of the calibration note uses this to
    /// reject captures where lens distortion makes the pinhole model untenable.
    static func centrality(of point: CGPoint, inWidth width: Int, height: Int) -> Double {
        guard width > 0, height > 0 else { return 1 }
        let dx = abs(point.x / Double(width) - 0.5)
        let dy = abs(point.y / Double(height) - 0.5)
        return min(1, max(dx, dy) / 0.5)
    }

    /// Above this the worker is asked to re-frame with the defect nearer the centre.
    static let centralityWarningThreshold = 0.6
}
