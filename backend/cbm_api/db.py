"""The API's only way into PostgreSQL: calling cbm_app entry functions.

The login this pool uses (cbm_app_api) has no table privileges, so there is deliberately no helper
for arbitrary SQL here. Each call is one function, one transaction.
"""

from typing import Any

from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

# Entry functions and their argument shapes. Anything else is refused before reaching the database.
_FUNCTIONS = {
    "sign_up": "cbm_app.sign_up(%s)",
    "login": "cbm_app.login(%s)",
    "select_membership": "cbm_app.select_membership(%s, %s)",
    "me": "cbm_app.me(%s)",
    "authenticate": "cbm_app.authenticate(%s, %s)",
    "logout": "cbm_app.logout(%s)",
    "claim_capture": "cbm_app.claim_capture(%s, %s)",
    "store_capture": "cbm_app.store_capture(%s, %s)",
    "reporter_reports": "cbm_app.reporter_reports(%s, %s)",
}


class Database:
    def __init__(self, url: str):
        self._pool = ConnectionPool(url, min_size=1, max_size=10, open=False,
                                    kwargs={"application_name": "cbm-app-api"})

    def open(self) -> None:
        self._pool.open(wait=True, timeout=30)

    def close(self) -> None:
        self._pool.close()

    def call(self, name: str, *args: Any) -> dict | None:
        sql = "SELECT " + _FUNCTIONS[name]
        params = [Jsonb(a) if isinstance(a, dict) else a for a in args]
        with self._pool.connection() as conn:
            row = conn.execute(sql, params).fetchone()
        return row[0] if row else None

    def ping(self) -> bool:
        with self._pool.connection() as conn:
            return conn.execute("SELECT 1").fetchone() == (1,)
