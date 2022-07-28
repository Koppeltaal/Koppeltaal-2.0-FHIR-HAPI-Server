package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil.RESOURCE_ORIGIN_SYSTEM;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;
import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.UPDATE;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Interceptor that injects the http://koppeltaal.nl/resource-origin extension on
 * newly created {@link DomainResource} entities.</p>
 *
 * <p>This can be used to determine if applications have a granted permission on entities
 * they originally created.</p>
 */
@Interceptor(order = Integer.MAX_VALUE)
public class InjectResourceOriginInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(InjectResourceOriginInterceptor.class);

	private final DaoRegistry daoRegistry;
	private final IFhirResourceDao<Device> deviceDao;
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

	public InjectResourceOriginInterceptor(DaoRegistry daoRegistry, IFhirResourceDao<Device> deviceDao, SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		this.daoRegistry = daoRegistry;
		this.deviceDao = deviceDao;
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;

		ensureSearchParameter();
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails) {

		final IBaseResource resource = requestDetails.getResource();
		final RestOperationTypeEnum operation = requestDetails.getRestOperationType();

		final boolean isDomainResource = resource instanceof DomainResource;

		if(!isDomainResource) return;

		//TODO: Verify PATCH request doesn't change the resource-origin

		if(operation == UPDATE) {
			ensureResourceOriginIsUnmodifiedOrEnsureResourceOrigin(requestDetails, (DomainResource) resource);
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
			.orElseThrow(() -> new InvalidRequestException("Device not present"));

		final Extension resourceOriginExtension = new Extension();
		resourceOriginExtension.setUrl(RESOURCE_ORIGIN_SYSTEM);
		final Reference deviceReference = new Reference(device);
		deviceReference.setType(ResourceType.Device.name());
		resourceOriginExtension.setValue(deviceReference);

		domainResource.addExtension(resourceOriginExtension);
	}

	private void ensureResourceOriginIsUnmodifiedOrEnsureResourceOrigin(RequestDetails requestDetails, DomainResource frontendResource) {

		final List<Extension> frontendResourceOriginExtension = frontendResource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);

		if(frontendResourceOriginExtension.isEmpty()) {
			ensureResourceOrigin(requestDetails, frontendResource);
		} else if(frontendResourceOriginExtension.size() == 1) {
			ensureResourceOriginIsUnmodified(requestDetails);
		} else {
			throw new InvalidRequestException("Cannot provide more than one resource-origin extension");
		}
	}

	private void ensureResourceOrigin(RequestDetails requestDetails, DomainResource resource) {
		final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());
		final IBaseResource existingResource = resourceDao.read(requestDetails.getId());

		final List<Extension> extensionsByUrl = ((DomainResource) existingResource).getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);

		if(extensionsByUrl.size() != 1) {

			throw new InternalErrorException(String.format(
				"Cannot set the resource-origin extension in the Update as the resource-extension isn't "
					+ "found on the persisted version [%s]", resource.getIdElement()));
		} else {
			resource.addExtension(extensionsByUrl.get(0));
		}
	}

	private void ensureResourceOriginIsUnmodified(RequestDetails requestDetails) {

		//PATCH?
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

			throw new ForbiddenOperationException("Unauthorized");
		}
	}

	private void ensureResourceOriginNotSet(RequestDetails requestDetails) {

		final DomainResource requestBodyResource = (DomainResource) requestDetails.getResource();

		final List<Extension> resourceOrigin = requestBodyResource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);

		if(!resourceOrigin.isEmpty()) {
			throw new InvalidRequestException("Not allowed to set extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}
	}

	private IIdType getResourceOriginDeviceId(DomainResource domainResource, RequestDetails requestDetails) {
		final List<Extension> resourceOrigin = domainResource.getExtensionsByUrl(RESOURCE_ORIGIN_SYSTEM);
		if(resourceOrigin.size() != 1) {
			throw new InvalidRequestException("Expecting a single extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}

		final IIdType referenceElement = ((Reference) resourceOrigin.get(0).getValue()).getReferenceElement();

		if(!StringUtils.startsWith(referenceElement.getValue(), "Device/")) {
			final Optional<Device> device = ResourceOriginUtil.getDevice(requestDetails, deviceDao);

			LOG.warn("Requesting Device [{}] attempted to provide an invalid resource for the resource-origin extension [{}]",
				device.isPresent() ? device.get().getIdElement().getIdPart() : "unknown", referenceElement.getValue());

			throw new InvalidRequestException("Expecting a Device reference value for extension with system " + RESOURCE_ORIGIN_SYSTEM);
		}

		return referenceElement;
	}

	private void ensureSearchParameter() {
		final IFhirResourceDao<SearchParameter> searchParameterDao = daoRegistry.getResourceDao(SearchParameter.class);

		final String searchParamUrl = "http://hl7.org/fhir/SearchParameter/resource-origin-extension";

		final SearchParameterMap paramMap = new SearchParameterMap();
		paramMap.add("url", new UriParam(searchParamUrl));
		final IBundleProvider searchResult = searchParameterDao.search(paramMap);

		if(searchResult.isEmpty()) {
			final SearchParameter searchParameter = new SearchParameter();
			searchParameter.setUrl(searchParamUrl);
			searchParameter.setName("Search Parameter for extension resource-origin");
			searchParameter.setStatus(PublicationStatus.ACTIVE);
			searchParameter.setExpression("false");
			searchParameter.setDescription("Search DomainResources by resource-origin");
			searchParameter.setCode("resource-origin");
			searchParameter.setTarget(Collections.singletonList(new CodeType(ResourceType.Device.name())));
			searchParameter.setType(SearchParamType.REFERENCE);
			searchParameter.setXpath("normal");


			//TODO: Simply creating a Resource with `DomainResource` as the base doesn't work for all resource types for some reason. The code below should be a lot easier..

			// DomainResource extends the base Resource. All of the listed Resources except Bundle, Parameters and Binary extend this resource.
			final List<ResourceType> resourceTypes = Arrays.stream(ResourceType.values())
				.filter(resourceType -> resourceType != ResourceType.Bundle && resourceType != ResourceType.Parameters && resourceType != ResourceType.Binary)
				.collect(Collectors.toList());

			final List<CodeType> allDomainResourceCodeTypes = resourceTypes.stream()
				.map((resourceType -> new CodeType(resourceType.name())))
				.collect(Collectors.toList());

			StringBuilder expressionBuilder = new StringBuilder();

			final Iterator<ResourceType> resourceTypeIterator = resourceTypes.iterator();
			while(resourceTypeIterator.hasNext()) {
				expressionBuilder.append(resourceTypeIterator.next());
				expressionBuilder.append(".extension('http://koppeltaal.nl/resource-origin')");

				if(resourceTypeIterator.hasNext()) {
					expressionBuilder.append(" | ");
				}
			}

			searchParameter.setBase(allDomainResourceCodeTypes);
			searchParameter.setExpression(expressionBuilder.toString());

			final DaoMethodOutcome daoMethodOutcome = searchParameterDao.create(searchParameter);
			if(!daoMethodOutcome.getCreated()) {
				throw new IllegalStateException("Unable to register the resource-origin SearchParameter: " + daoMethodOutcome.getOperationOutcome().getFormatCommentsPost());
			}

			LOG.info("Ensured SearchParam " + searchParamUrl);
		}
	}

}
