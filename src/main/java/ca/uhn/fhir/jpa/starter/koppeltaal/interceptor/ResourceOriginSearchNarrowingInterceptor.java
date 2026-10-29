package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Device;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Not extending the {@link SearchNarrowingInterceptor} as it only works with
 * default {@link
 * org.hl7.fhir.CompartmentDefinition} objects.
 */
@Interceptor
public class ResourceOriginSearchNarrowingInterceptor extends BaseAuthorizationInterceptor {

	public ResourceOriginSearchNarrowingInterceptor(DaoRegistry daoRegistry,
			IFhirResourceDao<Device> deviceDao) {

		super(daoRegistry, deviceDao);
	}

	/**
	 * Narrows every type level search, both <code>GET [type]?</code> and <code>POST [type]/_search</code>. Other
	 * interactions that return multiple resources (type level history) do not apply search parameters, those are
	 * filtered by the {@link ResourceOriginResponseFilterInterceptor}.
	 */
	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	protected void narrowSearch(RequestDetails requestDetails) {

		final String resourceName = requestDetails.getResourceName();

		if (requestDetails.getRestOperationType() != RestOperationTypeEnum.SEARCH_TYPE || StringUtils.isBlank(resourceName))
			return;
		else if ("ImplementationGuide".equals(resourceName) || PermissionUtil.isConformanceResourceType(resourceName))
			return; // readable for every client, resources with a resource-origin are filtered from the response

		List<String> relevantScopes = PermissionUtil.getScopesForRequest(requestDetails, CrudOperation.READ);

		if (relevantScopes.isEmpty())
			return; // no permission, not handled by this interceptor

		Set<String> resourceOrigins = PermissionUtil.getResourceOrigins(relevantScopes);

		if (resourceOrigins.isEmpty())
			return; // ALL permission, query already valid

		final Map<String, String[]> originalParameters = requestDetails.getParameters();
		final HashMap<String, String[]> narrowedSearchParameters = new HashMap<>(originalParameters);

		// If resource-origins are found, this means the permission is either OWN or
		// GRANTED. Access granted on another basis than these scopes (e.g. the delete flow) must widen this narrowing,
		// and must be applied in the ResourceOriginResponseFilterInterceptor as well, which removes the other entries.
		narrowedSearchParameters.put("resource-origin", new String[] { String.join(",", resourceOrigins) });

		requestDetails.setParameters(narrowedSearchParameters);
	}
}
