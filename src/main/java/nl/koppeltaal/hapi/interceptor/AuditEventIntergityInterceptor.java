package nl.koppeltaal.hapi.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 *
 */
@Component
@Interceptor
public class AuditEventIntergityInterceptor {
	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void restrictAuditEventUpdate(IBaseResource resource) {
		if (resource instanceof AuditEvent) {
			throw new ForbiddenOperationException("AuditEvents are not allowed to be updated.");
		}
	}
}
