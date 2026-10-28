package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The run decides per patient what the announced deletion has come to. What is
 * pinned here is that decision and, for each outcome, what does and does not
 * get written.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErasureCleanupServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
	private static final Instant PASSED = NOW.minus(1, ChronoUnit.DAYS);
	private static final Instant AHEAD = NOW.plus(10, ChronoUnit.DAYS);
	private static final IIdType PATIENT = new IdType("Patient", "p1");

	@Mock private DaoRegistry daoRegistry;
	@Mock private IFhirResourceDao<Task> taskDao;
	@Mock private IFhirResourceDao<AuditEvent> auditEventDao;
	@Mock private IFhirResourceDao<CareTeam> careTeamDao;
	@Mock private IFhirResourceDao<Group> groupDao;
	@Mock private EligibilityService eligibilityService;
	@Mock private PatientDeletionScope deletionScope;
	@Mock private ParticipatingApps participatingApps;
	@Mock private DeleteFlowAggregateLock aggregateLock;
	@Mock private ResourceEraser eraser;
	@Mock private DeleteFlowInventory inventory;
	@Mock private PlatformTransactionManager transactionManager;
	@Mock private jakarta.persistence.EntityManager entityManager;
	@Mock private org.hibernate.Session session;

	private ErasureCleanupService service;

	@BeforeEach
	void setUp() {
		when(daoRegistry.getResourceDao(Task.class)).thenReturn(taskDao);
		when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditEventDao);
		when(daoRegistry.getResourceDao(CareTeam.class)).thenReturn(careTeamDao);
		when(daoRegistry.<Task>getResourceDao("Task")).thenReturn(taskDao);
		when(daoRegistry.<Group>getResourceDao("Group")).thenReturn(groupDao);
		when(participatingApps.serverDeviceId()).thenReturn(new IdType("Device", "server"));
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(entityManager.unwrap(org.hibernate.Session.class)).thenReturn(session);
		when(inventory.patientsWithARunningCycle()).thenReturn(List.of("p1"));
		when(deletionScope.planFor(any())).thenReturn(new PatientDeletionScope.Plan(
				List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of(), List.of()));

		final CleanupProperties properties = new CleanupProperties();
		service = new ErasureCleanupService(daoRegistry, properties, eligibilityService,
				new AnnouncementFactory(properties, Clock.fixed(NOW, ZoneOffset.UTC)), deletionScope,
				participatingApps, aggregateLock, eraser, inventory, transactionManager, entityManager, "Koppeltaal domein VZVZ");
	}

	@Test
	void aHoldBeforeTheDeadlineWaits() {

		cycle(task("t1", Task.TaskStatus.ONHOLD, AHEAD));

		assertThat(outcomeOf(run())).isEqualTo("WAITING");
		verify(eraser, never()).erase(any(), any(), any());
		verify(taskDao, never()).update(any(Task.class), any(RequestDetails.class));
	}

	/** OPG-010: whoever still wants to block has to pull the handbrake again. */
	@Test
	void aHoldThatOutlivesTheDeadlineRestartsTheGrace() {

		cycle(task("t1", Task.TaskStatus.ONHOLD, PASSED));

		assertThat(outcomeOf(run())).isEqualTo("GRACE_RESET");
		assertThat(statusWritten()).isEqualTo(Task.TaskStatus.REQUESTED);
		verify(eraser, never()).erase(any(), any(), any());
	}

	/**
	 * OPG-010 wipes the holds, not a given consent: an application that accepted is not asked again. The grace period
	 * restarts for the patient as a whole, so every Task carries the new deadline.
	 */
	@Test
	void aGraceResetWipesTheHoldsButKeepsWhatWasAccepted() {

		final Task held = task("t1", Task.TaskStatus.ONHOLD, PASSED);
		held.setStatusReason(new CodeableConcept().addCoding(
				new Coding("http://vzvz.nl/fhir/CodeSystem/koppeltaal-delete-hold-reason", "data-export-pending", null)));
		cycle(held, task("t2", Task.TaskStatus.ACCEPTED, PASSED), task("t3", Task.TaskStatus.REQUESTED, PASSED));

		assertThat(outcomeOf(run())).isEqualTo("GRACE_RESET");
		final ArgumentCaptor<Task> written = ArgumentCaptor.forClass(Task.class);
		verify(taskDao, times(3)).update(written.capture(), any(RequestDetails.class));
		final Instant restarted = NOW.plus(30, ChronoUnit.DAYS);
		assertThat(written.getAllValues())
				.extracting(task -> task.getIdElement().getIdPart(), Task::getStatus, Task::hasStatusReason,
						task -> task.getRestriction().getPeriod().getEnd().toInstant())
				.containsExactlyInAnyOrder(
						tuple("t1", Task.TaskStatus.REQUESTED, false, restarted),
						tuple("t2", Task.TaskStatus.ACCEPTED, false, restarted),
						tuple("t3", Task.TaskStatus.REQUESTED, false, restarted));
	}

	@Test
	void thePatientIsErasedOnceTheDeadlinePasses() {

		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));

		assertThat(outcomeOf(run())).isEqualTo("ERASED");
		verify(eraser).erase(List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of());
		assertThat(statusWritten()).isEqualTo(Task.TaskStatus.COMPLETED);
		verify(auditEventDao).create(any(AuditEvent.class), any(RequestDetails.class));
	}

	/** Nobody left to wait for, so the grace period need not run out. */
	@Test
	void everyoneAcceptingErasesEarly() {

		cycle(task("t1", Task.TaskStatus.ACCEPTED, AHEAD));

		assertThat(outcomeOf(run())).isEqualTo("ERASED");
		verify(eraser).erase(any(), any(), any());
	}

	/** OPG-011: the patient came back, so the deletion is off. */
	@Test
	void renewedEngagementAborts() {

		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		when(eligibilityService.hasRenewedEngagement(any(), any())).thenReturn(true);

		assertThat(outcomeOf(run())).isEqualTo("ABORTED");
		assertThat(statusWritten()).isEqualTo(Task.TaskStatus.CANCELLED);
		verify(eraser, never()).erase(any(), any(), any());
	}

	/** Contradictory ownership stays behind for manual review; everything that is only this patient's goes. */
	@Test
	void aSharedResourceStaysBehindWhileThePatientIsErased() throws Exception {

		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		blocked(new PatientDeletionScope.Blocker(new IdType("Task", "shared"), "References another patient as well."));

		final CleanupRunReport report = run();

		assertThat(outcomeOf(report)).isEqualTo("ERASED");
		assertThat(report.getCounts()).containsEntry("RESOURCES", 2).containsEntry("BLOCKED_RESOURCES", 1);
		final var exported = new ObjectMapper().readTree(Files.readAllLines(Path.of(report.getDetailsFile())).get(0));
		assertThat(exported.path("disposition").asText()).isEqualTo("blocked");
		assertThat(exported.path("blockReason").asText()).contains("another patient");
		final var tag = ArgumentCaptor.forClass(Meta.class);
		verify(taskDao).metaAddOperation(eq(new IdType("Task", "shared")), tag.capture(), any(RequestDetails.class));
		assertThat(tag.getValue().getTag()).singleElement().satisfies(coding -> {
			assertThat(coding.getSystem()).isEqualTo("http://hl7.org/fhir/issue-type");
			assertThat(coding.getCode()).isEqualTo("deleted");
		});
		verify(eraser).erase(List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of(new IdType("Task", "shared")));
	}

	@Test
	void anUnclassifiedInboundReferenceStaysBehindTagged() throws Exception {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		blocked(new PatientDeletionScope.Blocker(new IdType("Group", "outside"),
				"Outside the deletion scope: Group.member.entity references Patient/p1."));

		final CleanupRunReport report = run();
		assertThat(outcomeOf(report)).isEqualTo("ERASED");
		final var exported = new ObjectMapper().readTree(Files.readAllLines(Path.of(report.getDetailsFile())).get(0));
		assertThat(exported.path("blockReason").asText()).contains("Group.member.entity", "Patient/p1");
		verify(groupDao).metaAddOperation(eq(new IdType("Group", "outside")), any(Meta.class), any(RequestDetails.class));
		verify(eraser).erase(List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of(new IdType("Group", "outside")));
	}

	@Test
	void aDeletedBlockedResourceIsReportedButNotTagged() throws Exception {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		blocked(new PatientDeletionScope.Blocker(new IdType("Task", "gone"), "Version 1 names another patient."));
		when(taskDao.read(eq(new IdType("Task", "gone")), any(RequestDetails.class))).thenThrow(new ResourceGoneException("gone"));

		final CleanupRunReport report = run();

		assertThat(outcomeOf(report)).isEqualTo("ERASED");
		assertThat(report.getCounts()).containsEntry("BLOCKED_RESOURCES", 1);
		verify(taskDao, never()).metaAddOperation(any(), any(Meta.class), any());
		verify(eraser).erase(List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of(new IdType("Task", "gone")));
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void blockReasonsRemainValidSingleLineJsonInDryAndRealRuns(boolean dryRun) throws Exception {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		final String reason = "Reference path \"Task.owner\" blocks erasure.\nTarget: CareTeam/team-1.";
		blocked(new PatientDeletionScope.Blocker(new IdType("Task", "outside"), reason));

		final CleanupRunReport report = service.run(dryRun, Clock.fixed(NOW, ZoneOffset.UTC), r -> { });
		final List<String> lines = Files.readAllLines(Path.of(report.getDetailsFile()));

		assertThat(lines).hasSize(3);
		final var exported = new ObjectMapper().readTree(lines.get(0));
		assertThat(exported.path("patient").asText()).isEqualTo("p1");
		assertThat(exported.path("resourceType").asText()).isEqualTo("Task");
		assertThat(exported.path("id").asText()).isEqualTo("outside");
		assertThat(exported.path("disposition").asText()).isEqualTo("blocked");
		assertThat(exported.path("blockReason").asText()).isEqualTo(reason);
		if (dryRun) verify(eraser, never()).erase(any(), any(), any());
	}

	/**
	 * The lock is transaction-scoped, so the run rereads the cycle under it. A
	 * hold placed after the run listed its work still counts.
	 */
	@Test
	void theCycleIsJudgedOnWhatTheLockSees() {

		cycle(task("t1", Task.TaskStatus.ONHOLD, PASSED));

		assertThat(outcomeOf(run())).isEqualTo("GRACE_RESET");
		verify(aggregateLock).lockPatient("p1");
		verify(eraser, never()).erase(any(), any(), any());
	}

	@Test
	void aDryRunListsTheWorkAndWritesNothing() throws Exception {

		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));

		final CleanupRunReport report = service.run(true, Clock.fixed(NOW, ZoneOffset.UTC), r -> { });

		assertThat(report.getCounts()).containsEntry("ERASED", 1).containsEntry("RESOURCES", 2);
		for (String line : Files.readAllLines(Path.of(report.getDetailsFile()))) {
			assertThat(new ObjectMapper().readTree(line).has("blockReason")).isFalse();
		}
		verify(eraser, never()).erase(any(), any(), any());
		verify(taskDao, never()).update(any(Task.class), any(RequestDetails.class));
		verify(auditEventDao, never()).create(any(AuditEvent.class), any(RequestDetails.class));
		verify(aggregateLock, never()).lockPatient(any());
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void retainedCareTeamUpdatesAreExportedAndAppliedBeforeErasure(boolean dryRun) throws Exception {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		var team = new CareTeam().setSubject(new Reference("Patient/p2"));
		team.setId("CareTeam/retained/_history/3");
		team.addParticipant().setMember(new Reference("Patient/p1"));
		team.addParticipant().setMember(new Reference("Practitioner/practitioner"));
		when(careTeamDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(team);
		when(deletionScope.planFor(any())).thenReturn(new PatientDeletionScope.Plan(
				List.of(new IdType("Task", "t1"), PATIENT), List.of(new PatientDeletionScope.ParticipantRemoval(
						new IdType("CareTeam/retained"), Set.of("Patient/p1"))), List.of(), List.of()));

		var report = service.run(dryRun, Clock.fixed(NOW, ZoneOffset.UTC), r -> { });
		assertThat(outcomeOf(report)).isEqualTo("ERASED");
		assertThat(report.getCounts()).containsEntry("RESOURCES", 2).containsEntry("DETACHED", 1);
		var lines = Files.readAllLines(Path.of(report.getDetailsFile()));
		assertThat(lines).hasSize(3);
		var detached = new ObjectMapper().readTree(lines.get(2));
		assertThat(detached.path("disposition").asText()).isEqualTo("detach");
		assertThat(detached.path("removedReferences").get(0).asText()).isEqualTo("Patient/p1");
		if (dryRun) {
			verify(careTeamDao, never()).update(any(CareTeam.class), any(RequestDetails.class));
		} else {
			var order = inOrder(careTeamDao, taskDao, eraser, auditEventDao);
			var updated = ArgumentCaptor.forClass(CareTeam.class);
			// The erase's own write, so a reference an earlier erase left behind does not refuse it.
			order.verify(careTeamDao).update(updated.capture(), isA(ErasureRequestDetails.class));
			order.verify(taskDao).update(any(Task.class), any(RequestDetails.class));
			order.verify(eraser).erase(any(), any(), any());
			order.verify(auditEventDao).create(any(AuditEvent.class), any(RequestDetails.class));
			assertThat(updated.getValue().getParticipant()).singleElement().satisfies(participant ->
					assertThat(participant.getMember().getReference()).isEqualTo("Practitioner/practitioner"));
			assertThat(team.getParticipant()).hasSize(2);
		}
	}

	@Test
	void inconsistentParticipantIndexesFailBeforeCompletingTheCycleOrErasing() {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		when(careTeamDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(new CareTeam());
		when(deletionScope.planFor(any())).thenReturn(new PatientDeletionScope.Plan(
				List.of(new IdType("Task", "t1"), PATIENT), List.of(new PatientDeletionScope.ParticipantRemoval(
						new IdType("CareTeam/retained"), Set.of("Patient/p1"))), List.of(), List.of()));
		var report = run();
		assertThat(report.getErrors()).singleElement().asString().contains("participant indexes");
		assertThat(report.getDetailsFile()).isNull();
		verify(taskDao, never()).update(any(Task.class), any(RequestDetails.class));
		verify(eraser, never()).erase(any(), any(), any());
		verify(transactionManager).rollback(any());
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void tagsTheRetainedTaskWithoutAContentUpdate(boolean dryRun) throws Exception {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		Task retained = new Task().setFor(new Reference("Patient/p2")).setOwner(new Reference("Patient/p1"))
				.setStatus(Task.TaskStatus.READY).setIntent(Task.TaskIntent.ORDER);
		retained.setId("Task/retained/_history/1");
		retained.getMeta().setVersionId("1").setLastUpdated(Date.from(NOW.minus(730, ChronoUnit.DAYS)));
		Task before = retained.copy();
		when(taskDao.read(any(IIdType.class), any(RequestDetails.class))).thenReturn(retained);
		when(deletionScope.planFor(any())).thenReturn(new PatientDeletionScope.Plan(
				List.of(new IdType("Task", "t1"), PATIENT), List.of(),
				List.of(new PatientDeletionScope.RetainedTaskOwner(new IdType("Task/retained"), "Patient/p1")), List.of()));

		var report = service.run(dryRun, Clock.fixed(NOW, ZoneOffset.UTC), r -> { });

		assertThat(outcomeOf(report)).isEqualTo("ERASED");
		assertThat(report.getCounts()).containsEntry("RESOURCES", 2).containsEntry("RETAINED", 1);
		var lines = Files.readAllLines(Path.of(report.getDetailsFile()));
		assertThat(lines).hasSize(3);
		assertThat(new ObjectMapper().readTree(lines.get(2)).path("disposition").asText()).isEqualTo("tag-deleted-owner");
		if (dryRun) {
			verify(taskDao, never()).update(any(Task.class), any(RequestDetails.class));
			verify(taskDao, never()).metaAddOperation(any(), any(Meta.class), any());
		} else {
			var tag = ArgumentCaptor.forClass(Meta.class);
			var order = inOrder(taskDao, eraser);
			order.verify(taskDao).metaAddOperation(org.mockito.ArgumentMatchers.eq(new IdType("Task/retained")),
					tag.capture(), any(RequestDetails.class));
			assertThat(tag.getValue().getTag()).singleElement().satisfies(coding -> {
				assertThat(coding.getSystem()).isEqualTo("http://hl7.org/fhir/issue-type");
				assertThat(coding.getCode()).isEqualTo("deleted");
			});
			var updated = ArgumentCaptor.forClass(Task.class);
			order.verify(taskDao).update(updated.capture(), any(RequestDetails.class));
			assertThat(updated.getValue().getIdElement().getIdPart()).isEqualTo("t1");
			order.verify(eraser).erase(List.of(new IdType("Task", "t1"), PATIENT),
					List.of(new PatientDeletionScope.RetainedTaskOwner(new IdType("Task/retained"), "Patient/p1")), List.of());
		}
		assertThat(retained.equalsDeep(before)).isTrue();
	}

	@Test
	void aFailedAuditDoesNotReportRolledBackResourcesAsErased() {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		when(auditEventDao.create(any(AuditEvent.class), any(RequestDetails.class)))
				.thenThrow(new IllegalStateException("Audit failure"));

		final CleanupRunReport report = run();

		assertThat(report.getErrors()).hasSize(1);
		assertThat(report.getCounts()).containsEntry("ERASED", 0).containsEntry("RESOURCES", 0);
		assertThat(report.getDetailsFile()).isNull();
		verify(transactionManager).rollback(any());
	}

	@Test
	void aFailedCommitDoesNotReportUncommittedResourcesAsErased() {
		cycle(task("t1", Task.TaskStatus.REQUESTED, PASSED));
		doThrow(new IllegalStateException("Commit failure")).when(transactionManager).commit(any());

		final CleanupRunReport report = run();

		assertThat(report.getErrors()).hasSize(1);
		assertThat(report.getCounts()).containsEntry("ERASED", 0).containsEntry("RESOURCES", 0);
		assertThat(report.getDetailsFile()).isNull();
	}

	private void blocked(PatientDeletionScope.Blocker blocker) {
		when(deletionScope.planFor(any())).thenReturn(new PatientDeletionScope.Plan(
				List.of(new IdType("Task", "t1"), PATIENT), List.of(), List.of(), List.of(blocker)));
	}

	private CleanupRunReport run() {
		return service.run(false, Clock.fixed(NOW, ZoneOffset.UTC), r -> { });
	}

	private static String outcomeOf(CleanupRunReport report) {
		return report.getCounts().entrySet().stream()
				.filter(entry -> !"RESOURCES".equals(entry.getKey()) && entry.getValue() > 0)
				.map(java.util.Map.Entry::getKey)
				.findFirst()
				.orElse("none");
	}

	private Task.TaskStatus statusWritten() {
		final ArgumentCaptor<Task> written = ArgumentCaptor.forClass(Task.class);
		verify(taskDao).update(written.capture(), any(RequestDetails.class));
		return written.getValue().getStatus();
	}

	private void cycle(Task... tasks) {
		when(taskDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(tasks)));
	}

	private static Task task(String id, Task.TaskStatus status, Instant deadline) {
		final Task task = new Task();
		task.setId(new IdType("Task", id));
		task.getCode().addCoding()
				.setSystem(DeleteFlowTaskUtil.TASK_CODE_SYSTEM)
				.setCode(DeleteFlowTaskUtil.DELETE_PENDING_CODE);
		task.setFor(new Reference(PATIENT.getValue()));
		task.setStatus(status);
		task.setRestriction(new Task.TaskRestrictionComponent()
				.setPeriod(new Period().setEnd(Date.from(deadline))));
		return task;
	}
}
