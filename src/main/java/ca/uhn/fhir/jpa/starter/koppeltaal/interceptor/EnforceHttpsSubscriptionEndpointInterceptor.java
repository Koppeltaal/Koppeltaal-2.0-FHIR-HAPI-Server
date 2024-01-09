package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Subscription;
import org.hl7.fhir.r4.model.Subscription.SubscriptionChannelComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Interceptor that makes sure the <code>Subscription.channel.endpoint</code> is
 * an
 * https url
 */
@Component
@Interceptor
public class EnforceHttpsSubscriptionEndpointInterceptor {
	private static final Logger LOG = LoggerFactory.getLogger(EnforceHttpsSubscriptionEndpointInterceptor.class);

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	protected void ensureHttpsOnCreate(IBaseResource resource) {
		validateSubscription(resource);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	protected void ensureHttpsOnUpdate(IBaseResource previousResource, IBaseResource newResource) {
		validateSubscription(newResource);
	}

	private void validateSubscription(IBaseResource resource) {
		if (resource instanceof Subscription) {
			SubscriptionChannelComponent channel = ((Subscription) resource).getChannel();
			String endpoint = channel.getEndpoint();

			if (!StringUtils.startsWith(endpoint, "https://")) {
				throw new PreconditionFailedException("Subscription.channel.endpoint must start with `https://`");
			}
		}
	}
}
