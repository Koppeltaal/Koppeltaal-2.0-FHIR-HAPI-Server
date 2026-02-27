#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRIVATE_KEY="$SCRIPT_DIR/private.pem"
JWKS_FILE="$SCRIPT_DIR/jwks.json"

if [ -f "$PRIVATE_KEY" ]; then
  echo "ERROR: $PRIVATE_KEY already exists. Remove it first to regenerate."
  exit 1
fi

echo "Generating RSA 2048-bit key pair..."
openssl genrsa -out "$PRIVATE_KEY" 2048 2>/dev/null

KID="kt2-e2e-$(openssl rand -hex 4)"

MODULUS_HEX=$(openssl rsa -in "$PRIVATE_KEY" -modulus -noout 2>/dev/null | sed 's/Modulus=//')
N=$(echo "$MODULUS_HEX" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')
E="AQAB"

cat > "$JWKS_FILE" <<EOF
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "$KID",
      "use": "sig",
      "alg": "RS512",
      "n": "$N",
      "e": "$E"
    }
  ]
}
EOF

echo ""
echo "Generated files:"
echo "  Private key: $PRIVATE_KEY (KEEP SECRET)"
echo "  JWKS:        $JWKS_FILE (commit this)"
echo ""
echo "Key ID: $KID"
echo ""
echo "Next steps:"
echo "  1. Commit e2e/jwks.json to the repository"
echo "  2. JWKS URL will be:"
echo "     https://raw.githubusercontent.com/Koppeltaal/Koppeltaal-2.0-FHIR-HAPI-Server/master/e2e/jwks.json"
echo "  3. Register a SMART Backend Service client with that JWKS URL"
echo "  4. Copy the private key for .env:"
echo "     awk 'NF {sub(/\\r/, \"\"); printf \"%s\\\\n\",\$0;}' $PRIVATE_KEY"
echo ""
echo "  5. Create e2e/.env from e2e/.env.example and fill in:"
echo "     KT2_E2E_CLIENT_ID=<client-id-from-step-3>"
echo "     KT2_E2E_KEY_ID=$KID"
echo "     KT2_E2E_PRIVATE_KEY=<output-from-step-4>"
