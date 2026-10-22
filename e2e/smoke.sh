#!/usr/bin/env bash
# Starts the FHIR server of this checkout in docker-compose (docker-compose.yml with the docker-compose.e2e.yml
# override) on a fresh database, with security enabled, and checks that it starts and answers like it should.
# Needs no credentials, so it runs in CI on every push.
#
# usage: ./e2e/smoke.sh
#
# The containers run in their own compose project (kt2-e2e), a running development environment is not touched.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

export KT2_E2E_PORT="${KT2_E2E_PORT:-8090}"
export KT2_E2E_SECURITY_ENABLED=true
BASE_URL="http://localhost:$KT2_E2E_PORT/fhir/DEFAULT"
COMPOSE=(docker compose -p kt2-e2e -f "$ROOT_DIR/docker-compose.yml" -f "$ROOT_DIR/docker-compose.e2e.yml")

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    "${COMPOSE[@]}" logs --tail 300 hapi-fhir-jpaserver-start || true
  fi
  "${COMPOSE[@]}" down --volumes
}
trap cleanup EXIT

# expect <description> <expected status> <url> [jq filter that must be true]
expect() {
  local description="$1" expected="$2" url="$3" filter="${4:-}"
  local body status
  body="$(mktemp)"
  status="$(curl -s -o "$body" -w '%{http_code}' -H 'Accept: application/fhir+json' "$url")"
  if [ "$status" != "$expected" ]; then
    echo "FAIL: $description: HTTP $status, expected $expected"
    head -c 500 "$body"; echo
    return 1
  fi
  if [ -n "$filter" ] && ! jq -e "$filter" "$body" > /dev/null; then
    echo "FAIL: $description: the response does not match $filter"
    head -c 500 "$body"; echo
    return 1
  fi
  echo "OK: $description"
}

"${COMPOSE[@]}" up --detach --build

# the server only answers once the Koppeltaal ImplementationGuide is installed
echo "Waiting for $BASE_URL/metadata"
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null "$BASE_URL/metadata"; then
    break
  fi
  sleep 5
done

expect "CapabilityStatement" 200 "$BASE_URL/metadata" '.resourceType == "CapabilityStatement"'
expect "SMART configuration" 200 "$BASE_URL/.well-known/smart-configuration" '.token_endpoint != null'
expect "Koppeltaal ImplementationGuide installed" 200 "$BASE_URL/ImplementationGuide" \
  '[.entry[]?.resource.packageId] | index("koppeltaalv2.00") != null'
expect "search without a token is rejected" 401 "$BASE_URL/Patient"
