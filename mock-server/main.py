"""
Local stand-in for the n8n `/cbm/capture` webhook.

Purpose: let both apps be exercised end to end - including the offline queue, the retry path,
and every rejection branch - without an n8n instance, a Postgres, or a MultiSet account.

It enforces exactly the checks the real endpoint must enforce, and no more:

  1. the bearer token matches
  2. the metadata parses and the schema version is one this server understands
  3. the transmitted bytes hash to `image.sha256`
  4. the decoded JPEG really measures `image.width` x `image.height`
  5. `camera.width`/`camera.height` equal those same dimensions
  6. `target.pixel` lies inside them
  7. `camera.trusted` is true - otherwise the request is recorded as NEEDS_LOCALIZATION

Check 4 is the one worth dwelling on: it is the only check that catches an app which computed
K correctly but wrote out a differently sized image, and it is cheap. The real n8n workflow
should do the same before anything reaches MultiSet.

    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8099

Point the app's Settings screen at http://<your-machine-ip>:8099 with token `dev-token`.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Form, Header, UploadFile, File
from fastapi.responses import JSONResponse, HTMLResponse
from PIL import Image

TOKEN = os.environ.get("CBM_MOCK_TOKEN", "dev-token")
BUILDING_ID = os.environ.get("CBM_BUILDING_ID", "ROOM-POC")
SCHEMA_VERSION = "1.0.0"
MAX_IMAGE_BYTES = 12 * 1024 * 1024

STORE = Path(__file__).parent / "received"
STORE.mkdir(exist_ok=True)

app = FastAPI(title="CBM Capture mock intake")

# capture_id -> stored record. In-memory, because the point is to test the client.
_received: dict[str, dict[str, Any]] = {}


def _error(status: int, code: str, detail: str | None = None, capture_id: str | None = None):
    body: dict[str, Any] = {"ok": False, "error": code}
    if detail:
        body["detail"] = detail
    if capture_id:
        body["capture_id"] = capture_id
    return JSONResponse(status_code=status, content=body)


def _authorised(authorization: str | None) -> bool:
    return authorization == f"Bearer {TOKEN}"


@app.get("/cbm/capture/health")
def health(authorization: str | None = Header(default=None)):
    if not _authorised(authorization):
        return _error(401, "UNAUTHORIZED")
    return {"ok": True, "building_id": BUILDING_ID, "schema_version": SCHEMA_VERSION}


@app.post("/cbm/capture")
async def submit_capture(
    metadata: str = Form(...),
    image: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    if not _authorised(authorization):
        return _error(401, "UNAUTHORIZED")

    try:
        meta = json.loads(metadata)
    except json.JSONDecodeError as exc:
        return _error(400, "MALFORMED_METADATA", str(exc))

    capture_id = meta.get("capture_id")
    if not capture_id:
        return _error(400, "MALFORMED_METADATA", "capture_id is required")

    # Idempotency. A client that never got our response will send the same package again, and
    # creating a second maintenance request for it would put the same defect on two work orders.
    if capture_id in _received:
        prior = _received[capture_id]
        return {
            "ok": True,
            "capture_id": capture_id,
            "request_id": prior["request_id"],
            "status": prior["status"],
            "duplicate": True,
        }

    if meta.get("schema_version") != SCHEMA_VERSION:
        return _error(422, "UNSUPPORTED_SCHEMA_VERSION",
                      f"server speaks {SCHEMA_VERSION}", capture_id)

    payload = await image.read()
    if len(payload) > MAX_IMAGE_BYTES:
        return _error(413, "IMAGE_TOO_LARGE", f"{len(payload)} bytes", capture_id)

    image_meta = meta.get("image", {})
    camera = meta.get("camera", {})
    target = (meta.get("target") or {}).get("pixel", {})

    digest = hashlib.sha256(payload).hexdigest()
    if digest != image_meta.get("sha256"):
        return _error(400, "CHECKSUM_MISMATCH",
                      f"declared {image_meta.get('sha256')}, computed {digest}", capture_id)

    try:
        decoded = Image.open(io.BytesIO(payload))
        decoded.load()
    except Exception as exc:
        return _error(400, "MALFORMED_METADATA", f"unreadable image: {exc}", capture_id)

    actual_w, actual_h = decoded.size

    # The whole reason this endpoint exists: K, the pixels, and the tap must describe one frame.
    problems = []
    if (actual_w, actual_h) != (image_meta.get("width"), image_meta.get("height")):
        problems.append(
            f"image is {actual_w}x{actual_h} but metadata declares "
            f"{image_meta.get('width')}x{image_meta.get('height')}"
        )
    if (camera.get("width"), camera.get("height")) != (actual_w, actual_h):
        problems.append(
            f"K describes {camera.get('width')}x{camera.get('height')} "
            f"but the image is {actual_w}x{actual_h}"
        )
    tx, ty = target.get("x"), target.get("y")
    if tx is None or ty is None or not (0 <= tx < actual_w and 0 <= ty < actual_h):
        problems.append(f"target pixel ({tx}, {ty}) is outside the image")

    if problems:
        return _error(422, "FRAME_MISMATCH", "; ".join(problems), capture_id)

    request_id = str(uuid.uuid4())
    # Untrusted intrinsics are still recorded - they are a real defect report - but they are
    # routed for manual placement instead of being unprojected with a K nobody believes.
    status = "RECEIVED" if camera.get("trusted") else "NEEDS_LOCALIZATION"

    (STORE / f"{capture_id}.jpg").write_bytes(payload)
    (STORE / f"{capture_id}.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    _received[capture_id] = {
        "request_id": request_id,
        "status": status,
        "received_at": datetime.now(timezone.utc).isoformat(),
        "metadata": meta,
    }

    print(
        f"[{status:19s}] {capture_id}  {actual_w}x{actual_h}  "
        f"K=({camera.get('fx'):.1f}, {camera.get('fy'):.1f}, {camera.get('cx'):.1f}, {camera.get('cy'):.1f})  "
        f"src={camera.get('source')}  target=({tx:.0f}, {ty:.0f})  "
        f"centrality={(meta.get('target') or {}).get('centrality')}"
    )

    if status == "NEEDS_LOCALIZATION":
        # Deliberately a 422, so the client's permanent-failure path gets exercised and the
        # worker is told the office will place this one by hand.
        return _error(422, "UNTRUSTED_INTRINSICS",
                      "intrinsics were not trusted; routed for manual localization", capture_id)

    return {
        "ok": True,
        "capture_id": capture_id,
        "request_id": request_id,
        "status": status,
        "duplicate": False,
    }


@app.get("/", response_class=HTMLResponse)
def index():
    """A bare listing, so you can see what arrived without leaving the browser."""
    rows = "".join(
        f"<tr><td>{cid[:8]}</td><td>{rec['status']}</td>"
        f"<td>{rec['metadata']['camera']['source']}</td>"
        f"<td>{rec['metadata']['image']['width']}x{rec['metadata']['image']['height']}</td>"
        f"<td>{rec['received_at']}</td>"
        f"<td>{(rec['metadata'].get('description') or '')[:60]}</td></tr>"
        for cid, rec in reversed(list(_received.items()))
    )
    return f"""
    <html><head><title>CBM Capture mock intake</title>
    <style>body{{font-family:system-ui;margin:2rem}}td,th{{padding:.4rem .8rem;border-bottom:1px solid #ddd;text-align:left}}</style>
    </head><body>
    <h1>CBM Capture mock intake</h1>
    <p>{len(_received)} package(s) received. Files are written to <code>{STORE}</code>.</p>
    <table><tr><th>capture</th><th>status</th><th>K source</th><th>size</th><th>received</th><th>description</th></tr>
    {rows}</table></body></html>
    """
