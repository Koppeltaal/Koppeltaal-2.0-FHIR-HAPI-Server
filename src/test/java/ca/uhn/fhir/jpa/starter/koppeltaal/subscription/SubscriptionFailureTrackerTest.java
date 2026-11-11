package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SubscriptionErrorProperties;
import ca.uhn.fhir.jpa.starter.koppeltaal.subscription.SubscriptionErrorStatus.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SubscriptionFailureTrackerTest {

	private static final String ENDPOINT = "https://vendor.example/hook";

	private final SubscriptionErrorProperties properties = new SubscriptionErrorProperties();
	private final SubscriptionErrorStatus errorStatus = mock(SubscriptionErrorStatus.class);
	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T09:00:00Z"));
	private final SubscriptionFailureTracker tracker = new SubscriptionFailureTracker(properties, errorStatus, clock);

	@BeforeEach
	void markingSucceedsByDefault() {
		when(errorStatus.markError(anyString(), any(), anyString())).thenReturn(Outcome.SET_TO_ERROR);
	}

	@Test
	void fourFailuresDoNothing() {
		failTimes(4, Duration.ofHours(10));

		verify(errorStatus, never()).markError(anyString(), any(), anyString());
	}

	@Test
	void fiveFailuresWithin24HoursDoNothing() {
		failTimes(5, Duration.ofHours(1));

		verify(errorStatus, never()).markError(anyString(), any(), anyString());
		assertThat(tracker.failureCount("sub")).isEqualTo(5);
	}

	@Test
	void fiveFailuresSpanning24HoursSetTheSubscriptionToError() {
		failSpanning24Hours();

		verify(errorStatus, times(1)).markError(eq("sub"), any(),
				eq("Delivery failed 5 times in a row since 2026-11-11T09:00:00Z; last error: HTTP 500"));
		assertThat(tracker.failureCount("sub")).isZero();
	}

	@Test
	void theThresholdIsCheckedAgainOnEveryLaterFailure() {
		failTimes(5, Duration.ofHours(1));
		clock.advance(Duration.ofHours(20));
		tracker.failure("sub", ENDPOINT, "HTTP 500");

		verify(errorStatus).markError(eq("sub"), any(),
				eq("Delivery failed 6 times in a row since 2026-11-11T09:00:00Z; last error: HTTP 500"));
	}

	@Test
	void aSuccessResetsTheCount() {
		failTimes(4, Duration.ofHours(10));
		tracker.success("sub");
		clock.advance(Duration.ofHours(25));
		failTimes(4, Duration.ZERO);

		verify(errorStatus, never()).markError(anyString(), any(), anyString());
		assertThat(tracker.failureCount("sub")).isEqualTo(4);
	}

	@Test
	void aChangedEndpointStartsANewStreak() {
		failTimes(4, Duration.ofHours(10));
		tracker.failure("sub", "https://fixed.example/hook", "HTTP 500");

		verify(errorStatus, never()).markError(anyString(), any(), anyString());
		assertThat(tracker.failureCount("sub")).isEqualTo(1);
	}

	@Test
	void aRetryLaterKeepsTheStreakForTheNextFailure() {
		when(errorStatus.markError(anyString(), any(), anyString())).thenReturn(Outcome.RETRY_LATER, Outcome.SET_TO_ERROR);
		failSpanning24Hours();
		assertThat(tracker.failureCount("sub")).isEqualTo(5);

		tracker.failure("sub", ENDPOINT, "HTTP 500");

		verify(errorStatus, times(2)).markError(eq("sub"), any(), anyString());
		assertThat(tracker.failureCount("sub")).isZero();
	}

	@Test
	void aStreakThatStartsDuringTheTransitionSurvives() {
		when(errorStatus.markError(anyString(), any(), anyString())).thenAnswer(invocation -> {
			tracker.success("sub");
			tracker.failure("sub", ENDPOINT, "HTTP 500");
			return Outcome.SET_TO_ERROR;
		});

		failSpanning24Hours();

		assertThat(tracker.failureCount("sub")).isEqualTo(1);
	}

	@Test
	void anEqualLookingStreakThatStartsDuringTheTransitionSurvives() {
		properties.setMaxFailures(1);
		properties.setMinFailureDuration(Duration.ZERO);
		AtomicInteger calls = new AtomicInteger();
		when(errorStatus.markError(anyString(), any(), anyString())).thenAnswer(invocation -> {
			if (calls.incrementAndGet() == 1) {
				tracker.success("sub");
				tracker.failure("sub", ENDPOINT, "HTTP 500"); // same instant: a new streak with equal values
				return Outcome.SET_TO_ERROR;
			}
			return Outcome.RETRY_LATER; // the nested transition keeps the new streak
		});

		tracker.failure("sub", ENDPOINT, "HTTP 500");

		assertThat(tracker.failureCount("sub")).isEqualTo(1);
	}

	@Test
	void theSubscriptionMustStillHaveTheFailingEndpoint() {
		failSpanning24Hours();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Predicate<Subscription>> stillFailing = ArgumentCaptor.forClass(Predicate.class);
		verify(errorStatus).markError(eq("sub"), stillFailing.capture(), anyString());
		assertThat(stillFailing.getValue().test(withEndpoint(ENDPOINT))).isTrue();
		assertThat(stillFailing.getValue().test(withEndpoint("https://fixed.example/hook"))).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1})
	void zeroOrLowerDisablesTheRule(int maxFailures) {
		properties.setMaxFailures(maxFailures);

		failTimes(10, Duration.ofHours(10));

		verify(errorStatus, never()).markError(anyString(), any(), anyString());
	}

	/** Five failures: the first at 09:00, the other four 24 hours later. */
	private void failSpanning24Hours() {
		tracker.failure("sub", ENDPOINT, "HTTP 500");
		clock.advance(Duration.ofHours(24));
		failTimes(4, Duration.ZERO);
	}

	private void failTimes(int times, Duration between) {
		for (int i = 0; i < times; i++) {
			tracker.failure("sub", ENDPOINT, "HTTP 500");
			clock.advance(between);
		}
	}

	private static Subscription withEndpoint(String endpoint) {
		Subscription subscription = new Subscription();
		subscription.getChannel().setEndpoint(endpoint);
		return subscription;
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
