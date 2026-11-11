package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.jpa.subscription.match.matcher.matching.ISubscriptionMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps HAPI's {@code subscriptionMatcher} bean ({@code SubscriptionProcessorConfig}) in an
 * {@link ErrorIsolatingSubscriptionMatcher}. HAPI's matching loop takes the matcher by type, so it gets the wrapper.
 */
public class ErrorIsolatingSubscriptionMatcherPostProcessor implements BeanPostProcessor {

	static final String MATCHER_BEAN_NAME = "subscriptionMatcher";

	private final ObjectProvider<SubscriptionErrorStatus> errorStatus;

	public ErrorIsolatingSubscriptionMatcherPostProcessor(ObjectProvider<SubscriptionErrorStatus> errorStatus) {
		this.errorStatus = errorStatus;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (MATCHER_BEAN_NAME.equals(beanName) && bean instanceof ISubscriptionMatcher matcher
				&& !(bean instanceof ErrorIsolatingSubscriptionMatcher)) {
			return new ErrorIsolatingSubscriptionMatcher(matcher, errorStatus::getObject);
		}
		return bean;
	}
}
