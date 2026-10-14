package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Task;

/**
 * Identifies the delete-pending Tasks that drive the patient cleanup flow, as
 * defined by the KT2DeletePendingTask profile. These Tasks are server-owned:
 * the Koppeltaalvoorziening creates them and only the owning application may
 * respond, by writing a status.
 */
public class DeleteFlowTaskUtil {

	public static final String TASK_PROFILE = "http://koppeltaal.nl/fhir/StructureDefinition/KT2DeletePendingTask";
	public static final String SECURITY_SYSTEM = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label";
	public static final String DELETE_FLOW_LABEL = "kt2-delete-flow";
	public static final String TASK_CODE_SYSTEM = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code";
	public static final String DELETE_PENDING_CODE = "delete-pending";

	public static Coding deleteFlowLabel() {
		return new Coding(SECURITY_SYSTEM, DELETE_FLOW_LABEL, "KT2 delete flow");
	}

	public static boolean hasDeleteFlowLabel(Task task) {
		return task.getMeta().getSecurity().stream()
				.anyMatch(coding -> SECURITY_SYSTEM.equals(coding.getSystem())
						&& DELETE_FLOW_LABEL.equals(coding.getCode()));
	}

	/** KT2DeletePendingTask requires this code, so it identifies the Task on its own. */
	public static boolean isDeletePendingTask(Task task) {
		return task.getCode().getCoding().stream()
				.anyMatch(coding -> TASK_CODE_SYSTEM.equals(coding.getSystem())
						&& DELETE_PENDING_CODE.equals(coding.getCode()));
	}
}
