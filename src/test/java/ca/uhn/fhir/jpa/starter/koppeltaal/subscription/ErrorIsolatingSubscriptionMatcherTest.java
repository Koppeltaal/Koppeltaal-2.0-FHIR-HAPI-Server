package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.subscription.match.matcher.matching.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceModifiedMessage;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.util.function.Predicate;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

class ErrorIsolatingSubscriptionMatcherTest {

	private static final String CRITERIA = "Task?instantiates-canonical.publisherId=x";

	private final ISubscriptionMatcher delegate = mock(ISubscriptionMatcher.class);
	private final SubscriptionErrorStatus errorStatus = mock(SubscriptionErrorStatus.class);
	private final ErrorIsolatingSubscriptionMatcher matcher = new ErrorIsolatingSubscriptionMatcher(delegate, () -> errorStatus);
	private final CanonicalSubscription subscription = subscription();
	private final ResourceModifiedMessage message = new ResourceModifiedMessage();

	@Test
	void anUnknownParameterSetsTheSubscriptionToErrorWithASafeReason() {
		MatchUrlService.UnrecognizedSearchParameterException unknownParameter =
				mock(MatchUrlService.UnrecognizedSearchParameterException.class);
		when(unknownParameter.getResourceName()).thenReturn("Task");
		when(unknownParameter.getParamName()).thenReturn("instantiates-canonical");
		when(delegate.match(any(), any())).thenThrow(new InternalErrorException(
				"HAPI-0001: Failure performing memory-match for resource ID[Task/other-party]", unknownParameter));

		assertThat(matcher.match(subscription, message).matched()).isFalse();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Predicate<Subscription>> stillFailing = ArgumentCaptor.forClass(Predicate.class);
		verify(errorStatus).markError(eq("broken"), stillFailing.capture(),
				eq("Criteria " + CRITERIA + " cannot be evaluated: resource type Task has no search parameter instantiates-canonical"));
		assertThat(stillFailing.getValue().test(new Subscription().setCriteria(CRITERIA))).isTrue();
		assertThat(stillFailing.getValue().test(new Subscription().setCriteria("Task?status=requested"))).isFalse();
	}

	@Test
	void anotherCriteriaErrorGetsAGenericReason() {
		when(delegate.match(any(), any())).thenThrow(new RuntimeException(new IllegalStateException(
				new InvalidRequestException("HAPI-1223: unknown chain on Task/other-party"))));

		assertThat(matcher.match(subscription, message).matched()).isFalse();
		verify(errorStatus).markError(eq("broken"), any(),
				eq("Criteria " + CRITERIA + " cannot be evaluated: the server could not evaluate them"));
	}

	@Test
	void aFailingStatusServiceStillOnlySkipsTheSubscription() {
		when(delegate.match(any(), any())).thenThrow(new InvalidRequestException("HAPI-0488"));
		when(errorStatus.markError(anyString(), any(), anyString())).thenThrow(new IllegalStateException("not ready"));

		assertThat(matcher.match(subscription, message).matched()).isFalse();
	}

	@Test
	void aMissingStatusServiceStillOnlySkipsTheSubscription() {
		ErrorIsolatingSubscriptionMatcher withoutService = new ErrorIsolatingSubscriptionMatcher(delegate, () -> {
			throw new IllegalStateException("no bean");
		});
		when(delegate.match(any(), any())).thenThrow(new InvalidRequestException("HAPI-0488"));

		assertThat(withoutService.match(subscription, message).matched()).isFalse();
	}

	@Test
	void anyOtherFailureOnlySkipsTheSubscription() {
		when(delegate.match(any(), any())).thenThrow(new DataAccessResourceFailureException("database unavailable"));

		assertThat(matcher.match(subscription, message).matched()).isFalse();
		verify(errorStatus, never()).markError(anyString(), any(), anyString());
	}

	@Test
	void aResultPassesThroughUnchanged() {
		InMemoryMatchResult result = InMemoryMatchResult.successfulMatch();
		when(delegate.match(any(), any())).thenReturn(result);

		assertThat(matcher.match(subscription, message)).isSameAs(result);
	}

	@Test
	void onlyHapisSubscriptionMatcherBeanIsWrapped() {
		@SuppressWarnings("unchecked")
		ObjectProvider<SubscriptionErrorStatus> provider = mock(ObjectProvider.class);
		ErrorIsolatingSubscriptionMatcherPostProcessor postProcessor = new ErrorIsolatingSubscriptionMatcherPostProcessor(provider);

		assertThat(postProcessor.postProcessAfterInitialization(delegate, "subscriptionMatcher"))
				.isInstanceOf(ErrorIsolatingSubscriptionMatcher.class);
		assertThat(postProcessor.postProcessAfterInitialization(delegate, "inMemorySubscriptionMatcher")).isSameAs(delegate);
		assertThat(postProcessor.postProcessAfterInitialization(matcher, "subscriptionMatcher")).isSameAs(matcher);
	}

	private static CanonicalSubscription subscription() {
		CanonicalSubscription subscription = new CanonicalSubscription();
		subscription.setIdElement(new IdType("Subscription", "broken"));
		subscription.setCriteriaString(CRITERIA);
		return subscription;
	}
}
