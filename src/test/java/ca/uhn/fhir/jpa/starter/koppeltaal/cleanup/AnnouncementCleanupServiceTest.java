package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnouncementCleanupServiceTest {

	@Mock private DaoRegistry daoRegistry;
	@Mock private IFhirResourceDao<Patient> patientDao;
	@Mock private IFhirResourceDao<Task> taskDao;
	@Mock private IFhirResourceDao<AuditEvent> auditEventDao;
	@Mock @SuppressWarnings("rawtypes") private IFhirSystemDao systemDao;
	@Mock private ParticipatingApps participatingApps;
	@Mock private EligibilityService eligibilityService;

	private AnnouncementCleanupService service;

	@BeforeEach
	void setUp() {
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(daoRegistry.getResourceDao(Task.class)).thenReturn(taskDao);
		when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditEventDao);
		when(daoRegistry.getSystemDao()).thenReturn(systemDao);
		when(participatingApps.participatingDeviceIds())
				.thenReturn(List.of(new IdType("Device", "app-1"), new IdType("Device", "app-2")));
		when(participatingApps.serverDeviceId()).thenReturn(new IdType("Device", "server-dev"));
		when(patientDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(patient("p1"), patient("p2"))));
		when(taskDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of()));
		when(eligibilityService.isEligible(any())).thenReturn(true);
		AnnouncementFactory factory = new AnnouncementFactory(new CleanupProperties(),
				Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC));
		service = new AnnouncementCleanupService(daoRegistry, participatingApps, eligibilityService,
				factory, "Koppeltaal domein VZVZ");
	}

	@SuppressWarnings("unchecked")
	private java.util.List<Bundle> capturedBundles() {
		ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
		verify(systemDao, org.mockito.Mockito.atLeast(0)).transaction(any(), captor.capture());
		return captor.getAllValues();
	}

	private static long taskEntries(java.util.List<Bundle> bundles) {
		return bundles.stream().flatMap(b -> b.getEntry().stream())
				.filter(e -> e.getResource() instanceof org.hl7.fhir.r4.model.Task).count();
	}

	private static long eventEntries(java.util.List<Bundle> bundles) {
		return bundles.stream().flatMap(b -> b.getEntry().stream())
				.filter(e -> e.getResource() instanceof AuditEvent).count();
	}

	private static Patient patient(String id) {
		Patient patient = new Patient();
		patient.setId("Patient/" + id);
		return patient;
	}

	private static Task existingDeletePendingTask(String patientId, String appDeviceId, Task.TaskStatus status) {
		Task task = new Task();
		task.setStatus(status);
		task.setFor(new Reference("Patient/" + patientId));
		task.setOwner(new Reference("Device/" + appDeviceId));
		return task;
	}

	@Test
	void progressListenerReceivesRunningSnapshotsWithTotal() {
		java.util.List<CleanupRunReport> snapshots = new java.util.ArrayList<>();
		service.run(true, snapshots::add);
		org.junit.jupiter.api.Assertions.assertFalse(snapshots.isEmpty());
		assertEquals(CleanupRunReport.RunStatus.RUNNING, snapshots.get(0).getStatus());
		assertEquals(2, snapshots.get(0).getPatientsTotal());
		// one initial snapshot plus one per evaluated patient
		assertEquals(3, snapshots.size());
		assertEquals(2, snapshots.get(2).getPatientsEvaluated());
	}

	@Test
	void createsTasksPerAppAndOneEventPerPatient() {
		CleanupRunReport report = service.run(false, r -> {});

		assertEquals(2, report.getPatientsEvaluated());
		assertEquals(2, report.getPatientsEligible());
		assertEquals(4, report.getTasksCreated());
		assertEquals(2, report.getAuditEventsCreated());
		assertTrue(report.getErrors().isEmpty());
		java.util.List<Bundle> bundles = capturedBundles();
		assertEquals(2, bundles.size());
		for (Bundle b : bundles) {
			for (Bundle.BundleEntryComponent entry : b.getEntry()) {
				assertEquals(Bundle.HTTPVerb.POST, entry.getRequest().getMethod());
				String expectedUrl = entry.getResource() instanceof org.hl7.fhir.r4.model.Task ? "Task" : "AuditEvent";
				assertEquals(expectedUrl, entry.getRequest().getUrl());
			}
		}
		assertEquals(Bundle.BundleType.TRANSACTION, bundles.get(0).getType());
		assertEquals(4, taskEntries(bundles));
		assertEquals(2, eventEntries(bundles));
	}

	@Test
	void existingPairIsSkippedAndNoNewAnnouncementEvent() {
		when(taskDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(
						existingDeletePendingTask("p1", "app-1", Task.TaskStatus.REQUESTED))));

		CleanupRunReport report = service.run(false, r -> {});

		// p1: app-1 exists -> only app-2 created, no new event; p2: both apps + event
		assertEquals(3, report.getTasksCreated());
		assertEquals(1, report.getAuditEventsCreated());
	}

	@Test
	void cancelledPairDoesNotCountAsExisting() {
		when(taskDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(
						existingDeletePendingTask("p1", "app-1", Task.TaskStatus.CANCELLED))));

		CleanupRunReport report = service.run(false, r -> {});

		assertEquals(4, report.getTasksCreated());
		assertEquals(2, report.getAuditEventsCreated());
	}

	@Test
	void dryRunWritesNothingButReportsEverything() {
		CleanupRunReport report = service.run(true, r -> {});

		assertTrue(report.isDryRun());
		assertEquals(4, report.getTasksCreated());
		assertEquals(2, report.getAuditEventsCreated());
		verify(systemDao, never()).transaction(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void transactionFailureIsIsolatedPerPatient() {
		when(systemDao.transaction(any(), any(Bundle.class)))
				.thenThrow(new RuntimeException("bundle boom"))
				.thenAnswer(inv -> inv.getArgument(1));

		CleanupRunReport report = service.run(false, r -> {});

		assertEquals(1, report.getErrors().size());
		assertEquals(2, report.getTasksCreated());
		assertEquals(1, report.getAuditEventsCreated());
	}

	@Test
	void ineligiblePatientsAreOnlyCounted() {
		when(eligibilityService.isEligible(any())).thenReturn(false);

		CleanupRunReport report = service.run(false, r -> {});

		assertEquals(2, report.getPatientsEvaluated());
		assertEquals(0, report.getPatientsEligible());
		assertEquals(0, report.getTasksCreated());
	}

	@Test
	void failureOnOnePatientIsIsolatedAndReported() {
		when(eligibilityService.isEligible(argThatPatient("p1"))).thenThrow(new IllegalStateException("boom"));
		when(eligibilityService.isEligible(argThatPatient("p2"))).thenReturn(true);

		CleanupRunReport report = service.run(false, r -> {});

		assertEquals(1, report.getPatientsEligible());
		assertEquals(2, report.getTasksCreated());
		assertEquals(1, report.getErrors().size());
		assertTrue(report.getErrors().get(0).contains("p1"));
	}

	private static org.hl7.fhir.instance.model.api.IIdType argThatPatient(String idPart) {
		return org.mockito.ArgumentMatchers.argThat(id -> id != null && idPart.equals(id.getIdPart()));
	}
}
