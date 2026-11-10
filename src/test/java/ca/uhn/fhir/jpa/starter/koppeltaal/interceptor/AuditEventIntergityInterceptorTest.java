package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;

class AuditEventIntergityInterceptorTest {

	@Test
	void anAuditEventUpdateIsForbidden() {
		assertThatThrownBy(() -> update(new AuditEvent())).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void otherResourcesCanBeUpdated() {
		assertThatCode(() -> update(new Task())).doesNotThrowAnyException();
	}

	/**
	 * Goes through HAPI's hook invocation, which turns anything but a runtime exception into a 500.
	 */
	private static void update(IBaseResource resource) {
		final InterceptorService registry = new InterceptorService();
		registry.registerInterceptor(new AuditEventIntergityInterceptor());

		final ServletRequestDetails request = new ServletRequestDetails();
		registry.callHooks(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED, new HookParams()
				.add(IBaseResource.class, resource)
				.add(IBaseResource.class, resource)
				.add(RequestDetails.class, request)
				.add(ServletRequestDetails.class, request)
				.add(TransactionDetails.class, new TransactionDetails()));
	}
}
