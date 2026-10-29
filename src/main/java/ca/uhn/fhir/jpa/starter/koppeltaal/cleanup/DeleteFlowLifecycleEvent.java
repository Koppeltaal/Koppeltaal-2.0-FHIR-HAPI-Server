package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import org.hl7.fhir.r4.model.AuditEvent;

/**
 * The aggregated cleanup transitions per patient, as ISO 21089 record lifecycle
 * codes. The announcement and the grace reset deliberately share the archive
 * code and are told apart by their action.
 */
public enum DeleteFlowLifecycleEvent {

	ANNOUNCED("archive", "Archive Record Lifecycle Event", AuditEvent.AuditEventAction.C),
	BLOCKED("hold", "Add Legal Hold Record Lifecycle Event", AuditEvent.AuditEventAction.U),
	UNBLOCKED("unhold", "Remove Legal Hold Record Lifecycle Event", AuditEvent.AuditEventAction.U),
	GRACE_RESET("archive", "Archive Record Lifecycle Event", AuditEvent.AuditEventAction.U),
	REACTIVATED("reactivate", "Re-activate Record Lifecycle Event", AuditEvent.AuditEventAction.U),
	DESTROYED("destroy", "Destroy/Delete Record Lifecycle Event", AuditEvent.AuditEventAction.D);

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
