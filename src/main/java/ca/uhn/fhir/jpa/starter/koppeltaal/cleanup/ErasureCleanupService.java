package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.util.JsonUtil;
import jakarta.persistence.EntityManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Task;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <p>
 * The run that watches the announced deletions and carries them out. Per
 * patient with a running cycle of delete-pending Tasks:
 * </p>
 * <ul>
 * <li>a hold before the grace deadline: nothing happens yet</li>
 * <li>a hold at the grace deadline: the holds are wiped and the grace period
 * restarts, so an application that wants to keep blocking has to pull the
 * handbrake again with a reason</li>
 * <li>no hold, and either the deadline passed or every Task accepted: the auth
 * check is run once more, and the deletion either aborts on renewed engagement
 * or goes ahead</li>
 * </ul>
 * <p>
 * A dry run evaluates all of that and lists what it would remove, without
 * writing anything.
 * </p>
 */
public class ErasureCleanupService implements CleanupService {

	private static final Logger LOG = LoggerFactory.getLogger(ErasureCleanupService.class);
	private static final int PAGE_SIZE = 500;

	public enum Outcome {
		/** Blocked by a handbrake, or the grace period is still running. */
		WAITING,

		/** A hold outlived the deadline: holds wiped, grace restarted. */
		GRACE_RESET,
		/** Renewed engagement: the announcement is cancelled and the clock restarts. */
		ABORTED,
		/** The patient was removed; blocked resources stayed behind for manual review. */
		ERASED
	}

	private final DaoRegistry daoRegistry;
	private final CleanupProperties properties;
	private final EligibilityService eligibilityService;
	private final AnnouncementFactory announcementFactory;
	private final PatientDeletionScope deletionScope;
	private final ParticipatingApps participatingApps;
	private final DeleteFlowAggregateLock aggregateLock;
	private final ResourceEraser eraser;
	private final DeleteFlowInventory inventory;
	private final TransactionTemplate transactions;
	private final EntityManager entityManager;
	private final String site;

	public ErasureCleanupService(DaoRegistry daoRegistry, CleanupProperties properties,
			EligibilityService eligibilityService, AnnouncementFactory announcementFactory,
			PatientDeletionScope deletionScope, ParticipatingApps participatingApps,
			DeleteFlowAggregateLock aggregateLock, ResourceEraser eraser, DeleteFlowInventory inventory,
			PlatformTransactionManager transactionManager, EntityManager entityManager, String site) {

		this.daoRegistry = daoRegistry;
		this.properties = properties;
		this.eligibilityService = eligibilityService;
		this.announcementFactory = announcementFactory;
		this.deletionScope = deletionScope;
		this.participatingApps = participatingApps;
		this.aggregateLock = aggregateLock;
		this.eraser = eraser;
		this.inventory = inventory;
		this.transactions = new TransactionTemplate(transactionManager);
		this.entityManager = entityManager;
		// A referrer created after the conflict check but before the link rows go
		// would otherwise survive pointing at an erased patient, silently: at read
		// committed the erase deletes that new link row and commits. On a snapshot
		// the row stays invisible, the patient cannot go, and the run reports a
		// failure it can retry instead. Measured both ways.
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.site = site;
	}

	@Override
	public CleanupRunReport run(boolean dryRun, Consumer<CleanupRunReport> progressListener) {
		return run(dryRun, Clock.systemUTC(), progressListener);
	}

	/**
	 * The clock is the seam that lets a test harness stand at a later date and
	 * watch a grace period expire without waiting a month for it.
	 */
	public CleanupRunReport run(boolean dryRun, Clock clock, Consumer<CleanupRunReport> progressListener) {

		final Instant startedAt = Instant.now();
		final Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
		final List<String> errors = new ArrayList<>();
		final List<String> affected = new ArrayList<>();
		final Map<String, Integer> dispositions = new LinkedHashMap<>();

		final List<String> patients = inventory.patientsWithARunningCycle();
		LOG.info("Erasure run starting (dryRun={}, patients with a running cycle={})", dryRun, patients.size());
		progressListener.accept(report(CleanupRunReport.RunStatus.RUNNING, startedAt, null, 0,
				patients.size(), outcomes, dispositions, errors, dryRun));

		int evaluated = 0;
		for (String patientIdPart : patients) {
			try {
				final Affected patientResources = new Affected();
				final Outcome outcome = handle(patientIdPart, dryRun, clock, patientResources);
				affected.addAll(patientResources.lines);
				patientResources.dispositions.forEach((disposition, count) -> dispositions.merge(disposition, count, Integer::sum));
				outcomes.merge(outcome, 1, Integer::sum);
			} catch (Exception e) {
				errors.add(String.format("Patient/%s: %s", patientIdPart, e.getMessage()));
				LOG.error("Erasure failed for Patient/{}", patientIdPart, e);
			}
			evaluated++;
			if (evaluated % 25 == 0) {
				progressListener.accept(report(CleanupRunReport.RunStatus.RUNNING, startedAt, null, evaluated,
						patients.size(), outcomes, dispositions, errors, dryRun));
			}
		}

		final CleanupRunReport finished = report(CleanupRunReport.RunStatus.COMPLETED, startedAt, Instant.now(),
				evaluated, patients.size(), outcomes, dispositions, errors, dryRun);
		finished.setDetailsFile(writeDetails(affected, dryRun, startedAt));
		return finished;
	}

	/**
	 * The list runs to tens of thousands of lines, which does not belong in an HTTP
	 * response; it is written where it can be kept and compared between runs.
	 */
	private String writeDetails(List<String> affected, boolean dryRun, Instant startedAt) {

		if (affected.isEmpty()) {
			return null;
		}
		final Path file = Path.of(System.getProperty("java.io.tmpdir"),
				String.format("kt2-erasure-%s-%d.ndjson", dryRun ? "dryrun" : "run", startedAt.toEpochMilli()));
		try (BufferedWriter writer = Files.newBufferedWriter(file)) {
			for (String line : affected) {
				writer.write(line);
				writer.newLine();
			}
		} catch (IOException e) {
			LOG.error("Could not write the affected-resource list to {}", file, e);
			return null;
		}
		LOG.info("Wrote {} affected resources to {}", affected.size(), file);
		return file.toString();
	}

	private Outcome handle(String patientIdPart, boolean dryRun, Clock clock, Affected affected) {

		if (dryRun) {
			final List<Task> cycle = cycleFor(patientIdPart);
			return cycle.isEmpty() ? Outcome.WAITING : decide(patientIdPart, cycle, true, clock, affected);
		}

		// OPG-011: the lock keeps the window between the auth check and the erase
		// to ourselves. It is a transaction-scoped advisory lock, so it only holds
		// from inside one - taken outside it would be released before the first
		// write. The cycle is read again under it, because the copy the run started
		// with may have picked up a hold since.
		return transactions.execute(status -> {
			// Set only this PostgreSQL transaction's isolation, before its first query.
			// Spring's isolation setter requires globally holding Hibernate connections
			// through afterCommit, where lifecycle auditing needs a second connection.
			entityManager.unwrap(Session.class).doWork(connection -> {
				try (var statement = connection.createStatement()) {
					statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
				}
			});
			aggregateLock.lockPatient(patientIdPart);
			final List<Task> current = cycleFor(patientIdPart);
			return current.isEmpty() ? Outcome.WAITING : decide(patientIdPart, current, false, clock, affected);
		});
	}

	private Outcome decide(String patientIdPart, List<Task> cycle, boolean dryRun, Clock clock,
			Affected affected) {

		final IIdType patientId = new IdType("Patient", patientIdPart);
		final boolean held = cycle.stream().anyMatch(task -> task.getStatus() == Task.TaskStatus.ONHOLD);
		final boolean allAccepted = cycle.stream().allMatch(task -> task.getStatus() == Task.TaskStatus.ACCEPTED);
		final boolean deadlinePassed = clock.instant().isAfter(graceDeadline(cycle));

		if (held) {
			return deadlinePassed ? graceReset(patientId, cycle, dryRun, clock) : Outcome.WAITING;
		}

		if (!deadlinePassed && !allAccepted) {
			return Outcome.WAITING;
		}

		if (eligibilityService.hasRenewedEngagement(patientId, clock.instant())) {
			return abort(patientId, cycle, dryRun);
		}

		return erase(patientId, cycle, dryRun, affected);
	}

	/**
	 * OPG-010: a hold that outlives the deadline wipes every hold and restarts the grace period. Only the holds are
	 * wiped: an application that accepted is not asked again. The new deadline goes on every Task of the cycle.
	 */
	private Outcome graceReset(IIdType patientId, List<Task> cycle, boolean dryRun, Clock clock) {

		if (!dryRun) {
			final Date deadline = Date.from(clock.instant().plus(properties.getGraceDays(), ChronoUnit.DAYS));
			for (Task task : cycle) {
				final Task reset = task.copy();
				if (reset.getStatus() == Task.TaskStatus.ONHOLD) {
					reset.setStatus(Task.TaskStatus.REQUESTED);
					reset.setStatusReason(null); // OPG-009
				}
				reset.setRestriction(new Task.TaskRestrictionComponent().setPeriod(new Period().setEnd(deadline)));
				store(reset);
			}
			record(DeleteFlowLifecycleEvent.GRACE_RESET, patientId);
		}
		LOG.info("Grace period restarted for {}; every hold was wiped", patientId);
		return Outcome.GRACE_RESET;
	}

	/** OPG-011: renewed engagement stops the deletion and the retention clock restarts. */
	private Outcome abort(IIdType patientId, List<Task> cycle, boolean dryRun) {

		if (!dryRun) {
			for (Task task : cycle) {
				final Task cancelled = task.copy();
				cancelled.setStatus(Task.TaskStatus.CANCELLED);
				cancelled.setStatusReason(null);
				store(cancelled);
			}
			record(DeleteFlowLifecycleEvent.REACTIVATED, patientId);
		}
		LOG.info("Deletion aborted for {}: the patient is engaged again", patientId);
		return Outcome.ABORTED;
	}

	private Outcome erase(IIdType patientId, List<Task> cycle, boolean dryRun, Affected affected) {

		final PatientDeletionScope.Plan plan = deletionScope.planFor(patientId);
		final List<IIdType> resources = plan.resources();
		final List<IIdType> blocked = plan.blockers().stream().map(PatientDeletionScope.Blocker::resourceId).toList();

		plan.blockers().forEach(blocker -> affected.add(row(patientId, blocker.resourceId(), "blocked", blocker.blockReason())));
		resources.forEach(id -> affected.add(row(patientId, id, "erase", null)));
		plan.detachments().forEach(removal -> affected.add(detachmentRow(patientId, removal)));
		plan.retainedTaskOwners().forEach(owner -> affected.add(retainedTaskOwnerRow(patientId, owner)));

		if (!dryRun) {
			final var taskDao = daoRegistry.getResourceDao(Task.class);
			final List<TaskOwnerTagging.TagAddition> retainedTasks = plan.retainedTaskOwners().stream().map(owner ->
					TaskOwnerTagging.prepare(taskDao.read(owner.taskId(), new SystemRequestDetails()),
							owner.patientReference())).toList();
			final List<IIdType> reviewTags = blocked.stream().filter(this::stillExists).toList();
			final var careTeamDao = daoRegistry.getResourceDao(CareTeam.class);
			final List<CareTeam> retainedTeams = plan.detachments().stream().map(removal ->
					CareTeamParticipantRemover.remove(careTeamDao.read(removal.careTeamId(), new SystemRequestDetails()),
							removal.references())).toList();
			for (CareTeam retainedTeam : retainedTeams) {
				careTeamDao.update(retainedTeam, new ErasureRequestDetails());
			}
			for (TaskOwnerTagging.TagAddition update : retainedTasks) {
				taskDao.metaAddOperation(update.taskId(), update.meta(), new SystemRequestDetails());
			}
			// The same tag as a retained Task, so a blocked resource can be found for its review.
			for (IIdType resource : reviewTags) {
				daoRegistry.getResourceDao(resource.getResourceType())
						.metaAddOperation(resource, TaskOwnerTagging.deletedReferenceTag(), new SystemRequestDetails());
			}
			// All of it commits together or none of it does. Completing the Tasks
			// outside the erase would strand the cycle: a later failure leaves them
			// COMPLETED, and takesPartInCycle() then skips the patient forever. And
			// OPG-013 wants proof the erase happened, so the destroy event belongs
			// here too - erased data whose record of erasure rolled back is worse
			// than either outcome on its own.
			for (Task task : cycle) {
				final Task completed = task.copy();
				completed.setStatus(Task.TaskStatus.COMPLETED);
				store(completed);
			}
			eraser.erase(resources, plan.retainedTaskOwners(), blocked);
			record(DeleteFlowLifecycleEvent.DESTROYED, patientId);
		}
		LOG.info("{} {} resources for {}", dryRun ? "Would remove" : "Removed", resources.size(), patientId);
		if (!blocked.isEmpty()) {
			LOG.warn("{} blocked resources kept for manual review while erasing {}", blocked.size(), patientId);
		}
		return Outcome.ERASED;
	}

	/** A deleted resource needs no tag: searches no longer find it and it has no index rows left. */
	private boolean stillExists(IIdType resourceId) {
		try {
			daoRegistry.getResourceDao(resourceId.getResourceType()).read(resourceId, new SystemRequestDetails());
			return true;
		} catch (ResourceGoneException gone) {
			return false;
		}
	}

	private static Map<String, Object> detachmentRow(IIdType patientId, PatientDeletionScope.ParticipantRemoval removal) {
		final Map<String, Object> row = new LinkedHashMap<>();
		row.put("patient", patientId.getIdPart());
		row.put("resourceType", "CareTeam");
		row.put("id", removal.careTeamId().getIdPart());
		row.put("disposition", "detach");
		row.put("removedReferences", removal.references().stream().sorted().toList());
		return row;
	}

	private static Map<String, Object> retainedTaskOwnerRow(IIdType patientId, PatientDeletionScope.RetainedTaskOwner owner) {
		final Map<String, Object> row = new LinkedHashMap<>();
		row.put("patient", patientId.getIdPart());
		row.put("resourceType", "Task");
		row.put("id", owner.taskId().getIdPart());
		row.put("disposition", "tag-deleted-owner");
		row.put("reason", "Task belongs to another patient. Add issue-type#deleted to meta.tag; preserve Task content, status, version and lastUpdated.");
		return row;
	}

	private static Map<String, Object> row(IIdType patientId, IIdType resourceId, String disposition, String blockReason) {
		final Map<String, Object> row = new LinkedHashMap<>();
		row.put("patient", patientId.getIdPart());
		row.put("resourceType", resourceId.getResourceType());
		row.put("id", resourceId.getIdPart());
		row.put("disposition", disposition);
		if (blockReason != null) row.put("blockReason", blockReason);
		return row;
	}

	/** One patient's lines for the details file, counted per disposition for the report. */
	private static final class Affected {
		private final List<String> lines = new ArrayList<>();
		private final Map<String, Integer> dispositions = new LinkedHashMap<>();

		private void add(Map<String, Object> row) {
			lines.add(JsonUtil.serialize(row, false));
			dispositions.merge((String) row.get("disposition"), 1, Integer::sum);
		}
	}

	/**
	 * The grace deadline of the cycle. Every Task of one announcement carries the
	 * same deadline, but the latest is the safe one to wait for.
	 */
	private static Instant graceDeadline(List<Task> cycle) {
		return cycle.stream()
				.map(task -> task.getRestriction().getPeriod().getEnd())
				.filter(end -> end != null)
				.map(Date::toInstant)
				.max(Instant::compareTo)
				.orElse(Instant.MAX);
	}

	/**
	 * This patient's running cycle, read fresh. The run's own copy was taken
	 * before the lock, so a hold placed in between would otherwise go unseen.
	 */
	private List<Task> cycleFor(String patientIdPart) {

		final SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Task.SP_CODE, new TokenParam(DeleteFlowTaskUtil.TASK_CODE_SYSTEM,
						DeleteFlowTaskUtil.DELETE_PENDING_CODE))
				.add(Task.SP_PATIENT, new ReferenceParam("Patient/" + patientIdPart));
		query.setCount(PAGE_SIZE);

		final List<IBaseResource> found = daoRegistry.getResourceDao(Task.class)
				.search(query, new SystemRequestDetails()).getResources(0, PAGE_SIZE);

		if (found.size() >= PAGE_SIZE) {
			throw new IllegalStateException(String.format(
					"Patient/%s has %d or more delete-pending Tasks; refusing to judge the cycle on a truncated view",
					patientIdPart, PAGE_SIZE));
		}

		return found.stream()
				.map(Task.class::cast)
				.filter(ErasureCleanupService::takesPartInCycle)
				.toList();
	}

	/** Cancelled Tasks from an earlier, aborted cycle are kept but no longer count. */
	private static boolean takesPartInCycle(Task task) {
		return task.getStatus() == Task.TaskStatus.REQUESTED
				|| task.getStatus() == Task.TaskStatus.ONHOLD
				|| task.getStatus() == Task.TaskStatus.ACCEPTED;
	}

	private void store(Task task) {
		daoRegistry.getResourceDao(Task.class).update(task, new SystemRequestDetails());
	}

	private void record(DeleteFlowLifecycleEvent lifecycleEvent, IIdType patientId) {
		final AuditEvent event = announcementFactory.buildLifecycleEvent(lifecycleEvent, patientId,
				participatingApps.serverDeviceId(), site);
		daoRegistry.getResourceDao(AuditEvent.class).create(event, new SystemRequestDetails());
	}

	private CleanupRunReport report(CleanupRunReport.RunStatus status, Instant startedAt, Instant finishedAt,
			int evaluated, int total, Map<Outcome, Integer> outcomes, Map<String, Integer> dispositions,
			List<String> errors, boolean dryRun) {

		final Map<String, Integer> counts = new LinkedHashMap<>();
		for (Outcome outcome : Outcome.values()) {
			counts.put(outcome.name(), outcomes.getOrDefault(outcome, 0));
		}
		// Only what is erased counts as a resource; what stays behind is reported on its own line.
		counts.put("RESOURCES", dispositions.getOrDefault("erase", 0));
		counts.put("RETAINED", dispositions.getOrDefault("tag-deleted-owner", 0));
		counts.put("DETACHED", dispositions.getOrDefault("detach", 0));
		counts.put("BLOCKED_RESOURCES", dispositions.getOrDefault("blocked", 0));

		return new CleanupRunReport(status, startedAt, finishedAt, evaluated,
				outcomes.getOrDefault(Outcome.ERASED, 0), 0, 0, errors, dryRun, total, counts);
	}
}
