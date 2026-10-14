package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.patch.FhirPatch;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Task;

import java.util.*;
import java.util.stream.Collectors;

import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.UPDATE;

/**
 * <p>
 * Interceptor that validates Task writes:
 * </p>
 * <ul>
 * <li>the `Task.partOf` is filled when the `Task.code` indicates it being a
 * subtask</li>
 * <li>a delete-pending Task is server-owned, so an application may only update
 * the Task it owns, and only its status</li>
 * </ul>
 * <p>
 * The IG grants a status write and nothing else, so the stored Task with that
 * status applied is what the response should look like. Anything else the
 * application sent - a moved deadline, a rewritten owner, a dropped security
 * label - is reported back to it by path, rather than listing per field what may
 * not change, a list that has to be kept in step with the profile.
 * </p>
 * <p>
 * The delete-flow rules run on the storage pointcuts rather than on the incoming
 * request, because they need the STORED Task to judge against: the request body
 * is what the application claims, not what the Task is. That also covers writes
 * that never reach `SERVER_INCOMING_REQUEST_PRE_HANDLED`, such as the entries of
 * a transaction bundle.
 * </p>
 */
@Interceptor(order = Integer.MAX_VALUE)
public class TaskValidationInterceptor {

	private static final String SYSTEM_URL = "http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code";
	private static final String[] SUBTASK_CODES = { "view" };
	private static final Set<Task.TaskStatus> CLIENT_WRITABLE_STATUSES = EnumSet.of(Task.TaskStatus.ONHOLD,
			Task.TaskStatus.ACCEPTED);

	private final FhirContext fhirContext;

	public TaskValidationInterceptor(FhirContext fhirContext) {
		this.fhirContext = fhirContext;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {

		final IBaseResource resource = requestDetails.getResource();
		final RestOperationTypeEnum operation = requestDetails.getRestOperationType();

		if (!(resource instanceof final Task task) || (operation != CREATE && operation != UPDATE))
			return;

		validateSubTask(task);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void resourceCreating(IBaseResource resource, RequestDetails requestDetails) {

		if (isServerWrite(requestDetails) || !(resource instanceof final Task task))
			return;

		// A client writes this payload, so neither half can be assumed: it may leave
		// the code out and still stamp on the marker that grants access to the flow,
		// or carry the code without the marker and slip past the activity check.
		if (DeleteFlowTaskUtil.isDeletePendingTask(task) || DeleteFlowTaskUtil.hasDeleteFlowLabel(task)) {
			throw new UnprocessableEntityException(String.format(
					"A Task with the '%s' security label is created by the Koppeltaalvoorziening only",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
		}
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void resourceUpdating(IBaseResource previousResource, IBaseResource updatedResource,
			RequestDetails requestDetails) {

		if (isServerWrite(requestDetails))
			return;

		if (!(previousResource instanceof final Task stored) || !(updatedResource instanceof final Task incoming))
			return;

		if (!DeleteFlowTaskUtil.isDeletePendingTask(stored)) {
			requireDeleteFlowMarkerNotAdded(incoming);
			return;
		}

		requireOwnTask(stored, requestDetails);
		requireClientWritableStatus(incoming);
		requireNothingElseChanged(stored, incoming);
	}

	/**
	 * The stored Task with the status the application wrote is what its response
	 * should look like; every remaining difference is something it may not change.
	 */
	private void requireNothingElseChanged(Task stored, Task incoming) {

		final List<String> changed = changedPaths(expectedResponse(stored, incoming), incoming);

		if (!changed.isEmpty()) {
			throw new ForbiddenOperationException(String.format(
					"A Task with the '%s' security label only accepts a status of 'on-hold' or 'accepted' and "
							+ "its statusReason; these were changed as well: %s",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL, String.join(", ", changed)));
		}
	}

	private Task expectedResponse(Task stored, Task incoming) {

		final Task expected = stored.copy();

		expected.setStatus(incoming.getStatus());
		expected.setStatusReason(incoming.hasStatusReason() ? incoming.getStatusReason() : null);
		// HAPI maintains these itself and a request may carry anything in them, so
		// they are taken as they come; the rest of meta is compared like any field.
		expected.getMeta().setVersionId(incoming.getMeta().getVersionId());
		expected.getMeta().setLastUpdated(incoming.getMeta().getLastUpdated());
		expected.getMeta().setSource(incoming.getMeta().getSource());

		return expected;
	}

	private List<String> changedPaths(Task expected, Task incoming) {

		final Parameters difference = (Parameters) new FhirPatch(fhirContext)
				.diff(asStored(expected), asStored(incoming));

		return difference.getParameter().stream()
				.map(TaskValidationInterceptor::pathOf)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
	}

	/**
	 * Compare what would be written down. An interceptor that merely reads a field
	 * leaves an empty element behind - HAPI's getters create one - and the differ
	 * reports that as a change even though FHIR considers it absent.
	 */
	private Task asStored(Task task) {

		final IParser parser = fhirContext.newJsonParser();
		return parser.parseResource(Task.class, parser.encodeResourceToString(task));
	}

	private static String pathOf(Parameters.ParametersParameterComponent operation) {
		return operation.getPart().stream()
				.filter(part -> "path".equals(part.getName()))
				.map(part -> part.getValue().primitiveValue())
				.findFirst()
				.orElse(null);
	}

	/**
	 * The Koppeltaalvoorziening drives its own transitions through the DAO; only
	 * the writes of applications are subject to these rules.
	 */
	private static boolean isServerWrite(RequestDetails requestDetails) {
		return requestDetails == null || requestDetails instanceof SystemRequestDetails;
	}

	/**
	 * Judged against the stored owner. Without a bearer token there is no device
	 * to compare against, which only happens when server security is disabled.
	 */
	private void requireOwnTask(Task stored, RequestDetails requestDetails) {

		final Optional<String> clientId = ResourceOriginUtil.getRequesterClientId(requestDetails);

		if (clientId.isEmpty())
			return;

		final String ownerId = stored.getOwner().getReferenceElement().getIdPart();

		if (!clientId.get().equals(ownerId)) {
			throw new ForbiddenOperationException(String.format(
					"A Task with the '%s' security label can only be updated by the application that owns it",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
		}
	}

	/** Adding the marker would forge a delete-pending Task out of an ordinary one. */
	private void requireDeleteFlowMarkerNotAdded(Task incoming) {

		if (DeleteFlowTaskUtil.hasDeleteFlowLabel(incoming)) {
			throw new ForbiddenOperationException(String.format(
					"An update that adds the server-owned '%s' security label is refused",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
		}
	}

	private void requireClientWritableStatus(Task incoming) {

		if (!CLIENT_WRITABLE_STATUSES.contains(incoming.getStatus())) {
			throw new UnprocessableEntityException(String.format(
					"A Task with the '%s' security label can only be updated to status 'on-hold' or 'accepted', but was '%s'",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL, statusCode(incoming)));
		}
	}

	private void validateSubTask(Task task) {

		if (!task.getPartOf().isEmpty())
			return;

		task.getCode().getCoding().stream()
				.filter(coding -> SYSTEM_URL.equals(coding.getSystem()))
				.filter(coding -> isSubtaskCode(coding.getCode()))
				.findFirst()
				.ifPresent(nullValue -> {
					throw new UnprocessableEntityException(
							String.format("Task with code '%s' should have a 'partOf' reference",
									task.getCode().getCoding().get(0).getCode()));
				});
	}

	private static String statusCode(Task task) {
		final String code = task.getStatus() == null ? null : task.getStatus().toCode();
		return code == null ? "<missing>" : code;
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
