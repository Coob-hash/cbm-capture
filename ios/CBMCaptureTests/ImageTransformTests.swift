import CoreGraphics
import Testing
@testable import CBMCapture

/// The normalised camera ray a pixel unprojects to under a given K.
///
/// This is the quantity the whole pipeline exists to compute correctly: `/elements/resolve-ray`
/// builds its ray from exactly this, so it is what the tests assert on. Checking `fx` and `cx`
/// individually would pass for a transform that scaled the focal length and the principal point
/// inconsistently; checking the ray cannot.
private func ray(pixel: CGPoint, camera: PinholeCamera) -> (x: Double, y: Double) {
    ((pixel.x - camera.cx) / camera.fx, (pixel.y - camera.cy) / camera.fy)
}

private let referenceCamera = PinholeCamera(
    fx: 1432.61, fy: 1431.95, cx: 959.48, cy: 719.71, width: 1920, height: 1440
)

@Suite("Intrinsics transform")
struct ImageTransformTests {

    // MARK: - Scaling

    @Test("Downscaling preserves the unprojected ray exactly")
    func scalePreservesRay() {
        let target = CGPoint(x: 1204, y: 388)
        let before = ray(pixel: target, camera: referenceCamera)

        let outSize = ImageTransform.downscaledSize(width: 1920, height: 1440, longSide: 1280)
        let scaled = ImageTransform.scale(referenceCamera, toWidth: outSize.width, height: outSize.height)
        let scaledTarget = ImageTransform.scale(point: target, from: (1920, 1440), to: outSize)
        let after = ray(pixel: scaledTarget, camera: scaled)

        #expect(abs(before.x - after.x) < 1e-9)
        #expect(abs(before.y - after.y) < 1e-9)
    }

    @Test("Downscaling never enlarges an image already below the limit")
    func scaleNeverUpscales() {
        let size = ImageTransform.downscaledSize(width: 640, height: 480, longSide: 1280)
        #expect(size == (640, 480))
    }

    @Test("Both principal-point components scale, each by its own axis")
    func principalPointScalesPerAxis() {
        // A deliberately non-uniform target exercises the per-axis ratios; using one factor for
        // all four parameters is the classic form of this bug.
        let scaled = ImageTransform.scale(referenceCamera, toWidth: 960, height: 480)
        #expect(abs(scaled.cx - referenceCamera.cx * 0.5) < 1e-9)
        #expect(abs(scaled.cy - referenceCamera.cy / 3.0) < 1e-9)
        #expect(abs(scaled.fx - referenceCamera.fx * 0.5) < 1e-9)
        #expect(abs(scaled.fy - referenceCamera.fy / 3.0) < 1e-9)
    }

    // MARK: - Rotation

    @Test("Rotation maps the ray by the matching rotation of the camera frame",
          arguments: [QuarterTurn.none, .cw90, .cw180, .cw270])
    func rotationRotatesRay(turn: QuarterTurn) {
        let target = CGPoint(x: 1204, y: 388)
        let before = ray(pixel: target, camera: referenceCamera)

        let rotated = ImageTransform.rotate(referenceCamera, by: turn)
        let rotatedTarget = ImageTransform.rotate(point: target, in: 1920, 1440, by: turn)
        let after = ray(pixel: rotatedTarget, camera: rotated)

        // Clockwise image rotation sends camera-space (x, y) to (-y, x), applied `turn` times.
        var expected = before
        for _ in 0..<turn.rawValue {
            expected = (-expected.y, expected.x)
        }

        #expect(abs(expected.x - after.x) < 1e-9)
        #expect(abs(expected.y - after.y) < 1e-9)
    }

    @Test("Four quarter-turns return the original intrinsics")
    func fourTurnsIsIdentity() {
        var camera = referenceCamera
        for _ in 0..<4 { camera = ImageTransform.rotate(camera, by: .cw90) }
        #expect(abs(camera.fx - referenceCamera.fx) < 1e-9)
        #expect(abs(camera.fy - referenceCamera.fy) < 1e-9)
        #expect(abs(camera.cx - referenceCamera.cx) < 1e-9)
        #expect(abs(camera.cy - referenceCamera.cy) < 1e-9)
        #expect(camera.width == referenceCamera.width)
        #expect(camera.height == referenceCamera.height)
    }

    @Test("A quarter-turn transposes the frame")
    func quarterTurnTransposes() {
        let rotated = ImageTransform.rotate(referenceCamera, by: .cw90)
        #expect(rotated.width == 1440)
        #expect(rotated.height == 1920)
    }

    @Test("Rotating a corner pixel lands on the matching corner")
    func rotationMapsCorners() {
        // Top-left of a landscape frame becomes top-right after a clockwise quarter-turn.
        let topLeft = ImageTransform.rotate(point: .zero, in: 1920, 1440, by: .cw90)
        #expect(abs(topLeft.x - 1440) < 1e-9)
        #expect(abs(topLeft.y - 0) < 1e-9)
    }

    // MARK: - The composed pipeline

    @Test("The full pipeline keeps K, the frame, and the tap in one coordinate system")
    func pipelineIsSelfConsistent() {
        let result = ImageTransform.apply(
            camera: referenceCamera,
            target: CGPoint(x: 1204, y: 388),
            turn: .cw90
        )

        // Portrait output, longest side capped.
        #expect(result.camera.width == 960)
        #expect(result.camera.height == 1280)
        #expect(result.target.x >= 0 && result.target.x < 960)
        #expect(result.target.y >= 0 && result.target.y < 1280)
        #expect(abs(result.scale - 960.0 / 1440.0) < 1e-9)
    }

    @Test("The tap is clamped inside the frame")
    func tapIsClamped() {
        let result = ImageTransform.apply(
            camera: referenceCamera,
            target: CGPoint(x: 1920, y: 1440),
            turn: .none
        )
        #expect(result.target.x <= Double(result.camera.width) - 1)
        #expect(result.target.y <= Double(result.camera.height) - 1)
    }

    // MARK: - Centrality

    @Test("Centrality is zero at the centre and one at the edge")
    func centralityBounds() {
        let centre = ImageTransform.centrality(of: CGPoint(x: 480, y: 640), inWidth: 960, height: 1280)
        #expect(abs(centre) < 1e-9)

        let edge = ImageTransform.centrality(of: CGPoint(x: 959, y: 640), inWidth: 960, height: 1280)
        #expect(edge > 0.99)
    }
}

@Suite("Plausibility gate")
struct IntrinsicsGateTests {

    @Test("A real ARKit calibration passes")
    func realCalibrationIsTrusted() {
        #expect(IntrinsicsGate.evaluate(referenceCamera).trusted)
    }

    @Test("The mismatched-frame defect is caught")
    func mismatchedFrameIsRejected() {
        // The live defect from section 3 of the calibration note: K describes a 1920x1440
        // frame while the pixels are a stock 4032x3024 photograph.
        let mismatched = PinholeCamera(
            fx: 1432.61, fy: 1431.95, cx: 959.48, cy: 719.71, width: 4032, height: 3024
        )
        let verdict = IntrinsicsGate.evaluate(mismatched)
        #expect(!verdict.trusted)
        #expect(verdict.rejections.contains(.principalPointOffCentre))
        #expect(verdict.rejections.contains(.focalOutOfRange))
    }

    @Test("The hardcoded environment default is rejected for a modern frame")
    func hardcodedDefaultIsRejected() {
        // fx = 1200 against a 4032-wide image is a 118-degree field of view: no phone lens.
        let fabricated = PinholeCamera(fx: 1200, fy: 1200, cx: 960, cy: 540, width: 4032, height: 3024)
        #expect(!IntrinsicsGate.evaluate(fabricated).trusted)
    }

    @Test("Wildly non-square pixels are rejected")
    func nonSquarePixelsRejected() {
        let skewed = PinholeCamera(fx: 1400, fy: 1000, cx: 960, cy: 720, width: 1920, height: 1440)
        #expect(IntrinsicsGate.evaluate(skewed).rejections.contains(.nonSquarePixels))
    }

    @Test("A manual override is never marked trusted, however plausible")
    func manualOverrideIsNeverTrusted() {
        let intrinsics = IntrinsicsGate.intrinsics(from: referenceCamera, source: .manualOverride)
        #expect(!intrinsics.trusted)
    }

    @Test("EXIF derivation follows f35 * W / 36 with a centred principal point")
    func exifDerivation() throws {
        let camera = try #require(IntrinsicsGate.fromEXIF(
            focalLengthIn35mmFilm: 26, width: 4032, height: 3024
        ))
        #expect(abs(camera.fx - 26.0 * 4032.0 / 36.0) < 1e-9)
        #expect(camera.fx == camera.fy)
        #expect(camera.cx == 2016)
        #expect(camera.cy == 1512)
        #expect(IntrinsicsGate.evaluate(camera).trusted)
    }

    @Test("A zero or negative focal length yields no camera at all, rather than a guess")
    func exifRefusesNonsense() {
        #expect(IntrinsicsGate.fromEXIF(focalLengthIn35mmFilm: 0, width: 4032, height: 3024) == nil)
    }
}
