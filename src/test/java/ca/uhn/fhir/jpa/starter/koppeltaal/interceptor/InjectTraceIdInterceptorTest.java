package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.starter.koppeltaal.util.RequestIdHolder;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InjectTraceIdInterceptorTest {

	/** A trace the server started itself: its own prefix and a UUIDv4. */
	private static final String SERVER_TRACE =
			"server-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

	/** Whichever request last carried a trace, as the holder remembers it. */
	private final RequestIdHolder requestIdHolder = mock(RequestIdHolder.class);
	private final InjectTraceIdInterceptor interceptor = new InjectTraceIdInterceptor(requestIdHolder);

	@Test
	void aNotificationSendsTheTraceOfTheChangeOn() {
		ResourceDeliveryMessage message = delivery("trace-1");

		interceptor.outgoingSubscriptionBeforeDelivery(message);

		assertEquals(Optional.of("trace-1"), header(message, "X-Trace-Id"));
	}

	@Test
	void aNotificationIsCorrelatedWithTheRequestThatChangedTheResource() {
		when(requestIdHolder.getRequestId("trace-1")).thenReturn(Optional.of("request-1"));
		ResourceDeliveryMessage message = delivery("trace-1");

		interceptor.outgoingSubscriptionBeforeDelivery(message);

		assertEquals(Optional.of("request-1"), header(message, "X-Correlation-Id"));
	}

	/**
	 * An application answering a notification carries the server's trace on (TOP-KT-006), but its request did not cause
	 * the server's later changes in that trace. They get no correlation id rather than the wrong one.
	 */
	@Test
	void aNotificationInATraceTheServerStartedHasNoCorrelationId() {
		when(requestIdHolder.getRequestId(anyString())).thenReturn(Optional.of("application-request-1"));
		ResourceDeliveryMessage message = delivery("server-0b6f8d2e-5c1a-4e7b-9f3d-2a8c6e1b4d70");

		interceptor.outgoingSubscriptionBeforeDelivery(message);

		assertEquals(Optional.empty(), header(message, "X-Correlation-Id"));
	}

	/** TOP-KT-006: without a trace the FHIR resource service may start one; it never sends "null" on. */
	@Test
	void aNotificationAboutAChangeWithoutATraceStartsOne() {
		ResourceDeliveryMessage message = delivery(null);

		interceptor.outgoingSubscriptionBeforeDelivery(message);

		String trace = header(message, "X-Trace-Id").orElseThrow();
		assertTrue(trace.matches(SERVER_TRACE), trace);
		assertTrue(header(message, "X-Request-ID").isPresent());
		assertEquals(Optional.empty(), header(message, "X-Correlation-Id"));
	}

	private static ResourceDeliveryMessage delivery(String transactionId) {
		ResourceDeliveryMessage message = new ResourceDeliveryMessage();
		message.setSubscription(new CanonicalSubscription());
		message.setTransactionId(transactionId);
		return message;
	}

	private static Optional<String> header(ResourceDeliveryMessage message, String name) {
		return message.getSubscription().getHeaders().stream()
				.filter(header -> header.startsWith(name + ": "))
				.map(header -> header.substring(name.length() + 2))
				.findFirst();
	}
}
