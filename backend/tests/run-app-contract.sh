#!/usr/bin/env bash
# Runs the Android app's client code against a real, throwaway App API.
#
#   backend/tests/run-app-contract.sh
#
# Starts PostgreSQL 16 with the workflows' reference schema and the app migrations, the API image on
# a local port, and a test site; then runs android's ApiContractIntegrationTest against it. Nothing
# touches the deployment; everything is removed on exit. Needs Docker and the Android toolchain.
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$(dirname "$HERE")"
ROOT="$(dirname "$BACKEND")"
NET=cbm-app-it-net; PG=cbm-app-it-pg; API=cbm-app-it-api; PORT=${CBM_IT_PORT:-18080}
API_PW=api-it-pw; CODE=it-site-code-0001

cleanup() { docker rm -f "$API" "$PG" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_USER=cbm_app -e POSTGRES_PASSWORD=owner-it-pw -e POSTGRES_DB=cbm_demo postgres:16 >/dev/null
until docker exec "$PG" pg_isready -U cbm_app -d cbm_demo -h 127.0.0.1 >/dev/null 2>&1; do sleep 1; done
psql() { docker exec -i -e PGOPTIONS="-c client_min_messages=warning" "$PG" psql -X -q -U cbm_app -d cbm_demo -v ON_ERROR_STOP=1 "$@"; }
psql < "$HERE/fixtures/workflow_schema.reference.sql" >/dev/null
for m in "$BACKEND"/migrations/0*.sql; do psql < "$m" >/dev/null; done
psql -c "ALTER ROLE cbm_app_api LOGIN PASSWORD '$API_PW'" \
     -c "INSERT INTO cbm_app.sites(id,name) VALUES ('API-TEST','Contract test site')" \
     -c "INSERT INTO cbm_app.site_access_codes(code,site_id) VALUES ('$CODE','API-TEST')"

(cd "$BACKEND" && docker build -q --target runtime -t cbm-app-api:it . >/dev/null)
docker run -d --name "$API" --network "$NET" -p "127.0.0.1:$PORT:8080" \
  -e CBM_APP_DATABASE_URL="postgresql://cbm_app_api:$API_PW@$PG:5432/cbm_demo" cbm-app-api:it >/dev/null
until curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null; do sleep 1; done

cd "$ROOT/android"
CBM_API_IT_URL="http://127.0.0.1:$PORT/" CBM_API_IT_SITE_CODE="$CODE" CBM_API_IT_SITE_ID=API-TEST \
  ./gradlew :app:testDebugUnitTest --tests "ai.cbm.capture.data.remote.ApiContractIntegrationTest" --rerun --no-daemon --console=plain -q
echo "app <-> API contract: passed"
