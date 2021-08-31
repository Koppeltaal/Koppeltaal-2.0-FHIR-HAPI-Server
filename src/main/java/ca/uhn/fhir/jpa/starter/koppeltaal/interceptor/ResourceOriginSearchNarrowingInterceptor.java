package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Device;

/**
 * Not extending the {@link SearchNarrowingInterceptor} as it only works with default {@link
 * org.hl7.fhir.CompartmentDefinition} objects.
 */
@Interceptor
public class ResourceOriginSearchNarrowingInterceptor extends BaseAuthorizationInterceptor {

	public ResourceOriginSearchNarrowingInterceptor(DaoRegistry daoRegistry, SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService) {
		super(daoRegistry, smartBackendServiceAuthorizationService);
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	protected void narrowSearch(RequestDetails requestDetails) {

		final String resourceName = requestDetails.getResourceName();

		if(requestDetails.getRequestType() != RequestTypeEnum.GET || StringUtils.isBlank(resourceName)) return;

		Device device = ResourceOriginUtil.getDevice(requestDetails, deviceDao)
				.orElseThrow(() -> new IllegalStateException("Device not found, now allowed to query"));

		PermissionDto permission = smartBackendServiceAuthorizationService.getPermission(device.getIdElement().getIdPart(), requestDetails)
			.orElseThrow(() -> new AuthenticationException("Unauthorized"));

		final Map<String, String[]> originalParameters = requestDetails.getParameters();
		final HashMap<String, String[]> narrowedSearchParameters = new HashMap<>(originalParameters);

		switch (permission.getScope()) {
			case ALL: return; //query already valid
			case OWN:
				narrowedSearchParameters.put("resource-origin", new String[]{"Device/" + device.getIdElement().getIdPart()});
				break;
			case GRANTED:

				StringBuilder grantedDeviceIdsBuilder = new StringBuilder();
				final Iterator<String> deviceIdIterator = permission.getGrantedDeviceIds().iterator();

				while(deviceIdIterator.hasNext()) {
					grantedDeviceIdsBuilder.append("Device/");
					grantedDeviceIdsBuilder.append(deviceIdIterator.next());
					if(deviceIdIterator.hasNext()) grantedDeviceIdsBuilder.append(",");
				}

				narrowedSearchParameters.put("resource-origin", new String[]{grantedDeviceIdsBuilder.toString()});
				break;
			default:
				throw new UnsupportedOperationException("Unable to  handle permission scope " + permission.getScope());
		}

		requestDetails.setParameters(narrowedSearchParameters);
	}
}
