package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IDao;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatientAssignmentValidationInterceptorTest {

	private static final RequestPartitionId PARTITION = RequestPartitionId.fromPartitionId(7);
	private final Map<String, Resource> resources = new LinkedHashMap<>();
	private final List<List<Object>> locks = new ArrayList<>();
	private final SystemRequestDetails request = new SystemRequestDetails().setRequestPartitionId(PARTITION);
	private PatientAssignmentValidationInterceptor interceptor;
	private JpaStorageSettings settings;
	private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		DaoRegistry registry = mock(DaoRegistry.class);
		IRequestPartitionHelperSvc partitions = mock(IRequestPartitionHelperSvc.class);
		when(partitions.determineReadPartitionForRequestForRead(any(), any()))
				.thenAnswer(call -> ((SystemRequestDetails) call.getArgument(0)).getRequestPartitionId());
		settings = new JpaStorageSettings();
		entityManager = mock(EntityManager.class);
		when(entityManager.createNativeQuery(anyString())).thenAnswer(call -> {
			List<Object> parameters = new ArrayList<>();
			Query query = mock(Query.class);
			when(query.setParameter(any(Integer.class), any())).thenAnswer(parameter -> {
				parameters.add(parameter.getArgument(1));
				return query;
			});
			when(query.getSingleResult()).thenAnswer(result -> {
				locks.add(parameters);
				return 1;
			});
			return query;
		});
		stubDao(registry, CareTeam.class);
		stubDao(registry, RelatedPerson.class);
		stubDao(registry, Task.class);
		interceptor = new PatientAssignmentValidationInterceptor(registry, partitions, settings, entityManager);
	}

	private <T extends Resource> void stubDao(DaoRegistry registry, Class<T> type) {
		@SuppressWarnings("unchecked")
		IFhirResourceDao<T> dao = mock(IFhirResourceDao.class);
		when(registry.getResourceDao(type)).thenReturn(dao);
		when(dao.read(any(), any())).thenAnswer(call -> {
			IIdType id = call.getArgument(0);
			SystemRequestDetails readRequest = call.getArgument(1);
			Resource value = resources.get(key(readRequest.getRequestPartitionId(), id.getValue()));
			if (value == null) throw new ResourceNotFoundException("Synthetic resource does not exist");
			return value;
		});
		when(dao.search(any(SearchParameterMap.class), any())).thenAnswer(call -> {
			SearchParameterMap search = call.getArgument(0);
			SystemRequestDetails searchRequest = call.getArgument(1);
			ReferenceParam owner = (ReferenceParam) search.get(Task.SP_OWNER).get(0).get(0);
			List<IBaseResource> matches = resources.values().stream().filter(Task.class::isInstance)
					.map(Task.class::cast)
					.filter(task -> searchRequest.getRequestPartitionId().equals(task.getUserData(Constants.RESOURCE_PARTITION_ID)))
					.filter(task -> task.getOwner().getReferenceElement().toUnqualifiedVersionless().getValue().equals(owner.getValue()))
					.map(IBaseResource.class::cast).toList();
			int offset = search.getOffset() == null ? 0 : search.getOffset();
			int count = search.getCount() == null ? 250 : search.getCount();
			return new SimpleBundleProvider(matches.subList(Math.min(offset, matches.size()), Math.min(offset + count, matches.size())));
		});
	}

	@Test
	void acceptsPatientOwnerForAnotherBeneficiaryOnCreate() {
		assertThatCode(() -> create(task("task-a", "Patient/patient-a", "Patient/patient-b")))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsPatientOwnerForAnotherBeneficiaryOnUpdate() {
		Task previous = task("task-a", "Patient/patient-a", "Patient/patient-a");
		Task updated = previous.copy().setFor(new Reference("Patient/patient-b"));
		assertThatCode(() -> update(previous, updated)).doesNotThrowAnyException();
	}

	@Test
	void acceptsTaggingALegacyTaskWithAConflictingRelatedPersonOwner() {
		store(relatedPerson("relative-a", "Patient/patient-a"));
		Task legacy = task("task-a", "RelatedPerson/relative-a", "Patient/patient-b");
		assertThatCode(() -> update(legacy, withDeletedTag(legacy))).doesNotThrowAnyException();
	}

	@Test
	void acceptsTaggingATaskWhoseOwnerNoLongerResolves() {
		Task kept = task("task-a", "CareTeam/erased-team", "Patient/patient-a");
		assertThatCode(() -> update(kept, withDeletedTag(kept))).doesNotThrowAnyException();
	}

	@Test
	void stillRejectsContentChangesToALegacyTaskWithAConflictingOwner() {
		store(relatedPerson("relative-a", "Patient/patient-a"));
		Task legacy = task("task-a", "RelatedPerson/relative-a", "Patient/patient-b");
		assertRejected(() -> update(legacy, withDeletedTag(legacy).setStatus(Task.TaskStatus.CANCELLED)));
	}

	@Test
	void comparesVersionedPatientReferencesByIdentity() {
		assertThatCode(() -> create(task("task-a", "Patient/patient-a/_history/2", "Patient/patient-a/_history/3")))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsLocalAbsolutePatientReferences() {
		request.setFhirServerBase("https://example.test/fhir");
		assertThatCode(() -> create(task("task-a", "https://example.test/fhir/Patient/patient-a", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsForeignPatientOwnerWithoutInferringBeneficiaryOwnership() {
		assertThatCode(() -> create(task("task-a", "https://elsewhere.test/fhir/Patient/patient-a", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsCareTeamWhoseSubjectDiffersFromTaskPatient() {
		store(careTeam("team-a", "Patient/patient-a"));
		assertRejected(() -> create(task("task-a", "CareTeam/team-a", "Patient/patient-b")));
	}

	@Test
	void acceptsCareTeamForTheTaskPatientWithVersionedOwner() {
		store(careTeam("team-a", "Patient/patient-a"));
		assertThatCode(() -> create(task("task-a", "CareTeam/team-a/_history/1", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsCareTeamWithoutSubject() {
		store(careTeam("team-a", null));
		assertThatCode(() -> create(task("task-a", "CareTeam/team-a", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsUnresolvableCareTeamOwner() {
		assertRejected(() -> create(task("task-a", "CareTeam/missing", "Patient/patient-a")));
	}

	@Test
	void preservesForeignCareTeamWithoutReadingItsLocalNamesake() {
		store(careTeam("team-a", "Patient/patient-a"));
		assertThatCode(() -> create(task("task-a", "https://elsewhere.test/fhir/CareTeam/team-a", "Patient/patient-b")))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsConfiguredLocalOwnerBaseUrl() {
		settings.getTreatBaseUrlsAsLocal().add("https://alias.test/fhir");
		store(careTeam("team-a", "Patient/patient-a"));
		assertThatCode(() -> create(task("task-a", "https://alias.test/fhir/CareTeam/team-a", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsRelatedPersonForAnotherPatient() {
		store(relatedPerson("relative-a", "Patient/patient-a"));
		assertRejected(() -> create(task("task-a", "RelatedPerson/relative-a", "Patient/patient-b")));
	}

	@Test
	void allowsSeparateRelatedPersonsForTheSameUserAcrossPatients() {
		RelatedPerson first = relatedPerson("relative-a", "Patient/patient-a");
		RelatedPerson second = relatedPerson("relative-b", "Patient/patient-b");
		first.addIdentifier().setSystem("urn:synthetic:user").setValue("same-user");
		second.setIdentifier(first.getIdentifier());
		store(first);
		store(second);
		assertThatCode(() -> {
			create(task("task-a", "RelatedPerson/relative-a", "Patient/patient-a"));
			create(task("task-b", "RelatedPerson/relative-b", "Patient/patient-b"));
		}).doesNotThrowAnyException();
	}

	@Test
	void leavesMissingRelatedPersonPatientToOrdinaryProfileValidation() {
		store(relatedPerson("relative-a", null));
		assertThatCode(() -> create(task("task-a", "RelatedPerson/relative-a", "Patient/patient-a")))
				.doesNotThrowAnyException();
	}

	@Test
	void preservesDeviceOwnedDeletePendingTasks() {
		Task task = task("task-a", "Device/device-a", "Patient/patient-a");
		task.getCode().addCoding().setSystem("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code").setCode("delete-pending");
		assertThatCode(() -> create(task)).doesNotThrowAnyException();
		assertThat(locks).isEmpty();
	}

	@Test
	void rejectsCareTeamSubjectChangesThatInvalidateExistingTasks() {
		CareTeam previous = careTeam("team-a", "Patient/patient-a");
		store(task("task-a", "CareTeam/team-a/_history/1", "Patient/patient-a"));
		assertRejected(() -> update(previous, careTeam("team-a", "Patient/patient-b")));
	}

	@Test
	void rejectsRelatedPersonPatientChangesThatInvalidateExistingTasks() {
		RelatedPerson previous = relatedPerson("relative-a", "Patient/patient-a");
		store(task("task-a", "RelatedPerson/relative-a", "Patient/patient-a"));
		assertRejected(() -> update(previous, relatedPerson("relative-a", "Patient/patient-b")));
	}

	@Test
	void acceptsUnchangedSubjectWhileRemovingLegacyCareTeamParticipants() {
		CareTeam previous = careTeam("team-a", "Patient/patient-a");
		previous.addParticipant().setMember(new Reference("Patient/patient-b"));
		CareTeam updated = previous.copy();
		updated.getParticipant().clear();
		store(task("legacy-task", "CareTeam/team-a", "Patient/patient-b"));
		assertThatCode(() -> update(previous, updated)).doesNotThrowAnyException();
		assertThat(locks).isEmpty();
	}

	@Test
	void allowsCareTeamSubjectRemovalWithoutInventingAPatient() {
		store(task("task-a", "CareTeam/team-a", "Patient/patient-a"));
		assertThatCode(() -> update(careTeam("team-a", "Patient/patient-a"), careTeam("team-a", null)))
				.doesNotThrowAnyException();
	}

	@Test
	void validatesFinalTaskStateAfterAnAtomicPatientReassignment() {
		CareTeam previous = careTeam("team-a", "Patient/patient-a");
		CareTeam updated = careTeam("team-a", "Patient/patient-b");
		store(task("task-a", "CareTeam/team-a", "Patient/patient-b"));
		assertThatCode(() -> update(previous, updated)).doesNotThrowAnyException();
	}

	@Test
	void checksTasksBeyondTheDefaultSynchronousSearchLimit() {
		for (int index = 0; index < 501; index++) {
			store(task("task-" + index, "CareTeam/team-a", "Patient/patient-b"));
		}
		store(task("last-task", "CareTeam/team-a", "Patient/patient-a"));
		assertRejected(() -> update(careTeam("team-a", "Patient/patient-a"), careTeam("team-a", "Patient/patient-b")));
	}

	@Test
	void readsOwnerInThePersistedResourcePartition() {
		store(careTeam("team-a", "Patient/patient-a"));
		CareTeam other = careTeam("team-a", "Patient/patient-b");
		other.setUserData(Constants.RESOURCE_PARTITION_ID, RequestPartitionId.fromPartitionId(8));
		store(other);
		Task task = task("task-a", "CareTeam/team-a", "Patient/patient-b");
		task.setUserData(Constants.RESOURCE_PARTITION_ID, RequestPartitionId.fromPartitionId(8));
		assertThatCode(() -> interceptor.resourceCreated(task, request)).doesNotThrowAnyException();
	}

	@Test
	void doesNotValidateTasksFromAnotherPartitionDuringOwnerChange() {
		Task other = task("task-a", "CareTeam/team-a", "Patient/patient-a");
		other.setUserData(Constants.RESOURCE_PARTITION_ID, RequestPartitionId.fromPartitionId(8));
		store(other);
		assertThatCode(() -> update(careTeam("team-a", "Patient/patient-a"), careTeam("team-a", "Patient/patient-b")))
				.doesNotThrowAnyException();
	}

	@Test
	void taskAndOwnerWritesAcquireTheSameAssignmentLock() {
		store(careTeam("team-a", "Patient/patient-a"));
		interceptor.resourceCreating(task("task-a", "CareTeam/team-a/_history/2", "Patient/patient-a"), request, PARTITION);
		interceptor.resourceUpdating(careTeam("team-a", "Patient/patient-a"), careTeam("team-a", "Patient/patient-b"), request);
		assertThat(locks).hasSize(2);
		assertThat(locks.get(0)).isEqualTo(locks.get(1));
	}

	@Test
	void locksOldAndNewTaskOwnersInTheSameOrderForOppositeMoves() {
		Task first = task("task-a", "CareTeam/team-a", "Patient/patient-a");
		Task second = task("task-b", "RelatedPerson/relative-b", "Patient/patient-a");
		interceptor.resourceUpdating(first, second, request);
		interceptor.resourceUpdating(second, first, request);
		assertThat(locks).hasSize(4);
		assertThat(locks.subList(0, 2)).containsExactlyElementsOf(locks.subList(2, 4));
	}

	@Test
	void separatesOwnerLocksAcrossPartitions() {
		Task task = task("task-a", "CareTeam/team-a", "Patient/patient-a");
		interceptor.resourceCreating(task, request, PARTITION);
		interceptor.resourceCreating(task, request, RequestPartitionId.fromPartitionId(8));
		assertThat(locks).hasSize(2);
		assertThat(locks.get(0)).isNotEqualTo(locks.get(1));
	}

	@Test
	void rereadsOwnerAfterLockInsteadOfTrustingThePersistenceContextSnapshot() {
		CareTeam stale = careTeam("team-a", "Patient/patient-a");
		JpaPid pid = JpaPid.fromId(42L);
		IDao.RESOURCE_PID.put(stale, pid);
		store(stale);
		ResourceTable entity = new ResourceTable();
		when(entityManager.find(ResourceTable.class, pid)).thenReturn(entity);
		doAnswer(call -> {
			store(careTeam("team-a", "Patient/patient-b"));
			return null;
		}).when(entityManager).refresh(entity);
		assertRejected(() -> create(task("task-a", "CareTeam/team-a", "Patient/patient-a")));
	}

	@Test
	void doesNotTreatContainedPatientOwnerAsTheBeneficiary() {
		Task task = task("task-a", "#owner", "Patient/patient-a");
		Patient contained = new Patient();
		contained.setId("owner");
		task.addContained(contained);
		assertThatCode(() -> create(task)).doesNotThrowAnyException();
	}

	@Test
	void validatesContainedCareTeamOwner() {
		Task task = task("task-a", "#owner", "Patient/patient-b");
		CareTeam contained = careTeam("owner", "Patient/patient-a");
		contained.setId("owner");
		task.addContained(contained);
		assertRejected(() -> create(task));
	}

	@Test
	void acceptsMatchingContainedPatientIdentity() {
		Task task = task("task-a", "#owner", "#owner");
		Patient contained = new Patient();
		contained.setId("owner");
		task.addContained(contained);
		assertThatCode(() -> create(task)).doesNotThrowAnyException();
	}

	@Test
	void preservesLogicalPatientOwnershipWhenEqualityCannotBeEstablished() {
		Task task = task("task-a", null, "Patient/patient-a");
		task.getOwner().setType("Patient").getIdentifier().setSystem("urn:synthetic:patient").setValue("unresolved");
		assertThatCode(() -> create(task)).doesNotThrowAnyException();
	}

	@Test
	void preservesLegacyTaskWithoutForWhenEqualityCannotBeEstablished() {
		assertThatCode(() -> create(task("task-a", "Patient/patient-a", null))).doesNotThrowAnyException();
	}

	@Test
	void preservesLogicalTaskForWhenEqualityCannotBeEstablished() {
		Task task = task("task-a", "Patient/patient-a", null);
		task.getFor().setType("Patient").getIdentifier().setSystem("urn:synthetic:patient").setValue("unresolved");
		assertThatCode(() -> create(task)).doesNotThrowAnyException();
	}

	private void create(Task task) {
		interceptor.resourceCreating(task, request, PARTITION);
		interceptor.resourceCreated(task, request);
	}

	private void update(Resource previous, Resource updated) {
		interceptor.resourceUpdating(previous, updated, request);
		updated.setUserData(Constants.RESOURCE_PARTITION_ID, previous.getUserData(Constants.RESOURCE_PARTITION_ID));
		store(updated);
		interceptor.resourceUpdated(previous, updated, request);
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOf(UnprocessableEntityException.class);
	}

	private void store(Resource resource) {
		resources.put(key((RequestPartitionId) resource.getUserData(Constants.RESOURCE_PARTITION_ID), resource.getIdElement().toUnqualifiedVersionless().getValue()), resource);
	}

	private static String key(RequestPartitionId partition, String id) {
		return partition.toString() + ":" + id;
	}

	private static Task task(String id, String owner, String patient) {
		Task task = new Task().setOwner(new Reference(owner)).setFor(new Reference(patient));
		return identified(task, "Task/" + id);
	}

	/** What HAPI passes as the new version on a $meta-add: the stored Task, one tag richer. */
	private static Task withDeletedTag(Task stored) {
		Task tagged = stored.copy();
		tagged.getMeta().addTag("http://hl7.org/fhir/issue-type", "deleted", "Deleted");
		return tagged;
	}

	private static CareTeam careTeam(String id, String patient) {
		CareTeam team = new CareTeam();
		if (patient != null) team.setSubject(new Reference(patient));
		return identified(team, "CareTeam/" + id);
	}

	private static RelatedPerson relatedPerson(String id, String patient) {
		RelatedPerson person = new RelatedPerson();
		if (patient != null) person.setPatient(new Reference(patient));
		return identified(person, "RelatedPerson/" + id);
	}

	private static <T extends Resource> T identified(T resource, String id) {
		resource.setId(id);
		resource.setUserData(Constants.RESOURCE_PARTITION_ID, PARTITION);
		return resource;
	}
}
