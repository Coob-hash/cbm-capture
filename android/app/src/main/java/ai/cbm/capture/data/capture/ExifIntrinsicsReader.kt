package ai.cbm.capture.data.capture

import ai.cbm.capture.domain.imaging.PinholeCamera
import ai.cbm.capture.domain.intrinsics.IntrinsicsGate
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Last-resort intrinsics, from the JPEG's own EXIF.
 *
 * This is the estimate proposed in section 4 of the calibration note: `fx = f35 * W / 36`, with
 * square pixels and a centred principal point. Good to a few percent, which is enough to hit a
 * door but not a reason to prefer it over a factory calibration.
 *
 * The one place it genuinely shines is lens switching: `FocalLengthIn35mmFilm` describes the
 * lens that actually took the photograph, so a 0.5x or 3x capture is handled with no
 * configuration at all. A single hardcoded `CAMERA_FX` cannot do that even for one phone.
 */
object ExifIntrinsicsReader {

    /** Derive K from the EXIF of an already-encoded JPEG, for its own dimensions. */
    fun read(jpeg: ByteArray, width: Int, height: Int): PinholeCamera? {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return null

        val f35 = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
        if (f35 > 0) {
            return IntrinsicsGate.fromExif(f35, width, height)
        }

        // Some devices omit the 35 mm equivalent but publish the physical focal length. Without
        // a sensor width to pair it with, that number alone cannot produce a focal length in
        // pixels, so there is nothing to salvage - and inventing a sensor size would be exactly
        // the silent fallback this design removes.
        return null
    }
}
