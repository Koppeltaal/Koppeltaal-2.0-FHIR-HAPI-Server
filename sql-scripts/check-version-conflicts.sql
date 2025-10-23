-- ==============================================================================
-- Check for Resource Version Conflicts
-- ==============================================================================
-- Purpose: Find resources with version tracking issues
-- ==============================================================================

\echo '======================================================================'
\echo '  Resource Version Conflict Detection'
\echo '======================================================================'
\echo ''

-- Check audit-event-type specifically
\echo '1. Check audit-event-type resource versions:'
\echo '----------------------------------------------------------------------'

SELECT
    r.res_id,
    r.fhir_id,
    r.res_type,
    r.res_version,
    r.res_updated,
    r.res_deleted_at
FROM HFJ_RESOURCE r
WHERE r.fhir_id = 'audit-event-type'
ORDER BY r.res_version;

\echo ''
\echo '2. Check audit-event-type version history:'
\echo '----------------------------------------------------------------------'

SELECT
    v.pid,
    v.res_id,
    v.res_type,
    v.res_ver,
    v.res_updated,
    v.has_tags
FROM HFJ_RES_VER v
JOIN HFJ_RESOURCE r ON v.res_id = r.res_id
WHERE r.fhir_id = 'audit-event-type'
ORDER BY v.res_ver;

\echo ''
\echo '3. Check for duplicate resources with same fhir_id:'
\echo '----------------------------------------------------------------------'

SELECT
    fhir_id,
    COUNT(*) as duplicate_count,
    STRING_AGG(res_id::text, ', ') as res_ids
FROM HFJ_RESOURCE
GROUP BY fhir_id
HAVING COUNT(*) > 1
ORDER BY duplicate_count DESC
LIMIT 20;

\echo ''
\echo '4. Check all nictiz package resources:'
\echo '----------------------------------------------------------------------'

SELECT
    r.res_type,
    COUNT(*) as count
FROM npm_package_ver_res npvr
JOIN npm_package_ver npv ON npvr.packver_pid = npv.pid
JOIN HFJ_RESOURCE r ON npvr.binary_res_id = r.res_id
WHERE npv.package_id LIKE 'nictiz%'
GROUP BY r.res_type
ORDER BY r.res_type;

\echo ''
\echo '5. Check NPM packages currently in database:'
\echo '----------------------------------------------------------------------'

SELECT
    np.package_id,
    np.cur_version_id,
    COUNT(npvr.pid) as resource_count
FROM npm_package np
LEFT JOIN npm_package_ver npv ON np.pid = npv.package_pid
LEFT JOIN npm_package_ver_res npvr ON npv.pid = npvr.packver_pid
GROUP BY np.package_id, np.cur_version_id
ORDER BY np.package_id;

\echo ''
\echo '======================================================================'
\echo '  Analysis Complete'
\echo '======================================================================'
\echo ''
