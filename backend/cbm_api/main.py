"""CBM App API.

The phone talks only to this service. It owns accounts, sessions and the app's records in schema
cbm_app; the n8n workflows work on top of the same database and never receive calls from the phone.

Every rule that protects data (password hashing, lockout, the one-hour session, roles, ownership)
lives in the database functions. This layer adds what a database cannot: HTTP, Google token
verification, request-size and per-address rate limits, and never echoing a request body.
"""

import hashlib
import json
import math
import threading
import time
from collections import deque
from contextlib import asynccontextmanager
from datetime import date
from typing import Annotated, Literal
from uuid import UUID

import psycopg
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
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


FmAction = Literal["approve_intervention", "reject_intervention", "approve_completion", "request_rework"]


class Decision(Strict):
    """The FM's decision on one ticket, as the dashboard card offered it.

    approval_id and expected_updated_at come from the card: the workflows refuse the decision if
    the ticket moved on meanwhile, so nobody decides a ticket they are no longer looking at.
    request_id is the phone's own id for this tap, which makes a repeat harmless.
    """
    ticket_id: int = Field(ge=1, le=999_999_999)
    action: FmAction
    reason: str | None = Field(default=None, max_length=2000)
    approval_id: UUID
    expected_updated_at: str = Field(max_length=64)
    request_id: str = Field(max_length=80, pattern=r"^[A-Za-z0-9_:.-]+$")


class OfferResponse(Strict):
    ticket_id: int = Field(ge=1, le=999_999_999)
    offer_id: str = Field(max_length=100)
    decision: Literal["accept", "deny"]


class Skills(Strict):
    skills: list[str] = Field(min_length=1, max_length=10)


CheckResult = Literal["PASSED", "FAILED", "NOT_PERFORMED"]
Outcome = Literal["COMPLETED", "PARTIAL", "NOT_COMPLETED"]


class ReportFields(Strict):
    """The technician's half of the report template. The locked half — ticket, asset, location,
    who they are — is read from the ticket and the account, never taken from the phone."""
    work_date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    findings: str = Field(min_length=1, max_length=3000)
    work_performed: str = Field(min_length=20, max_length=6000)
    materials: str = Field(default="", max_length=2000)
    checks: str = Field(min_length=1, max_length=4000)
    check_result: CheckResult
    outcome: Outcome
    remaining_issues: str = Field(min_length=1, max_length=4000)
    declaration: bool
    photo_caption: str | None = Field(default=None, max_length=500)

    @field_validator("work_date")
    @classmethod
    def _a_day_that_exists(cls, value: str) -> str:
        date.fromisoformat(value)  # the pattern checked the shape; 2026-99-99 is refused here
        return value


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
    large = request.url.path == "/v1/captures" or request.url.path.endswith("/report")
    limit = settings.max_capture_bytes + 128 * 1024 if large else settings.max_body_bytes
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
        meta = json.loads(metadata, parse_constant=_not_json, parse_float=_finite_float)
    except (ValueError, RecursionError):
        fail("INVALID_REQUEST", http=422, message="metadata is not valid JSON.")
    if not isinstance(meta, dict):
        fail("INVALID_REQUEST", http=422, message="metadata must be a JSON object.")
    data = image.file.read(settings.max_capture_bytes + 1)
    if len(data) > settings.max_capture_bytes:
        fail("TOO_LARGE", http=413, message="The image is too large.")
    try:
        captures.check_image(data, meta)
        captures.check_geometry(meta)
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


def _not_json(constant: str):
    """Python's json reads NaN and Infinity; JSON has no such numbers, and the database refuses them."""
    raise ValueError(f"{constant} is not a JSON number")


def _finite_float(text: str) -> float:
    """A number too large for a float (1e400) would be read as infinity: refused like Infinity."""
    value = float(text)
    if not math.isfinite(value):
        raise ValueError(f"{text} is out of range")
    return value


@app.get("/v1/reports")
def my_reports(token: Annotated[str, Depends(bearer)]):
    return {"reports": check(db.call("reporter_reports", token, 50))["reports"]}


# ---- The facility manager's decisions -----------------------------------------------------------

@app.get("/v1/fm/queue")
def fm_queue(token: Annotated[str, Depends(bearer)]):
    """The two queues waiting for this FM: authorize an intervention, approve a completion."""
    result = check(db.call("fm_queue", token))
    return {k: result[k] for k in ("site_id", "authorizations", "completions", "counts")}


@app.post("/v1/fm/decisions")
def fm_decide(body: Decision, token: Annotated[str, Depends(bearer)]):
    """Approve or reject, exactly as the email link does; the workflows carry it out.

    A rejection or a rework request needs a reason. 409 means the ticket moved on (someone decided
    it by email or in the chat, or the stage changed): the app reloads the queue.
    """
    payload = body.model_dump(mode="json") | {"token": token}
    result = db.call("fm_decide", payload)
    if result is None:
        fail("UNAUTHENTICATED")
    if result.get("status") != "OK":
        if result.get("status") == "BLOCKED":
            fail("BLOCKED", message=result.get("reason") or None)
        fail(result.get("status") or "INTERNAL")
    return {k: result[k] for k in ("outcome", "ticket_id", "ticket_status", "settling", "card")}


@app.get("/v1/photos/{capture_id}")
def photo(capture_id: UUID, token: Annotated[str, Depends(bearer)]):
    """The reporter's photo behind a card: for the site's FM, or the technician whose job it is."""
    result = check(db.call("fm_photo", token, str(capture_id)))
    path = captures.path_for(settings.capture_dir, str(capture_id))
    if path is None or not path.is_file():
        fail("NOT_FOUND", message="The image is no longer stored.")
    return FileResponse(path, media_type="image/jpeg")


# ---- The technician's jobs -----------------------------------------------------------------------

@app.get("/v1/technician/jobs")
def technician_jobs(token: Annotated[str, Depends(bearer)]):
    """Offers to answer, jobs in hand, work completed, and this technician's own skills."""
    result = check(db.call("technician_jobs", token))
    return {k: result[k] for k in ("me", "skill_catalog", "offers", "current", "completed")}


@app.post("/v1/technician/offers")
def technician_respond(body: OfferResponse, token: Annotated[str, Depends(bearer)]):
    """Accept or decline a job offer: the same record the offer email's link writes."""
    result = check(db.call("technician_respond", body.model_dump(mode="json") | {"token": token}))
    return {"ticket_id": result["ticket_id"], "decision": result["decision"]}


@app.post("/v1/technician/skills")
def technician_skills(body: Skills, token: Annotated[str, Depends(bearer)]):
    """The technician's own skills, from "What do you work on?". Dispatch matches jobs to them."""
    result = db.call("set_technician_skills", token, body.skills)
    if result is not None and result.get("status") == "INVALID":
        fail("INVALID", http=422, message="Choose from the listed skills.")
    return {"skills": check(result)["skills"]}


@app.post("/v1/technician/jobs/{ticket_id}/report", status_code=202)
def submit_report(ticket_id: int,
                  token: Annotated[str, Depends(bearer)],
                  report: Annotated[str, Form(max_length=32 * 1024)],
                  photo: Annotated[UploadFile | None, File()] = None):
    """The work report, written in the app: the template's fields and an optional AFTER photo.

    The PDF is not made here. WF2 renders it with the template's own renderer, so a report written
    in the app and one written in the browser are the same document, and claims it through the
    workflows' existing submission function.
    """
    if not 1 <= ticket_id <= 999_999_999:
        fail("NOT_FOUND")
    try:
        fields = ReportFields.model_validate_json(report)
    except ValidationError as exc:
        if any(e["loc"] and e["loc"][0] == "work_date" for e in exc.errors()):
            fail("INVALID_REPORT", http=422,
                 message="The work date is not a real date. Write it as YYYY-MM-DD, for example 2026-09-22.")
        fail("INVALID_REPORT", http=422)
    if not fields.declaration:
        fail("INVALID_REPORT", http=422, message="Confirm the declaration before sending the report.")

    data = photo.file.read(settings.max_capture_bytes + 1) if photo is not None else b""
    payload: dict = {"token": token, "ticket_id": ticket_id,
                     "report": fields.model_dump(mode="json", exclude={"photo_caption"})}
    if data:
        if len(data) > settings.max_capture_bytes:
            fail("TOO_LARGE", http=413, message="The photo is too large.")
        if captures.decoded_jpeg_size(data) is None:
            fail("NOT_A_JPEG", http=422, message="The photo could not be read as a JPEG image.")
        payload["photo"] = {"caption": fields.photo_caption, "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()}
    elif fields.photo_caption:
        fail("INVALID_REPORT", http=422, message="There is a caption but no photo.")

    # One request at a time per ticket, from the claim to the stored photo: a retry cannot write its
    # bytes between another request's write and its "stored". The database checks the hash as well.
    with _report_lock(ticket_id):
        result = db.call("submit_technician_report", payload)
        # Already sent - a double tap, or a retry after the first answer was lost, even once the
        # workflows have moved the job on: the same receipt.
        if result is not None and result.get("already_sent"):
            return {"report_id": result["report_id"], "status": "SENT"}
        # This round's report was already sent and this one says something else: it is refused, not
        # answered "sent" while its answers are dropped.
        if result is not None and result.get("status") == "ALREADY_SENT":
            fail("REPORT_ALREADY_SENT",
                 extra={"report_id": result["report_id"]} if result.get("report_id") else None)
        claim = check(result, ok="UPLOAD" if data else "STORED")
        if not data:
            return {"report_id": claim["report_id"], "status": "SENT"}
        path = captures.report_path_for(settings.capture_dir, claim["report_id"])
        captures.store(settings.capture_dir, claim["report_id"], data, target=path)
        stored = db.call("store_technician_report", token,
                         {"report_id": claim["report_id"], "photo_sha256": payload["photo"]["sha256"]})
        if stored is not None and stored.get("status") == "CONFLICT":
            fail("CONFLICT", message="The report changed while its photo was being sent. Send it again.")
        stored = check(stored, ok="STORED")
    return {"report_id": stored["report_id"], "status": "SENT"}


# Striped locks: enough to keep two requests for the same ticket apart in this process, without
# a lock object per ticket ever created. (The API runs as one process; see the Dockerfile.)
_REPORT_LOCKS = [threading.Lock() for _ in range(64)]


def _report_lock(ticket_id: int) -> threading.Lock:
    return _REPORT_LOCKS[ticket_id % len(_REPORT_LOCKS)]


@app.get("/v1/technician/jobs/{ticket_id}/report")
def report_state(ticket_id: int, token: Annotated[str, Depends(bearer)]):
    """Whether this job's report has already been written, so the form is not filled twice."""
    result = check(db.call("my_report_state", token, ticket_id))
    return {k: result[k] for k in ("sent", "state", "at") if k in result}


@app.get("/v1/technician/jobs/{ticket_id}/report-link")
def technician_report_link(ticket_id: int, token: Annotated[str, Depends(bearer)]):
    """The link to the workflows' report template for a job of this technician's."""
    if not 1 <= ticket_id <= 999_999_999:
        fail("NOT_FOUND")
    if not settings.portal_base_url:
        fail("PORTAL_NOT_CONFIGURED", http=503, message="The report form address is not configured.")
    result = check(db.call("technician_report_link", token, ticket_id))
    url = f"{settings.portal_base_url}/cbm-technician-report?ticket={result['ticket_id']}&token={result['token']}"
    return {"ticket_id": result["ticket_id"], "url": url, "expires_at": result["expires_at"]}
