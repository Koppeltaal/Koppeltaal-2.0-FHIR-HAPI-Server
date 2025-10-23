# CLAUDE.md - AI Assistant Context for Koppeltaal 2.0 HAPI FHIR Server

## Project Overview

This is a HAPI FHIR Server (version 8.2.0) deployment for the Koppeltaal 2.0 project. It serves as the central FHIR repository for the Dutch healthcare integration platform.

**Related Repository**: [Koppeltaal-2.0-FHIR](https://github.com/vzvznl/Koppeltaal-2.0-FHIR) (FHIR Implementation Guide)

### Key Components

- **HAPI FHIR JPA Server Starter**: Spring Boot application serving FHIR R4 endpoints
- **PostgreSQL Database**: Stores FHIR resources, terminology cache, and search indexes
- **Package Registry**: Auto-loads Koppeltaal 2.0 FHIR Implementation Guide on startup
- **SQL Scripts**: Database management and troubleshooting tools (in `sql-scripts/`)

---

## Database Architecture

### Main Tables

- **HFJ_RESOURCE**: Core resource storage (Patient, ValueSet, etc.)
- **HFJ_RES_VER**: Resource version history
- **HFJ_SPIDX_***: Search parameter indexes (STRING, TOKEN, URI, DATE, etc.)
- **TRM_***: Terminology cache (CodeSystem, ValueSet, Concept storage)
- **NPM_PACKAGE_***: NPM package metadata and cached resources

### Key Relationships

1. **Resource → Version History**: `HFJ_RESOURCE.res_id` ← `HFJ_RES_VER.res_id`
2. **Resource → Search Indexes**: `HFJ_RESOURCE.res_id` ← `HFJ_SPIDX_*.res_id`
3. **CodeSystem → Versions**: `TRM_CODESYSTEM` → `TRM_CODESYSTEM_VER` (circular via `current_version_pid`)
4. **NPM Package → Resources**: `NPM_PACKAGE` → `NPM_PACKAGE_VER` → `NPM_PACKAGE_VER_RES` → `HFJ_RESOURCE`

---

## Common Database Issues

### 1. Version Conflicts

**Error**: `HAPI-0989: Trying to update Resource/_history/X but this is not the current version`

**Root Causes**:
- Resource exists with different version than package expects
- Broken search indexes (resource not properly indexed)
- Failed package installation left corrupt data

**Solution**: Use `sql-scripts/remove-ig.sh` to clean database and reinstall package

### 2. Broken Search Indexes

**Symptoms**:
- Resources exist in `HFJ_RESOURCE` but have NO entries in `HFJ_SPIDX_URI`
- Resources not searchable via canonical URL
- Inconsistent search results

**Detection**:
```sql
-- Find resources with no search indexes
SELECT r.res_id, r.fhir_id, r.res_type
FROM HFJ_RESOURCE r
LEFT JOIN HFJ_SPIDX_URI u ON r.res_id = u.res_id
WHERE u.res_id IS NULL
  AND r.res_type IN ('ValueSet', 'CodeSystem', 'StructureDefinition');
```

**Solution**: Trigger HAPI's `$reindex` operation or use removal scripts to clean and reinstall

### 3. Circular Foreign Key Dependencies

**Issue**: `TRM_CODESYSTEM.current_version_pid` creates circular dependency with `TRM_CODESYSTEM_VER`

**Solution**: Always NULL out `current_version_pid` before deleting `TRM_CODESYSTEM_VER`

```sql
UPDATE TRM_CODESYSTEM
SET current_version_pid = NULL
WHERE res_id IN (SELECT res_id FROM resources_to_delete);

DELETE FROM TRM_CODESYSTEM_VER WHERE codesystem_pid IN (...);
DELETE FROM TRM_CODESYSTEM WHERE res_id IN (...);
```

### 4. Failed ValueSet Expansion

**Error**: `FAILED_TO_EXPAND` status in ValueSets

**Causes**:
- Referenced CodeSystem not loaded in cache
- Hibernate Search not initialized
- Missing concepts in terminology cache

**Detection**: Run `sql-scripts/hapi_queries.sql` - Section 3: Production Health Checks

**Solution**:
1. Check if CodeSystem concepts are loaded: `SELECT * FROM TRM_CONCEPT WHERE ...`
2. Delete failed expansions from `TRM_VALUESET` table
3. Restart server to trigger re-expansion

---

## SQL Scripts Guide

All database management scripts are located in `sql-scripts/`. See `sql-scripts/README.md` for detailed usage.

### Quick Reference

| Script | Purpose | Destructive? |
|--------|---------|-------------|
| `hapi_queries.sql` | Diagnostics & health checks | No (read-only) |
| `check-version-conflicts.sql` | Find version conflicts & duplicates | No (read-only) |
| `preview-ig-removal.sql` | Preview what will be deleted | No (read-only) |
| `remove-ig-from-database.sql` | Remove all Koppeltaal resources | **YES** |
| `remove-ig.sh` | User-friendly wrapper for removal | **YES** |

### Database Connection Pattern

```bash
# Set password
export PGPASSWORD=your_password

# Connect
psql -h HOST -p PORT -U USERNAME -d DATABASE -f sql-scripts/SCRIPT.sql
```

**Staging Example**:
```bash
export PGPASSWORD=staging_password
./sql-scripts/remove-ig.sh 35.204.98.239 5432 hapi_fhir_server_staging hapi_fhir_server_staging --yes
```

---

## Package Installation Process

### How HAPI Loads Packages

1. **Startup**: HAPI reads `hapi.fhir.implementationguides` from application.yaml
2. **Download**: Fetches package .tgz from URL or local path
3. **Extract**: Unzips and reads package.json, resources
4. **Cache**: Stores in `NPM_PACKAGE` and `NPM_PACKAGE_VER` tables
5. **Install**: Creates/updates FHIR resources in `HFJ_RESOURCE`
6. **Index**: Builds search indexes in `HFJ_SPIDX_*` tables
7. **Terminology**: Loads CodeSystems/ValueSets into `TRM_*` tables

### Package Detection Methods

When cleaning database, the removal scripts use 4 methods to find ALL Koppeltaal resources:

1. **NPM Package Binaries**: Resources linked via `NPM_PACKAGE_VER_RES.binary_res_id`
2. **Canonical URL**: Resources with URL containing `koppeltaal.nl` or `vzvz.nl` in `HFJ_SPIDX_URI`
3. **FHIR ID Pattern**: Resources with ID matching `KT2%`, `koppeltaal%`, or OID patterns
4. **Broken Indexes**: Recent resources with NO search indexes (likely from failed uploads)

This multi-method approach ensures no orphaned resources are left behind.

---

## Troubleshooting Workflows

### Workflow: Clean Package Reinstall

Use when package is corrupt, search indexes broken, or version conflicts exist:

```bash
# 1. Set credentials
export PGPASSWORD=your_password

# 2. Preview impact (optional)
psql -h HOST -p PORT -U USER -d DB -f sql-scripts/preview-ig-removal.sql

# 3. Remove package
./sql-scripts/remove-ig.sh HOST PORT USER DB --yes

# 4. Restart HAPI server
# Package will auto-reinstall from implementationguides config
```

### Workflow: Investigate Startup Crash

When HAPI crashes on startup with package errors:

```bash
# 1. Check what's in NPM cache
psql -h HOST -p PORT -U USER -d DB -c "
SELECT package_id, cur_version_id, COUNT(*)
FROM npm_package
GROUP BY package_id, cur_version_id;
"

# 2. Look for version conflicts
psql -h HOST -p PORT -U USER -d DB -f sql-scripts/check-version-conflicts.sql

# 3. Clean database if needed
./sql-scripts/remove-ig.sh HOST PORT USER DB --yes
```

### Workflow: Pre-Deployment Health Check

Before deploying new package version:

```bash
# Run comprehensive diagnostics
psql -h HOST -p PORT -U USER -d DB -f sql-scripts/hapi_queries.sql

# Look for:
# - FAILED_TO_EXPAND ValueSets (Section 3, Health Check 3)
# - Missing CodeSystem concepts (Section 3, Health Check 4)
# - Invalid codes (Section 3, Health Check 5)
# - Version conflicts (use check-version-conflicts.sql)
```

---

## Development Tips

### Querying FHIR Resources

```sql
-- Find resource by FHIR ID
SELECT * FROM HFJ_RESOURCE WHERE fhir_id = 'audit-event-type';

-- Get resource with all search indexes
SELECT
    r.res_id, r.fhir_id, r.res_type,
    u.sp_uri as canonical_url
FROM HFJ_RESOURCE r
LEFT JOIN HFJ_SPIDX_URI u ON r.res_id = u.res_id AND u.sp_name = 'url'
WHERE r.fhir_id = 'KT2Patient';

-- Find all Koppeltaal resources by canonical URL
SELECT DISTINCT r.res_type, COUNT(*)
FROM HFJ_SPIDX_URI s
JOIN HFJ_RESOURCE r ON s.res_id = r.res_id
WHERE s.sp_name = 'url'
  AND (s.sp_uri LIKE '%koppeltaal.nl%' OR s.sp_uri LIKE '%vzvz.nl%')
GROUP BY r.res_type;
```

### Checking Terminology Cache

```sql
-- Check if CodeSystem is loaded
SELECT cs.code_system_uri, csv.cs_version_id, COUNT(c.pid) as concept_count
FROM TRM_CODESYSTEM cs
JOIN TRM_CODESYSTEM_VER csv ON cs.current_version_pid = csv.pid
LEFT JOIN TRM_CONCEPT c ON csv.pid = c.codesystem_pid
WHERE cs.code_system_uri LIKE '%koppeltaal%'
GROUP BY cs.code_system_uri, csv.cs_version_id;

-- Check ValueSet expansion status
SELECT
    vs.url,
    vs.expansion_status,
    COUNT(vsc.pid) as expanded_concept_count
FROM TRM_VALUESET vs
LEFT JOIN TRM_VALUESET_CONCEPT vsc ON vs.pid = vsc.valueset_pid
WHERE vs.url LIKE '%koppeltaal%'
GROUP BY vs.url, vs.expansion_status;
```

---

## Important Configuration

### Package Auto-Load Config

Check `application.yaml` or environment variables:

```yaml
hapi:
  fhir:
    implementationguides:
      koppeltaalv2:
        name: koppeltaalv2.00
        version: 0.15.0-beta.7d
        url: https://github.com/vzvznl/Koppeltaal-2.0-FHIR/releases/download/v0.15.0-beta.7d/koppeltaalv2-0.15.0-beta.7d-minimal.tgz
```

### Database Connection

Environment variables or application.yaml:
- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`

---

## Best Practices

1. **Always Preview**: Run `preview-ig-removal.sql` before destructive operations
2. **Test on Staging**: Test database scripts on staging before production
3. **Backup First**: Create database backup before running removal scripts
4. **Use Wrapper Script**: Prefer `remove-ig.sh` over direct SQL for safety prompts
5. **Check Health**: Run `hapi_queries.sql` before and after package updates
6. **Monitor Logs**: Watch HAPI logs during package installation for errors
7. **Case Sensitivity**: Package IDs may use different casing (`koppeltaalv2.00` vs `Koppeltaalv2.00`)

---

## Resources

- **HAPI FHIR Docs**: https://hapifhir.io/hapi-fhir/docs/
- **PostgreSQL Docs**: https://www.postgresql.org/docs/current/
- **Koppeltaal 2.0 IG**: https://github.com/vzvznl/Koppeltaal-2.0-FHIR
- **FHIR R4 Spec**: http://hl7.org/fhir/R4/

---

## Quick Commands Cheat Sheet

```bash
# Connect to database
export PGPASSWORD=password
psql -h host -p 5432 -U username -d database

# Run diagnostics
psql -h host -p 5432 -U username -d database -f sql-scripts/hapi_queries.sql

# Preview package removal
psql -h host -p 5432 -U username -d database -f sql-scripts/preview-ig-removal.sql

# Remove package (safe with prompt)
./sql-scripts/remove-ig.sh host 5432 username database

# Remove package (skip prompt)
./sql-scripts/remove-ig.sh host 5432 username database --yes

# Check NPM packages
psql -h host -p 5432 -U username -d database -c "SELECT package_id, cur_version_id FROM npm_package;"

# Count Koppeltaal resources
psql -h host -p 5432 -U username -d database -c "
SELECT r.res_type, COUNT(*)
FROM HFJ_RESOURCE r
JOIN HFJ_SPIDX_URI s ON r.res_id = s.res_id
WHERE s.sp_uri LIKE '%koppeltaal.nl%'
GROUP BY r.res_type;"
```
