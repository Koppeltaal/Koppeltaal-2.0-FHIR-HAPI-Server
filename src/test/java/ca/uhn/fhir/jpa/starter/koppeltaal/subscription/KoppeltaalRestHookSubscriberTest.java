package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KoppeltaalRestHookSubscriberTest {

	private final SubscriptionFailureTracker tracker = mock(SubscriptionFailureTracker.class);
	private final KoppeltaalRestHookSubscriber subscriber = new KoppeltaalRestHookSubscriber(tracker);
	private final AtomicInteger redirectTargetCalls = new AtomicInteger();
	private HttpServer endpoint;

	@BeforeEach
	void startEndpoint() throws IOException {
		subscriber.setFhirContextForUnitTest(FhirContext.forR4Cached());
		endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		for (int status : new int[] {200, 302, 404, 500}) {
			endpoint.createContext("/" + status, exchange -> {
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
			});
		}
		for (int status : new int[] {302, 303}) {
			endpoint.createContext("/redirect-" + status, exchange -> {
				exchange.getResponseHeaders().add("Location", url("redirect-target"));
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
			});
		}
		endpoint.createContext("/redirect-target", exchange -> {
			redirectTargetCalls.incrementAndGet();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		endpoint.start();
	}

	@AfterEach
	void stopEndpoint() {
		endpoint.stop(0);
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 302})
	void aNotificationAnsweredBelow400Succeeds(int status) {
		assertThatCode(() -> subscriber.sendNotification(notificationTo(url(status)))).doesNotThrowAnyException();

		verify(tracker).success("sub");
		verify(tracker, never()).failure(anyString(), anyString(), anyString());
	}

	@ParameterizedTest
	@ValueSource(ints = {302, 303})
	void aRedirectCountsAsDeliveredWithoutBeingFollowed(int status) {
		assertThatCode(() -> subscriber.sendNotification(notificationTo(url("redirect-" + status))))
				.doesNotThrowAnyException();

		verify(tracker).success("sub");
		assertThat(redirectTargetCalls).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(ints = {404, 500})
	void aNotificationAnsweredWith4xxOr5xxFails(int status) {
		assertThatThrownBy(() -> subscriber.sendNotification(notificationTo(url(status)))).hasMessage("HTTP " + status);

		verify(tracker).failure("sub", url(status), "HTTP " + status);
		verify(tracker, never()).success(anyString());
	}

	@Test
	void anUnreachableEndpointFailsAsBefore() throws IOException {
		final int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		String unreachable = "http://127.0.0.1:" + closedPort + "/hook";

		assertThatThrownBy(() -> subscriber.sendNotification(notificationTo(unreachable)))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageStartingWith("HAPI-0005");
		verify(tracker).failure(eq("sub"), eq(unreachable), anyString());
	}

	@Test
	void theResponseIsClosedWhenItsStatusCannotBeRead() throws IOException {
		IHttpRequest request = mock(IHttpRequest.class);
		IHttpResponse response = mock(IHttpResponse.class);
		when(request.execute()).thenReturn(response);
		when(response.getStatus()).thenThrow(new IllegalStateException("unreadable status"));
		KoppeltaalRestHookSubscriber withUnreadableResponse = new KoppeltaalRestHookSubscriber(tracker) {
			@Override
			IHttpRequest notificationRequest(CanonicalSubscription subscription) {
				return request;
			}
		};

		assertThatThrownBy(() -> withUnreadableResponse.sendNotification(notificationTo(url(200))))
				.hasMessage("unreadable status");
		verify(response).close();
	}

	@Test
	void aFailingTrackerDoesNotChangeTheOutcome() {
		doThrow(new IllegalStateException("tracker broken")).when(tracker).failure(anyString(), anyString(), anyString());
		doThrow(new IllegalStateException("tracker broken")).when(tracker).success(anyString());

		assertThatThrownBy(() -> subscriber.sendNotification(notificationTo(url(500)))).hasMessage("HTTP 500");
		assertThatCode(() -> subscriber.sendNotification(notificationTo(url(200)))).doesNotThrowAnyException();
	}

	private static ResourceDeliveryMessage notificationTo(String url) {
		CanonicalSubscription subscription = new CanonicalSubscription();
		subscription.setIdElement(new IdType("Subscription", "sub"));
		subscription.setEndpointUrl(url);
		ResourceDeliveryMessage message = new ResourceDeliveryMessage();
		message.setSubscription(subscription);
		return message;
	}

	private String url(Object path) {
		return "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/" + path;
	}
}
