package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.DELETE;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.UPDATE;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Interceptor that injects the https://koppeltaal.nl/resource-origin extension on
 * newly created {@link DomainResource} entities.</p>
 *
 * <p>This can be used to determine if applications have a granted permission on entities
 * they originally created.</p>
 */
@Interceptor
public class InjectResourceOriginInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(InjectResourceOriginInterceptor.class);

	private final DaoRegistry daoRegistry;
	private final IFhirResourceDao<Device> deviceDao;
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

	public InjectResourceOriginInterceptor(DaoRegistry daoRegistry, SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		this.daoRegistry = daoRegistry;
		this.deviceDao = daoRegistry.getResourceDao(Device.class);
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails, RestOperationTypeEnum operation) {

		final IBaseResource resource = requestDetails.getResource();
		final boolean isDomainResource = resource instanceof DomainResource;

		if(!isDomainResource) return;

		if(operation == UPDATE || operation == DELETE) {
			ensureResourceOriginIsUnmodified(requestDetails);
		}

		if(operation != CREATE) return;

		ensureResourceOriginNotSet(requestDetails);

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

	private void ensureResourceOriginIsUnmodified(RequestDetails requestDetails) {
		final IBaseResource requestBodyResource = requestDetails.getResource();

		final IIdType requestBodyResourceOriginDevice = getResourceOriginDeviceId((DomainResource) requestBodyResource, requestDetails);

		final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());
		final IBaseResource existingResource = resourceDao.read(requestDetails.getId());

		final IIdType existingResourceOriginDevice = getResourceOriginDeviceId((DomainResource) existingResource, requestDetails);

		final String existingResourceOriginDeviceIdPart = existingResourceOriginDevice.getIdPart();
		final String bodyResourceOriginDeviceIdPart = requestBodyResourceOriginDevice.getIdPart();

		if(!StringUtils.equals(existingResourceOriginDeviceIdPart, bodyResourceOriginDeviceIdPart)) {

			final Optional<Device> device = ResourceOriginUtil.getDevice(requestDetails, deviceDao);

			LOG.warn("Requesting Device [{}] attempted to change the resource origin on [{}] from [{}] to [{}]",
				device.isPresent() ? device.get().getIdElement().getIdPart() : "unknown", requestDetails.getResourceName(),
				existingResourceOriginDeviceIdPart, bodyResourceOriginDeviceIdPart);

			throw new SecurityException("Unauthorized");
		}
	}

	private void ensureResourceOriginNotSet(RequestDetails requestDetails) {

		final DomainResource requestBodyResource = (DomainResource) requestDetails.getResource();

		final List<Extension> resourceOrigin = requestBodyResource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);

		if(!resourceOrigin.isEmpty()) {
			throw new IllegalArgumentException("Not allowed to set extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}
	}

	private IIdType getResourceOriginDeviceId(DomainResource domainResource, RequestDetails requestDetails) {
		final List<Extension> resourceOrigin = domainResource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);
		if(resourceOrigin.size() != 1) {
			throw new IllegalArgumentException("Expecting a single extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}

		final IIdType referenceElement = ((Reference) resourceOrigin.get(0).getValue()).getReferenceElement();

		if(!StringUtils.startsWith(referenceElement.getValue(), "Device/")) {
			final Optional<Device> device = ResourceOriginUtil.getDevice(requestDetails, deviceDao);

			LOG.warn("Requesting Device [{}] attempted to provide an invalid resource for the resource-origin extension [{}]",
				device.isPresent() ? device.get().getIdElement().getIdPart() : "unknown", referenceElement.getValue());

			throw new IllegalArgumentException("Expecting a Device reference value for extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}

		return referenceElement;
	}

}
