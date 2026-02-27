#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COLLECTION="${1:-top-kt-011}"
ENV="${2:-staging}"

# Load environment-specific .env, fall back to .env
if [ -f "$SCRIPT_DIR/.env.$ENV" ]; then
  set -a; source "$SCRIPT_DIR/.env.$ENV"; set +a
elif [ -f "$SCRIPT_DIR/.env" ]; then
  set -a; source "$SCRIPT_DIR/.env"; set +a
fi

# Check required variables
: "${KT2_E2E_CLIENT_ID:?Set KT2_E2E_CLIENT_ID in e2e/.env.$ENV or environment}"
: "${KT2_E2E_PRIVATE_KEY:?Set KT2_E2E_PRIVATE_KEY in e2e/.env.$ENV or environment}"
: "${KT2_E2E_KEY_ID:?Set KT2_E2E_KEY_ID in e2e/.env.$ENV or environment}"

ENV_FILE="$SCRIPT_DIR/$COLLECTION/$ENV.postman_environment.json"
COLLECTION_FILE="$SCRIPT_DIR/$COLLECTION/$COLLECTION.postman_collection.json"

if [ ! -f "$COLLECTION_FILE" ]; then
  echo "ERROR: Collection not found: $COLLECTION_FILE"
  exit 1
fi
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: Environment not found: $ENV_FILE"
  echo "Available: $(ls "$SCRIPT_DIR/$COLLECTION/"*.postman_environment.json 2>/dev/null | xargs -I{} basename {} .postman_environment.json)"
  exit 1
fi

REPORT_DIR="$SCRIPT_DIR/reports"
mkdir -p "$REPORT_DIR"
REPORT_FILE="$REPORT_DIR/$COLLECTION-$ENV-report.html"

echo "Running $COLLECTION against $ENV"
echo ""

newman run "$COLLECTION_FILE" \
  -e "$ENV_FILE" \
  --env-var "clientId=$KT2_E2E_CLIENT_ID" \
  --env-var "privateKeyPem=$KT2_E2E_PRIVATE_KEY" \
  --env-var "keyId=$KT2_E2E_KEY_ID" \
  --timeout-request 60000 \
  --reporters cli,htmlextra \
  --reporter-htmlextra-export "$REPORT_FILE"

echo ""
echo "HTML report: $REPORT_FILE"
