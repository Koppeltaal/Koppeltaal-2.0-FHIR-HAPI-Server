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

# Pass the credentials in a temporary environment file instead of command line arguments, which other users of the
# machine can see. KT2_E2E_BASE_URL optionally overrides the baseUrl of the environment.
SECRET_ENV_FILE="$(mktemp)"
trap 'rm -f "$SECRET_ENV_FILE"' EXIT
chmod 600 "$SECRET_ENV_FILE"
node -e '
  const fs = require("fs");
  const environment = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  const set = (key, value) => {
    const variable = environment.values.find((v) => v.key === key);
    if (variable) variable.value = value;
    else environment.values.push({ key, value, type: "secret", enabled: true });
  };
  set("clientId", process.env.KT2_E2E_CLIENT_ID);
  set("privateKeyPem", process.env.KT2_E2E_PRIVATE_KEY);
  set("keyId", process.env.KT2_E2E_KEY_ID);
  if (process.env.KT2_E2E_BASE_URL) set("baseUrl", process.env.KT2_E2E_BASE_URL);
  fs.writeFileSync(process.argv[2], JSON.stringify(environment));
' "$ENV_FILE" "$SECRET_ENV_FILE"

newman run "$COLLECTION_FILE" \
  -e "$SECRET_ENV_FILE" \
  --timeout-request 60000 \
  --reporters cli,htmlextra \
  --reporter-htmlextra-export "$REPORT_FILE"

echo ""
echo "HTML report: $REPORT_FILE"
