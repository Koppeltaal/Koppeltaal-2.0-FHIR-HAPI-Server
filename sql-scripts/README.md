# HAPI FHIR Database SQL Scripts

This directory contains SQL scripts for managing and troubleshooting the HAPI FHIR PostgreSQL database.

## Prerequisites

- Access to the HAPI FHIR PostgreSQL database
- `psql` command-line tool installed
- Database credentials (host, port, username, database name, password)

## Setting Database Credentials

Before running any scripts, set the PostgreSQL password as an environment variable:

```bash
export PGPASSWORD=your_password
```

## Scripts Overview

### 1. Diagnostic Scripts

#### `hapi_queries.sql`
**Purpose**: Comprehensive diagnostic queries for investigating terminology cache and package issues.

**Use Cases**:
- Pre-deployment health checks
- Post-deployment verification
- Troubleshooting ValueSet expansion failures
- Checking CodeSystem concept loading
- Verifying package versions

**Usage**:
```bash
psql -h HOST -p PORT -U USERNAME -d DATABASE -f hapi_queries.sql
```

**Example**:
```bash
psql -h 35.204.98.239 -p 5432 -U hapi_fhir_server_staging -d hapi_fhir_server_staging -f hapi_queries.sql
```

#### `check-version-conflicts.sql`
**Purpose**: Detect resource version conflicts and duplicate resources.

**Use Cases**:
- Investigating "not the current version" errors
- Finding duplicate resources with same fhir_id
- Checking version history integrity

**Usage**:
```bash
psql -h HOST -p PORT -U USERNAME -d DATABASE -f check-version-conflicts.sql
```

---

### 2. Package Removal Scripts

These scripts are used to completely remove the Koppeltaal/VZVZ package from the database, which is necessary when:
- The search index needs to be rebuilt
- Package installation has failed and left corrupt data
- You need to cleanly reinstall a different version

#### `preview-ig-removal.sql`
**Purpose**: Preview what resources will be deleted WITHOUT actually deleting them (read-only).

**Usage**:
```bash
psql -h HOST -p PORT -U USERNAME -d DATABASE -f preview-ig-removal.sql
```

**What it shows**:
- Count of resources by type
- Sample resources to be removed
- Estimated impact on each database table
- Total number of resources to delete

#### `remove-ig-from-database.sql`
**Purpose**: **DESTRUCTIVE** - Completely removes ALL Koppeltaal/VZVZ package resources.

**⚠️ WARNING**: This script performs DELETE operations. Create a database backup before running!

**What it deletes**:
- All package resources (StructureDefinitions, CodeSystems, ValueSets, SearchParameters, etc.)
- Terminology cache entries (TRM_* tables)
- Search indexes
- NPM package metadata
- Resource version history

**Usage** (direct):
```bash
psql -h HOST -p PORT -U USERNAME -d DATABASE -f remove-ig-from-database.sql
```

**Usage** (recommended - via wrapper script):
```bash
./remove-ig.sh HOST PORT USERNAME DATABASE [--yes]
```

**Resource Detection Methods**:
The script uses 4 methods to ensure ALL Koppeltaal resources are found:
1. NPM package Binary resources
2. Resources by canonical URL (koppeltaal.nl / vzvz.nl)
3. Resources by fhir_id pattern (KT2%, koppeltaal%, etc.)
4. Recently updated resources with broken search indexes

#### `remove-ig.sh`
**Purpose**: User-friendly wrapper script for `remove-ig-from-database.sql`.

**Features**:
- Colored output for better readability
- Safety prompts (unless `--yes` flag is used)
- Input validation
- Error handling

**Usage**:
```bash
./remove-ig.sh <host> <port> <username> <database> [--yes]
```

**Example** (with prompt):
```bash
./remove-ig.sh 35.204.98.239 5432 hapi_fhir_server_staging hapi_fhir_server_staging
```

**Example** (skip prompt):
```bash
./remove-ig.sh 35.204.98.239 5432 hapi_fhir_server_staging hapi_fhir_server_staging --yes
```

---

## Common Workflows

### Workflow 1: Clean Package Reinstallation

When you need to reinstall the Koppeltaal package due to corruption or search index issues:

```bash
# 1. Set credentials
export PGPASSWORD=your_password

# 2. Preview what will be deleted (optional but recommended)
psql -h HOST -p PORT -U USERNAME -d DATABASE -f preview-ig-removal.sql

# 3. Remove the old package
./remove-ig.sh HOST PORT USERNAME DATABASE --yes

# 4. Restart HAPI server to allow clean reinstallation
# (The package will be automatically reinstalled on startup from the configured implementationguides)
```

### Workflow 2: Troubleshooting Package Upload Issues

When package upload fails with version conflicts or other errors:

```bash
# 1. Set credentials
export PGPASSWORD=your_password

# 2. Check for version conflicts
psql -h HOST -p PORT -U USERNAME -d DATABASE -f check-version-conflicts.sql

# 3. Run diagnostic queries
psql -h HOST -p PORT -U USERNAME -d DATABASE -f hapi_queries.sql

# 4. If needed, remove and reinstall package (see Workflow 1)
```

### Workflow 3: Pre-Deployment Health Check

Before deploying a new package version:

```bash
# 1. Set credentials
export PGPASSWORD=your_password

# 2. Run health checks
psql -h HOST -p PORT -U USERNAME -d DATABASE -f hapi_queries.sql

# 3. Look for:
#    - FAILED_TO_EXPAND status in ValueSets
#    - Missing CodeSystem concepts
#    - Duplicate resources
#    - Version conflicts
```

---

## Troubleshooting Guide

### Issue: "Trying to update Resource/_history/X but this is not the current version"

**Cause**: Resource exists in database with different version than package is trying to install.

**Solution**:
1. Run `check-version-conflicts.sql` to identify the resource
2. Check if resource has broken search indexes (no entries in HFJ_SPIDX_URI)
3. Run `remove-ig.sh` to clean database
4. Restart HAPI server for clean reinstallation

### Issue: Search index is broken (resources not searchable)

**Cause**: Search index rebuild failed or was interrupted.

**Solution**:
1. Remove package using `remove-ig.sh`
2. Restore/rebuild search index using HAPI's $reindex operation
3. Reinstall package

### Issue: HAPI server crashes on startup after package upload

**Cause**: NPM package cache contains corrupt data and HAPI tries to reinstall on every startup.

**Solution**:
1. Run `preview-ig-removal.sql` to see what's in NPM cache
2. Run `remove-ig.sh` to clean NPM_PACKAGE tables
3. Restart HAPI server

---

## Database Connection Examples

### Staging Environment
```bash
export PGPASSWORD=staging_password
psql -h 35.204.98.239 -p 5432 -U hapi_fhir_server_staging -d hapi_fhir_server_staging
```

### Production Environment
```bash
export PGPASSWORD=production_password
psql -h prod-host -p 5432 -U hapi_fhir_server -d hapi_fhir_server
```

---

## Safety Notes

1. **Always preview first**: Run `preview-ig-removal.sql` before running `remove-ig-from-database.sql`
2. **Backup before deletion**: Create database backup before running destructive operations
3. **Use staging first**: Test scripts on staging environment before running on production
4. **Verify credentials**: Double-check host/port/database before running scripts
5. **Check impact**: Review the preview output to ensure you're not deleting unintended resources

---

## Script Maintenance

### When to update `remove-ig-from-database.sql`

Update the script if:
- New Koppeltaal resource types are added
- New HAPI database tables are added that reference resources
- Resource identification patterns change (new URL patterns, etc.)

### Testing changes

Always test script changes on a staging/test database before using in production.

---

## Additional Resources

- [HAPI FHIR Documentation](https://hapifhir.io/hapi-fhir/docs/)
- [PostgreSQL psql Documentation](https://www.postgresql.org/docs/current/app-psql.html)
- [Koppeltaal 2.0 FHIR IG Repository](https://github.com/vzvznl/Koppeltaal-2.0-FHIR)
