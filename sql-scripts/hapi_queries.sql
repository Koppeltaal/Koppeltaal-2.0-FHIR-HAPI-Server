-- ==============================================================================
-- HAPI FHIR Database Diagnostic Queries for Koppeltaal 2.0
-- ==============================================================================
-- Purpose: Investigate and verify Koppeltaal terminology cache status
-- Database: HAPI FHIR Server (PostgreSQL)
-- Version: 1.0
-- Last Updated: 2025-10-23
--
-- USAGE:
-- - Run these queries to diagnose terminology cache issues
-- - Use SECTION 4 queries for production pre-deployment health checks
-- - Use SECTION 5 queries for post-deployment verification
-- - Queries are safe to run in production (read-only unless marked otherwise)
-- ==============================================================================

-- ==============================================================================
-- SECTION 1: FHIR Resource Queries
-- ==============================================================================

-- Query 1: Check for resources with koppeltaal-expansion canonical URL
SELECT
    r.res_type,
    r.res_id,
    rv.res_ver,
    sp.sp_value_normalized as canonical_url
FROM HFJ_RESOURCE r
JOIN HFJ_RES_VER rv ON r.res_id = rv.res_id AND r.res_ver = rv.res_ver
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND sp.sp_value_normalized LIKE '%koppeltaal-expansion%'
ORDER BY sp.sp_value_normalized, rv.res_ver;

-- Query 2: Check all Koppeltaal/VZVZ CodeSystems and ValueSets
SELECT
    r.res_type,
    r.fhir_id,
    rv.res_ver,
    sp.sp_value_normalized as canonical_url
FROM HFJ_RESOURCE r
JOIN HFJ_RES_VER rv ON r.res_id = rv.res_id AND r.res_ver = rv.res_ver
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND (sp.sp_value_normalized LIKE '%koppeltaal%' OR sp.sp_value_normalized LIKE '%vzvz%')
  AND r.res_type IN ('CodeSystem', 'ValueSet')
ORDER BY r.res_type, sp.sp_value_normalized;

-- ==============================================================================
-- SECTION 2: Terminology Cache Status (KEY DIAGNOSTIC QUERIES)
-- ==============================================================================

-- Query 3a: Check specific ValueSet expansion status
-- Expected: expansion_status = 'EXPANDED', concept_count >= 1
SELECT
    vs.url,
    vs.expansion_status,
    COUNT(vsc.pid) as concept_count
FROM TRM_VALUESET vs
LEFT JOIN TRM_VALUESET_CONCEPT vsc ON vs.pid = vsc.valueset_pid
WHERE vs.url = 'http://vzvz.nl/fhir/ValueSet/koppeltaal-expansion'
GROUP BY vs.url, vs.expansion_status;

-- Query 3b: Check ALL Koppeltaal/VZVZ ValueSet expansion statuses
-- Expected: All should have expansion_status = 'EXPANDED'
-- CRITICAL: Any FAILED_TO_EXPAND status indicates a problem
SELECT
    vs.url,
    vs.expansion_status,
    COUNT(vsc.pid) as concept_count
FROM TRM_VALUESET vs
LEFT JOIN TRM_VALUESET_CONCEPT vsc ON vs.pid = vsc.valueset_pid
WHERE vs.url LIKE '%koppeltaal%' OR vs.url LIKE '%vzvz%'
GROUP BY vs.url, vs.expansion_status
ORDER BY vs.expansion_status, vs.url;

-- Query 3c: Check ALL failed ValueSet expansions (any system)
-- Use this for comprehensive system health check
SELECT
    vs.url,
    vs.expansion_status,
    COUNT(vsc.pid) as concept_count
FROM TRM_VALUESET vs
LEFT JOIN TRM_VALUESET_CONCEPT vsc ON vs.pid = vsc.valueset_pid
WHERE vs.expansion_status = 'FAILED_TO_EXPAND'
GROUP BY vs.url, vs.expansion_status
ORDER BY vs.url;

-- Query 4: Check CodeSystem concepts in terminology cache
-- Expected: 1 concept (026-RolvdNaaste)
SELECT
    c.codeval as code,
    c.display,
    cs.code_system_uri,
    csv.cs_version_id
FROM TRM_CONCEPT c
JOIN TRM_CODESYSTEM_VER csv ON c.codesystem_pid = csv.pid
JOIN TRM_CODESYSTEM cs ON csv.codesystem_pid = cs.pid
WHERE cs.code_system_uri = 'http://vzvz.nl/fhir/CodeSystem/koppeltaal-expansion';

-- Query 5: Check CodeSystem version and concept count
SELECT
    cs.code_system_uri,
    csv.cs_version_id,
    COUNT(c.pid) as concept_count
FROM TRM_CODESYSTEM cs
LEFT JOIN TRM_CODESYSTEM_VER csv ON cs.pid = csv.codesystem_pid
LEFT JOIN TRM_CONCEPT c ON csv.pid = c.codesystem_pid
WHERE cs.code_system_uri = 'http://vzvz.nl/fhir/CodeSystem/koppeltaal-expansion'
GROUP BY cs.code_system_uri, csv.cs_version_id;

-- Query 6: Check for INVALID codes in terminology cache
-- Expected after fix: 0 rows (no INVALID codes should exist)
SELECT
    c.codeval as code,
    c.display,
    cs.code_system_uri
FROM TRM_CONCEPT c
JOIN TRM_CODESYSTEM_VER csv ON c.codesystem_pid = csv.pid
JOIN TRM_CODESYSTEM cs ON csv.codesystem_pid = cs.pid
WHERE c.codeval = 'INVALID'
   OR c.codeval LIKE '%026-RolvdNaaste%'
   OR cs.code_system_uri LIKE '%koppeltaal-expansion%';

-- ==============================================================================
-- SECTION 3: Production Health Checks
-- ==============================================================================
-- Run these queries before deploying to production to verify system health

-- Health Check 1: Verify all Koppeltaal/VZVZ CodeSystems are loaded
-- Expected: Should return entries for all package CodeSystems
SELECT
    r.res_type,
    r.fhir_id,
    rv.res_ver as version,
    sp.sp_value_normalized as canonical_url
FROM HFJ_RESOURCE r
JOIN HFJ_RES_VER rv ON r.res_id = rv.res_id AND r.res_ver = rv.res_ver
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND r.res_type = 'CodeSystem'
  AND (sp.sp_value_normalized LIKE '%koppeltaal%' OR sp.sp_value_normalized LIKE '%vzvz%')
ORDER BY sp.sp_value_normalized;

-- Health Check 2: Verify all Koppeltaal/VZVZ ValueSets are loaded
-- Expected: Should return entries for all package ValueSets
SELECT
    r.res_type,
    r.fhir_id,
    rv.res_ver as version,
    sp.sp_value_normalized as canonical_url
FROM HFJ_RESOURCE r
JOIN HFJ_RES_VER rv ON r.res_id = rv.res_id AND r.res_ver = rv.res_ver
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND r.res_type = 'ValueSet'
  AND (sp.sp_value_normalized LIKE '%koppeltaal%' OR sp.sp_value_normalized LIKE '%vzvz%')
ORDER BY sp.sp_value_normalized;

-- Health Check 3: Check for ANY failed ValueSet expansions
-- Expected: 0 rows (no failures)
-- CRITICAL: Any results indicate terminology cache issues
SELECT
    vs.url,
    vs.expansion_status,
    COUNT(vsc.pid) as concept_count
FROM TRM_VALUESET vs
LEFT JOIN TRM_VALUESET_CONCEPT vsc ON vs.pid = vsc.valueset_pid
WHERE vs.expansion_status = 'FAILED_TO_EXPAND'
GROUP BY vs.url, vs.expansion_status
ORDER BY vs.url;

-- Health Check 4: Verify CodeSystem concepts are loaded in cache
-- Expected: Should show all Koppeltaal/VZVZ CodeSystems with their concept counts
SELECT
    cs.code_system_uri,
    csv.cs_version_id,
    COUNT(c.pid) as concept_count
FROM TRM_CODESYSTEM cs
LEFT JOIN TRM_CODESYSTEM_VER csv ON cs.pid = csv.codesystem_pid
LEFT JOIN TRM_CONCEPT c ON csv.pid = c.codesystem_pid
WHERE cs.code_system_uri LIKE '%koppeltaal%' OR cs.code_system_uri LIKE '%vzvz%'
GROUP BY cs.code_system_uri, csv.cs_version_id
ORDER BY cs.code_system_uri;

-- Health Check 5: Check for invalid or unexpected codes in cache
-- Expected: 0 rows with 'INVALID' code
SELECT
    c.codeval as code,
    c.display,
    cs.code_system_uri,
    csv.cs_version_id
FROM TRM_CONCEPT c
JOIN TRM_CODESYSTEM_VER csv ON c.codesystem_pid = csv.pid
JOIN TRM_CODESYSTEM cs ON csv.codesystem_pid = cs.pid
WHERE c.codeval = 'INVALID'
  AND (cs.code_system_uri LIKE '%koppeltaal%' OR cs.code_system_uri LIKE '%vzvz%');

-- ==============================================================================
-- SECTION 4: Post-Deployment Verification
-- ==============================================================================
-- Run these queries after deploying to production to verify successful deployment

-- Verification 1: Check deployed package version
-- Replace ${EXPECTED_VERSION} with the version you're deploying (e.g., '0.15.0-beta.7d')
SELECT
    r.res_type,
    r.fhir_id,
    rv.res_ver as version,
    sp.sp_value_normalized as canonical_url
FROM HFJ_RESOURCE r
JOIN HFJ_RES_VER rv ON r.res_id = rv.res_id AND r.res_ver = rv.res_ver
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND r.res_type IN ('ImplementationGuide', 'StructureDefinition', 'CodeSystem', 'ValueSet')
  AND (sp.sp_value_normalized LIKE '%koppeltaal%' OR sp.sp_value_normalized LIKE '%vzvz%')
  -- AND rv.res_ver = '${EXPECTED_VERSION}'  -- Uncomment and replace ${EXPECTED_VERSION}
ORDER BY r.res_type, sp.sp_value_normalized;

-- Verification 2: Count resources by type and verify expected counts
SELECT
    r.res_type,
    COUNT(*) as resource_count
FROM HFJ_RESOURCE r
JOIN HFJ_SPIDX_STRING sp ON r.res_id = sp.res_id
WHERE sp.sp_name = 'url'
  AND (sp.sp_value_normalized LIKE '%koppeltaal%' OR sp.sp_value_normalized LIKE '%vzvz%')
GROUP BY r.res_type
ORDER BY r.res_type;

-- Verification 3: Ensure no FAILED_TO_EXPAND status after deployment
-- Expected: 0 rows
SELECT
    vs.url,
    vs.expansion_status
FROM TRM_VALUESET vs
WHERE vs.expansion_status = 'FAILED_TO_EXPAND'
  AND (vs.url LIKE '%koppeltaal%' OR vs.url LIKE '%vzvz%');

-- ==============================================================================
-- SECTION 5: Fix Commands (USE WITH CAUTION - WRITE OPERATIONS)
-- ==============================================================================

-- Fix 1: Delete specific failed ValueSet expansion
-- This forces HAPI to re-expand the ValueSet on next validation request
-- DELETE FROM TRM_VALUESET WHERE url = 'http://vzvz.nl/fhir/ValueSet/koppeltaal-expansion';

-- Fix 2: Delete ALL failed Koppeltaal/VZVZ ValueSet expansions
-- Use this when multiple ValueSets have FAILED_TO_EXPAND status
-- Must delete concepts first due to foreign key constraint
/*
DELETE FROM TRM_VALUESET_CONCEPT
WHERE valueset_pid IN (
    SELECT pid FROM TRM_VALUESET
    WHERE expansion_status = 'FAILED_TO_EXPAND'
      AND (url LIKE '%koppeltaal%' OR url LIKE '%vzvz%')
);

DELETE FROM TRM_VALUESET
WHERE expansion_status = 'FAILED_TO_EXPAND'
  AND (url LIKE '%koppeltaal%' OR url LIKE '%vzvz%');
*/

-- After deleting, validation should trigger automatic re-expansion
-- Alternatively, manually trigger expansion via FHIR API:
-- curl -X GET "https://FHIR_SERVER_URL/fhir/DEFAULT/ValueSet/koppeltaal-expansion/\$expand"

-- ==============================================================================
-- SECTION 6: Connection Examples
-- ==============================================================================

-- STAGING Environment:
-- export PGPASSWORD=your_password
-- psql -h staging_host -p 5432 -U hapi_fhir_server_staging -d hapi_fhir_server_staging -f hapi_queries.sql

-- PRODUCTION Environment:
-- export PGPASSWORD=your_password
-- psql -h production_host -p 5432 -U hapi_fhir_server_production -d hapi_fhir_server_production -f hapi_queries.sql

-- Interactive connection:
-- psql -h host -p 5432 -U username -d database_name

-- ==============================================================================
-- TROUBLESHOOTING GUIDE
-- ==============================================================================

-- Problem: FAILED_TO_EXPAND status in terminology cache
-- Symptoms:
--   - ValueSets show expansion_status = 'FAILED_TO_EXPAND'
--   - Validation accepts invalid codes (due to permissive fallback behavior)
--   - Server logs show: "ValueSet.url[...] is present in terminology tables
--     but not ready for persistence-backed invocation"
--
-- Root Cause:
--   - ValueSet expansion failed during pre-processing
--   - Cached failure prevents re-expansion attempts
--
-- Solution:
--   1. Run Health Check 3 to identify failed expansions
--   2. Use Fix 2 to delete failed expansion entries
--   3. Trigger re-expansion via validation or $expand operation
--   4. Run Verification 3 to confirm fix

-- Problem: Hibernate Search not initialized
-- Symptoms:
--   - Error: "HSEARCH800001: Hibernate Search was not initialized"
--   - $expand operations fail with 503 error
--   - Validation shows invalid codes as warnings instead of errors
--
-- Impact:
--   - Prevents $expand operations
--   - Prevents persistence-backed validation
--   - HAPI falls back to in-memory validation
--
-- Workaround:
--   - In-memory validation still detects invalid codes (as warnings)
--   - Not a data issue - infrastructure/configuration issue
--
-- Long-term Fix:
--   - Address Hibernate Search configuration in HAPI deployment
--   - Consult HAPI FHIR 8.2.0 documentation for Hibernate Search setup

-- Problem: Invalid codes in production data
-- Symptoms:
--   - Query 6 or Health Check 5 returns rows with 'INVALID' code
--   - Data created before validation was fixed
--
-- Solution:
--   - Identify affected resources (e.g., ActivityDefinitions)
--   - Coordinate with data owners to correct or remove invalid resources
--   - Use FHIR API to update resources with valid codes

-- ==============================================================================
-- KNOWN ISSUES (As of 2025-10-23)
-- ==============================================================================
--
-- Issue 1: Hibernate Search not initialized (STAGING)
-- Status: Open
-- Server: HAPI FHIR 8.2.0
-- Error: "HSEARCH800001: Hibernate Search was not initialized"
-- Impact: $expand operations fail, validation shows warnings instead of errors
-- Workaround: In-memory validation still works
--
-- Issue 2: Historical invalid codes in ActivityDefinitions (STAGING)
-- Status: Identified - Awaiting cleanup
-- Impact: 16 ActivityDefinitions contain 'INVALID' code from before fix
-- Resolution: Coordinate cleanup with data owners
--
-- ==============================================================================
-- VERSION HISTORY
-- ==============================================================================
-- v1.0 (2025-10-23): Initial comprehensive production-ready version
--   - Added production health checks (Section 3)
--   - Added post-deployment verification (Section 4)
--   - Added troubleshooting guide
--   - Documented known issues
--   - Added connection examples
