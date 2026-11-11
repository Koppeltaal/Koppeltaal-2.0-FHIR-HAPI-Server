package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SubscriptionErrorProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed notifications per Subscription. A Subscription goes to status error once it failed
 * {@code max-failures} times in a row and the first of those failures is at least {@code min-failure-duration} ago; a
 * successful notification resets the streak. Reaching the threshold is the decision: a success that arrives while
 * the status is being set does not cancel it. The streaks live in memory, so a restart starts them again.
 */
public class SubscriptionFailureTracker {

	private final SubscriptionErrorProperties properties;
	private final SubscriptionErrorStatus errorStatus;
	private final Clock clock;
	private final ConcurrentHashMap<String, Streak> streaks = new ConcurrentHashMap<>();

	public SubscriptionFailureTracker(SubscriptionErrorProperties properties, SubscriptionErrorStatus errorStatus,
			Clock clock) {
		this.properties = properties;
		this.errorStatus = errorStatus;
		this.clock = clock;
	}

	public void success(String subscriptionId) {
		streaks.remove(subscriptionId);
	}

	public void failure(String subscriptionId, String endpoint, String error) {
		if (properties.getMaxFailures() <= 0) {
			return;
		}
		final Instant now = clock.instant();
		// a different endpoint starts a new streak: its owner may have fixed it
		final Streak streak = streaks.compute(subscriptionId, (id, previous) ->
				previous == null || !Objects.equals(previous.endpoint(), endpoint)
						? new Streak(1, now, endpoint)
						: new Streak(previous.failures() + 1, previous.since(), endpoint));
		if (streak.failures() < properties.getMaxFailures()
				|| now.isBefore(streak.since().plus(properties.getMinFailureDuration()))) {
			return;
		}
		final SubscriptionErrorStatus.Outcome outcome = errorStatus.markError(subscriptionId,
				stored -> Objects.equals(endpoint, stored.getChannel().getEndpoint()),
				String.format("Delivery failed %d times in a row since %s; last error: %s", streak.failures(),
						streak.since(), error));
		if (outcome != SubscriptionErrorStatus.Outcome.RETRY_LATER) {
			// only this streak, by identity: one started meanwhile stays, even when its values are equal
			streaks.computeIfPresent(subscriptionId, (id, current) -> current == streak ? null : current);
		}
	}

	int failureCount(String subscriptionId) {
		final Streak streak = streaks.get(subscriptionId);
		return streak == null ? 0 : streak.failures();
	}

	private record Streak(int failures, Instant since, String endpoint) {
	}
}
