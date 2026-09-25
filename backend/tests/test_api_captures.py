import copy
import hashlib
import json
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from io import BytesIO
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from cbm_api import captures, internal, main
from conftest import SITE, auth, sign_up


@lru_cache(maxsize=None)
def _encoded(width: int, height: int) -> bytes:
    buf = BytesIO()
    Image.new("RGB", (width, height), (118, 128, 138)).save(buf, "JPEG", quality=60)
    return buf.getvalue()


def jpeg(width: int, height: int, filler: bytes = b"") -> bytes:
    """A real JPEG of the given size. The API decodes what it accepts, so the fixture must decode.

    The filler travels in a comment segment right after the start marker, which decoders skip: two
    images with different fillers are different bytes (and hashes) but the same picture.
    """
    data = _encoded(width, height)
    comment = b"\xff\xfe" + (2 + len(filler)).to_bytes(2, "big") + filler
    return data[:2] + comment + data[2:]


def header_only_jpeg(width: int, height: int, filler: bytes = b"") -> bytes:
    """Start marker, a baseline frame header with the given size, end marker - and no pixels.

    Its header claims an image and there is none behind it; the API must refuse it.
    """
    sof = (b"\xff\xc0" + (17).to_bytes(2, "big") + b"\x08" + height.to_bytes(2, "big") + width.to_bytes(2, "big")
           + b"\x03" + b"\x01\x11\x00\x02\x11\x00\x03\x11\x00")
    return b"\xff\xd8" + sof + b"\xff\xfe" + (2 + len(filler)).to_bytes(2, "big") + filler + b"\xff\xd9"


def metadata(image: bytes, width=960, height=1280, x=512.0, report_id=None, capture_id=None, **overrides) -> dict:
    meta = {
        "schema_version": "2.0.0",
        "capture_id": capture_id or str(uuid.uuid4()),
        "report_id": report_id or str(uuid.uuid4()),
        "building_id": SITE,
        "description": "Door handle detached",
        "captured_at": "2026-09-19T10:00:00Z",
        "client": {"platform": "ANDROID", "device_model": "Pixel 8"},
        "image": {"width": width, "height": height, "sha256": hashlib.sha256(image).hexdigest()},
        "camera": {"source": "ARCORE", "trusted": True, "fx": 954.6, "fy": 955.1, "cx": 480.2, "cy": 639.6,
                   "width": width, "height": height},
        "target": {"pixel": {"x": x, "y": 700.0}, "source": "USER_TAP", "centrality": 0.1},
    }
    meta.update(overrides)
    return meta


def post(client, token, meta: dict, image: bytes):
    return client.post("/v1/captures", headers=auth(token),
                       data={"metadata": json.dumps(meta)}, files={"image": ("capture.jpg", image, "image/jpeg")})


def reporter(client) -> str:
    _, r = sign_up(client)
    return r.json()["token"]


def test_capture_is_stored_and_the_workflows_are_notified(client, owner):
    token = reporter(client)
    image = jpeg(960, 1280, os.urandom(2000))
    meta = metadata(image)
    owner.execute("LISTEN cbm_app_capture")
    r = post(client, token, meta, image)
    assert r.status_code == 202, r.text
    assert r.json() == {"capture_id": meta["capture_id"], "report_id": meta["report_id"], "status": "STORED"}
    notified = [n.payload for n in owner.notifies(timeout=5.0, stop_after=1)]
    owner.execute("UNLISTEN cbm_app_capture")
    assert notified == [meta["capture_id"]]
    stored = os.path.join(os.environ["CBM_APP_CAPTURE_DIR"], f"app-{meta['capture_id']}.jpg")
    with open(stored, "rb") as f:
        assert f.read() == image
    reports = client.get("/v1/reports", headers=auth(token)).json()["reports"]
    assert [(x["report_id"], x["status"], x["description"]) for x in reports] == [(meta["report_id"], "RECEIVED", "Door handle detached")]
    # WF1 sees it with the shape of its Capture Input node.
    item = owner.execute("SELECT x FROM cbm_app.captures_for_intake(%s) x", [meta["capture_id"]]).fetchone()[0]
    assert item["source"] == "APP" and item["id"] == f"app-{meta['capture_id']}" and item["camera"]["source"] == "ARCORE"


def test_replay_is_harmless_but_a_different_photo_is_a_conflict(client):
    token = reporter(client)
    image = jpeg(960, 1280, b"first")
    meta = metadata(image)
    assert post(client, token, meta, image).status_code == 202
    again = post(client, token, meta, image)
    assert again.status_code == 200 and again.json()["status"] == "STORED"
    other = jpeg(960, 1280, b"second")
    clash = post(client, token, metadata(other, capture_id=meta["capture_id"], report_id=meta["report_id"]), other)
    assert clash.status_code == 409 and clash.json()["error"] == "CONFLICT"


def test_the_bytes_must_be_the_photo_the_metadata_describes(client):
    token = reporter(client)
    image = jpeg(960, 1280, b"x")
    bad_hash = post(client, token, metadata(image) | {"image": {"width": 960, "height": 1280, "sha256": "0" * 64}}, image)
    assert bad_hash.status_code == 422 and bad_hash.json()["error"] == "CHECKSUM_MISMATCH"
    wrong_size = jpeg(1280, 960, b"x")
    r = post(client, token, metadata(wrong_size, width=960, height=1280), wrong_size)
    assert r.status_code == 422 and r.json()["error"] == "FRAME_MISMATCH"
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
    r = post(client, token, metadata(png), png)
    assert r.status_code == 422 and r.json()["error"] == "NOT_A_JPEG"


def test_a_jpeg_header_with_no_image_behind_it_is_refused(client, owner):
    """Audit 2026-09-24, finding 10: a frame header is a claim, not an image."""
    token = reporter(client)
    empty = header_only_jpeg(960, 1280, b"no compressed pixel data")
    meta = metadata(empty)
    r = post(client, token, meta, empty)
    assert r.status_code == 422 and r.json()["error"] == "NOT_A_JPEG", r.text
    # Cut short after the header: truncated pixel data is refused as well.
    whole = jpeg(960, 1280, b"z")
    cut = whole[: len(whole) // 2]
    assert post(client, token, metadata(cut), cut).json()["error"] == "NOT_A_JPEG"
    assert owner.execute("SELECT count(*) FROM cbm_app.report_photos WHERE capture_id=%s", [meta["capture_id"]]).fetchone()[0] == 0


def test_a_description_over_500_characters_is_named(client):
    """Audit 2026-09-24, finding 6: the app is told which field to correct, not "not valid"."""
    token = reporter(client)
    image = jpeg(960, 1280, b"long description")
    r = post(client, token, metadata(image, description="x" * 501), image)
    assert r.status_code == 422 and r.json()["error"] == "DESCRIPTION_TOO_LONG", r.text
    assert "500" in r.json()["message"]
    fixed = metadata(image, description="x" * 500)
    assert post(client, token, fixed, image).status_code == 202


def test_the_database_enforces_the_frame_invariant(client, owner):
    token = reporter(client)
    image = jpeg(960, 1280, b"y")
    meta = metadata(image, x=5000.0)  # tap outside the frame
    r = post(client, token, meta, image)
    assert r.status_code == 422 and r.json()["error"] == "INVALID_CAPTURE"
    assert owner.execute("SELECT count(*) FROM cbm_app.reports WHERE id=%s", [meta["report_id"]]).fetchone()[0] == 0
    assert not os.path.exists(os.path.join(os.environ["CBM_APP_CAPTURE_DIR"], f"app-{meta['capture_id']}.jpg"))


# Second audit 2026-09-24, finding 5: geometry the ray to the damage cannot be cast from.
GEOMETRY_GAPS = {
    "no camera": lambda m: m.update(camera={}),
    "no tap": lambda m: m["target"].update(pixel={}),
    "zero focal length": lambda m: m["camera"].update(fx=0),
    "null camera and tap": lambda m: m.update(camera=None, target={"pixel": None, "source": "USER_TAP"}),
    "focal length as text": lambda m: m["camera"].update(fy="955"),
    "centre outside the image": lambda m: m["camera"].update(cx=-1),
    "no source for K": lambda m: m["camera"].pop("source"),
    "trusted not a boolean": lambda m: m["camera"].update(trusted="yes"),
}


@pytest.mark.parametrize("gap", GEOMETRY_GAPS)
def test_a_capture_without_usable_geometry_is_refused(client, owner, gap):
    token = reporter(client)
    image = jpeg(960, 1280, gap.encode())
    meta = metadata(image)
    GEOMETRY_GAPS[gap](meta)
    r = post(client, token, meta, image)
    assert r.status_code == 422 and r.json()["error"] == "INVALID_CAPTURE", r.text
    assert owner.execute("SELECT count(*) FROM cbm_app.report_photos WHERE capture_id=%s",
                         [meta["capture_id"]]).fetchone()[0] == 0


@pytest.mark.parametrize("gap", GEOMETRY_GAPS)
def test_the_database_refuses_the_same_geometry(client, owner, monkeypatch, gap):
    """The API's check skipped: the table's own constraint refuses what it would have."""
    monkeypatch.setattr(captures, "check_geometry", lambda meta: None)
    token = reporter(client)
    image = jpeg(960, 1280, gap.encode())
    meta = metadata(image)
    GEOMETRY_GAPS[gap](meta)
    r = post(client, token, meta, image)
    assert r.status_code == 422 and r.json()["error"] == "INVALID_CAPTURE", r.text
    assert owner.execute("SELECT count(*) FROM cbm_app.reports WHERE id=%s", [meta["report_id"]]).fetchone()[0] == 0


def test_numbers_json_does_not_have_are_refused(client):
    """NaN and Infinity are read by Python's json but are not JSON: refused, not a 500 from the database."""
    token = reporter(client)
    image = jpeg(960, 1280, b"nan")
    for bad in ('"centrality": NaN', '"centrality": 1e400', '"centrality": -Infinity'):
        text = json.dumps(metadata(image)).replace('"centrality": 0.1', bad)
        r = client.post("/v1/captures", headers=auth(token), data={"metadata": text},
                        files={"image": ("capture.jpg", image, "image/jpeg")})
        assert r.status_code == 422 and r.json()["error"] == "INVALID_REQUEST", (bad, r.text)


def test_overlapping_retries_of_one_photo_are_both_answered(client, owner, monkeypatch):
    """Second audit 2026-09-24, finding 6: the original and its retry write the file at the same moment."""
    token = reporter(client)
    image = jpeg(960, 1280, b"overlap")
    meta = metadata(image)
    both_written = threading.Barrier(2)
    real_fsync = captures.os.fsync

    def fsync_together(fd):
        real_fsync(fd)
        both_written.wait(timeout=10)  # both files are on disk and neither has been renamed yet

    monkeypatch.setattr(captures.os, "fsync", fsync_together)
    http = TestClient(main.app, raise_server_exceptions=False)
    with ThreadPoolExecutor(max_workers=2) as pool:
        codes = list(pool.map(lambda _: post(http, token, copy.deepcopy(meta), image).status_code, range(2)))
    http.close()
    assert codes == [202, 202], codes
    store = Path(os.environ["CBM_APP_CAPTURE_DIR"])
    assert (store / f"app-{meta['capture_id']}.jpg").read_bytes() == image
    assert not list(store.glob(f"app-{meta['capture_id']}*.part")), "no temporary file is left behind"
    assert owner.execute("SELECT status FROM cbm_app.report_photos WHERE capture_id=%s",
                         [meta["capture_id"]]).fetchone()[0] == "STORED"


def test_only_a_reporter_session_can_upload(client):
    _, r = sign_up(client, role="TECHNICIAN")
    image = jpeg(960, 1280)
    denied = post(client, r.json()["token"], metadata(image), image)
    assert denied.status_code == 401
    assert client.post("/v1/captures", data={"metadata": "{}"}, files={"image": ("a.jpg", image, "image/jpeg")}).status_code == 401
    token = reporter(client)
    assert post(client, token, metadata(image, building_id="ELSEWHERE"), image).json()["error"] == "SITE_MISMATCH"
    no_meta = client.post("/v1/captures", headers=auth(token), files={"image": ("a.jpg", image, "image/jpeg")})
    assert no_meta.status_code == 422 and no_meta.json()["error"] == "INVALID_REQUEST"


def test_upload_size_limit(client):
    token = reporter(client)
    big = jpeg(960, 1280, b"\x00" * 60000) * 100  # ~6 MB
    r = post(client, token, metadata(big), big)
    assert r.status_code == 413 and r.json()["error"] == "TOO_LARGE"


def test_internal_service_serves_stored_images_only(client):
    token = reporter(client)
    image = jpeg(960, 1280, b"internal")
    meta = metadata(image)
    assert post(client, token, meta, image).status_code == 202
    with TestClient(internal.app) as svc:
        got = svc.get(f"/internal/captures/{meta['capture_id']}/image")
        assert got.status_code == 200 and got.content == image and got.headers["content-type"] == "image/jpeg"
        assert svc.get(f"/internal/captures/{uuid.uuid4()}/image").status_code == 404
        assert svc.get("/internal/captures/..%2Fetc%2Fpasswd/image").status_code == 404
    # The public API has no such route.
    assert client.get(f"/internal/captures/{meta['capture_id']}/image").status_code == 404
