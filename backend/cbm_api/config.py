"""Settings, read once from the environment. Nothing secret has a default."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str
    google_client_ids: frozenset[str]
    # Behind the edge proxy the socket peer is the proxy; then the client is the last
    # X-Forwarded-For entry (added by ngrok).
    trust_proxy: bool
    auth_requests_per_minute: int
    max_body_bytes: int
    max_capture_bytes: int
    capture_dir: str


def load() -> Settings:
    url = os.environ.get("CBM_APP_DATABASE_URL", "")
    if not url:
        raise RuntimeError("CBM_APP_DATABASE_URL is required")
    ids = frozenset(x.strip() for x in os.environ.get("CBM_APP_GOOGLE_CLIENT_IDS", "").split(",") if x.strip())
    return Settings(
        database_url=url,
        google_client_ids=ids,
        trust_proxy=os.environ.get("CBM_APP_TRUST_PROXY", "0") == "1",
        auth_requests_per_minute=int(os.environ.get("CBM_APP_AUTH_REQUESTS_PER_MINUTE", "20")),
        max_body_bytes=int(os.environ.get("CBM_APP_MAX_BODY_BYTES", str(64 * 1024))),
        # A capture is a <= 1280 px JPEG, typically 200-400 KB; 5 MiB leaves room without inviting abuse.
        max_capture_bytes=int(os.environ.get("CBM_APP_MAX_CAPTURE_BYTES", str(5 * 1024 * 1024))),
        capture_dir=os.environ.get("CBM_APP_CAPTURE_DIR", "/data/captures"),
    )
