"""Google ID token verification. Only verified claims leave this module.

The app obtains an ID token from Google (Android Credential Manager). This checks its signature
against Google's published keys, its expiry, its issuer, and that it was issued for one of our OAuth
client IDs, then returns {subject, email, email_verified, name}.
"""

from google.auth.transport import requests as google_requests
from google.oauth2 import id_token

_ISSUERS = {"accounts.google.com", "https://accounts.google.com"}
_transport = google_requests.Request()


class GoogleTokenError(Exception):
    pass


def verify(token: str, client_ids: frozenset[str]) -> dict:
    try:
        claims = id_token.verify_oauth2_token(token, _transport, audience=None)
    except ValueError as exc:  # bad signature, expired, malformed
        raise GoogleTokenError(str(exc)) from exc
    if claims.get("iss") not in _ISSUERS or claims.get("aud") not in client_ids:
        raise GoogleTokenError("token not issued for this app")
    return {
        "subject": claims["sub"],
        "email": claims.get("email", ""),
        "email_verified": bool(claims.get("email_verified")),
        "name": claims.get("name"),
    }
