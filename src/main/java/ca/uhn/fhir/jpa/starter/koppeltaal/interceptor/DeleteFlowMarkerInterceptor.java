package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Parameters;

/**
 * <p>
 * The kt2-delete-flow marker is server-owned (IG: Borging, a). An application can neither set nor remove it, on
 * any resource type, and cannot delete a resource that carries it: the Koppeltaalvoorziening erases those itself.
 * </p>
 * <p>
 * {@code $meta-add} and {@code $meta-delete} change labels without passing the storage pointcuts, so they are
 * checked on the incoming request, where HAPI has already parsed their {@code meta} parameter.
 * </p>
 */
@Interceptor
public class DeleteFlowMarkerInterceptor {

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void resourceCreating(IBaseResource resource, RequestDetails requestDetails) {
		if (!isServerWrite(requestDetails) && DeleteFlowTaskUtil.hasDeleteFlowLabel(resource)) {
			throw new UnprocessableEntityException(String.format(
					"The '%s' security label is set by the Koppeltaalvoorziening only", DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
		}
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void resourceUpdating(IBaseResource previousResource, IBaseResource updatedResource,
			RequestDetails requestDetails) {
		if (!isServerWrite(requestDetails)
				&& DeleteFlowTaskUtil.hasDeleteFlowLabel(previousResource) != DeleteFlowTaskUtil.hasDeleteFlowLabel(updatedResource)) {
			throw markerIsServerOwned();
		}
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_DELETED)
	public void resourceDeleting(IBaseResource resource, RequestDetails requestDetails) {
		if (!isServerWrite(requestDetails) && DeleteFlowTaskUtil.hasDeleteFlowLabel(resource)) {
			throw new ForbiddenOperationException(String.format(
					"A resource with the '%s' security label is removed by the Koppeltaalvoorziening only",
					DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
		}
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails, RestOperationTypeEnum operation) {
		if ((operation == RestOperationTypeEnum.META_ADD || operation == RestOperationTypeEnum.META_DELETE)
				&& requestDetails.getResource() instanceof Parameters parameters
				&& parameters.getParameter().stream()
						.filter(parameter -> parameter.getValue() instanceof Meta)
						.flatMap(parameter -> ((Meta) parameter.getValue()).getSecurity().stream())
						.anyMatch(DeleteFlowTaskUtil::isDeleteFlowLabel)) {
			throw markerIsServerOwned();
		}
	}

	private static ForbiddenOperationException markerIsServerOwned() {
		return new ForbiddenOperationException(String.format(
				"The '%s' security label is server-owned; an application cannot add or remove it",
				DeleteFlowTaskUtil.DELETE_FLOW_LABEL));
	}

	/** The Koppeltaalvoorziening drives the flow itself through the DAO; only application writes are checked. */
	private static boolean isServerWrite(RequestDetails requestDetails) {
		return requestDetails == null || requestDetails instanceof SystemRequestDetails;
	}
}
