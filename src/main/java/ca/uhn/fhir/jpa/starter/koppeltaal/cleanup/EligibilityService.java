package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the eligibility rule from the opschoning IG page, evaluated per
 * candidate so the query load scales with candidates instead of with the
 * ever-growing volume of login AuditEvents:
 * old enough AND no successful login (T_auth, direct or via RelatedPerson) in
 * the retention window AND (while the transition rule is active) no Task
 * activity in that window.
 */
public class EligibilityService {

	private static final String AUTH_TYPE_CODE = "110114";
	private static final String AUTH_SUBTYPE_LOGIN = "110122";
	private static final String AUTH_SUBTYPE_NODE = "110126";

	private static final Logger LOG = LoggerFactory.getLogger(EligibilityService.class);
	private static final int MAX_HISTORY_ENTRIES = 100_000;

	private final DaoRegistry daoRegistry;
	private final CleanupProperties properties;
	private final Clock clock;
	private Map<String, Date> creationDates;

	public EligibilityService(DaoRegistry daoRegistry, CleanupProperties properties, Clock clock) {
		this.daoRegistry = daoRegistry;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Preloads every patient's creation date from one type-level history sweep,
	 * replacing a per-patient version-1 read. Falls back to the per-patient read
	 * for anything not in the map.
	 */
	public void beginRun() {
		Map<String, Date> dates = new HashMap<>();
		for (IBaseResource version : daoRegistry.getResourceDao(Patient.class)
				.history(null, null, null, new SystemRequestDetails())
				.getResources(0, MAX_HISTORY_ENTRIES)) {
			IIdType id = version.getIdElement();
			if ("1".equals(id.getVersionIdPart()) && version.getMeta().getLastUpdated() != null) {
				dates.putIfAbsent(id.getIdPart(), version.getMeta().getLastUpdated());
			}
		}
		creationDates = dates;
		LOG.info("Preloaded creation dates for {} patients from type-level history", dates.size());
	}

	public boolean isEligible(IIdType patientId) {
		Instant windowStart = clock.instant().minus(properties.getMinPatientAgeDays(), ChronoUnit.DAYS);
		return isOldEnough(patientId, windowStart)
				&& !hasRecentSuccessfulAuth(patientId, windowStart)
				&& !(properties.isTransitionRuleActive(clock) && hasRecentTaskActivity(patientId, windowStart));
	}

	private boolean isOldEnough(IIdType patientId, Instant windowStart) {
		Date createdAt = creationDates != null ? creationDates.get(patientId.getIdPart()) : null;
		if (createdAt == null) {
			IdType version1 = new IdType("Patient", patientId.getIdPart(), "1");
			IBaseResource created = daoRegistry.getResourceDao(Patient.class).read(version1, new SystemRequestDetails());
			createdAt = created.getMeta().getLastUpdated();
		}
		return createdAt != null && createdAt.toInstant().isBefore(windowStart);
	}

	private boolean hasRecentSuccessfulAuth(IIdType patientId, Instant windowStart) {
		ReferenceOrListParam entities = new ReferenceOrListParam()
				.addOr(new ReferenceParam("Patient/" + patientId.getIdPart()));
		for (IIdType relatedPersonId : relatedPersonIdsOf(patientId)) {
			entities.addOr(new ReferenceParam("RelatedPerson/" + relatedPersonId.getIdPart()));
		}
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(AuditEvent.SP_TYPE, new TokenParam(null, AUTH_TYPE_CODE))
				.add(AuditEvent.SP_SUBTYPE, new TokenOrListParam(null, AUTH_SUBTYPE_LOGIN, AUTH_SUBTYPE_NODE))
				.add(AuditEvent.SP_OUTCOME, new TokenParam(null, "0"))
				.add(AuditEvent.SP_DATE, new DateRangeParam(
						new DateParam(ParamPrefixEnum.GREATERTHAN_OR_EQUALS, Date.from(windowStart)), null))
				.add(AuditEvent.SP_ENTITY, entities);
		return !daoRegistry.getResourceDao(AuditEvent.class)
				.search(query, new SystemRequestDetails()).isEmpty();
	}

	private List<IIdType> relatedPersonIdsOf(IIdType patientId) {
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(RelatedPerson.SP_PATIENT, new ReferenceParam("Patient/" + patientId.getIdPart()));
		List<IIdType> ids = new ArrayList<>();
		for (IBaseResource resource : daoRegistry.getResourceDao(RelatedPerson.class)
				.search(query, new SystemRequestDetails()).getResources(0, 100)) {
			ids.add(resource.getIdElement().toUnqualifiedVersionless());
		}
		return ids;
	}

	private boolean hasRecentTaskActivity(IIdType patientId, Instant windowStart) {
		SearchParameterMap query = SearchParameterMap.newSynchronous()
				.add(Task.SP_PATIENT, new ReferenceParam("Patient/" + patientId.getIdPart()));
		query.setLastUpdated(new DateRangeParam(
				new DateParam(ParamPrefixEnum.GREATERTHAN_OR_EQUALS, Date.from(windowStart)), null));
		query.setCount(500);
		for (IBaseResource resource : daoRegistry.getResourceDao(Task.class)
				.search(query, new SystemRequestDetails()).getResources(0, 500)) {
			if (!isDeletePendingTask((Task) resource)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDeletePendingTask(Task task) {
		// The delete-pending Task itself must not count as patient activity (IG:
		// "de delete-pending Task zelf uitgezonderd"), or announcing would block
		// every later run and the per-app top-up for two years.
		return task.getCode().getCoding().stream()
				.anyMatch(coding -> AnnouncementFactory.TASK_CODE_SYSTEM.equals(coding.getSystem())
						&& "delete-pending".equals(coding.getCode()));
	}
}
