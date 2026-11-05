package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Finds candidate subjects without loading every Task body; each cycle is reread under its patient lock. */
public class DeleteFlowInventory {

	private static final String RUNNING_PATIENTS = """
			SELECT DISTINCT p.fhir_id
			FROM hfj_resource t
			JOIN hfj_res_link l ON l.src_resource_id = t.res_id
			  AND l.src_path = 'Task.for' AND l.target_resource_type = 'Patient'
			JOIN hfj_resource p ON p.res_id = l.target_resource_id AND p.res_type = 'Patient'
			WHERE t.res_type = 'Task' AND t.res_deleted_at IS NULL
			  AND EXISTS (SELECT 1 FROM hfj_spidx_token c WHERE c.res_id = t.res_id
			    AND c.res_type = 'Task' AND c.sp_name = 'code'
			    AND c.sp_system = :system AND c.sp_value = :code)
			  AND EXISTS (SELECT 1 FROM hfj_spidx_token s WHERE s.res_id = t.res_id
			    AND s.res_type = 'Task' AND s.sp_name = 'status'
			    AND s.sp_value IN ('requested', 'on-hold', 'accepted'))
			ORDER BY p.fhir_id
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public DeleteFlowInventory(DataSource dataSource) {
		jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	public List<String> patientsWithARunningCycle() {
		return jdbc.queryForList(RUNNING_PATIENTS, Map.of(
				"system", DeleteFlowTaskUtil.TASK_CODE_SYSTEM,
				"code", DeleteFlowTaskUtil.DELETE_PENDING_CODE), String.class);
	}
}
