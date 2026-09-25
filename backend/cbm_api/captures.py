"""The capture store: checking an uploaded JPEG and keeping its bytes.

Images live on the API's own volume as app-<capture_id>.jpg. The database holds the metadata and
the storage reference; WF1 fetches the bytes from the internal service (internal.py), which mounts
the same volume read-only.
"""

import hashlib
import math
import os
import re
import uuid
from io import BytesIO
from pathlib import Path

from PIL import Image

_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

# A reporter's capture is at most 1280 px on its long side; a technician's AFTER photo may be the
# camera's full frame. 40 megapixels bounds the decode's memory (about 120 MB) well above either.
MAX_PIXELS = 40_000_000


class ImageError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def jpeg_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) from the JPEG frame header, without decoding pixels. A claim, not proof:
    decoded_jpeg_size() is what accepts an image."""
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return None
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xFF:
            i += 1
            continue
        if marker == 0x01 or 0xD0 <= marker <= 0xD9:
            i += 2
            continue
        length = int.from_bytes(data[i + 2:i + 4], "big")
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = int.from_bytes(data[i + 5:i + 7], "big")
            width = int.from_bytes(data[i + 7:i + 9], "big")
            return width, height
        i += 2 + length
    return None


def decoded_jpeg_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) of a JPEG that actually decodes, or None.

    The frame header says what size an image claims to be; it does not say there are pixels after
    it. A start marker, a frame header and an end marker pass jpeg_size() and would reach the
    workflows as an image nobody can open. So the pixels are decoded - after the header has shown
    the size is within MAX_PIXELS, so a small file cannot claim a huge frame and exhaust memory.
    """
    size = jpeg_size(data)
    if size is None:
        return None
    width, height = size
    if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
        return None
    try:
        # MPO: the multi-picture JPEG some phone cameras write; its first image is a plain JPEG.
        with Image.open(BytesIO(data), formats=["JPEG", "MPO"]) as im:
            if im.size != (width, height):
                return None
            im.load()  # decodes every scan; truncated or missing pixel data raises
    except Exception:  # noqa: BLE001 - any decoder complaint means "not a usable JPEG"
        return None
    return width, height


def check_image(data: bytes, metadata: dict) -> None:
    """The bytes must be the JPEG the metadata describes: same SHA-256, same decoded frame size.

    The database separately checks that K, the image size and the tap share one frame; this is the
    half it cannot see: that the size claimed is the size of the bytes actually received, and that
    those bytes are an image.
    """
    image = metadata.get("image") if isinstance(metadata.get("image"), dict) else {}
    size = decoded_jpeg_size(data)
    if size is None:
        raise ImageError("NOT_A_JPEG", "The image could not be read as a JPEG.")
    if hashlib.sha256(data).hexdigest() != str(image.get("sha256", "")).lower():
        raise ImageError("CHECKSUM_MISMATCH", "The image does not match its SHA-256.")
    if size != (image.get("width"), image.get("height")):
        raise ImageError("FRAME_MISMATCH", f"The image is {size[0]}x{size[1]}, not the size in its metadata.")


# Where K came from (capture-metadata.schema.json, camera.source).
K_SOURCES = {"ARKIT", "ARCORE", "ANDROID_CAMERA2", "EXIF", "MANUAL_OVERRIDE"}


def _finite(value) -> float | None:
    """A finite JSON number, or None. A JSON boolean is not a number here, and Python's json module
    reads NaN and Infinity, which no camera produces."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except OverflowError:
        return None
    return number if math.isfinite(number) else None


def check_geometry(metadata: dict) -> None:
    """The camera and the tap must be complete and usable: the ray to the damage is cast from them.

    K with positive focal lengths and its principal point inside the frame, K's frame the image's
    size, the tap inside the frame, and where K came from. Call after check_image(), which has
    established the image's size. A missing member or a JSON null is refused here with a message;
    the database refuses the same captures (report_photos_geometry), so nothing reaches the
    workflows without them.
    """
    image = metadata.get("image") if isinstance(metadata.get("image"), dict) else {}
    width, height = image.get("width"), image.get("height")
    camera = metadata.get("camera")
    target = metadata.get("target")
    pixel = target.get("pixel") if isinstance(target, dict) else None
    if not isinstance(camera, dict) or not camera or not isinstance(pixel, dict) or not pixel:
        raise ImageError("INVALID_CAPTURE", "The capture has no camera data or no marked point.")
    if camera.get("source") not in K_SOURCES or not isinstance(camera.get("trusted"), bool):
        raise ImageError("INVALID_CAPTURE", "The camera data does not say where it came from.")
    fx, fy, cx, cy = (_finite(camera.get(k)) for k in ("fx", "fy", "cx", "cy"))
    if fx is None or fy is None or cx is None or cy is None or fx <= 0 or fy <= 0:
        raise ImageError("INVALID_CAPTURE", "The camera data is incomplete: its focal lengths must be positive numbers.")
    if _finite(camera.get("width")) != width or _finite(camera.get("height")) != height:
        raise ImageError("INVALID_CAPTURE", "The camera data describes another image size.")
    if not (0 <= cx <= width and 0 <= cy <= height):
        raise ImageError("INVALID_CAPTURE", "The camera's centre is outside the image.")
    x, y = _finite(pixel.get("x")), _finite(pixel.get("y"))
    if x is None or y is None or not (0 <= x < width and 0 <= y < height):
        raise ImageError("INVALID_CAPTURE", "The marked point is not inside the image.")


def path_for(capture_dir: str, capture_id: str) -> Path | None:
    if not _UUID.match(capture_id or ""):
        return None
    return Path(capture_dir) / f"app-{capture_id}.jpg"


def report_path_for(capture_dir: str, report_id: str) -> Path | None:
    """The AFTER photo of a technician's report, in the same store as the reporters' photos."""
    if not _UUID.match(report_id or ""):
        return None
    return Path(capture_dir) / f"report-{report_id}.jpg"


def store(capture_dir: str, capture_id: str, data: bytes, target: Path | None = None) -> None:
    """Write atomically: a crash leaves either no file or the whole file, never a partial image.

    Every write has a temporary file of its own. Two requests storing the same capture at once - a
    retry overlapping the original - used to share one '.part' path, so the first rename took the
    file from under the second, which failed with a 500. Both write the bytes the database holds the
    hash of, so whichever rename lands last leaves the same, whole image.
    """
    target = target or path_for(capture_dir, capture_id)
    if target is None:
        raise ImageError("INVALID", "Invalid capture id.")
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f"{target.stem}.{uuid.uuid4().hex}.part")
    try:
        with open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, target)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
