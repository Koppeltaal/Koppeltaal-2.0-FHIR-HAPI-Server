package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the inventory SQL against isolated synthetic tables, without a mocked bundle provider. */
@EnabledIfSystemProperty(named = "cleanup.postgres.tests", matches = "true")
class DeleteFlowInventoryPostgresTest {

	private final String schema = "kt2_inventory_test_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate admin;
	private JdbcTemplate sql;
	private DeleteFlowInventory inventory;

	@BeforeEach
	void createFixtureSchema() {
		String url = System.getProperty("cleanup.jdbc.url",
				"jdbc:postgresql://127.0.0.1:5432/hapi_fhir_server_prd_restore");
		String user = System.getProperty("cleanup.jdbc.user", System.getProperty("user.name"));
		admin = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
		admin.execute("CREATE SCHEMA " + schema);
		var dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
		sql = new JdbcTemplate(dataSource);
		inventory = new DeleteFlowInventory(dataSource);
		sql.execute("CREATE TABLE hfj_resource (res_id bigint PRIMARY KEY, res_type text, fhir_id text, res_deleted_at timestamp)");
		sql.execute("CREATE TABLE hfj_res_link (src_resource_id bigint, target_resource_id bigint, src_path text, target_resource_type text)");
		sql.execute("CREATE TABLE hfj_spidx_token (res_id bigint, res_type text, sp_name text, sp_system text, sp_value text)");
	}

	@AfterEach
	void dropFixtureSchema() {
		if (admin != null) {
			admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void includesEveryPatientBeyondBothFormerPagingBoundariesWithoutDuplicates() {
		sql.execute("INSERT INTO hfj_resource SELECT n, 'Patient', 'patient-' || n, NULL FROM generate_series(1,750) n");
		sql.execute("INSERT INTO hfj_resource SELECT 1000+n, 'Task', 'task-' || n, NULL FROM generate_series(1,1500) n");
		sql.execute("INSERT INTO hfj_res_link SELECT 1000+n, 1+(n-1)%750, 'Task.for', 'Patient' FROM generate_series(1,1500) n");
		sql.execute("INSERT INTO hfj_spidx_token SELECT 1000+n, 'Task', 'code', 'http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code', 'delete-pending' FROM generate_series(1,1500) n");
		sql.execute("INSERT INTO hfj_spidx_token SELECT 1000+n, 'Task', 'status', 'http://hl7.org/fhir/task-status', 'requested' FROM generate_series(1,1500) n");

		assertThat(inventory.patientsWithARunningCycle()).hasSize(750).doesNotHaveDuplicates()
				.contains("patient-1", "patient-251", "patient-501", "patient-750");
	}

	@Test
	void onlyLiveDeletePendingTasksInTheThreeRunningStatusesContributeSubjects() {
		addTask(1, "requested", "delete-pending", "Task.for", false);
		addTask(2, "on-hold", "delete-pending", "Task.for", false);
		addTask(3, "accepted", "delete-pending", "Task.for", false);
		addTask(4, "completed", "delete-pending", "Task.for", false);
		addTask(5, "cancelled", "delete-pending", "Task.for", false);
		addTask(6, "requested", "fulfill", "Task.for", false);
		addTask(7, "requested", "delete-pending", "Task.owner", false);
		addTask(8, "requested", "delete-pending", "Task.for", true);
		addTask(9, "requested", "delete-pending", "Task.for", false);
		sql.update("UPDATE hfj_spidx_token SET sp_system='urn:another-code-system' WHERE res_id=1009 AND sp_name='code'");

		assertThat(inventory.patientsWithARunningCycle()).containsExactly("patient-1", "patient-2", "patient-3");
	}

	private void addTask(long patient, String status, String code, String path, boolean deleted) {
		sql.update("INSERT INTO hfj_resource VALUES (?, 'Patient', ?, NULL)", patient, "patient-" + patient);
		sql.update("INSERT INTO hfj_resource VALUES (?, 'Task', ?, " + (deleted ? "CURRENT_TIMESTAMP" : "NULL") + ")",
				1000 + patient, "task-" + patient);
		sql.update("INSERT INTO hfj_res_link VALUES (?, ?, ?, 'Patient')", 1000 + patient, patient, path);
		sql.update("INSERT INTO hfj_spidx_token VALUES (?, 'Task', 'code', 'http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code', ?)",
				1000 + patient, code);
		sql.update("INSERT INTO hfj_spidx_token VALUES (?, 'Task', 'status', 'http://hl7.org/fhir/task-status', ?)",
				1000 + patient, status);
	}
}
