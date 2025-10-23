-- ==============================================================================
-- Preview ALL Koppeltaal/VZVZ Package Resources Removal
-- ==============================================================================
-- Purpose: Show what will be deleted without actually deleting
-- Database: HAPI FHIR Server (PostgreSQL)
-- Version: 2.0
-- Last Updated: 2025-10-23
--
-- SAFE TO RUN: This script only performs SELECT queries (read-only)
-- ==============================================================================

\echo '======================================================================'
\echo '  Preview: Koppeltaal/VZVZ Package Removal Impact'
\echo '======================================================================'
\echo ''

-- Find all Koppeltaal/VZVZ resources
\echo '1. Resources to be removed (by type):'
\echo '----------------------------------------------------------------------'

WITH all_koppeltaal_resources AS (
    -- From NPM package
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM npm_package_ver_res npvr
    JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
    JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
    WHERE LOWER(npv.package_id) = 'koppeltaalv2.00'

    UNION

    -- From canonical URL (most reliable)
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_SPIDX_URI s
    JOIN HFJ_RESOURCE r ON s.res_id = r.res_id
    WHERE s.sp_name = 'url'
      AND (s.sp_uri LIKE '%koppeltaal.nl%' OR s.sp_uri LIKE '%vzvz.nl%')

    UNION

    -- From fhir_id pattern
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    WHERE r.fhir_id LIKE 'KT2%'
       OR r.fhir_id LIKE 'koppeltaal%'
       OR r.fhir_id LIKE '%koppeltaal%'
       OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'
)
SELECT
    res_type,
    COUNT(*) as resource_count
FROM all_koppeltaal_resources
GROUP BY res_type
ORDER BY res_type;

\echo ''
\echo 'Sample of resources to be removed:'
\echo '----------------------------------------------------------------------'

WITH all_koppeltaal_resources AS (
    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM npm_package_ver_res npvr
    JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
    JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
    WHERE LOWER(npv.package_id) = 'koppeltaalv2.00'

    UNION

    SELECT DISTINCT r.res_id, r.res_type, r.fhir_id, r.res_updated
    FROM HFJ_RESOURCE r
    WHERE r.fhir_id LIKE 'KT2%'
       OR r.fhir_id LIKE 'koppeltaal%'
       OR r.fhir_id LIKE '%koppeltaal%'
       OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472'
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

-- Create temp table for preview
CREATE TEMP TABLE resources_preview AS
-- METHOD 1: Get resources directly from NPM package (most reliable)
SELECT DISTINCT r.res_id
FROM npm_package_ver_res npvr
JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
WHERE npv.package_id = 'koppeltaalv2.00'

UNION

-- METHOD 2: Also get resources by fhir_id pattern (backup for any orphaned resources)
SELECT DISTINCT r.res_id
FROM HFJ_RESOURCE r
WHERE r.fhir_id LIKE 'KT2%'
   OR r.fhir_id LIKE 'koppeltaal%'
   OR r.fhir_id LIKE '%koppeltaal%'
   OR r.fhir_id LIKE '2.16.840.1.113883.2.4.3.11.22.472';

\echo '2. Rows to be deleted from each table:'
\echo '----------------------------------------------------------------------'

\echo 'HFJ_SPIDX_STRING:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_STRING WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_SPIDX_TOKEN:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_TOKEN WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_SPIDX_DATE:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_DATE WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_SPIDX_NUMBER:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_NUMBER WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_SPIDX_QUANTITY:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_QUANTITY WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_SPIDX_URI:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_SPIDX_URI WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RES_LINK (as source):'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RES_LINK WHERE src_resource_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RES_LINK (as target):'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RES_LINK WHERE target_resource_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RES_TAG:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RES_TAG WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RES_PARAM_PRESENT:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RES_PARAM_PRESENT WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RES_VER:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RES_VER WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_FORCED_ID:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_FORCED_ID WHERE resource_pid IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_HISTORY_TAG:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_HISTORY_TAG WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_IDX_CMP_STRING_UNIQ:'
SELECT COUNT(*) as rows_to_delete FROM HFJ_IDX_CMP_STRING_UNIQ WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo 'HFJ_RESOURCE (main table):'
SELECT COUNT(*) as rows_to_delete FROM HFJ_RESOURCE WHERE res_id IN (SELECT res_id FROM resources_preview);

\echo ''
\echo '3. Total impact:'
\echo '----------------------------------------------------------------------'

SELECT COUNT(*) as total_resources_to_delete FROM resources_preview;

\echo ''
\echo '======================================================================'
\echo '  Preview Complete'
\echo '======================================================================'
\echo ''
\echo 'SCOPE: This will remove ALL Koppeltaal/VZVZ package resources:'
\echo '  - ImplementationGuide'
\echo '  - StructureDefinitions (profiles, extensions)'
\echo '  - CodeSystems'
\echo '  - ValueSets'
\echo '  - SearchParameters'
\echo '  - And any other Koppeltaal/VZVZ resources'
\echo ''
\echo 'To proceed with deletion, run:'
\echo '  ./scripts/remove-ig.sh <host> <port> <username> <database>'
\echo ''
\echo 'After deletion, you can:'
\echo '  1. Rebuild the search index'
\echo '  2. Re-upload the package using sync-fhir-package.py'
\echo ''

-- Clean up temp table
DROP TABLE resources_preview;
