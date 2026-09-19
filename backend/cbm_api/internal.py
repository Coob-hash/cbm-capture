"""Internal image service, for the workflows only.

Runs in its own container on the Docker network with no published port, so a phone can never
reach it; that network isolation is its access control. It serves the capture store read-only.
"""

import os

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse

from .captures import path_for

CAPTURE_DIR = os.environ.get("CBM_APP_CAPTURE_DIR", "/data/captures")

app = FastAPI(title="CBM App internal", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/healthz")
def healthz():
    return {"ok": os.path.isdir(CAPTURE_DIR)}


@app.get("/internal/captures/{capture_id}/image")
def capture_image(capture_id: str):
    path = path_for(CAPTURE_DIR, capture_id)
    if path is None or not path.is_file():
        return JSONResponse(status_code=404, content={"error": "NOT_FOUND", "message": "No stored image."})
    return FileResponse(path, media_type="image/jpeg", filename=path.name)
