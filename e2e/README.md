# E2E Tests

Automated end-to-end tests for the Koppeltaal 2.0 FHIR HAPI Server using [Newman](https://github.com/postmanlabs/newman) (Postman CLI).

## Prerequisites

- [Node.js](https://nodejs.org/) (v16+)
- Newman: `npm install -g newman newman-reporter-htmlextra`
- OpenSSL (for key generation)

## One-time setup

### 1. Generate RSA key pair

```bash
./e2e/generate-keys.sh
```

This creates:
- `e2e/private.pem` (gitignored, keep secret)
- `e2e/jwks.json` (public key, commit this)

### 2. Register SMART Backend Service client

Register a client with the Koppeltaal auth server using the JWKS URL:

```
https://raw.githubusercontent.com/Koppeltaal/Koppeltaal-2.0-FHIR-HAPI-Server/master/e2e/jwks.json
```

The client needs permissions to read/write Task resources and read AuditEvents.

### 3. Configure secrets

```bash
cp e2e/.env.example e2e/.env.staging
```

Fill in `e2e/.env.staging` (or `e2e/.env.prd` for production).
The runner loads `e2e/.env.<env>`, falling back to `e2e/.env`:

| Variable | Description |
|----------|-------------|
| `KT2_E2E_CLIENT_ID` | Client ID from step 2 |
| `KT2_E2E_KEY_ID` | Key ID printed by generate-keys.sh |
| `KT2_E2E_PRIVATE_KEY` | PEM content with newlines as `\n` |

To convert PEM to single-line format:

```bash
awk 'NF {sub(/\r/, ""); printf "%s\\n",$0;}' e2e/private.pem
```

## Running tests

### Local

```bash
./e2e/run.sh                          # runs top-kt-011 against staging (default)
./e2e/run.sh top-kt-011 prd           # runs top-kt-011 against production
./e2e/run.sh top-kt-012 staging       # runs a different topic (when available)
```

### CI (GitHub Actions)

Set these as repository secrets:
- `KT2_E2E_CLIENT_ID`
- `KT2_E2E_PRIVATE_KEY`
- `KT2_E2E_KEY_ID`

```yaml
- name: Run E2E tests
  env:
    KT2_E2E_CLIENT_ID: ${{ secrets.KT2_E2E_CLIENT_ID }}
    KT2_E2E_PRIVATE_KEY: ${{ secrets.KT2_E2E_PRIVATE_KEY }}
    KT2_E2E_KEY_ID: ${{ secrets.KT2_E2E_KEY_ID }}
  run: ./e2e/run.sh
```

## Test suites

### TOP-KT-011: AuditEvent Validation

Tests that every FHIR REST interaction generates a correct AuditEvent per the TOP-KT-011 specification.

**Flow**: Setup -> Capability -> Subscribe -> Create Task -> Read Task -> Update Task -> Search Task -> Delete Task -> Validate SendNotification -> Cleanup

Each step validates 20+ fields on the resulting AuditEvent:
- `type` (system, code, display)
- `subtype` (system, code, display)
- `action` (C/R/U/D/E)
- `outcome` (0 = success)
- `agent` (Device reference, DCM 110153, requestor, no network)
- `source` (site hostname, observer Device reference)
- `entity` (resource type, versioned reference, no deprecated name)
- `meta.profile` (KT2AuditEvent)
- Extensions (trace-id, request-id, resource-origin)

## Adding new test suites

Create a new folder under `e2e/`:

```
e2e/
  top-kt-012/
    top-kt-012.postman_collection.json
    staging.postman_environment.json
```

Then run with: `./e2e/run.sh top-kt-012`
