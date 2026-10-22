#!/usr/bin/env bash
# Generates the key pair of the e2e client.
#
# usage: ./e2e/generate-keys.sh          # public key, to upload in the domain admin
#        ./e2e/generate-keys.sh --jwks   # also writes e2e/jwks.json, to commit and register as JWKS URL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRIVATE_KEY="$SCRIPT_DIR/private.pem"
PUBLIC_KEY="$SCRIPT_DIR/public.pem"
JWKS_FILE="$SCRIPT_DIR/jwks.json"
WRITE_JWKS=false

if [ "${1:-}" = "--jwks" ]; then
  WRITE_JWKS=true
fi

if [ -f "$PRIVATE_KEY" ]; then
  echo "ERROR: $PRIVATE_KEY already exists. Remove it first to regenerate."
  exit 1
fi

echo "Generating RSA 2048-bit key pair..."
openssl genrsa -out "$PRIVATE_KEY" 2048 2>/dev/null
openssl rsa -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY" 2>/dev/null

KID="kt2-e2e-$(openssl rand -hex 4)"

if [ "$WRITE_JWKS" = "true" ]; then
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
fi

echo ""
echo "Generated files:"
echo "  Private key: $PRIVATE_KEY (gitignored, KEEP SECRET)"
echo "  Public key:  $PUBLIC_KEY (gitignored)"
if [ "$WRITE_JWKS" = "true" ]; then
  echo "  JWKS:        $JWKS_FILE (commit this)"
fi
echo ""
echo "Key ID: $KID"
echo ""
echo "Next steps:"
if [ "$WRITE_JWKS" = "true" ]; then
  echo "  1. Commit e2e/jwks.json and register a SMART service in the domain admin with the JWKS URL:"
  echo "     https://raw.githubusercontent.com/Koppeltaal/Koppeltaal-2.0-FHIR-HAPI-Server/master/e2e/jwks.json"
else
  echo "  1. Register a SMART service in the domain admin and paste this public key in the Public Key tab:"
  echo ""
  cat "$PUBLIC_KEY"
fi
echo ""
echo "  2. Copy the private key for .env:"
echo "     awk 'NF {sub(/\\r/, \"\"); printf \"%s\\\\n\",\$0;}' $PRIVATE_KEY"
echo ""
echo "  3. Create e2e/.env.<env> from e2e/.env.example and fill in:"
echo "     KT2_E2E_CLIENT_ID=<client-id-from-step-1>"
echo "     KT2_E2E_KEY_ID=$KID"
echo "     KT2_E2E_PRIVATE_KEY=\"<output-from-step-2>\""
