#!/usr/bin/env bash
# Runs the app's SQL and API test suites against a throwaway PostgreSQL 16 in Docker.
#
#   backend/tests/run-tests.sh
#
# The database is loaded with the workflows' schema (a reference copy, WORKFLOW_SCHEMA), then the
# app migrations, exactly as on the live server. Nothing touches the deployment; every container
# and the network are removed on exit.
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$(dirname "$HERE")"
WORKFLOW_SCHEMA="${WORKFLOW_SCHEMA:-$HERE/fixtures/workflow_schema.reference.sql}"
NET=cbm-app-test-net; PG=cbm-app-test-pg; IMG=cbm-app-api:test
OWNER_PW=owner-test-pw; API_PW=api-test-pw

cleanup() { docker rm -f "$PG" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_USER=cbm_app -e POSTGRES_PASSWORD="$OWNER_PW" \
  -e POSTGRES_DB=cbm_demo postgres:16 >/dev/null
until docker exec "$PG" pg_isready -U cbm_app -d cbm_demo -h 127.0.0.1 >/dev/null 2>&1; do sleep 1; done

psql() { docker exec -i -e PGOPTIONS="-c client_min_messages=warning" "$PG" psql -X -q -U cbm_app -d cbm_demo -v ON_ERROR_STOP=1 "$@"; }
echo "== workflow schema: $(basename "$WORKFLOW_SCHEMA")"
psql < "$WORKFLOW_SCHEMA" >/dev/null
for m in "$BACKEND"/migrations/0*.sql; do
  echo "== apply $(basename "$m") (twice: migrations must be repeatable)"
  psql < "$m" >/dev/null; psql < "$m" >/dev/null
done

echo "== SQL suite"
psql < "$HERE/test_app_schema.sql" | grep -E "passed" || { echo "SQL suite FAILED"; exit 1; }

echo "== API suite"
psql -c "ALTER ROLE cbm_app_api LOGIN PASSWORD '$API_PW'"
(cd "$BACKEND" && docker build -q --target test -t "$IMG" . >/dev/null)
docker run --rm --network "$NET" \
  -e CBM_APP_DATABASE_URL="postgresql://cbm_app_api:$API_PW@$PG:5432/cbm_demo" \
  -e CBM_APP_TEST_OWNER_URL="postgresql://cbm_app:$OWNER_PW@$PG:5432/cbm_demo" \
  "$IMG"
