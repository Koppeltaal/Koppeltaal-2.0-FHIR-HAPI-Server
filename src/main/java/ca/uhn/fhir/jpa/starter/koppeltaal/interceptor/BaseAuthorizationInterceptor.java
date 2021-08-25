package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseAuthorizationInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(BaseAuthorizationInterceptor.class);

	protected final DaoRegistry daoRegistry;
	protected final IFhirResourceDao<Device> deviceDao;
	protected final SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService;

	protected BaseAuthorizationInterceptor(DaoRegistry daoRegistry, SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService) {
		this.daoRegistry = daoRegistry;
		this.deviceDao = daoRegistry.getResourceDao(Device.class);
		this.smartBackendServiceAuthorizationService = smartBackendServiceAuthorizationService;
	}

	protected Device getResourceOriginDevice(IBaseResource requestDetailsResource) {
		Optional<IIdType> resourceOriginOptional = ResourceOriginUtil.getResourceOriginDeviceId(requestDetailsResource);

		if(!resourceOriginOptional.isPresent()) {
			LOG.warn("Found resource ({}) without resource-origin", requestDetailsResource.getIdElement());
			throw new AuthenticationException("Unauthorized");
		}

		IIdType resourceOriginDeviceId = resourceOriginOptional.get();
		return deviceDao.read(resourceOriginDeviceId);
	}
}
