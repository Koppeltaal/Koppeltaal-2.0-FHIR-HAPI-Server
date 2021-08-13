package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;

/**
 * <p>Interceptor that injects the https://koppeltaal.nl/resource-origin extension on
 * newly created {@link DomainResource} entities.</p>
 *
 * <p>This can be used to determine if applications have a granted permission on entities
 * they originally created.</p>
 */
@Interceptor
public class InjectResourceOriginInterceptor {

	private final IFhirResourceDao<Device> deviceDao;
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

	public InjectResourceOriginInterceptor(IFhirResourceDao<Device> deviceDao,
		SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		this.deviceDao = deviceDao;
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails, RestOperationTypeEnum operation) {

		final IBaseResource resource = requestDetails.getResource();
		final boolean isDomainResource = resource instanceof DomainResource;

		//TODO: Protect resource-origin from missing and changing
		if(operation != CREATE || !isDomainResource) return;

		if("Device".equals(requestDetails.getResourceName())) {
			// the domain admin should be able to create devices no matter what.
			// devices are used for authorizations, but this makes it impossible
			//  to create the initial device needed for the domain admin
			final String requesterClientId = ResourceOriginUtil.getRequesterClientId(requestDetails)
				.orElseThrow(() -> new IllegalStateException("client_id not present"));

			if(StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), requesterClientId)) return;
		}

		DomainResource domainResource = (DomainResource) resource;

		Device device = ResourceOriginUtil.getDevice(requestDetails, deviceDao)
			.orElseThrow(() -> new IllegalArgumentException("Device not present"));

		final Extension resourceOriginExtension = new Extension();
		resourceOriginExtension.setUrl(RESOURCE_ORIGIN_SYSTEM);
		final Reference deviceReference = new Reference(device);
		deviceReference.setType(ResourceType.Device.name());
		resourceOriginExtension.setValue(deviceReference);

		domainResource.addExtension(resourceOriginExtension);
	}

}
