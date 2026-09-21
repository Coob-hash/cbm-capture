"""The capture store: checking an uploaded JPEG and keeping its bytes.

Images live on the API's own volume as app-<capture_id>.jpg. The database holds the metadata and
the storage reference; WF1 fetches the bytes from the internal service (internal.py), which mounts
the same volume read-only.
"""

import hashlib
import os
import re
from pathlib import Path

_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class ImageError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def jpeg_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) from the JPEG frame header, without decoding pixels."""
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


def check_image(data: bytes, metadata: dict) -> None:
    """The bytes must be the JPEG the metadata describes: same SHA-256, same decoded frame size.

    The database separately checks that K, the image size and the tap share one frame; this is the
    half it cannot see: that the size claimed is the size of the bytes actually received.
    """
    image = metadata.get("image") if isinstance(metadata.get("image"), dict) else {}
    size = jpeg_size(data)
    if size is None:
        raise ImageError("NOT_A_JPEG", "The image is not a JPEG.")
    if hashlib.sha256(data).hexdigest() != str(image.get("sha256", "")).lower():
        raise ImageError("CHECKSUM_MISMATCH", "The image does not match its SHA-256.")
    if size != (image.get("width"), image.get("height")):
        raise ImageError("FRAME_MISMATCH", f"The image is {size[0]}x{size[1]}, not the size in its metadata.")


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
    """Write atomically: a crash leaves either no file or the whole file, never a partial image."""
    target = target or path_for(capture_dir, capture_id)
    if target is None:
        raise ImageError("INVALID", "Invalid capture id.")
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".part")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, target)
