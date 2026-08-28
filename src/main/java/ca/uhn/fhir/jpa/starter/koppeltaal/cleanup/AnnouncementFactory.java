package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;

/**
 * Builds the announcement resources exactly as prescribed by the
 * KT2DeletePendingTask profile and the auditevent-opschoning-archive example
 * from IG package 0.16.4 (PR-77).
 */
public class AnnouncementFactory {

	static final String TASK_PROFILE = "http://koppeltaal.nl/fhir/StructureDefinition/KT2DeletePendingTask";
	static final String AUDIT_PROFILE = "http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent";
	static final String SECURITY_SYSTEM = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label";
	static final String TASK_CODE_SYSTEM = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code";
	static final String LIFECYCLE_SYSTEM = "http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle";
	static final String DCM_SYSTEM = "http://dicom.nema.org/resources/ontology/DCM";

	private final CleanupProperties properties;
	private final Clock clock;

	public AnnouncementFactory(CleanupProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public Task buildDeletePendingTask(IIdType patientId, IIdType appDeviceId, IIdType serverDeviceId) {
		Instant now = clock.instant();
		Task task = new Task();
		task.getMeta().addProfile(TASK_PROFILE);
		task.getMeta().addSecurity(deleteFlowLabel());
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setCode(new CodeableConcept().addCoding(new Coding(TASK_CODE_SYSTEM, "delete-pending", "Delete pending")));
		task.setFor(typedReference("Patient", patientId));
		task.setOwner(typedReference("Device", appDeviceId));
		task.setRequester(typedReference("Device", serverDeviceId));
		task.setAuthoredOn(Date.from(now));
		task.setRestriction(new Task.TaskRestrictionComponent()
				.setPeriod(new Period().setEnd(Date.from(now.plus(properties.getGraceDays(), ChronoUnit.DAYS)))));
		return task;
	}

	public AuditEvent buildArchiveEvent(IIdType patientId, IIdType serverDeviceId, String site) {
		AuditEvent event = new AuditEvent();
		event.getMeta().addProfile(AUDIT_PROFILE);
		event.getMeta().addSecurity(deleteFlowLabel());
		event.setType(new Coding(LIFECYCLE_SYSTEM, "archive", "Archive Record Lifecycle Event"));
		event.setAction(AuditEvent.AuditEventAction.C);
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

	private static Coding deleteFlowLabel() {
		return new Coding(SECURITY_SYSTEM, "kt2-delete-flow", "KT2 delete flow");
	}

	private static Reference typedReference(String type, IIdType id) {
		return new Reference(type + "/" + id.getIdPart()).setType(type);
	}
}
