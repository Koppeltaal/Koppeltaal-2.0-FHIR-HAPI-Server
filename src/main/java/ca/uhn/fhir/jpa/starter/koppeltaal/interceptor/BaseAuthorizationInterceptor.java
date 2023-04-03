package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import org.hl7.fhir.r4.model.Device;

public abstract class BaseAuthorizationInterceptor {

	protected final DaoRegistry daoRegistry;
	protected final IFhirResourceDao<Device> deviceDao;

	protected BaseAuthorizationInterceptor(DaoRegistry daoRegistry,
		IFhirResourceDao<Device> deviceDao) {

		this.daoRegistry = daoRegistry;
		this.deviceDao = deviceDao;
	}
}
