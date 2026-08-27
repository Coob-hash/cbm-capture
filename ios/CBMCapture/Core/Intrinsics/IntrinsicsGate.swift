import Foundation

/// Plausibility check applied to the *final* transmitted-frame intrinsics.
///
/// Implements section 3 of `docs/INTRINSICS.md`, which in turn mirrors the gate proposed in
/// section 5.1 of the calibration note. The contract of this type is narrow and important:
/// it decides whether K is trustworthy, and it never repairs or substitutes a value. A
/// silently substituted fallback is what produces confidently wrong GlobalIds.
enum IntrinsicsGate {

    enum Rejection: String, Sendable, Equatable {
        case focalOutOfRange = "FOCAL_OUT_OF_RANGE"
        case principalPointOffCentre = "PRINCIPAL_POINT_OFF_CENTRE"
        case nonSquarePixels = "NON_SQUARE_PIXELS"
        case degenerateFrame = "DEGENERATE_FRAME"
        case nonFinite = "NON_FINITE"

        var explanation: String {
            switch self {
            case .focalOutOfRange:
                "The focal length is outside the range any phone lens produces for this image size."
            case .principalPointOffCentre:
                "The principal point is far from the image centre, which usually means the calibration describes a different frame than the photo."
            case .nonSquarePixels:
                "Horizontal and vertical focal lengths disagree by more than 5%."
            case .degenerateFrame:
                "The image dimensions are invalid."
            case .nonFinite:
                "The calibration contains a non-finite value."
            }
        }
    }

    struct Verdict: Sendable, Equatable {
        var trusted: Bool
        var rejections: [Rejection]

        static let trusted = Verdict(trusted: true, rejections: [])
    }

    // Bounds. `fx` between 0.5W and 2.5W spans roughly a 23-90 degree horizontal field of view,
    // which brackets every phone lens from ultra-wide to 3x telephoto with margin.
    static let minFocalRatio = 0.5
    static let maxFocalRatio = 2.5
    static let minPrincipalRatio = 0.30
    static let maxPrincipalRatio = 0.70
    static let maxAspectDisagreement = 0.05

    static func evaluate(_ camera: PinholeCamera) -> Verdict {
        var rejections: [Rejection] = []

        guard camera.width > 0, camera.height > 0 else {
            return Verdict(trusted: false, rejections: [.degenerateFrame])
        }

        let values = [camera.fx, camera.fy, camera.cx, camera.cy]
        guard values.allSatisfy({ $0.isFinite }) else {
            return Verdict(trusted: false, rejections: [.nonFinite])
        }

        let w = Double(camera.width)
        let h = Double(camera.height)

        if camera.fx < minFocalRatio * w || camera.fx > maxFocalRatio * w
            || camera.fy < minFocalRatio * h || camera.fy > maxFocalRatio * h {
            rejections.append(.focalOutOfRange)
        }

        if camera.cx < minPrincipalRatio * w || camera.cx > maxPrincipalRatio * w
            || camera.cy < minPrincipalRatio * h || camera.cy > maxPrincipalRatio * h {
            rejections.append(.principalPointOffCentre)
        }

        let largest = max(camera.fx, camera.fy)
        if largest > 0, abs(camera.fx - camera.fy) / largest > maxAspectDisagreement {
            rejections.append(.nonSquarePixels)
        }

        return Verdict(trusted: rejections.isEmpty, rejections: rejections)
    }

    /// Assemble the wire type, stamping the verdict onto it.
    ///
    /// `MANUAL_OVERRIDE` is forced untrusted regardless of the numbers: a hand-entered K is a
    /// debugging aid, and letting it look authoritative defeats the audit trail.
    static func intrinsics(
        from camera: PinholeCamera,
        source: IntrinsicsSource,
        skew: Double = 0,
        lens: String? = nil,
        distortion: [Double]? = nil
    ) -> CameraIntrinsics {
        let verdict = evaluate(camera)
        return CameraIntrinsics(
            source: source,
            trusted: verdict.trusted && source != .manualOverride,
            fx: camera.fx,
            fy: camera.fy,
            cx: camera.cx,
            cy: camera.cy,
            skew: skew,
            width: camera.width,
            height: camera.height,
            lens: lens,
            distortion: distortion
        )
    }

    /// EXIF fallback, section 4 of the calibration note: `fx = f35 * W / 36`, square pixels,
    /// centred principal point. 36 mm is the full-frame sensor width the 35 mm-equivalent
    /// focal length is defined against.
    static func fromEXIF(focalLengthIn35mmFilm f35: Double, width: Int, height: Int) -> PinholeCamera? {
        guard f35 > 0, width > 0, height > 0 else { return nil }
        let fx = f35 * Double(width) / 36.0
        return PinholeCamera(fx: fx, fy: fx,
                             cx: Double(width) / 2.0, cy: Double(height) / 2.0,
                             width: width, height: height)
    }
}
