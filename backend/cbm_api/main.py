"""CBM App API.

The phone talks only to this service. It owns accounts, sessions and the app's records in schema
cbm_app; the n8n workflows work on top of the same database and never receive calls from the phone.

Every rule that protects data (password hashing, lockout, the one-hour session, roles, ownership)
lives in the database functions. This layer adds what a database cannot: HTTP, Google token
verification, request-size and per-address rate limits, and never echoing a request body.
"""

import json
import threading
import time
from collections import deque
from contextlib import asynccontextmanager
from typing import Annotated, Literal
from uuid import UUID

import psycopg
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import captures, config, google_id
from .db import Database
from .errors import check, fail

settings = config.load()
db = Database(settings.database_url)


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.open()
    yield
    db.close()


app = FastAPI(title="CBM App API", version="1.0.0", lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url="/v1/openapi.json")


# ---- Request models. Sizes are capped here; content rules are the database's. -----------------

class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Device(Strict):
    id: UUID
    platform: Literal["ANDROID", "IOS"]
    model: str | None = Field(default=None, max_length=100)
    os_version: str | None = Field(default=None, max_length=50)
    app_version: str | None = Field(default=None, max_length=50)


Role = Literal["USER", "TECHNICIAN", "FM"]


class SignUp(Strict):
    site_code: str = Field(max_length=64)
    role: Role
    email: str = Field(max_length=254)
    password: str = Field(max_length=128)
    display_name: str | None = Field(default=None, max_length=150)
    device: Device


class GoogleSignUp(Strict):
    site_code: str = Field(max_length=64)
    role: Role
    id_token: str = Field(max_length=4096)
    device: Device


class Login(Strict):
    email: str = Field(max_length=254)
    password: str = Field(max_length=128)
    device: Device


class GoogleLogin(Strict):
    id_token: str = Field(max_length=4096)
    device: Device


class SelectRole(Strict):
    membership_id: UUID


# ---- Cross-cutting guards ----------------------------------------------------------------------

@app.exception_handler(StarletteHTTPException)
async def http_error(_: Request, exc: StarletteHTTPException):
    body = exc.detail if isinstance(exc.detail, dict) else {"error": "HTTP_ERROR", "message": str(exc.detail)}
    return JSONResponse(status_code=exc.status_code, content=body, headers=exc.headers)


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError):
    # Pydantic's default body echoes the submitted input, which here can be a password.
    fields = sorted({".".join(str(p) for p in e["loc"][1:]) or "body" for e in exc.errors()})
    return JSONResponse(status_code=422, content={"error": "INVALID_REQUEST",
                                                  "message": "Invalid or missing fields.", "fields": fields})


@app.middleware("http")
async def limit_body(request: Request, call_next):
    length = request.headers.get("content-length")
    limit = settings.max_capture_bytes + 128 * 1024 if request.url.path == "/v1/captures" else settings.max_body_bytes
    if length is not None and (not length.isdigit() or int(length) > limit):
        return JSONResponse(status_code=413, content={"error": "TOO_LARGE", "message": "Request too large."})
    if length is None and request.method in ("POST", "PUT", "PATCH"):
        return JSONResponse(status_code=411, content={"error": "LENGTH_REQUIRED", "message": "Content-Length required."})
    return await call_next(request)


class _RateLimiter:
    """Sliding one-minute window per client address, for the unauthenticated endpoints.

    The per-account lockout in the database stops guessing one password; this stops one address
    from trying many accounts. In memory, so it is per API process — enough for one container.
    """

    def __init__(self, per_minute: int):
        self.per_minute = per_minute
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            q = self._hits.setdefault(key, deque())
            while q and now - q[0] > 60:
                q.popleft()
            if len(q) >= self.per_minute:
                return False
            q.append(now)
            if len(self._hits) > 10_000:  # forget idle addresses
                for k in [k for k, v in self._hits.items() if not v]:
                    del self._hits[k]
            return True


limiter = _RateLimiter(settings.auth_requests_per_minute)


def client_address(request: Request) -> str:
    if settings.trust_proxy:
        # The last entry is the one ngrok added from the connection it accepted. Earlier entries
        # come from the client and can be forged, so they must not key the rate limit.
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            return forwarded.split(",")[-1].strip()
    return request.client.host if request.client else "unknown"


def rate_limited(request: Request) -> None:
    if not limiter.allow(client_address(request)):
        fail("RATE_LIMITED", http=429, message="Too many attempts from this address. Wait a minute.",
             headers={"Retry-After": "60"})


def bearer(authorization: Annotated[str | None, Header()] = None) -> str:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or len(token) != 64:
        fail("UNAUTHENTICATED")
    return token


def google_claims(token: str) -> dict:
    if not settings.google_client_ids:
        fail("GOOGLE_NOT_CONFIGURED", http=503, message="Google sign-in is not configured on this server.")
    try:
        return google_id.verify(token, settings.google_client_ids)
    except google_id.GoogleTokenError:
        fail("INVALID_GOOGLE_IDENTITY")


def session_body(result: dict) -> dict:
    return {k: result[k] for k in ("token", "expires_at", "new_device", "membership_id", "memberships", "user")}


# ---- Endpoints ---------------------------------------------------------------------------------

@app.get("/healthz")
def healthz():
    return {"ok": db.ping()}


@app.post("/v1/auth/signup", status_code=201, dependencies=[Depends(rate_limited)])
def sign_up(body: SignUp):
    return session_body(check(db.call("sign_up", body.model_dump(mode="json"))))


@app.post("/v1/auth/signup/google", status_code=201, dependencies=[Depends(rate_limited)])
def sign_up_google(body: GoogleSignUp):
    claims = google_claims(body.id_token)
    payload = {"site_code": body.site_code, "role": body.role, "google": claims,
               "device": body.device.model_dump(mode="json")}
    return session_body(check(db.call("sign_up", payload)))


@app.post("/v1/auth/login", dependencies=[Depends(rate_limited)])
def login(body: Login):
    return session_body(check(db.call("login", body.model_dump(mode="json"))))


@app.post("/v1/auth/login/google", dependencies=[Depends(rate_limited)])
def login_google(body: GoogleLogin):
    claims = google_claims(body.id_token)
    return session_body(check(db.call("login", {"google": claims, "device": body.device.model_dump(mode="json")})))


@app.post("/v1/auth/role")
def select_role(body: SelectRole, token: Annotated[str, Depends(bearer)]):
    result = check(db.call("select_membership", token, body.membership_id))
    return {"membership_id": result["membership_id"]}


@app.get("/v1/me")
def me(token: Annotated[str, Depends(bearer)]):
    result = check(db.call("me", token))
    return {k: result[k] for k in ("expires_at", "user", "membership", "memberships")}


@app.post("/v1/auth/logout", status_code=204)
def logout(token: Annotated[str, Depends(bearer)]):
    db.call("logout", token)


@app.post("/v1/captures", status_code=202)
def submit_capture(token: Annotated[str, Depends(bearer)],
                   metadata: Annotated[str, Form(max_length=64 * 1024)],
                   image: Annotated[UploadFile, File()]):
    """A reporter's photo: the capture metadata (contract/capture-metadata.schema.json) plus the JPEG.

    Checks the bytes against the metadata, records the capture, stores the image, then marks it
    STORED, which notifies the workflows. Safe to repeat with the same capture_id.
    """
    try:
        meta = json.loads(metadata)
    except ValueError:
        fail("INVALID_REQUEST", http=422, message="metadata is not valid JSON.")
    if not isinstance(meta, dict):
        fail("INVALID_REQUEST", http=422, message="metadata must be a JSON object.")
    data = image.file.read(settings.max_capture_bytes + 1)
    if len(data) > settings.max_capture_bytes:
        fail("TOO_LARGE", http=413, message="The image is too large.")
    try:
        captures.check_image(data, meta)
    except captures.ImageError as exc:
        fail(exc.code, http=422, message=str(exc))
    try:
        claim = db.call("claim_capture", token, meta)
    except psycopg.errors.InvalidParameterValue:
        fail("INVALID_CAPTURE", http=422,
             message="The capture metadata is inconsistent: frame size, target pixel or a required field.")
    if claim and claim.get("status") == "DUPLICATE":
        return JSONResponse(status_code=200, content={"capture_id": claim["capture_id"], "report_id": claim["report_id"],
                                                      "status": claim["photo_status"]})
    claim = check(claim, ok="UPLOAD")
    captures.store(settings.capture_dir, claim["capture_id"], data)
    stored = check(db.call("store_capture", token, {"capture_id": claim["capture_id"], "image_bytes": len(data)}), ok="STORED")
    return {"capture_id": stored["capture_id"], "report_id": stored["report_id"], "status": "STORED"}


@app.get("/v1/reports")
def my_reports(token: Annotated[str, Depends(bearer)]):
    return {"reports": check(db.call("reporter_reports", token, 50))["reports"]}
