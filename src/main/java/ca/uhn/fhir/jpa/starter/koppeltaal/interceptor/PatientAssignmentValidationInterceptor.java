package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
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
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;

/**
 * Rejects contradictory patient assignments for storage writes, including DAO transactions.
 * References without an established patient identity retain ordinary profile validation.
 * Per-owner transaction locks protect concurrent writes at READ COMMITTED isolation.
 */
@Interceptor(order = -1000)
public class PatientAssignmentValidationInterceptor {

	private static final int PAGE_SIZE = 200;
	private static final int LOCK_NAMESPACE = "kt2-patient-assignment".hashCode();
	private static final Set<String> PATIENT_BOUND_OWNERS = Set.of("CareTeam", "RelatedPerson");

	private final DaoRegistry registry;
	private final IRequestPartitionHelperSvc partitions;
	private final JpaStorageSettings settings;
	private final EntityManager entityManager;

	public PatientAssignmentValidationInterceptor(DaoRegistry registry, IRequestPartitionHelperSvc partitions,
			JpaStorageSettings settings, EntityManager entityManager) {
		this.registry = registry;
		this.partitions = partitions;
		this.settings = settings;
		this.entityManager = entityManager;
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void resourceCreating(IBaseResource resource, RequestDetails request, RequestPartitionId partition) {
		if (resource instanceof Task task) {
			lockOwners(partition, request, task.getOwner());
		}
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void resourceUpdating(IBaseResource previous, IBaseResource updated, RequestDetails request) {
		if (previous instanceof Task stored && updated instanceof Task incoming) {
			lockOwners(partitionOf(previous, request), request, stored.getOwner(), incoming.getOwner());
		} else if (patientChanged(previous, updated, request)) {
			lockOwners(partitionOf(previous, request), request,
					new Reference(updated.getIdElement().toUnqualifiedVersionless().getValue()));
		}
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void resourceCreated(IBaseResource resource, RequestDetails request) {
		if (resource instanceof Task task) {
			validateTask(task, request);
		}
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void resourceUpdated(IBaseResource previous, IBaseResource updated, RequestDetails request) {
		if (updated instanceof Task task) {
			if (!onlyMetaChanged(previous, task)) validateTask(task, request);
		} else if (patientChanged(previous, updated, request)) {
			validateOwnedTasks((DomainResource) updated, request);
		}
	}

	/**
	 * $meta-add and $meta-delete fire this hook with the stored Task on both sides. A tag cannot change the
	 * patient assignment, and a Task kept for manual review must still take its review tag.
	 */
	private static boolean onlyMetaChanged(IBaseResource previous, Task updated) {
		if (!(previous instanceof Task stored)) return false;
		Task before = stored.copy();
		Task after = updated.copy();
		before.setMeta(null);
		after.setMeta(null);
		return before.equalsDeep(after);
	}

	private void validateTask(Task task, RequestDetails request) {
		if (!task.hasOwner()) return;
		Reference ownerReference = task.getOwner();
		if (!ownerReference.hasReference()) return;
		Resource containedOwner = containedResource(task, ownerReference);
		String ownerType = containedOwner == null ? referenceType(ownerReference) : containedOwner.fhirType();
		if (ownerType == null || !PATIENT_BOUND_OWNERS.contains(ownerType)) return;

		DomainResource owner;
		if (containedOwner != null) {
			owner = (DomainResource) containedOwner;
		} else {
			IIdType ownerId = localOwnerId(ownerReference, request);
			if (ownerId == null) return;
			RequestPartitionId partition = partitionOf(task, request);
			// HAPI substitutes transaction UUIDs before the deferred precommit hooks.
			lockOwners(partition, request, ownerReference);
			SystemRequestDetails readRequest = SystemRequestDetails.forRequestPartitionId(partition);
			try {
				owner = "CareTeam".equals(ownerType)
						? readCurrent(registry.getResourceDao(CareTeam.class), ownerId, readRequest)
						: readCurrent(registry.getResourceDao(RelatedPerson.class), ownerId, readRequest);
			} catch (ResourceNotFoundException | ResourceGoneException missingOwner) {
				throw new UnprocessableEntityException("Task.owner must resolve locally to validate its patient assignment");
			}
		}
		Reference patient = patientReference(owner);
		if (owner instanceof CareTeam && patient == null) return;
		DomainResource patientContainer = containedOwner == null ? owner : task;
		requireSamePatient(task, patient, patientContainer, patientPath(owner), request);
	}

	private void validateOwnedTasks(DomainResource owner, RequestDetails request) {
		Reference patient = patientReference(owner);
		if (owner instanceof CareTeam && patient == null) return;
		RequestPartitionId partition = partitionOf(owner, request);
		String ownerId = owner.getIdElement().toUnqualifiedVersionless().getValue();
		lockOwners(partition, request, new Reference(ownerId));
		IFhirResourceDao<Task> taskDao = registry.getResourceDao(Task.class);
		SystemRequestDetails searchRequest = SystemRequestDetails.forRequestPartitionId(partition);
		for (int offset = 0; ; offset += PAGE_SIZE) {
			SearchParameterMap search = SearchParameterMap.newSynchronous()
					.add(Task.SP_OWNER, new ReferenceParam(ownerId));
			search.setCount(PAGE_SIZE);
			search.setOffset(offset);
			search.setSort(new SortSpec(Task.SP_RES_ID));
			// BaseHapiFhirResourceDao.setOffsetAndCount also consults RequestDetails.
			searchRequest.setParameters(Map.of(Constants.PARAM_COUNT, new String[] {Integer.toString(PAGE_SIZE)},
					Constants.PARAM_OFFSET, new String[] {Integer.toString(offset)}));
			List<IBaseResource> tasks = taskDao.search(search, searchRequest).getResources(0, PAGE_SIZE);
			for (IBaseResource resource : tasks) {
				Task task = refreshCurrent(taskDao, (Task) resource, searchRequest);
				requireSamePatient(task, patient, owner, patientPath(owner), request);
			}
			if (tasks.size() < PAGE_SIZE) return;
		}
	}

	private void requireSamePatient(Task task, Reference ownerPatient, DomainResource ownerContainer,
			String ownerPath, RequestDetails request) {
		String taskPatient = patientIdentity(task.getFor(), task, request);
		String assignedPatient = patientIdentity(ownerPatient, ownerContainer, request);
		if (taskPatient != null && assignedPatient != null && !taskPatient.equals(assignedPatient)) {
			throw new UnprocessableEntityException("Task.for must reference the same Patient as " + ownerPath);
		}
	}

	private String patientIdentity(Reference reference, DomainResource container, RequestDetails request) {
		if (reference == null || !reference.hasReference()) return null;
		if (reference.getReference().startsWith("#")) {
			Resource contained = containedResource(container, reference);
			return contained != null && "Patient".equals(contained.fhirType())
					? container.getIdElement().toUnqualifiedVersionless().getValue() + reference.getReference() : null;
		}
		return "Patient".equals(reference.getReferenceElement().getResourceType())
				? referenceIdentity(reference, request) : null;
	}

	private boolean patientChanged(IBaseResource previous, IBaseResource updated, RequestDetails request) {
		if (!(updated instanceof CareTeam) && !(updated instanceof RelatedPerson)) return false;
		Reference before = patientReference(previous);
		Reference after = patientReference(updated);
		String beforeId = referenceIdentity(before, request);
		String afterId = referenceIdentity(after, request);
		if (beforeId != null || afterId != null) return !Objects.equals(beforeId, afterId);
		return before == null ? after != null : after == null || !before.equalsDeep(after);
	}

	private static Reference patientReference(IBaseResource owner) {
		if (owner instanceof CareTeam team) return team.hasSubject() ? team.getSubject() : null;
		if (owner instanceof RelatedPerson person) return person.hasPatient() ? person.getPatient() : null;
		return null;
	}

	private static String patientPath(IBaseResource owner) {
		return owner instanceof CareTeam ? "CareTeam.subject" : "RelatedPerson.patient";
	}

	private static Resource containedResource(DomainResource container, Reference reference) {
		if (!reference.hasReference() || !reference.getReference().startsWith("#")) return null;
		String containedId = reference.getReference().substring(1);
		return container.getContained().stream()
				.filter(resource -> containedId.equals(resource.getIdElement().getIdPart())
						|| ("#" + containedId).equals(resource.getIdElement().getIdPart()))
				.findFirst().orElse(null);
	}

	private static String referenceType(Reference reference) {
		String type = reference.getReferenceElement().getResourceType();
		return type != null ? type : reference.getType();
	}

	private String referenceIdentity(Reference reference, RequestDetails request) {
		if (reference == null || !reference.hasReference()) return null;
		IIdType id = reference.getReferenceElement();
		return isLocal(id, request) ? id.toUnqualifiedVersionless().getValue() : id.toVersionless().getValue();
	}

	private IIdType localOwnerId(Reference reference, RequestDetails request) {
		IIdType id = reference.getReferenceElement();
		return id.getResourceType() != null && PATIENT_BOUND_OWNERS.contains(id.getResourceType())
				&& id.hasIdPart() && (!id.hasBaseUrl() || settings.getTreatBaseUrlsAsLocal().contains(id.getBaseUrl()))
				? new IdType(id.toUnqualifiedVersionless().getValue()) : null;
	}

	private boolean isLocal(IIdType id, RequestDetails request) {
		return !id.hasBaseUrl() || settings.getTreatBaseUrlsAsLocal().contains(id.getBaseUrl())
				|| (request != null && Objects.equals(id.getBaseUrl(), request.getFhirServerBase()));
	}

	private RequestPartitionId partitionOf(IBaseResource resource, RequestDetails request) {
		Object storedPartition = resource.getUserData(Constants.RESOURCE_PARTITION_ID);
		if (storedPartition instanceof RequestPartitionId partition) return partition;
		return partitions.determineReadPartitionForRequestForRead(request, resource.getIdElement());
	}

	private void lockOwners(RequestPartitionId partition, RequestDetails request, Reference... references) {
		TreeSet<String> ownerIds = new TreeSet<>();
		for (Reference reference : references) {
			IIdType id = localOwnerId(reference, request);
			if (id != null) ownerIds.add(id.getValue());
		}
		if (ownerIds.isEmpty()) return;
		String partitionKey = partition.isAllPartitions() ? "*" : partition.getPartitionIds().toString();
		for (String ownerId : ownerIds) {
			// Hash collisions serialize unrelated owners; they do not weaken the guard.
			entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1, ?2)")
					.setParameter(1, LOCK_NAMESPACE)
					.setParameter(2, (partitionKey + ":" + ownerId).hashCode())
					.getSingleResult();
		}
	}

	private <T extends Resource> T readCurrent(IFhirResourceDao<T> dao, IIdType id, RequestDetails request) {
		return refreshCurrent(dao, dao.read(id, request), request);
	}

	private <T extends Resource> T refreshCurrent(IFhirResourceDao<T> dao, T resource, RequestDetails request) {
		JpaPid pid = IDao.RESOURCE_PID.get(resource);
		if (pid == null) return resource;
		entityManager.flush();
		ResourceTable entity = entityManager.find(ResourceTable.class, pid);
		entityManager.refresh(entity);
		// HAPI's parser caches the current history row in this transient field.
		entity.setCurrentVersionEntity(null);
		return dao.read(resource.getIdElement().toUnqualifiedVersionless(), request);
	}
}
