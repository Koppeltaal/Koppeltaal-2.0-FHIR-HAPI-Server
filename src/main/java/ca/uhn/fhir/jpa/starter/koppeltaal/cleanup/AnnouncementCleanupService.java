package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The announcement run: evaluates every patient, and for each eligible one
 * creates the delete-pending Task per participating app plus one archive
 * AuditEvent, in a single transaction per patient. Idempotent per patient×app
 * pair; a dry run evaluates and counts without writing.
 */
public class AnnouncementCleanupService implements CleanupService {

	private static final Logger LOG = LoggerFactory.getLogger(AnnouncementCleanupService.class);
	private static final int MAX_PATIENTS = 10_000;

	private final DaoRegistry daoRegistry;
	private final ParticipatingApps participatingApps;
	private final EligibilityService eligibilityService;
	private final AnnouncementFactory announcementFactory;
	private final String site;

	public AnnouncementCleanupService(DaoRegistry daoRegistry, ParticipatingApps participatingApps,
			EligibilityService eligibilityService, AnnouncementFactory announcementFactory, String site) {
		this.daoRegistry = daoRegistry;
		this.participatingApps = participatingApps;
		this.eligibilityService = eligibilityService;
		this.announcementFactory = announcementFactory;
		this.site = site;
	}

	@Override
	public CleanupRunReport run(boolean dryRun, java.util.function.Consumer<CleanupRunReport> progressListener) {
		Instant startedAt = Instant.now();
		List<IIdType> apps = participatingApps.participatingDeviceIds();
		IIdType serverDevice = participatingApps.serverDeviceId();
		LOG.info("Cleanup announcement run starting (dryRun={}, participating apps={})", dryRun, apps.size());

		int evaluated = 0;
		int eligible = 0;
		int tasksCreated = 0;
		int eventsCreated = 0;
		List<String> errors = new ArrayList<>();

		List<IIdType> patientIds = allPatientIds();
		eligibilityService.beginRun();
		LOG.info("Evaluating {} patients", patientIds.size());
		progressListener.accept(new CleanupRunReport(CleanupRunReport.RunStatus.RUNNING, startedAt, null,
				0, 0, 0, 0, errors, dryRun, patientIds.size()));
		for (IIdType patientId : patientIds) {
			evaluated++;
			if (evaluated % 50 == 0) {
				LOG.info("Progress: {}/{} patients evaluated, {} eligible, {} tasks planned",
						evaluated, patientIds.size(), eligible, tasksCreated);
			}
			try {
				if (!eligibilityService.isEligible(patientId)) {
					continue;
				}
				eligible++;
				Set<String> existingPairs = existingDeletePendingOwners(patientId);
				List<IIdType> missingApps = new ArrayList<>();
				for (IIdType app : apps) {
					if (!existingPairs.contains(app.getIdPart())) {
						missingApps.add(app);
					}
				}
				boolean firstAnnouncement = existingPairs.isEmpty();
				if (missingApps.isEmpty()) {
					continue;
				}
				if (!dryRun) {
					// One FHIR transaction bundle per patient: atomic, and far less
					// per-resource overhead than individual DAO creates.
					Bundle bundle = new Bundle();
					bundle.setType(Bundle.BundleType.TRANSACTION);
					for (IIdType app : missingApps) {
						bundle.addEntry()
								.setResource(announcementFactory.buildDeletePendingTask(patientId, app, serverDevice))
								.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Task");
					}
					if (firstAnnouncement) {
						bundle.addEntry()
								.setResource(announcementFactory.buildArchiveEvent(patientId, serverDevice, site))
								.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("AuditEvent");
					}
					daoRegistry.getSystemDao().transaction(new SystemRequestDetails(), bundle);
				}
				tasksCreated += missingApps.size();
				if (firstAnnouncement) {
					eventsCreated++;
				}
			} catch (Exception e) {
				LOG.error(String.format("Announcement failed for Patient/%s, continuing", patientId.getIdPart()), e);
				String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
				errors.add(String.format("Patient/%s: %s", patientId.getIdPart(), message));
			} finally {
				// Snapshot after every patient, not per 50: young patients fail the age
				// gate in microseconds via the preloaded history map, so sparse snapshots
				// made the console jump from mid-run straight to COMPLETED.
				progressListener.accept(new CleanupRunReport(CleanupRunReport.RunStatus.RUNNING, startedAt, null,
						evaluated, eligible, tasksCreated, eventsCreated, errors, dryRun, patientIds.size()));
			}
		}

		LOG.info("Cleanup announcement run done: evaluated={}, eligible={}, tasks={}, events={}, errors={}",
				evaluated, eligible, tasksCreated, eventsCreated, errors.size());
		return new CleanupRunReport(CleanupRunReport.RunStatus.COMPLETED, startedAt, Instant.now(),
				evaluated, eligible, tasksCreated, eventsCreated, errors, dryRun, patientIds.size());
	}

	private List<IIdType> allPatientIds() {
		// The DAO's synchronous search path ignores SearchParameterMap offsets
		// (verified empirically: every offset returned the first page again), so
		// no offset paging here: one capped synchronous search. ~1.4k patients
		// today; the cap bounds memory and MUST fail loudly when reached - a
		// cleanup selection must never be silently truncated.
		SearchParameterMap query = SearchParameterMap.newSynchronous();
		// Both are needed: the sync path ignores the map's loadSynchronousUpTo and
		// silently caps at the internal default (200 observed) unless count is set.
		query.setLoadSynchronousUpTo(MAX_PATIENTS);
		query.setCount(MAX_PATIENTS);
		List<IIdType> ids = new ArrayList<>();
		for (IBaseResource resource : daoRegistry.getResourceDao(Patient.class)
				.search(query, new SystemRequestDetails()).getResources(0, MAX_PATIENTS)) {
			ids.add(resource.getIdElement().toUnqualifiedVersionless());
		}
		LOG.info("Collected {} patients for evaluation", ids.size());
		if (ids.size() >= MAX_PATIENTS) {
			throw new IllegalStateException(String.format(
					"Patient count reached the %d cap; raise MAX_PATIENTS before running", MAX_PATIENTS));
		}
		return ids;
	}

	private Set<String> existingDeletePendingOwners(IIdType patientId) {
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Task.SP_PATIENT, new ReferenceParam("Patient/" + patientId.getIdPart()))
				.add(Task.SP_CODE, new TokenParam(AnnouncementFactory.TASK_CODE_SYSTEM, "delete-pending"));
		query.setCount(500);
		Set<String> owners = new HashSet<>();
		List<IBaseResource> found = daoRegistry.getResourceDao(Task.class)
				.search(query, new SystemRequestDetails()).getResources(0, 500);
		if (found.size() >= 500) {
			throw new IllegalStateException(String.format(
					"Patient/%s has 500+ delete-pending tasks; refusing to risk duplicate announcements",
					patientId.getIdPart()));
		}
		for (IBaseResource resource : found) {
			Task task = (Task) resource;
			if (task.getStatus() != Task.TaskStatus.CANCELLED
					&& task.getFor() != null && task.getFor().getReference() != null
					&& patientId.getIdPart().equals(task.getFor().getReferenceElement().getIdPart())
					&& task.getOwner() != null && task.getOwner().getReference() != null) {
				owners.add(task.getOwner().getReferenceElement().getIdPart());
			}
		}
		return owners;
	}
}
