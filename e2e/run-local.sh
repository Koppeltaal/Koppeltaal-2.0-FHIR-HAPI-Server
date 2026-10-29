#!/usr/bin/env bash
# Runs an e2e collection against the FHIR server of this checkout, in docker-compose
# (docker-compose.yml with the docker-compose.e2e.yml override). The server trusts the tokens of the
# staging auth server, so the e2e client credentials from e2e/.env.local work against it.
#
# usage: ./e2e/run-local.sh [collection]
#
# Required in e2e/.env.local: KT2_E2E_CLIENT_ID, KT2_E2E_KEY_ID, KT2_E2E_PRIVATE_KEY, KT2_E2E_AUDIENCE.
# Set KT2_E2E_KEEP=true to keep the containers running afterwards (only once security is enabled).
#
# The containers run in their own compose project (kt2-e2e), a running development environment is not touched.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
COLLECTION="${1:-top-kt-011}"

if [ -f "$SCRIPT_DIR/.env.local" ]; then
  set -a; source "$SCRIPT_DIR/.env.local"; set +a
fi

: "${KT2_E2E_CLIENT_ID:?Set KT2_E2E_CLIENT_ID in e2e/.env.local}"
: "${KT2_E2E_AUDIENCE:?Set KT2_E2E_AUDIENCE (the aud claim of the staging access tokens) in e2e/.env.local}"

export KT2_E2E_PORT="${KT2_E2E_PORT:-8090}"
export KT2_E2E_AUDIENCE
BASE_URL="http://localhost:$KT2_E2E_PORT/fhir/DEFAULT"
# picked up by run.sh, so newman uses the same port
export KT2_E2E_BASE_URL="$BASE_URL"
COMPOSE=(docker compose -p kt2-e2e -f "$ROOT_DIR/docker-compose.yml" -f "$ROOT_DIR/docker-compose.e2e.yml")
SECURITY_ENABLED=false

cleanup() {
  # never leave a server without security behind
  if [ "${KT2_E2E_KEEP:-false}" != "true" ] || [ "$SECURITY_ENABLED" != "true" ]; then
    "${COMPOSE[@]}" down --volumes
  fi
}
trap cleanup EXIT

wait_for_server() {
  echo "Waiting for $BASE_URL/metadata"
  for _ in $(seq 1 120); do
    if curl -sf -o /dev/null "$BASE_URL/metadata"; then
      return 0
    fi
    sleep 5
  done
  echo "ERROR: the FHIR server did not start, see: ${COMPOSE[*]} logs hapi-fhir-jpaserver-start"
  return 1
}

# 1. Without security, create the Device of the e2e client. The server finds the Device of a client by the
#    client id as logical id, so it is created with a PUT, which the resource-origin checks do not allow.
KT2_E2E_SECURITY_ENABLED=false "${COMPOSE[@]}" up --detach --build
wait_for_server

curl -sf -o /dev/null -X PUT "$BASE_URL/Device/$KT2_E2E_CLIENT_ID" \
  -H "Content-Type: application/fhir+json" \
  --data @- <<EOF
{
  "resourceType": "Device",
  "id": "$KT2_E2E_CLIENT_ID",
  "meta": { "profile": [ "http://koppeltaal.nl/fhir/StructureDefinition/KT2Device" ] },
  "identifier": [ { "system": "http://vzvz.nl/fhir/NamingSystem/koppeltaal-client-id", "value": "$KT2_E2E_CLIENT_ID" } ],
  "status": "active",
  "deviceName": [ { "name": "KT2 e2e client", "type": "user-friendly-name" } ]
}
EOF
echo "Created Device/$KT2_E2E_CLIENT_ID"

# 2. Restart the server with security enabled
KT2_E2E_SECURITY_ENABLED=true "${COMPOSE[@]}" up --detach --no-build hapi-fhir-jpaserver-start
wait_for_server
SECURITY_ENABLED=true

# 3. Run the collection
"$SCRIPT_DIR/run.sh" "$COLLECTION" local
