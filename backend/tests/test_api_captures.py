import hashlib
import json
import os
import uuid

from fastapi.testclient import TestClient

from cbm_api import internal
from conftest import SITE, auth, sign_up


def jpeg(width: int, height: int, filler: bytes = b"") -> bytes:
    """A minimal JPEG: start marker, a baseline frame header with the given size, end marker.

    Enough for the API, which reads the frame header and never decodes pixels.
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


def test_the_database_enforces_the_frame_invariant(client, owner):
    token = reporter(client)
    image = jpeg(960, 1280, b"y")
    meta = metadata(image, x=5000.0)  # tap outside the frame
    r = post(client, token, meta, image)
    assert r.status_code == 422 and r.json()["error"] == "INVALID_CAPTURE"
    assert owner.execute("SELECT count(*) FROM cbm_app.reports WHERE id=%s", [meta["report_id"]]).fetchone()[0] == 0
    assert not os.path.exists(os.path.join(os.environ["CBM_APP_CAPTURE_DIR"], f"app-{meta['capture_id']}.jpg"))


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
