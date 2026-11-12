package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.TraceHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;

/**
 * Builds the announcement resources exactly as prescribed by the
 * KT2DeletePendingTask profile and the auditevent-opschoning-archive example
 * from IG package 0.16.4 (PR-77).
 * <p>
 * Both name the server's own Device as resource-origin. Nothing fills it in for
 * them: these writes go through the DAO without a client request or a bearer
 * token, which is all {@code InjectResourceOriginInterceptor} works from.
 * </p>
 */
public class AnnouncementFactory {

	static final String AUDIT_PROFILE = "http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent";
	static final String DCM_SYSTEM = "http://dicom.nema.org/resources/ontology/DCM";
	static final String TRACE_ID = "http://koppeltaal.nl/fhir/StructureDefinition/trace-id";
	static final String REQUEST_ID = "http://koppeltaal.nl/fhir/StructureDefinition/request-id";
	static final String CORRELATION_ID = "http://koppeltaal.nl/fhir/StructureDefinition/correlation-id";

	private final CleanupProperties properties;
	private final Clock clock;

	public AnnouncementFactory(CleanupProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public Task buildDeletePendingTask(IIdType patientId, IIdType appDeviceId, IIdType serverDeviceId) {
		Instant now = clock.instant();
		Task task = new Task();
		task.getMeta().addProfile(DeleteFlowTaskUtil.TASK_PROFILE);
		task.getMeta().addSecurity(deleteFlowLabel());
		task.addExtension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, typedReference("Device", serverDeviceId));
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setCode(new CodeableConcept().addCoding(new Coding(DeleteFlowTaskUtil.TASK_CODE_SYSTEM, DeleteFlowTaskUtil.DELETE_PENDING_CODE,
				"Delete pending")));
		task.setFor(typedReference("Patient", patientId));
		task.setOwner(typedReference("Device", appDeviceId));
		task.setRequester(typedReference("Device", serverDeviceId));
		task.setAuthoredOn(Date.from(now));
		task.setRestriction(new Task.TaskRestrictionComponent()
				.setPeriod(new Period().setEnd(Date.from(now.plus(properties.getGraceDays(), ChronoUnit.DAYS)))));
		return task;
	}

	public AuditEvent buildArchiveEvent(IIdType patientId, IIdType serverDeviceId, String site, TraceHeaders trace) {
		return buildLifecycleEvent(DeleteFlowLifecycleEvent.ANNOUNCED, patientId, serverDeviceId, site, trace);
	}

	/**
	 * The aggregated cleanup lifecycle event for one patient. It carries the
	 * patient, never the Task: the audit trail records the state of the process,
	 * not the individual application's status write. The trace headers are those
	 * of what caused the event: the application's request, or the cleanup run.
	 */
	public AuditEvent buildLifecycleEvent(DeleteFlowLifecycleEvent lifecycleEvent, IIdType patientId,
			IIdType serverDeviceId, String site, TraceHeaders trace) {
		AuditEvent event = new AuditEvent();
		event.getMeta().addProfile(AUDIT_PROFILE);
		event.getMeta().addSecurity(deleteFlowLabel());
		event.addExtension(ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM, typedReference("Device", serverDeviceId));
		addId(event, TRACE_ID, trace.traceId());
		addId(event, REQUEST_ID, trace.requestId());
		addId(event, CORRELATION_ID, trace.correlationId());
		event.setType(new Coding(DeleteFlowLifecycleEvent.LIFECYCLE_SYSTEM, lifecycleEvent.getCode(),
				lifecycleEvent.getDisplay()));
		event.setAction(lifecycleEvent.getAction());
		event.setOutcome(AuditEvent.AuditEventOutcome._0);
		event.setRecorded(Date.from(clock.instant()));
		event.addAgent()
				.setType(new CodeableConcept().addCoding(new Coding(DCM_SYSTEM, "110153", "Source Role ID")))
				.setWho(typedReference("Device", serverDeviceId))
				.setRequestor(true);
		event.setSource(new AuditEvent.AuditEventSourceComponent()
				.setSite(site)
				.setObserver(typedReference("Device", serverDeviceId)));
		event.addEntity()
				.setWhat(typedReference("Patient", patientId))
				.setType(new Coding("http://hl7.org/fhir/resource-types", "Patient", "Patient"))
				.setRole(new Coding("http://terminology.hl7.org/CodeSystem/object-role", "1", "Patient"));
		return event;
	}

	private static void addId(AuditEvent event, String url, String value) {
		if (StringUtils.isNotBlank(value)) {
			event.addExtension(url, new IdType(value));
		}
	}

	private static Coding deleteFlowLabel() {
		return DeleteFlowTaskUtil.deleteFlowLabel();
	}

	private static Reference typedReference(String type, IIdType id) {
		return new Reference(type + "/" + id.getIdPart()).setType(type);
	}
}
