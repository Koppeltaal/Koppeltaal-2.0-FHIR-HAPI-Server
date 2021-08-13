package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Not using the {@link AuthorizationInterceptor} as custom {@link org.hl7.fhir.CompartmentDefinition} objects are not allowed.
 */
@Interceptor
public class ResourceOriginAuthorizationInterceptor extends BaseAuthorizationInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginAuthorizationInterceptor.class);
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

	public ResourceOriginAuthorizationInterceptor(DaoRegistry daoRegistry,
		SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService,
		SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		super(daoRegistry, smartBackendServiceAuthorizationService);
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void authorizeRequest(RequestDetails requestDetails) {

		final String resourceName = requestDetails.getResourceName();

		if(StringUtils.isBlank(resourceName)) return; //capabilities service

		if("Device".equals(resourceName)) {
			// the domain admin should be able to create devices no matter what.
			// devices are used for authorizations, but this makes it impossible
			// to create the initial device needed for the domain admin without whitelisting
			final String requesterClientId = ResourceOriginUtil.getRequesterClientId(requestDetails)
				.orElseThrow(() -> new IllegalStateException("client_id not present"));

			if(StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), requesterClientId)) return;
		}

		Device requestingDevice = ResourceOriginUtil.getDevice(requestDetails, deviceDao)
			.orElseThrow(() -> new IllegalStateException("Device not present"));

		validate(requestDetails, requestingDevice);
	}

	private void validate(RequestDetails requestDetails, Device requestingDevice) {
		final String requestingDeviceId = requestingDevice.getIdElement().getIdPart();
		final String resourceName = requestDetails.getResourceName();

		final Optional<PermissionDto> permissionOptional = smartBackendServiceAuthorizationService.getPermission(requestingDeviceId, requestDetails);

		if(!permissionOptional.isPresent()) {
			LOG.info("Device [{}] executed [{}] on [{}] but no permission was found", requestingDeviceId,
				requestDetails.getRequestType(), resourceName);
			throw new SecurityException("Unauthorized");
		}

		final PermissionDto permission = permissionOptional.get();
		final CrudOperation operation = permission.getOperation();
		IBaseResource existingResource = null;

		final IIdType resourceId = requestDetails.getId();
		if(resourceId != null) {
			final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(resourceName);
			existingResource = resourceDao.read(resourceId);
		}

		switch (permission.getScope()) {
			case ALL: return; //valid
			case OWN:

				// Create will inject the resource-origin and READ all will be handled by the ResourceOriginSearchNarrowingInterceptor
				if(operation == CrudOperation.CREATE || (operation == CrudOperation.READ && resourceId == null)) return;  //valid

				Device resourceOriginDevice = getResourceOriginDevice(existingResource);
				final String resourceOriginDeviceId = resourceOriginDevice.getIdElement().getIdPart();

				if(resourceOriginDeviceId.equals(requestingDeviceId)) return; //valid

				LOG.warn("Device [{}] executed [{}] on [{}] with OWN permission on resource [{}], "
					+ "but doesn't equal the resource-origin [{}]", requestingDeviceId, operation, resourceName,
					permission.getResourceType(), resourceOriginDeviceId);
				break;
			case GRANTED:

				// READ all will be handled by the ResourceOriginSearchNarrowingInterceptor
				if(operation == CrudOperation.READ && resourceId == null) return;  //valid

				if(permission.getGrantedDeviceIds().contains(requestingDeviceId)) return; //valid

				LOG.warn("Device [{}] executed [{}] on [{}] with GRANTED permission on resource [{}], "
						+ "but granted devices are [{}]", requestingDeviceId, operation, resourceName,
					permission.getResourceType(), permission.getGrantedDeviceIds());
				break;
			default:
				LOG.warn("Unsupported scope: [{}]", permission.getScope());
		}

		throw new SecurityException("Unauthorized");
	}
}
