import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Turns an ARKit `CVPixelBuffer` into the exact JPEG that will be transmitted.
///
/// This type performs the pixel half of the transform that `ImageTransform` performs on K.
/// It intentionally accepts an `ImageTransform.Result` rather than loose parameters, so the
/// rotation and output size applied to the pixels are provably the same ones applied to the
/// intrinsics and to the worker's tap.
/// `@unchecked` because of `ciContext`: `CIContext` is documented by Apple as thread-safe -
/// multiple threads may share one and render through it concurrently - but it is not annotated
/// `Sendable`, so the compiler cannot see that. The alternative, building a context per frame,
/// is the well-known way to make an AR app stutter.
struct PixelBufferRenderer: @unchecked Sendable {

    enum RenderError: Error, LocalizedError {
        case cannotCreateImage
        case cannotCreateContext
        case cannotEncodeJPEG
        case dimensionMismatch(expected: String, actual: String)

        var errorDescription: String? {
            switch self {
            case .cannotCreateImage: "The camera frame could not be converted to an image."
            case .cannotCreateContext: "The image could not be prepared for upload."
            case .cannotEncodeJPEG: "The image could not be encoded as JPEG."
            case let .dimensionMismatch(expected, actual):
                "Internal error: rendered \(actual) but the calibration describes \(expected)."
            }
        }
    }

    /// A single shared CIContext. Creating one per frame is a well-known way to make an AR app
    /// stutter, since each carries its own Metal pipeline state.
    private let ciContext: CIContext

    init(ciContext: CIContext = CIContext(options: [.useSoftwareRenderer: false])) {
        self.ciContext = ciContext
    }

    /// Render, rotate, downscale, and JPEG-encode in one pass.
    ///
    /// The returned image is guaranteed to measure `transform.camera.width` x
    /// `transform.camera.height`; the check is not defensive padding, it is the invariant that
    /// makes the transmitted K meaningful, so it is enforced rather than assumed.
    func encodeJPEG(
        from pixelBuffer: CVPixelBuffer,
        applying transform: ImageTransform.Result,
        quality: CGFloat = 0.85
    ) throws -> Data {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let sourceImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else {
            throw RenderError.cannotCreateImage
        }

        let outWidth = transform.camera.width
        let outHeight = transform.camera.height

        let rotatedAndScaled = try rotateAndScale(
            sourceImage,
            turn: transform.turn,
            outWidth: outWidth,
            outHeight: outHeight
        )

        guard rotatedAndScaled.width == outWidth, rotatedAndScaled.height == outHeight else {
            throw RenderError.dimensionMismatch(
                expected: "\(outWidth)x\(outHeight)",
                actual: "\(rotatedAndScaled.width)x\(rotatedAndScaled.height)"
            )
        }

        return try encode(rotatedAndScaled, quality: quality)
    }

    // MARK: - Geometry

    /// Rotation and resampling in a single draw.
    ///
    /// Core Graphics bitmap contexts are y-up, and `CGContext.draw(_:in:)` renders an image
    /// upright in that space, so a positive rotation angle turns the drawn content
    /// counter-clockwise as seen. Clockwise quarter-turns therefore use a negative angle.
    private func rotateAndScale(
        _ image: CGImage,
        turn: QuarterTurn,
        outWidth: Int,
        outHeight: Int
    ) throws -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: outWidth,
            height: outHeight,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            throw RenderError.cannotCreateContext
        }

        context.interpolationQuality = .high

        // After rotating the drawing space, the source occupies the pre-rotation footprint:
        // a quarter-turn swaps which output dimension each source axis maps onto.
        let swapsAxes = (turn == .cw90 || turn == .cw270)
        let drawWidth = CGFloat(swapsAxes ? outHeight : outWidth)
        let drawHeight = CGFloat(swapsAxes ? outWidth : outHeight)

        context.translateBy(x: CGFloat(outWidth) / 2, y: CGFloat(outHeight) / 2)
        context.rotate(by: -CGFloat(turn.rawValue) * .pi / 2)
        context.draw(image, in: CGRect(x: -drawWidth / 2, y: -drawHeight / 2,
                                       width: drawWidth, height: drawHeight))

        guard let output = context.makeImage() else { throw RenderError.cannotCreateContext }
        return output
    }

    // MARK: - Encoding

    /// Encode with an explicit orientation of 1 (normal).
    ///
    /// The rotation is already baked into the pixels; writing a non-trivial orientation tag on
    /// top of that would rotate the image a second time in any consumer that honours the tag,
    /// while leaving K describing the un-rotated frame.
    private func encode(_ image: CGImage, quality: CGFloat) throws -> Data {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data, UTType.jpeg.identifier as CFString, 1, nil
        ) else {
            throw RenderError.cannotEncodeJPEG
        }

        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality,
            kCGImagePropertyOrientation: CGImagePropertyOrientation.up.rawValue
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)

        guard CGImageDestinationFinalize(destination) else {
            throw RenderError.cannotEncodeJPEG
        }
        return data as Data
    }
}
