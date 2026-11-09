package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Task;

/**
 * The kt2-delete-flow marker as an additive grant on top of the authorization matrix (IG: Toegang buiten de
 * matrix). A participating application reads every delete-flow AuditEvent, but of the delete-pending Tasks only
 * the ones it owns, and only those it may write; which writes, {@link TaskValidationInterceptor} decides.
 */
public final class DeleteFlowAccess {

	private DeleteFlowAccess() {
	}

	/** On any other resource type the marker grants nothing. */
	public static boolean isDeleteFlowResource(IBaseResource resource) {
		return (resource instanceof Task || resource instanceof AuditEvent) && DeleteFlowTaskUtil.hasDeleteFlowLabel(resource);
	}

	public static boolean mayRead(IBaseResource resource, Device caller) {
		if (!isDeleteFlowResource(resource) || !isParticipant(caller)) {
			return false;
		}
		return resource instanceof AuditEvent || isOwner((Task) resource, caller);
	}

	public static boolean mayUpdate(IBaseResource resource, Device caller) {
		return resource instanceof Task task && isDeleteFlowResource(task) && isParticipant(caller) && isOwner(task, caller);
	}

	/** Every application with an active registration takes part in the cleanup. */
	public static boolean isParticipant(Device caller) {
		return caller != null && caller.getStatus() == Device.FHIRDeviceStatus.ACTIVE;
	}

	private static boolean isOwner(Task task, Device caller) {
		final IIdType owner = task.getOwner().getReferenceElement();
		return "Device".equals(owner.getResourceType()) && caller.getIdElement().getIdPart().equals(owner.getIdPart());
	}
}
