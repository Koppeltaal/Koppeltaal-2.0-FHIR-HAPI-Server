-- ==============================================================================
-- Remove ALL Koppeltaal/VZVZ Package Resources from HAPI FHIR Database
-- ==============================================================================
-- Purpose: Fully remove all Koppeltaal/VZVZ resources from database to allow
--          search index restoration
-- Database: HAPI FHIR Server (PostgreSQL)
-- Version: 2.0
-- Last Updated: 2025-10-23
--
-- WARNING: This script performs DELETE operations. Review carefully before running.
-- RECOMMENDATION: Create a database backup before running this script.
--
-- SCOPE: Removes ALL resources from the Koppeltaal 2.0 package:
--   - ImplementationGuide
--   - StructureDefinition (profiles, extensions)
--   - CodeSystem
--   - ValueSet
--   - SearchParameter
--   - ActivityDefinition
--   - Any other resources with koppeltaal/vzvz URLs
--
-- USAGE:
-- export PGPASSWORD=your_password
-- psql -h host -p 5432 -U username -d database_name -f remove-ig-from-database.sql
-- ==============================================================================

-- ==============================================================================
-- STEP 1: Identify ALL Koppeltaal/VZVZ resources to remove
-- ==============================================================================

\echo '=== STEP 1: Identifying all Koppeltaal/VZVZ resources ===='

-- Find all resources to be removed (from NPM package + fhir_id pattern)
\echo ''
\echo 'Resources to be removed (by type):'

WITH all_koppeltaal_resources AS (
    -- Method 1: From NPM package Binaries
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM npm_package_ver_res npvr
    JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
    JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
    WHERE LOWER(npv.package_id) = 'koppeltaalv2.00'

    UNION

    -- Method 2: From canonical URL (most reliable for FHIR resources)
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_SPIDX_URI s
    JOIN HFJ_RESOURCE r ON s.res_id = r.res_id
    WHERE s.sp_name = 'url'
      AND (s.sp_uri LIKE '%koppeltaal.nl%' OR s.sp_uri LIKE '%vzvz.nl%')

    UNION

    -- Method 3: From fhir_id pattern (catches orphaned resources)
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    WHERE r.fhir_id LIKE 'KT2%'
       OR r.fhir_id LIKE 'koppeltaal%'
       OR r.fhir_id LIKE '%koppeltaal%'
       OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'

    UNION

    -- Method 4: Recently updated resources with broken indexes
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    LEFT JOIN HFJ_SPIDX_URI u ON r.res_id = u.res_id
    WHERE r.res_updated > '2025-10-01'
      AND u.res_id IS NULL
      AND r.res_type IN ('ValueSet', 'CodeSystem', 'StructureDefinition', 'SearchParameter', 'ActivityDefinition', 'NamingSystem')
      AND r.fhir_id IN (
        'audit-event-type', 'endpoint-connection-type',
        'trace-id', 'task-instantiates', 'resource-origin-extension',
        'request-id', 'publisherId-extension', 'participant', 'correlation-id',
        'resource-origin', 'instantiates'
      )
)
SELECT
    res_type,
    COUNT(*) as resource_count
FROM all_koppeltaal_resources
GROUP BY res_type
ORDER BY res_type;

\echo ''
\echo 'Sample resources to be removed:'

WITH all_koppeltaal_resources AS (
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM npm_package_ver_res npvr
    JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
    JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
    WHERE LOWER(npv.package_id) = 'koppeltaalv2.00'

    UNION

    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_SPIDX_URI s
    JOIN HFJ_RESOURCE r ON s.res_id = r.res_id
    WHERE s.sp_name = 'url'
      AND (s.sp_uri LIKE '%koppeltaal.nl%' OR s.sp_uri LIKE '%vzvz.nl%')

    UNION

    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    WHERE r.fhir_id LIKE 'KT2%'
       OR r.fhir_id LIKE 'koppeltaal%'
       OR r.fhir_id LIKE '%koppeltaal%'
       OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'

    UNION

    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    LEFT JOIN HFJ_SPIDX_URI u ON r.res_id = u.res_id
    WHERE r.res_updated > '2025-10-01'
      AND u.res_id IS NULL
      AND r.res_type IN ('ValueSet', 'CodeSystem', 'StructureDefinition', 'SearchParameter', 'ActivityDefinition', 'NamingSystem')
      AND r.fhir_id IN (
        'audit-event-type', 'endpoint-connection-type',
        'trace-id', 'task-instantiates', 'resource-origin-extension',
        'request-id', 'publisherId-extension', 'participant', 'correlation-id',
        'resource-origin', 'instantiates'
      )
)
SELECT
    res_id,
    fhir_id,
    res_type,
    res_updated as last_updated
FROM all_koppeltaal_resources
ORDER BY res_type, fhir_id
LIMIT 20;

\echo ''

-- ==============================================================================
-- STEP 2: Create temporary table with all resource IDs to delete
-- ==============================================================================

\echo '=== STEP 2: Creating list of resources to delete ===='

-- This creates a temporary table with all res_id values to delete
-- METHOD 1: Get resources directly from NPM package (most reliable)
CREATE TEMP TABLE resources_to_delete AS
SELECT DISTINCT r.res_id, r.res_type, r.fhir_id
FROM npm_package_ver_res npvr
JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
WHERE LOWER(npv.package_id) = 'koppeltaalv2.00'

UNION

-- METHOD 2: Get resources by canonical URL (MOST IMPORTANT - catches all FHIR resources)
SELECT DISTINCT r.res_id, r.res_type, r.fhir_id
FROM HFJ_SPIDX_URI s
JOIN HFJ_RESOURCE r ON s.res_id = r.res_id
WHERE s.sp_name = 'url'
  AND (s.sp_uri LIKE '%koppeltaal.nl%' OR s.sp_uri LIKE '%vzvz.nl%')

UNION

-- METHOD 3: Get resources by fhir_id pattern (backup for any orphaned resources)
SELECT DISTINCT r.res_id, r.res_type, r.fhir_id
FROM HFJ_RESOURCE r
WHERE r.fhir_id LIKE 'KT2%'
   OR r.fhir_id LIKE 'koppeltaal%'
   OR r.fhir_id LIKE '%koppeltaal%'
   OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'

UNION

-- METHOD 4: Get recently updated resources with broken indexes (no URI index)
-- These are likely from failed package uploads
SELECT DISTINCT r.res_id, r.res_type, r.fhir_id
FROM HFJ_RESOURCE r
LEFT JOIN HFJ_SPIDX_URI u ON r.res_id = u.res_id
WHERE r.res_updated > '2025-10-01'
  AND u.res_id IS NULL
  AND r.res_type IN ('ValueSet', 'CodeSystem', 'StructureDefinition', 'SearchParameter', 'ActivityDefinition', 'NamingSystem')
  AND r.fhir_id IN (
    'audit-event-type', 'endpoint-connection-type',
    'trace-id', 'task-instantiates', 'resource-origin-extension',
    'request-id', 'publisherId-extension', 'participant', 'correlation-id',
    'resource-origin', 'instantiates'
  );

\echo ''
\echo 'Total resources to delete:'
SELECT res_type, COUNT(*) as count
FROM resources_to_delete
GROUP BY res_type
ORDER BY res_type;

\echo ''
\echo 'Grand total:'
SELECT COUNT(*) as total_resources FROM resources_to_delete;

\echo ''

-- ==============================================================================
-- STEP 3: Delete from dependent tables (foreign key constraints)
-- ==============================================================================

\echo '=== STEP 3: Deleting from dependent tables ===='

-- Delete from terminology cache tables FIRST (they reference HFJ_RESOURCE)
-- Must delete in cascade order: deepest children first
\echo 'Deleting from terminology cache tables...'

-- ValueSet-related deletions
\echo '  Deleting TRM_VALUESET_C_DESIGNATION...'
DELETE FROM TRM_VALUESET_C_DESIGNATION
WHERE valueset_concept_pid IN (
    SELECT pid FROM TRM_VALUESET_CONCEPT
    WHERE valueset_pid IN (
        SELECT pid FROM TRM_VALUESET
        WHERE res_id IN (SELECT res_id FROM resources_to_delete)
    )
);

\echo '  Deleting TRM_VALUESET_CONCEPT...'
DELETE FROM TRM_VALUESET_CONCEPT
WHERE valueset_pid IN (
    SELECT pid FROM TRM_VALUESET
    WHERE res_id IN (SELECT res_id FROM resources_to_delete)
);

\echo '  Deleting TRM_VALUESET...'
DELETE FROM TRM_VALUESET
WHERE res_id IN (SELECT res_id FROM resources_to_delete);

-- CodeSystem concept-related deletions (deepest level first)
\echo '  Deleting TRM_CONCEPT_DESIG...'
DELETE FROM TRM_CONCEPT_DESIG
WHERE concept_pid IN (
    SELECT pid FROM TRM_CONCEPT
    WHERE codesystem_pid IN (
        SELECT pid FROM TRM_CODESYSTEM_VER
        WHERE codesystem_pid IN (
            SELECT pid FROM TRM_CODESYSTEM
            WHERE res_id IN (SELECT res_id FROM resources_to_delete)
        )
    )
);

\echo '  Deleting TRM_CONCEPT_PROPERTY...'
DELETE FROM TRM_CONCEPT_PROPERTY
WHERE concept_pid IN (
    SELECT pid FROM TRM_CONCEPT
    WHERE codesystem_pid IN (
        SELECT pid FROM TRM_CODESYSTEM_VER
        WHERE codesystem_pid IN (
            SELECT pid FROM TRM_CODESYSTEM
            WHERE res_id IN (SELECT res_id FROM resources_to_delete)
        )
    )
);

\echo '  Deleting TRM_CONCEPT_PC_LINK...'
DELETE FROM TRM_CONCEPT_PC_LINK
WHERE codesystem_pid IN (
    SELECT pid FROM TRM_CODESYSTEM
    WHERE res_id IN (SELECT res_id FROM resources_to_delete)
);

\echo '  Deleting TRM_CONCEPT...'
DELETE FROM TRM_CONCEPT
WHERE codesystem_pid IN (
    SELECT pid FROM TRM_CODESYSTEM_VER
    WHERE codesystem_pid IN (
        SELECT pid FROM TRM_CODESYSTEM
        WHERE res_id IN (SELECT res_id FROM resources_to_delete)
    )
);

\echo '  Clearing TRM_CODESYSTEM.current_version_pid (circular ref)...'
UPDATE TRM_CODESYSTEM
SET current_version_pid = NULL
WHERE res_id IN (SELECT res_id FROM resources_to_delete);

\echo '  Deleting TRM_CODESYSTEM_VER...'
DELETE FROM TRM_CODESYSTEM_VER
WHERE codesystem_pid IN (
    SELECT pid FROM TRM_CODESYSTEM
    WHERE res_id IN (SELECT res_id FROM resources_to_delete)
);

\echo '  Deleting TRM_CODESYSTEM...'
DELETE FROM TRM_CODESYSTEM
WHERE res_id IN (SELECT res_id FROM resources_to_delete);

-- Delete from NPM package cache (CRITICAL - must happen before HFJ_RESOURCE deletion)
\echo 'Deleting from NPM package cache...'
\echo '  This prevents HAPI from trying to reinstall the package on startup.'

\echo '  Deleting NPM_PACKAGE_VER_RES...'
DELETE FROM npm_package_ver_res
WHERE packver_pid IN (
    SELECT pid FROM npm_package_ver WHERE LOWER(package_id) = 'koppeltaalv2.00'
);

\echo '  Deleting NPM_PACKAGE_VER...'
DELETE FROM npm_package_ver WHERE LOWER(package_id) = 'koppeltaalv2.00';

\echo '  Deleting NPM_PACKAGE...'
DELETE FROM npm_package WHERE LOWER(package_id) = 'koppeltaalv2.00';

-- Delete from search parameter indexes
\echo 'Deleting from search parameter indexes...'
DELETE FROM HFJ_SPIDX_STRING WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_TOKEN WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_DATE WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_NUMBER WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_QUANTITY WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_QUANTITY_NRML WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_COORDS WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_SPIDX_URI WHERE res_id IN (SELECT res_id FROM resources_to_delete);

-- Delete from resource links
\echo 'Deleting from resource links...'
DELETE FROM HFJ_RES_LINK WHERE src_resource_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_RES_LINK WHERE target_resource_id IN (SELECT res_id FROM resources_to_delete);

-- Delete from other tables
\echo 'Deleting from other tables...'
DELETE FROM HFJ_RES_TAG WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_RES_PARAM_PRESENT WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_RES_VER_PROV WHERE res_pid IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_RES_VER WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_FORCED_ID WHERE resource_pid IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_HISTORY_TAG WHERE res_id IN (SELECT res_id FROM resources_to_delete);
DELETE FROM HFJ_IDX_CMP_STRING_UNIQ WHERE res_id IN (SELECT res_id FROM resources_to_delete);

\echo ''

-- ==============================================================================
-- STEP 4: Delete from main resource table
-- ==============================================================================

\echo '=== STEP 4: Deleting from main resource table ===='

DELETE FROM HFJ_RESOURCE WHERE res_id IN (SELECT res_id FROM resources_to_delete);

\echo ''

-- ==============================================================================
-- STEP 5: Verify deletion
-- ==============================================================================

\echo '=== STEP 5: Verifying deletion ===='

-- Count remaining Koppeltaal resources
\echo ''
\echo 'Remaining Koppeltaal/VZVZ resources (should be 0):'
SELECT COUNT(*) as remaining_count
FROM HFJ_RESOURCE r
WHERE r.fhir_id LIKE 'KT2%'
   OR r.fhir_id LIKE 'koppeltaal%'
   OR r.fhir_id LIKE '%koppeltaal%'
   OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472';

\echo ''
\echo 'Expected: remaining_count = 0'
\echo ''

-- Show any remaining resources by type
\echo 'Remaining resources by type (should be empty):'
SELECT
    r.res_type,
    COUNT(*) as count
FROM HFJ_RESOURCE r
WHERE r.fhir_id LIKE 'KT2%'
   OR r.fhir_id LIKE 'koppeltaal%'
   OR r.fhir_id LIKE '%koppeltaal%'
   OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'
GROUP BY r.res_type
ORDER BY r.res_type;

\echo ''

-- Show sample of any remaining resources
\echo 'Sample of remaining resources (should be empty):'
SELECT
    r.res_id,
    r.fhir_id,
    r.res_type,
    r.res_updated as last_updated
FROM HFJ_RESOURCE r
WHERE r.fhir_id LIKE 'KT2%'
   OR r.fhir_id LIKE 'koppeltaal%'
   OR r.fhir_id LIKE '%koppeltaal%'
   OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'
ORDER BY r.res_type, r.fhir_id
LIMIT 10;

\echo ''

-- Clean up temp table
DROP TABLE resources_to_delete;

\echo ''
\echo '======================================================================'
\echo '  DELETION COMPLETE'
\echo '======================================================================'
\echo ''
\echo 'All Koppeltaal/VZVZ package resources have been removed from database.'
\echo ''
\echo 'Next steps:'
\echo '  1. Restore/rebuild the search index'
\echo '  2. Re-upload the package using sync-fhir-package.py:'
\echo ''
\echo 'Example:'
\echo '  python3 scripts/sync-fhir-package.py sync SERVER_URL PACKAGE_URL --yes'
\echo ''
