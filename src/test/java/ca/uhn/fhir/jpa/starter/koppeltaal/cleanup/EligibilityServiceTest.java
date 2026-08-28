package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EligibilityServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final IIdType PATIENT_ID = new IdType("Patient", "p1");

	@Mock private DaoRegistry daoRegistry;
	@Mock private IFhirResourceDao<Patient> patientDao;
	@Mock private IFhirResourceDao<RelatedPerson> relatedPersonDao;
	@Mock private IFhirResourceDao<AuditEvent> auditEventDao;
	@Mock private IFhirResourceDao<Task> taskDao;

	private EligibilityService eligibilityService;

	@BeforeEach
	void setUp() {
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(daoRegistry.getResourceDao(RelatedPerson.class)).thenReturn(relatedPersonDao);
		when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditEventDao);
		when(daoRegistry.getResourceDao(Task.class)).thenReturn(taskDao);
		eligibilityService = new EligibilityService(daoRegistry, new CleanupProperties(),
				Clock.fixed(NOW, ZoneOffset.UTC));
		stubCreatedYearsAgo(3);
		stubRelatedPersons();
		stubAuthEvents();
		stubRecentTasks();
	}

	private void stubCreatedYearsAgo(int years) {
		Patient version1 = new Patient();
		version1.setId("Patient/p1/_history/1");
		version1.getMeta().setLastUpdated(Date.from(NOW.minusSeconds(365L * 24 * 3600 * years)));
		when(patientDao.read(argThat(id -> id != null && "1".equals(id.getVersionIdPart())), any()))
				.thenReturn(version1);
	}

	private void stubRelatedPersons(RelatedPerson... relatedPersons) {
		when(relatedPersonDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(relatedPersons)));
	}

	private void stubAuthEvents(AuditEvent... events) {
		when(auditEventDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(events)));
	}

	private void stubRecentTasks(Task... tasks) {
		when(taskDao.search(any(SearchParameterMap.class), any()))
				.thenReturn(new SimpleBundleProvider(List.of(tasks)));
	}

	private static Task deletePendingTask() {
		Task task = new Task();
		task.getCode().addCoding(
				new Coding(AnnouncementFactory.TASK_CODE_SYSTEM, "delete-pending", "Delete pending"));
		return task;
	}

	@Test
	void recentDeletePendingTaskDoesNotBlockEligibility() {
		stubRecentTasks(deletePendingTask());
		assertTrue(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void recentOtherTaskStillBlocksEligibility() {
		stubRecentTasks(deletePendingTask(), new Task());
		assertFalse(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void oldQuietPatientIsEligible() {
		assertTrue(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void youngPatientIsNotEligible() {
		stubCreatedYearsAgo(1);
		assertFalse(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void recentAuthEventBlocksEligibility() {
		stubAuthEvents(new AuditEvent());
		assertFalse(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void recentTaskActivityBlocksEligibilityWhileTransitionRuleActive() {
		stubRecentTasks(new Task());
		assertFalse(eligibilityService.isEligible(PATIENT_ID));
	}

	@Test
	void taskActivityIgnoredAfterTransitionWindow() {
		CleanupProperties properties = new CleanupProperties();
		properties.setTransitionDate(java.time.LocalDate.parse("2020-01-01"));
		eligibilityService = new EligibilityService(daoRegistry, properties, Clock.fixed(NOW, ZoneOffset.UTC));
		stubRecentTasks(new Task());
		assertTrue(eligibilityService.isEligible(PATIENT_ID));
	}
}
