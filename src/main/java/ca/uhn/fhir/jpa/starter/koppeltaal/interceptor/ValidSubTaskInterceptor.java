package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.UPDATE;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Task;

/**
 * <p>
 * Interceptor that makes sure the `Task.partOf` is filled when the `Task.code`
 * indicates it being a subtask
 * </p>
 */
@Interceptor(order = Integer.MAX_VALUE)
public class ValidSubTaskInterceptor {

	private static final String SYSTEM_URL = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code";
	private static final String[] SUBTASK_CODES = { "view" };

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {

		final IBaseResource resource = requestDetails.getResource();
		final RestOperationTypeEnum operation = requestDetails.getRestOperationType();

		final boolean isTask = resource instanceof Task;

		if (!isTask || (operation != CREATE && operation != UPDATE))
			return;

		final Task task = (Task) resource;
		if (!task.getPartOf().isEmpty())
			return;

		task.getCode().getCoding().stream()
				.filter(coding -> coding.getSystem().equals(SYSTEM_URL))
				.filter(coding -> isSubtaskCode(coding.getCode()))
				.findFirst()
				.ifPresent(nullValue -> {
					throw new UnprocessableEntityException(
							String.format("Task with code '%s' should have a 'partOf' reference",
									task.getCode().getCoding().get(0).getCode()));
				});
	}

	private boolean isSubtaskCode(String code) {
		for (String subtaskCode : SUBTASK_CODES) {
			if (subtaskCode.equals(code)) {
				return true;
			}
		}
		return false;
	}
}
