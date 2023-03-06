package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Not an official part op Koppeltaal, but we want to apply a default sort of descending.
 * This Interceptor will set the parameter if not provided by the client.
 */
@Interceptor
public class DefaultDescendingSortInterceptor {

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	protected void ensureDefaultSort(RequestDetails requestDetails) {

    final String resourceName = requestDetails.getResourceName();

    if(requestDetails.getRequestType() != RequestTypeEnum.GET || StringUtils.isBlank(resourceName)) return;

		final Map<String, String[]> requestDetailsParameters = requestDetails.getParameters();

    if(!requestDetailsParameters.containsKey("_sort")) {
      final HashMap<String, String[]> sortIncludedParams = new HashMap<>(requestDetailsParameters);
      sortIncludedParams.put("_sort", new String[]{"-_lastUpdated"});
      requestDetails.setParameters(sortIncludedParams);
    }
	}
}
