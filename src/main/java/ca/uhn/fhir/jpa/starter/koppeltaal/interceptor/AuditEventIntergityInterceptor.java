package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
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
	public void restrictAuditEventUpdate(IBaseResource resource) throws IllegalAccessException {
		if (resource instanceof AuditEvent) {
			throw new IllegalAccessException("AuditEvents are not allowed to be updated.");
		}
	}
}
