package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SubscriptionErrorProperties;
import ca.uhn.fhir.jpa.subscription.match.deliver.resthook.SubscriptionDeliveringRestHookSubscriber;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/**
 * Keeps one Subscription that cannot be matched or delivered from blocking or burdening the others: a failing one
 * goes to status error instead.
 */
@Configuration
public class SubscriptionErrorConfig {

	@Bean
	public static BeanPostProcessor errorIsolatingSubscriptionMatcherPostProcessor(
			ObjectProvider<SubscriptionErrorStatus> errorStatus) {
		return new ErrorIsolatingSubscriptionMatcherPostProcessor(errorStatus);
	}

	@Bean
	public SubscriptionErrorStatus subscriptionErrorStatus(DaoRegistry daoRegistry) {
		return new SubscriptionErrorStatus(daoRegistry);
	}

	@Bean
	public SubscriptionFailureTracker subscriptionFailureTracker(SubscriptionErrorProperties properties,
			SubscriptionErrorStatus errorStatus) {
		return new SubscriptionFailureTracker(properties, errorStatus, Clock.systemUTC());
	}

	/**
	 * HAPI takes the rest-hook subscriber from the context by type ({@code SubscriptionDeliveryHandlerFactory}), so a
	 * primary prototype replaces its own.
	 */
	@Bean
	@Primary
	@Scope("prototype")
	public SubscriptionDeliveringRestHookSubscriber koppeltaalRestHookSubscriber(SubscriptionFailureTracker failureTracker) {
		return new KoppeltaalRestHookSubscriber(failureTracker);
	}
}
