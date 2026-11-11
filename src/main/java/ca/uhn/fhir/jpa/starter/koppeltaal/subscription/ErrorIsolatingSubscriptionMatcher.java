package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.subscription.match.matcher.matching.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceModifiedMessage;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HAPI matches a resource change against every active Subscription in one loop that does not catch per Subscription:
 * one Subscription whose criteria cannot be evaluated (HAPI-0488 for an unknown parameter) ends the loop, and the
 * Subscriptions after it get no notification. This wrapper contains such a failure to its own Subscription.
 */
public class ErrorIsolatingSubscriptionMatcher implements ISubscriptionMatcher {

	private static final Logger LOG = LoggerFactory.getLogger(ErrorIsolatingSubscriptionMatcher.class);

	private final ISubscriptionMatcher delegate;
	private final Supplier<SubscriptionErrorStatus> errorStatus;

	public ErrorIsolatingSubscriptionMatcher(ISubscriptionMatcher delegate, Supplier<SubscriptionErrorStatus> errorStatus) {
		this.delegate = delegate;
		this.errorStatus = errorStatus;
	}

	@Override
	public InMemoryMatchResult match(CanonicalSubscription subscription, ResourceModifiedMessage message) {
		try {
			return delegate.match(subscription, message);
		} catch (RuntimeException e) {
			if (ExceptionUtils.throwableOfType(e, InvalidRequestException.class) != null) {
				markError(subscription, e);
			} else {
				LOG.error("Could not match Subscription/{}, skipping it for this resource", subscription.getIdPart(), e);
			}
			return InMemoryMatchResult.noMatch();
		}
	}

	private void markError(CanonicalSubscription subscription, RuntimeException cause) {
		final String criteria = subscription.getCriteriaString();
		try {
			errorStatus.get().markError(subscription.getIdPart(), stored -> StringUtils.equals(criteria, stored.getCriteria()),
					reason(criteria, cause));
		} catch (RuntimeException e) {
			LOG.error("Could not set Subscription/{} to error", subscription.getIdPart(), e);
		}
	}

	/**
	 * Built from the Subscription's own criteria only: HAPI's messages can name the resource that triggered the
	 * matching, a resource of another party (the database matcher appends its id to the criteria).
	 */
	static String reason(String criteria, Throwable cause) {
		final MatchUrlService.UnrecognizedSearchParameterException unknownParameter =
				ExceptionUtils.throwableOfType(cause, MatchUrlService.UnrecognizedSearchParameterException.class);
		final String detail = unknownParameter == null
				? "the server could not evaluate them"
				: "resource type " + unknownParameter.getResourceName() + " has no search parameter "
						+ unknownParameter.getParamName();
		return "Criteria " + criteria + " cannot be evaluated: " + detail;
	}
}
