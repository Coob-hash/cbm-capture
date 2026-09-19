"""The API tests run against a real PostgreSQL holding the workflows' schema plus the app migrations.

CBM_APP_DATABASE_URL     the API's own restricted login (cbm_app_api), exactly as deployed
CBM_APP_TEST_OWNER_URL   the owner login, used only to create fixtures the API cannot create
"""

import os
import uuid

import psycopg
import pytest

os.environ.setdefault("CBM_APP_GOOGLE_CLIENT_IDS", "test-client.apps.googleusercontent.com")
os.environ.setdefault("CBM_APP_AUTH_REQUESTS_PER_MINUTE", "10000")
os.environ.setdefault("CBM_APP_CAPTURE_DIR", "/tmp/cbm-captures")

from fastapi.testclient import TestClient  # noqa: E402

from cbm_api import main  # noqa: E402

SITE = "API-TEST"
CODE = "api-test-code-0001"


@pytest.fixture(scope="session")
def owner():
    with psycopg.connect(os.environ["CBM_APP_TEST_OWNER_URL"], autocommit=True) as conn:
        conn.execute("INSERT INTO cbm_app.sites(id,name) VALUES (%s,'API test site') ON CONFLICT DO NOTHING", [SITE])
        conn.execute("INSERT INTO cbm_app.site_access_codes(code,site_id) VALUES (%s,%s) ON CONFLICT DO NOTHING", [CODE, SITE])
        yield conn


@pytest.fixture(scope="session")
def client(owner):
    with TestClient(main.app) as c:
        yield c


def device(n: int = 1) -> dict:
    return {"id": f"00000000-0000-4000-8000-{n:012d}", "platform": "ANDROID", "model": "Pixel 8"}


def new_email() -> str:
    return f"u{uuid.uuid4().hex[:12]}@example.com"


def sign_up(client, role="USER", email=None, password="long-enough-1", dev=1):
    email = email or new_email()
    r = client.post("/v1/auth/signup", json={"site_code": CODE, "role": role, "email": email,
                                             "password": password, "device": device(dev)})
    return email, r


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}
