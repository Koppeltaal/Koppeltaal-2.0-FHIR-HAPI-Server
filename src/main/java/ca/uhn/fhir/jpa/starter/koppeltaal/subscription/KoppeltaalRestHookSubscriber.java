package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.subscription.match.deliver.resthook.SubscriptionDeliveringRestHookSubscriber;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.client.apache.ApacheHttpRequest;
import ca.uhn.fhir.rest.client.apache.ApacheRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IHttpClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import java.io.IOException;
import java.util.HashMap;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HAPI's rest-hook delivery with three changes for notifications without payload, the only kind Koppeltaal uses
 * (TOP-KT-006): an endpoint that answers 400 or higher fails the notification, where HAPI only fails on an unreachable
 * endpoint; a redirect is not followed, so a notification only reaches the endpoint the owner registered; and every
 * notification is counted, so a Subscription that keeps failing goes to status error ({@link SubscriptionFailureTracker}).
 * Deliveries with a payload are HAPI's and not counted.
 */
public class KoppeltaalRestHookSubscriber extends SubscriptionDeliveringRestHookSubscriber {

	private static final Logger LOG = LoggerFactory.getLogger(KoppeltaalRestHookSubscriber.class);

	private final SubscriptionFailureTracker failureTracker;

	public KoppeltaalRestHookSubscriber(SubscriptionFailureTracker failureTracker) {
		this.failureTracker = failureTracker;
	}

	@Override
	protected void sendNotification(ResourceDeliveryMessage theMsg) {
		final CanonicalSubscription subscription = theMsg.getSubscription();
		final int status;
		try {
			final IHttpResponse response = notificationRequest(subscription).execute();
			try {
				status = response.getStatus();
			} finally {
				response.close();
			}
		} catch (IOException e) {
			track(() -> failureTracker.failure(subscription.getIdPart(), subscription.getEndpointUrl(), e.getMessage()));
			throw new ResourceNotFoundException(Msg.code(5) + e.getMessage());
		}
		if (status >= 400) {
			track(() -> failureTracker.failure(subscription.getIdPart(), subscription.getEndpointUrl(), "HTTP " + status));
			throw new UnclassifiedServerFailureException(status, "HTTP " + status);
		}
		track(() -> failureTracker.success(subscription.getIdPart()));
	}

	/**
	 * HAPI's request for a notification without payload, with redirects off: the Apache client follows a 303 by
	 * default, also for a POST. The client's timeouts, proxy and connection checks stay as they are.
	 */
	IHttpRequest notificationRequest(CanonicalSubscription subscription) {
		final IHttpClient client = myFhirContext.getRestfulClientFactory().getHttpClient(
				new StringBuilder(subscription.getEndpointUrl()), new HashMap<>(), "", RequestTypeEnum.POST,
				parseHeadersFromSubscription(subscription));
		final IHttpRequest request = client.createParamRequest(myFhirContext, new HashMap<>(), null);
		if (request instanceof ApacheHttpRequest apacheRequest
				&& myFhirContext.getRestfulClientFactory() instanceof ApacheRestfulClientFactory factory
				&& factory.getNativeHttpClient() instanceof Configurable nativeClient
				&& nativeClient.getConfig() != null) {
			apacheRequest.getApacheRequest()
					.setConfig(RequestConfig.copy(nativeClient.getConfig()).setRedirectsEnabled(false).build());
		}
		return request;
	}

	/** Keeping count must never change the outcome of a notification. */
	private static void track(Runnable bookkeeping) {
		try {
			bookkeeping.run();
		} catch (RuntimeException e) {
			LOG.error("Could not record the outcome of a notification", e);
		}
	}
}
