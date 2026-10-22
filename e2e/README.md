# E2E Tests

Automated end-to-end tests for the Koppeltaal 2.0 FHIR HAPI Server using [Newman](https://github.com/postmanlabs/newman) (Postman CLI).

## Prerequisites

- [Node.js](https://nodejs.org/) (v16+)
- Newman: `npm install -g newman newman-reporter-htmlextra`
- OpenSSL (for key generation)

## One-time setup

### 1. Generate a key pair and register the client

The client is a SMART Backend Service in the domain admin of the environment (for staging
`https://staging-domain-admin.koppeltaal.headease.nl`). The auth server can get its public key in two ways: from a
JWKS URL or from an uploaded public key. Choose one of the two paths below.

The client needs permissions to read/write Task resources and read AuditEvents.

#### Path A: JWKS URL

```bash
./e2e/generate-keys.sh --jwks
```

This creates:
- `e2e/private.pem`: the private key (gitignored, keep it secret)
- `e2e/public.pem`: the public key (gitignored, not used in this path)
- `e2e/jwks.json`: the public key as JWKS (commit this)

Commit `e2e/jwks.json` and, once it is on `master`, create a SMART service with this URL in the **JWKS URL** tab:

```
https://raw.githubusercontent.com/Koppeltaal/Koppeltaal-2.0-FHIR-HAPI-Server/master/e2e/jwks.json
```

`KT2_E2E_KEY_ID` must be the `kid` from `jwks.json`. A new key only needs a new `jwks.json` commit, the
registration in the domain admin stays the same.

The repository already contains a `jwks.json` of an existing e2e client. Generating a new one replaces it, and a
client registered with the old key then stops working.

#### Path B: upload the public key

```bash
./e2e/generate-keys.sh
```

This creates two files, both gitignored, so nothing needs to be committed:
- `e2e/private.pem`: the private key, keep it secret
- `e2e/public.pem`: the public key for the client registration

Create a SMART service, open the **Public Key** tab and paste the content of `e2e/public.pem` (an RSA key of at least
2048 bits, starting with `-----BEGIN PUBLIC KEY-----`). With an uploaded public key the auth server does not check the
`kid`, but the client assertion still sends the key ID that the script prints.

### 2. Configure secrets

```bash
cp e2e/.env.example e2e/.env.staging
```

Fill in `e2e/.env.staging` (or `e2e/.env.prd` for production).
The runner loads `e2e/.env.<env>`, falling back to `e2e/.env`:

| Variable | Description |
|----------|-------------|
| `KT2_E2E_CLIENT_ID` | Client ID from step 1 |
| `KT2_E2E_KEY_ID` | Key ID printed by generate-keys.sh, sent as `kid` in the client assertion. Path A: must match the `kid` in `jwks.json`; path B: not checked |
| `KT2_E2E_PRIVATE_KEY` | PEM content with newlines as `\n`, in double quotes |

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

### Against this checkout (docker-compose)

`run-local.sh` builds and starts the FHIR server of this checkout with `docker-compose.yml` and the
`docker-compose.e2e.yml` override, and runs a collection against it. The server has security enabled and
trusts the tokens of the staging auth server, so the staging e2e client is used.

```bash
./e2e/run-local.sh                    # runs top-kt-011 against http://localhost:8090/fhir/DEFAULT
KT2_E2E_KEEP=true ./e2e/run-local.sh  # keeps the containers running afterwards
```

The containers run in their own compose project (`kt2-e2e`, containers `kt2-e2e-*`), so a running development
environment from `docker-compose.yml` is not touched. The containers are always removed when the run stops before
security is enabled.

Put the credentials in `e2e/.env.local`, together with:

| Variable | Description |
|----------|-------------|
| `KT2_E2E_AUDIENCE` | The `aud` claim of the access tokens of the staging auth server |
| `KT2_E2E_PORT` | Optional, host port of the FHIR server (default `8090`) |
| `KT2_E2E_HEAP` | Optional, maximum heap of the FHIR server (default `4g`) |

The server finds the Device of a client by the client id as logical id. The script first starts the server
without security to create `Device/<client id>`, then restarts it with security enabled.

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
