package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.AnnouncementFactory;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.DeleteFlowAggregateLock;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.DeleteFlowLifecycleEvent;
import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.ParticipatingApps;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <p>
 * Records the aggregated state of the cleanup process per patient when an
 * application writes a status on its delete-pending Task. The individual status
 * write is already covered by the regular update AuditEvent; what is recorded
 * here is the transition of the process as a whole:
 * </p>
 * <ul>
 * <li>the first handbrake blocks the process, which is recorded as `hold`</li>
 * <li>the last handbrake released unblocks it, recorded as `unhold` - unless
 * that leaves every Task accepted, because then the early completion is reached
 * and the `destroy` of the erase itself is the next event</li>
 * </ul>
 * <p>
 * Judging the aggregate means reading the sibling Tasks, so concurrent status
 * writes for one patient are serialised on that patient; without it two
 * applications releasing at the same moment would each still see the other
 * holding and no unhold would be recorded at all.
 * </p>
 * <p>
 * The aggregate is judged inside the transaction of the status write, under the
 * patient lock, but the event is written after that transaction commits. Writing
 * it inside would join the same transaction, and a failed insert there marks it
 * rollback-only: the status write of the application would be lost over a failed
 * audit record. Transitions the Koppeltaalvoorziening drives itself are skipped;
 * the cleanup run records those.
 * </p>
 */
@Interceptor
public class DeleteFlowLifecycleAuditInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(DeleteFlowLifecycleAuditInterceptor.class);

	/** A truncated view of the tasks would misjudge the aggregate. */
	private static final int MAX_TASKS_PER_PATIENT = 2_000;

	private final DaoRegistry daoRegistry;
	private final AnnouncementFactory announcementFactory;
	private final ParticipatingApps participatingApps;
	private final DeleteFlowAggregateLock aggregateLock;
	private final TransactionTemplate ownTransaction;
	private final String site;

	private volatile IIdType serverDeviceId;

	public DeleteFlowLifecycleAuditInterceptor(DaoRegistry daoRegistry, AnnouncementFactory announcementFactory,
			ParticipatingApps participatingApps, DeleteFlowAggregateLock aggregateLock,
			PlatformTransactionManager transactionManager, String site) {

		this.daoRegistry = daoRegistry;
		this.announcementFactory = announcementFactory;
		this.participatingApps = participatingApps;
		this.aggregateLock = aggregateLock;
		this.ownTransaction = new TransactionTemplate(transactionManager);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.site = site;
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void resourceUpdated(IBaseResource previousResource, IBaseResource updatedResource,
			RequestDetails requestDetails) {

		if (requestDetails == null || requestDetails instanceof SystemRequestDetails)
			return;

		if (!(previousResource instanceof final Task stored) || !(updatedResource instanceof final Task updated))
			return;

		if (!DeleteFlowTaskUtil.isDeletePendingTask(stored))
			return;

		final Task.TaskStatus previous = stored.getStatus();
		final Task.TaskStatus current = updated.getStatus();

		final boolean pullsHandbrake = current == Task.TaskStatus.ONHOLD && previous != Task.TaskStatus.ONHOLD;
		final boolean releasesHandbrake = previous == Task.TaskStatus.ONHOLD && current != Task.TaskStatus.ONHOLD;

		if (!pullsHandbrake && !releasesHandbrake)
			return;

		final IIdType patientId = stored.getFor().getReferenceElement();

		if (!"Patient".equals(patientId.getResourceType()) || patientId.getIdPart() == null) {
			LOG.warn("Delete flow Task {} does not reference a Patient, skipping the aggregated lifecycle event",
					stored.getIdElement());
			return;
		}

		try {
			aggregateTransition(patientId, updated, pullsHandbrake)
					.ifPresent(lifecycleEvent -> recordOnceCommitted(lifecycleEvent, patientId));
		} catch (Exception e) {
			LOG.error("Could not judge the aggregated cleanup state for Task {}; "
					+ "the status write itself is kept", updated.getIdElement(), e);
		}
	}

	private Optional<DeleteFlowLifecycleEvent> aggregateTransition(IIdType patientId, Task updated,
			boolean pullsHandbrake) {

		aggregateLock.lockPatient(patientId.getIdPart());

		final List<Task> otherTasks = otherOpenDeleteFlowTasks(patientId, updated.getIdElement().getIdPart());

		if (otherTasks.stream().anyMatch(task -> task.getStatus() == Task.TaskStatus.ONHOLD))
			return Optional.empty(); // another application still holds, so the aggregate does not move

		if (pullsHandbrake)
			return Optional.of(DeleteFlowLifecycleEvent.BLOCKED);

		if (updated.getStatus() == Task.TaskStatus.ACCEPTED
				&& otherTasks.stream().allMatch(task -> task.getStatus() == Task.TaskStatus.ACCEPTED)) {

			LOG.info("Every delete-pending Task for {} is accepted, early completion reached", patientId);
			return Optional.empty();
		}

		return Optional.of(DeleteFlowLifecycleEvent.UNBLOCKED);
	}

	private void recordOnceCommitted(DeleteFlowLifecycleEvent lifecycleEvent, IIdType patientId) {

		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			record(lifecycleEvent, patientId);
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					record(lifecycleEvent, patientId);
				} catch (Exception e) {
					LOG.error("Could not record the aggregated cleanup lifecycle event '{}' for {}; "
							+ "the status write itself is kept", lifecycleEvent.getCode(), patientId, e);
				}
			}
		});
	}

	/**
	 * In its own transaction. After a commit the original one is still bound but
	 * finished, so anything written there would participate in a transaction that
	 * never commits again - Spring's own contract for
	 * {@link TransactionSynchronization#afterCommit()} calls for REQUIRES_NEW.
	 */
	private void record(DeleteFlowLifecycleEvent lifecycleEvent, IIdType patientId) {

		final AuditEvent event = announcementFactory.buildLifecycleEvent(lifecycleEvent, patientId,
				serverDeviceId(), site);

		ownTransaction.executeWithoutResult(
				status -> daoRegistry.getResourceDao(AuditEvent.class).create(event, new SystemRequestDetails()));

		LOG.info("Recorded aggregated cleanup lifecycle event '{}' for {}", lifecycleEvent.getCode(), patientId);
	}

	/** The observer Device does not change while the server runs. */
	private IIdType serverDeviceId() {

		if (serverDeviceId == null) {
			serverDeviceId = participatingApps.serverDeviceId();
		}
		return serverDeviceId;
	}

	/**
	 * The other delete-pending Tasks of this patient that still take part in the
	 * running cycle. Cancelled and completed Tasks are kept for traceability but
	 * no longer weigh in on the aggregate.
	 */
	private List<Task> otherOpenDeleteFlowTasks(IIdType patientId, String excludedTaskIdPart) {

		final SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Task.SP_PATIENT, new ReferenceParam("Patient/" + patientId.getIdPart()))
				.add(Task.SP_CODE, new TokenParam(DeleteFlowTaskUtil.TASK_CODE_SYSTEM,
						DeleteFlowTaskUtil.DELETE_PENDING_CODE));
		query.setCount(MAX_TASKS_PER_PATIENT);

		final List<IBaseResource> found = daoRegistry.getResourceDao(Task.class)
				.search(query, new SystemRequestDetails()).getResources(0, MAX_TASKS_PER_PATIENT);

		if (found.size() >= MAX_TASKS_PER_PATIENT) {
			throw new IllegalStateException(String.format(
					"Patient/%s has %d or more delete-pending tasks; refusing to judge the aggregate on a truncated view",
					patientId.getIdPart(), MAX_TASKS_PER_PATIENT));
		}

		return found.stream()
				.map(Task.class::cast)
				.filter(task -> !task.getIdElement().getIdPart().equals(excludedTaskIdPart))
				.filter(DeleteFlowLifecycleAuditInterceptor::takesPartInCycle)
				.collect(Collectors.toList());
	}

	private static boolean takesPartInCycle(Task task) {
		return task.getStatus() == Task.TaskStatus.REQUESTED
				|| task.getStatus() == Task.TaskStatus.ONHOLD
				|| task.getStatus() == Task.TaskStatus.ACCEPTED;
	}
}
