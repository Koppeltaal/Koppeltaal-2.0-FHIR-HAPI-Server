package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes an active Subscription out of use by setting its status to {@code error}, with the reason in
 * {@code Subscription.error}. HAPI only keeps active Subscriptions in its registry and removes this one after the
 * write; the owner sees why and can activate it again.
 */
public class SubscriptionErrorStatus {

	public enum Outcome {
		/** The Subscription is now in status error. */
		SET_TO_ERROR,
		/** Nothing to do: the Subscription is gone, no longer active, or its owner changed what failed. */
		NOT_APPLICABLE,
		/** Not done now, because another thread is on it or the write failed; a later failure may try again. */
		RETRY_LATER
	}

	private static final Logger LOG = LoggerFactory.getLogger(SubscriptionErrorStatus.class);
	private static final int MAX_REASON_LENGTH = 1000;

	private final IFhirResourceDao<Subscription> subscriptionDao;
	private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

	public SubscriptionErrorStatus(DaoRegistry daoRegistry) {
		this.subscriptionDao = daoRegistry.getResourceDao(Subscription.class);
	}

	/**
	 * Does not throw for a non-null id, JVM errors aside: it runs inside HAPI's matching and delivery, which must go on
	 * for the other Subscriptions.
	 *
	 * @param stillFailing whether the stored Subscription still has what failed; when its owner corrected that in the
	 *                     meantime, it stays active
	 */
	public Outcome markError(String subscriptionId, Predicate<Subscription> stillFailing, String reason) {
		if (!inProgress.add(subscriptionId)) {
			return Outcome.RETRY_LATER;
		}
		try {
			final SystemRequestDetails requestDetails = new SystemRequestDetails();
			final Subscription subscription = subscriptionDao.read(new IdType("Subscription", subscriptionId), requestDetails);
			if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE || !stillFailing.test(subscription)) {
				return Outcome.NOT_APPLICABLE;
			}
			subscription.setStatus(Subscription.SubscriptionStatus.ERROR);
			subscription.setError(StringUtils.abbreviate(reason, MAX_REASON_LENGTH));
			subscriptionDao.update(subscription, requestDetails);
			LOG.warn("Set Subscription/{} of {} to error: {}", subscriptionId,
					ResourceOriginUtil.getResourceOriginDeviceId(subscription).map(IIdType::getValue).orElse("an unknown owner"),
					reason);
			return Outcome.SET_TO_ERROR;
		} catch (ResourceNotFoundException | ResourceGoneException e) {
			return Outcome.NOT_APPLICABLE;
		} catch (RuntimeException e) {
			LOG.warn("Could not set Subscription/{} to error; a later failure tries again", subscriptionId, e);
			return Outcome.RETRY_LATER;
		} finally {
			inProgress.remove(subscriptionId);
		}
	}
}
