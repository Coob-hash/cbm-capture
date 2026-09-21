"""Database status codes -> HTTP responses, in one place.

The database answers with {"status": CODE}; the API decides what that means on the wire. Every error
body has the same shape: {"error": CODE, "message": text}.
"""

from datetime import datetime, timezone

from fastapi import HTTPException

_HTTP = {
    "INVALID_ROLE": 422, "INVALID_EMAIL": 422, "WEAK_PASSWORD": 422, "INVALID_DEVICE": 422,
    "INVALID_SITE_CODE": 422, "INVALID": 422, "SITE_MISMATCH": 422,
    "ACCOUNT_EXISTS": 409, "ALREADY_BOUND": 409, "CONFLICT": 409,
    "BLOCKED": 409, "OFFER_GONE": 409, "REASON_REQUIRED": 422, "INVALID_REPORT": 422,
    "INVALID_CREDENTIALS": 401, "INVALID_GOOGLE_IDENTITY": 401, "UNAUTHENTICATED": 401,
    "LOCKED": 429,
    "DISABLED": 403, "FORBIDDEN": 403,
    "NO_ACCOUNT": 404, "NOT_FOUND": 404,
}

_MESSAGES = {
    "INVALID_ROLE": "Role must be USER, TECHNICIAN or FM.",
    "INVALID_EMAIL": "Enter a valid email address.",
    "WEAK_PASSWORD": "The password must be 8 to 128 characters.",
    "INVALID_DEVICE": "The device description is invalid.",
    "INVALID_SITE_CODE": "This site code is not valid. Scan the site's QR code again.",
    "ACCOUNT_EXISTS": "An account with this email already exists. Log in instead.",
    "ALREADY_BOUND": "This session already has a role. Log in again to change it.",
    "INVALID_CREDENTIALS": "Email or password is wrong.",
    "INVALID_GOOGLE_IDENTITY": "Google sign-in could not be verified.",
    "UNAUTHENTICATED": "Your session has ended. Log in again.",
    "LOCKED": "Too many wrong passwords. Try again later.",
    "DISABLED": "This account is disabled.",
    "FORBIDDEN": "Not allowed.",
    "NO_ACCOUNT": "No account for this Google identity. Sign up first.",
    "NOT_FOUND": "Not found.",
    "INVALID": "The request is not valid.",
    "SITE_MISMATCH": "This capture belongs to another site than your session.",
    "CONFLICT": "This capture id was already used for a different photo or report.",
    "BLOCKED": "This ticket has moved on. Open it again to see where it stands.",
    "OFFER_GONE": "This offer is no longer open.",
    "REASON_REQUIRED": "Say why, in a few words.",
    "INVALID_REPORT": "The report is not complete. Check the dates, the answers and the confirmation.",
}


def fail(code: str, http: int | None = None, message: str | None = None, headers: dict | None = None):
    raise HTTPException(status_code=http or _HTTP.get(code, 400),
                        detail={"error": code, "message": message or _MESSAGES.get(code, code)},
                        headers=headers)


def check(result: dict | None, ok: str = "OK") -> dict:
    """Pass a successful database answer through; turn anything else into its HTTP error."""
    if result is None:
        fail("UNAUTHENTICATED")
    status = result.get("status")
    if status == ok:
        return result
    headers = None
    if status == "LOCKED" and result.get("locked_until"):
        until = datetime.fromisoformat(result["locked_until"])
        seconds = max(1, int((until - datetime.now(timezone.utc)).total_seconds()))
        headers = {"Retry-After": str(seconds)}
    fail(status or "INTERNAL", http=None if status in _HTTP else 500, headers=headers)
