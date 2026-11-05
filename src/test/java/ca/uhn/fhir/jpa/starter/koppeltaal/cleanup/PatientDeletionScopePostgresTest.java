package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real ownership queries against isolated PostgreSQL tables, not canned query results. */
@EnabledIfSystemProperty(named = "cleanup.postgres.tests", matches = "true")
class PatientDeletionScopePostgresTest {

	private final String schema = "kt2_scope_test_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate admin;
	private JdbcTemplate sql;
	private SessionFactory factory;
	private EntityManager entityManager;
	private PatientDeletionScope scope;

	@BeforeEach
	void setUp() {
		String url = System.getProperty("cleanup.jdbc.url", "jdbc:postgresql://127.0.0.1:5432/hapi_fhir_server_prd_restore");
		String user = System.getProperty("cleanup.jdbc.user", System.getProperty("user.name"));
		admin = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
		admin.execute("CREATE SCHEMA " + schema);
		String isolatedUrl = url + "?currentSchema=" + schema;
		sql = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, user, ""));
		sql.execute("CREATE TABLE hfj_resource (res_id bigint PRIMARY KEY, res_type text, fhir_id text, res_ver bigint DEFAULT 1, res_deleted_at timestamp)");
		sql.execute("CREATE TABLE hfj_res_link (src_resource_id bigint, target_resource_id bigint, src_path text, target_resource_type text)");
		sql.execute("CREATE TABLE hfj_res_ver (res_id bigint, res_ver bigint, res_text_vc text, res_text bigint, res_encoding text DEFAULT 'JSON')");
		factory = new Configuration().setProperty("hibernate.connection.url", isolatedUrl)
				.setProperty("hibernate.connection.username", user).setProperty("hibernate.connection.password", "")
				.setProperty("hibernate.hbm2ddl.auto", "none").buildSessionFactory();
		entityManager = factory.createEntityManager();
		scope = new PatientDeletionScope(entityManager);
		resource(1, "Patient", "x");
		resource(2, "Patient", "y");
	}

	@AfterEach
	void tearDown() {
		if (entityManager != null) entityManager.close();
		if (factory != null) factory.close();
		if (admin != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
	}

	@Test
	void membershipDoesNotMakeAnOrganizationalCareTeamPatientOwned() {
		resource(10, "CareTeam", "organization-team");
		link(10, 1, "CareTeam.participant.member");
		assertThat(resources("x")).containsExactly("Patient/x");
	}

	@Test
	void subjectDeterminesOwnershipEvenWithAnotherPatientAsParticipant() {
		resource(10, "CareTeam", "x-team");
		link(10, 1, "CareTeam.subject");
		link(10, 2, "CareTeam.participant.member");
		assertThat(resources("x")).containsExactlyInAnyOrder("CareTeam/x-team", "Patient/x");
	}

	@Test
	void ownerCascadeIncludesLegacyTasksWithoutFor() {
		resource(10, "CareTeam", "team");
		resource(11, "RelatedPerson", "relative");
		resource(20, "Task", "team-task");
		resource(21, "Task", "relative-task");
		link(10, 1, "CareTeam.subject");
		link(11, 1, "RelatedPerson.patient");
		link(20, 10, "Task.owner");
		link(21, 11, "Task.owner");
		assertThat(resources("x")).containsExactlyInAnyOrder("CareTeam/team", "RelatedPerson/relative",
				"Task/team-task", "Task/relative-task", "Patient/x");
	}

	@Test
	void beneficiaryDeletionAlsoDetectsConflictingOwnerPatient() {
		resource(10, "RelatedPerson", "relative-of-x");
		resource(20, "Task", "task-for-y");
		link(10, 1, "RelatedPerson.patient");
		link(20, 10, "Task.owner");
		link(20, 2, "Task.for");
		assertThat(scope.blockersFor(new IdType("Patient", "y")))
				.anySatisfy(blocker -> assertThat(blocker.blockReason()).contains("Task.for", "Patient/y", "Patient/x"));
		assertThat(scope.blockersFor(new IdType("Patient", "x")))
				.anySatisfy(blocker -> assertThat(blocker.blockReason()).contains("Task.for", "Patient/y", "Patient/x"));
	}

	@Test
	void retainedTeamMembershipsAreDetachedWhileItsTasksAndAuditEventsSurvive() {
		resource(10, "CareTeam", "retained-team");
		resource(11, "RelatedPerson", "relative");
		resource(20, "Task", "retained-task");
		resource(30, "AuditEvent", "audit");
		link(10, 2, "CareTeam.subject");
		link(10, 1, "CareTeam.participant.member");
		link(10, 1, "CareTeam.participant.member");
		link(10, 11, "CareTeam.participant.member");
		link(11, 1, "RelatedPerson.patient");
		link(20, 10, "Task.owner");
		link(20, 2, "Task.for");
		link(30, 1, "AuditEvent.entity.what");
		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("RelatedPerson/relative", "Patient/x");
		assertThat(plan.blockers()).isEmpty();
		assertThat(plan.detachments()).singleElement().satisfies(removal -> {
			assertThat(removal.careTeamId().getValue()).isEqualTo("CareTeam/retained-team");
			assertThat(removal.references()).containsExactlyInAnyOrder("Patient/x", "RelatedPerson/relative");
		});
		assertThat(sql.queryForObject("SELECT count(*) FROM hfj_res_link", Integer.class)).isEqualTo(8);
	}

	@Test
	void unclassifiedInboundReferencesBlockInsteadOfBeingSilentlyRemoved() {
		resource(10, "Group", "group");
		link(10, 1, "Group.member.entity");
		link(10, 1, "Group.member.entity");
		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
			assertThat(blocker.resourceId().getValue()).isEqualTo("Group/group");
			assertThat(blocker.blockReason()).contains("Group.member.entity", "Patient/x");
		});
	}

	@Test
	void patientOwnerDoesNotTransferAnotherBeneficiarysTaskIntoErasureScope() {
		resource(10, "Task", "owned");
		resource(11, "Task", "conflicting");
		link(10, 1, "Task.owner");
		link(11, 1, "Task.owner");
		link(11, 2, "Task.for");
		version(10, 1, "{\"owner\":{\"reference\":\"Patient/x\"}}");
		assertThat(resources("x")).containsExactly("Task/owned", "Patient/x");
		assertThat(scope.blockersFor(new IdType("Patient", "y"))).isEmpty();
		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.blockers()).isEmpty();
		assertThat(plan.retainedTaskOwners()).singleElement()
				.satisfies(owner -> assertThat(owner.taskId().getValue()).isEqualTo("Task/conflicting"));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void taskWithoutBeneficiaryGoesWithTheOnlyPatientItNames(boolean deleted) {
		resource(10, "Task", "legacy");
		version(10, 1, "{\"owner\":{\"reference\":\"Patient/x\"}}");
		version(10, 2, "{\"status\":\"ready\",\"owner\":{\"reference\":\"Patient/x/_history/1\"}}");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");
		else link(10, 1, "Task.owner");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Task/legacy", "Patient/x");
		assertThat(plan.blockers()).isEmpty();
		assertThat(plan.retainedTaskOwners()).isEmpty();
		assertThat(resources("y")).containsExactly("Patient/y");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"owner\":{\"reference\":\"Patient/y\"}}", "{\"owner\":{\"reference\":\"Practitioner/p\"}}",
			"{\"for\":{\"identifier\":{\"value\":\"123\"}},\"owner\":{\"reference\":\"Patient/x\"}}", "{}"})
	void taskWithoutBeneficiaryStillBlocksWhenAnyVersionNamesSomeoneElse(String firstVersion) {
		resource(10, "Task", "reassigned");
		version(10, 1, firstVersion);
		version(10, 2, "{\"owner\":{\"reference\":\"Patient/x\"}}");
		link(10, 1, "Task.owner");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
		assertThat(plan.blockers()).singleElement()
				.satisfies(blocker -> assertThat(blocker.resourceId().getValue()).isEqualTo("Task/reassigned"));
	}

	@Test
	void plansOneTagForCurrentPatientOwnerWithoutErasingTheTaskOrItsHistory() {
		resource(10, "Task", "retained");
		link(10, 2, "Task.for");
		link(10, 1, "Task.owner");
		link(10, 1, "Task.owner");
		version(10, 1, "{\"for\":{\"reference\":\"Patient/y\"},\"owner\":{\"reference\":\"Patient/x\"}}");
		var plan = scope.planFor(new IdType("Patient/x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
		assertThat(plan.blockers()).isEmpty();
		assertThat(plan.retainedTaskOwners()).singleElement().satisfies(owner -> {
			assertThat(owner.taskId().getValue()).isEqualTo("Task/retained");
			assertThat(owner.patientReference()).isEqualTo("Patient/x");
		});
		assertThat(sql.queryForObject("SELECT count(*) FROM hfj_res_ver WHERE res_id=10", Integer.class)).isEqualTo(1);
		assertThat(scope.planFor(new IdType("Patient/y")).retainedTaskOwners()).isEmpty();
	}

	@Test
	void retainingAnOwnerDoesNotExcuseOtherUnhandledReferencesOnTheSameTask() {
		resource(10, "Task", "retained");
		link(10, 2, "Task.for");
		link(10, 1, "Task.owner");
		link(10, 1, "Task.focus");
		assertThat(scope.planFor(new IdType("Patient/x")).blockers()).singleElement()
				.satisfies(blocker -> assertThat(blocker.blockReason()).contains("Task.focus"));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void formerPatientOwnerCanRemainInAnotherBeneficiarysTaskHistory(boolean deleted) {
		resource(10, "Task", "retained");
		version(10, 1, "{\"for\":{\"reference\":\"Patient/y\"},\"owner\":{\"reference\":\"Patient/x\"}}");
		version(10, 2, "{\"for\":{\"reference\":\"Patient/y\"},\"owner\":{\"reference\":\"Patient/y\"}}");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");
		else {
			link(10, 2, "Task.for");
			link(10, 2, "Task.owner");
		}
		var plan = scope.planFor(new IdType("Patient/x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
		assertThat(plan.blockers()).isEmpty();
	}

	@Test
	void changingTheBeneficiaryStillBlocksErasingAnotherPatientsHistory() {
		resource(10, "Task", "reassigned");
		link(10, 1, "Task.for");
		version(10, 1, "{\"for\":{\"reference\":\"Patient/y\"}}");
		version(10, 2, "{\"for\":{\"reference\":\"Patient/x\"}}");
		var plan = scope.planFor(new IdType("Patient/x"));
		assertThat(plan.blockers()).singleElement()
				.satisfies(blocker -> assertThat(blocker.blockReason()).contains("Version 1", "Patient/y"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
	}

	@Test
	void crossPatientCareTeamOwnershipBlocksFromEitherSide() {
		resource(10, "CareTeam", "team-x");
		resource(20, "Task", "task-y");
		link(10, 1, "CareTeam.subject");
		link(20, 10, "Task.owner");
		link(20, 2, "Task.for");
		for (String patient : List.of("x", "y")) {
			assertThat(scope.blockersFor(new IdType("Patient", patient))).singleElement()
					.satisfies(blocker -> assertThat(blocker.blockReason()).contains("CareTeam/team-x", "Patient/x", "Patient/y"));
		}
	}

	@Test
	void crossPatientParentTasksBlockFromEitherSide() {
		resource(10, "Task", "parent");
		resource(20, "Task", "child");
		link(10, 1, "Task.for");
		link(20, 2, "Task.for");
		link(20, 10, "Task.partOf");
		for (String patient : List.of("x", "y")) {
			assertThat(scope.planFor(new IdType("Patient", patient)).blockers()).singleElement()
					.satisfies(blocker -> assertThat(blocker.blockReason()).contains("Task.partOf", "Task/parent"));
		}
	}

	@Test
	void deletedOwnershipAndOwnerCascadesComeFromJsonHistory() {
		resource(10, "CareTeam", "deleted-team");
		resource(20, "Task", "deleted-task");
		resource(21, "Task", "direct-deleted-task");
		sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id IN (10,20,21)");
		version(10, 1, "{\"subject\":{\"reference\":\"Patient/x/_history/1\"}}");
		version(20, 1, "{\"owner\":{\"reference\":\"CareTeam/deleted-team\"}}");
		version(21, 1, "{\"for\":{\"reference\":\"Patient/x\"}}");
		assertThat(resources("x")).containsExactlyInAnyOrder("CareTeam/deleted-team", "Task/deleted-task",
				"Task/direct-deleted-task", "Patient/x");
		assertThat(scope.blockersFor(new IdType("Patient", "x"))).isEmpty();
	}

	@Test
	void deletedParticipantHistoryDoesNotMakeTeamOwned() {
		resource(10, "CareTeam", "deleted-team");
		sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");
		version(10, 1, "{\"participant\":[{\"member\":{\"reference\":\"Patient/x\"}}]}");
		assertThat(resources("x")).containsExactly("Patient/x");
	}

	@Test
	void aPreviousOwnersPatientBlocksErasingAllVersions() {
		resource(10, "RelatedPerson", "changed-patient");
		link(10, 1, "RelatedPerson.patient");
		version(10, 1, "{\"patient\":{\"reference\":\"Patient/y\"}}");
		version(10, 2, "{\"patient\":{\"reference\":\"Patient/x\"}}");
		assertThat(scope.blockersFor(new IdType("Patient", "x"))).singleElement()
				.satisfies(blocker -> assertThat(blocker.blockReason()).contains("Version 1", "Patient/y", "another patient"));
	}

	@Test
	void aFormerPatientCannotBeErasedWhileOwnedHistoryWasReassignedToAnotherPatient() {
		resource(10, "RelatedPerson", "changed-patient");
		link(10, 1, "RelatedPerson.patient");
		version(10, 1, "{\"patient\":{\"reference\":\"Patient/y\"}}");
		version(10, 2, "{\"patient\":{\"reference\":\"Patient/x\"}}");
		assertThat(scope.planFor(new IdType("Patient", "y")).blockers()).singleElement()
				.satisfies(blocker -> assertThat(blocker.blockReason()).contains("Version 2", "Patient/x", "another patient"));
	}

	@ParameterizedTest
	@CsvSource({"CareTeam,false", "CareTeam,true", "RelatedPerson,false", "RelatedPerson,true"})
	void historicalOwnerOnlyTaskAssignmentsBlockErasingEitherPatientsVersions(String ownerType, boolean deleted) {
		long secondOwner = 2;
		String firstReference = "Patient/x";
		String secondReference = "Patient/y";
		if (!"Patient".equals(ownerType)) {
			resource(10, ownerType, "owner-x");
			resource(11, ownerType, "owner-y");
			String path = ownerType + ("CareTeam".equals(ownerType) ? ".subject" : ".patient");
			link(10, 1, path);
			link(11, 2, path);
			secondOwner = 11;
			firstReference = ownerType + "/owner-x";
			secondReference = ownerType + "/owner-y";
		}
		resource(20, "Task", "reassigned-owner-only");
		version(20, 1, "{\"owner\":{\"reference\":\"" + firstReference + "/_history/1\"}}");
		version(20, 2, "{\"owner\":{\"reference\":\"" + secondReference + "\"}}");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=20");
		else link(20, secondOwner, "Task.owner");

		for (String patient : List.of("x", "y")) {
			var plan = scope.planFor(new IdType("Patient", patient));
			assertThat(plan.blockers()).anySatisfy(blocker -> {
				assertThat(blocker.resourceId().getValue()).isEqualTo("Task/reassigned-owner-only");
				assertThat(blocker.blockReason()).contains("Task.owner", "Patient/x", "Patient/y");
			});
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void formerPatientOwnedTaskNowAssignedToOrganizationalTeamGoesWithThePatient(boolean deleted) {
		resource(10, "CareTeam", "patient-team");
		resource(11, "CareTeam", "organization-team");
		resource(20, "Task", "ambiguous-owner-only");
		link(10, 1, "CareTeam.subject");
		version(20, 1, "{\"owner\":{\"reference\":\"CareTeam/patient-team\"}}");
		version(20, 2, "{\"owner\":{\"reference\":\"CareTeam/organization-team\"}}");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=20");
		else link(20, 11, "Task.owner");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue)
				.contains("Task/ambiguous-owner-only").doesNotContain("CareTeam/organization-team");
		assertThat(plan.blockers()).isEmpty();
	}

	@Test
	void olderVersionsOwnedByAnOrganizationalTeamGoWithTheCurrentBeneficiary() {
		resource(10, "CareTeam", "organization-team");
		resource(20, "Task", "assigned-later");
		link(20, 1, "Task.for");
		link(20, 10, "Task.owner");
		version(20, 1, "{\"owner\":{\"reference\":\"CareTeam/organization-team\"}}");
		version(20, 2, "{\"for\":{\"reference\":\"Patient/x\"},\"owner\":{\"reference\":\"CareTeam/organization-team\"}}");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Task/assigned-later", "Patient/x");
		assertThat(plan.blockers()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"Patient/y", "Practitioner/missing"})
	void aVersionWithoutBeneficiaryWhoseOwnerNamesSomeoneElseIsKeptForReview(String owner) {
		resource(10, "Task", "reassigned");
		link(10, 1, "Task.for");
		version(10, 1, "{\"owner\":{\"reference\":\"" + owner + "\"}}");
		version(10, 2, "{\"for\":{\"reference\":\"Patient/x\"}}");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
			assertThat(blocker.resourceId().getValue()).isEqualTo("Task/reassigned");
			assertThat(blocker.blockReason()).contains("Version 1", "names another patient or does not resolve");
		});
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
	}

	@Test
	void referrersOfABlockedResourceAreLeftAlone() {
		resource(10, "Task", "reassigned");
		resource(20, "Task", "follow-up");
		link(10, 1, "Task.for");
		link(20, 2, "Task.for");
		link(20, 10, "Task.partOf");
		version(10, 1, "{\"for\":{\"reference\":\"Patient/y\"}}");
		version(10, 2, "{\"for\":{\"reference\":\"Patient/x\"}}");

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.blockers()).extracting(blocker -> blocker.resourceId().getValue()).containsExactly("Task/reassigned");
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Patient/x");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void ownerHistoryCannotEstablishCurrentPatientOwnershipForSubjectlessTeam(boolean deleted) {
		resource(10, "CareTeam", "changed-team");
		resource(20, "Task", "owner-only");
		version(10, 1, "{\"subject\":{\"reference\":\"Patient/x\"}}");
		version(10, 2, "{}");
		version(20, 1, "{\"owner\":{\"reference\":\"CareTeam/changed-team\"}}");
		link(20, 10, "Task.owner");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");
		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
			assertThat(blocker.resourceId().getValue()).isEqualTo("CareTeam/changed-team");
			assertThat(blocker.blockReason()).contains("patient ownership");
		});
		assertThat(plan.resources()).extracting(IIdType::getValue).containsExactly("Task/owner-only", "Patient/x");
	}

	@Test
	void ownerOnlyTaskCanMoveBetweenOwnersOfTheSamePatient() {
		resource(10, "CareTeam", "team");
		resource(11, "RelatedPerson", "relative");
		resource(20, "Task", "owner-only");
		link(10, 1, "CareTeam.subject");
		link(11, 1, "RelatedPerson.patient");
		link(20, 11, "Task.owner");
		version(20, 1, "{\"owner\":{\"reference\":\"CareTeam/team\"}}");
		version(20, 2, "{\"owner\":{\"reference\":\"RelatedPerson/relative\"}}");
		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).contains("Task/owner-only");
		assertThat(plan.blockers()).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"CareTeam,false", "CareTeam,true", "RelatedPerson,false", "RelatedPerson,true"})
	void formerlyPatientOwnedActorWithoutCurrentPatientOwnershipBlocksErasure(String ownerType, boolean deleted) {
		resource(10, ownerType, "formerly-owned");
		String field = "CareTeam".equals(ownerType) ? "subject" : "patient";
		version(10, 1, "{\"" + field + "\":{\"reference\":\"Patient/x\"}}");
		version(10, 2, "{}");
		if (deleted) sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");

		assertThat(scope.planFor(new IdType("Patient", "x")).blockers()).anySatisfy(blocker -> {
			assertThat(blocker.resourceId().getValue()).isEqualTo(ownerType + "/formerly-owned");
			assertThat(blocker.blockReason()).contains(ownerType + "." + field, "patient ownership", "Patient/x");
		});
	}

	@ParameterizedTest
	@CsvSource({"CareTeam,false", "CareTeam,true", "RelatedPerson,false", "RelatedPerson,true"})
	void initiallyUnassignedActorCanBeErasedWithItsCurrentPatient(String ownerType, boolean deleted) {
		resource(10, ownerType, "now-owned");
		String field = "CareTeam".equals(ownerType) ? "subject" : "patient";
		version(10, 1, "{}");
		version(10, 2, "{\"" + field + "\":{\"reference\":\"Patient/x/_history/1\"}}");
		if (deleted) {
			sql.update("UPDATE hfj_resource SET res_deleted_at=now() WHERE res_id=10");
			sql.update("INSERT INTO hfj_res_ver (res_id,res_ver,res_encoding) VALUES (10,3,'DEL')");
		} else link(10, 1, ownerType + "." + field);

		var plan = scope.planFor(new IdType("Patient", "x"));
		assertThat(plan.resources()).extracting(IIdType::getValue).contains(ownerType + "/now-owned");
		assertThat(plan.blockers()).isEmpty();
	}

	@Test
	void oversizedScopesFailClosed() {
		sql.execute("INSERT INTO hfj_resource SELECT n,'Task','task-' || n,1,NULL FROM generate_series(10,2009) n");
		sql.execute("INSERT INTO hfj_res_link SELECT n,1,'Task.for','Patient' FROM generate_series(10,2009) n");
		assertThatThrownBy(() -> resources("x")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("2001 resources");
	}

	@Test
	void foreignPatientReferenceDoesNotEqualALocalPatientWithTheSameId() {
		resource(10, "RelatedPerson", "relative");
		resource(20, "Task", "foreign-patient");
		link(10, 1, "RelatedPerson.patient");
		link(20, 10, "Task.owner");
		version(20, 1, "{\"for\":{\"reference\":\"https://external.example/fhir/Patient/x\"},\"owner\":{\"reference\":\"RelatedPerson/relative\"}}");
		assertThat(scope.planFor(new IdType("Patient", "x")).blockers()).anySatisfy(blocker ->
				assertThat(blocker.blockReason()).contains("https://external.example/fhir/Patient/x"));
	}

	private List<String> resources(String patient) {
		return scope.resourcesFor(new IdType("Patient", patient)).stream().map(IIdType::getValue).toList();
	}

	private void resource(long pid, String type, String id) {
		sql.update("INSERT INTO hfj_resource (res_id,res_type,fhir_id) VALUES (?,?,?)", pid, type, id);
	}

	private void link(long source, long target, String path) {
		sql.update("INSERT INTO hfj_res_link SELECT ?,res_id,?,res_type FROM hfj_resource WHERE res_id=?", source, path, target);
	}

	private void version(long pid, int version, String json) {
		sql.update("INSERT INTO hfj_res_ver (res_id,res_ver,res_text_vc) VALUES (?,?,?)", pid, version, json);
	}
}
