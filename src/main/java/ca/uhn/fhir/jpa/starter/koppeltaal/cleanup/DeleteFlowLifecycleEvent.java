package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import org.hl7.fhir.r4.model.AuditEvent;

/**
 * The aggregated cleanup transitions per patient, as ISO 21089 record lifecycle
 * codes. Only the transitions that are implemented today are listed; the grace
 * reset (archive with action U), the cancellation (reactivate) and the erase
 * (destroy) follow with the runs that drive them.
 */
public enum DeleteFlowLifecycleEvent {

	ANNOUNCED("archive", "Archive Record Lifecycle Event", AuditEvent.AuditEventAction.C),
	BLOCKED("hold", "Add Legal Hold Record Lifecycle Event", AuditEvent.AuditEventAction.U),
	UNBLOCKED("unhold", "Remove Legal Hold Record Lifecycle Event", AuditEvent.AuditEventAction.U);

	static final String LIFECYCLE_SYSTEM = "http://terminology.hl7.org/CodeSystem/iso-21089-lifecycle";

	private final String code;
	private final String display;
	private final AuditEvent.AuditEventAction action;

	DeleteFlowLifecycleEvent(String code, String display, AuditEvent.AuditEventAction action) {
		this.code = code;
		this.display = display;
		this.action = action;
	}

	public String getCode() {
		return code;
	}

	public String getDisplay() {
		return display;
	}

	public AuditEvent.AuditEventAction getAction() {
		return action;
	}
}
