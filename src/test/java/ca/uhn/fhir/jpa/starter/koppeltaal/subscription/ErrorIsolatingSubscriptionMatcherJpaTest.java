package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.subscription.match.matcher.matching.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceModifiedMessage;
import ca.uhn.fhir.jpa.subscription.submit.interceptor.SubscriptionValidatingInterceptor;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.messaging.BaseResourceMessage;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Subscription;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Reproduces the reference-environment incident of 2026-11-10 against HAPI's own matcher. */
class ErrorIsolatingSubscriptionMatcherJpaTest extends BaseJpaR4Test {

	private static final String UNKNOWN_PARAMETER_CRITERIA = "Task?instantiates-canonical.publisherId=x";
	private static final String VALID_CRITERIA = "Task?status=requested";

	@Autowired
	private ISubscriptionMatcher hapiMatcher;

	@Autowired
	private SubscriptionValidatingInterceptor hapiSubscriptionValidator;

	@Test
	void aBrokenSubscriptionNoLongerStopsTheNextOne() {
		IIdType broken = storeLegacySubscription(UNKNOWN_PARAMETER_CRITERIA);
		IIdType valid = storeSubscription(VALID_CRITERIA);
		ResourceModifiedMessage change = changeOf(storeRequestedTask());
		ErrorIsolatingSubscriptionMatcher matcher = matcher();

		// HAPI's loop for one change: the broken Subscription first, then the next one
		assertThat(matcher.match(canonical(broken, UNKNOWN_PARAMETER_CRITERIA), change).matched()).isFalse();
		assertThat(matcher.match(canonical(valid, VALID_CRITERIA), change).matched()).isTrue();

		Subscription stored = read(broken);
		assertThat(stored.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ERROR);
		assertThat(stored.getError()).isEqualTo("Criteria " + UNKNOWN_PARAMETER_CRITERIA
				+ " cannot be evaluated: resource type Task has no search parameter instantiates-canonical");
		assertThat(read(valid).getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
	}

	@Test
	void theDatabaseMatcherDoesNotLeakTheTriggeringResource() {
		IIdType broken = storeLegacySubscription(UNKNOWN_PARAMETER_CRITERIA);
		IIdType task = storeRequestedTask();
		boolean inMemory = mySubscriptionSettings.isEnableInMemorySubscriptionMatching();
		mySubscriptionSettings.setEnableInMemorySubscriptionMatching(false);
		try {
			assertThat(matcher().match(canonical(broken, UNKNOWN_PARAMETER_CRITERIA), changeOf(task)).matched()).isFalse();
		} finally {
			mySubscriptionSettings.setEnableInMemorySubscriptionMatching(inMemory);
		}

		Subscription stored = read(broken);
		assertThat(stored.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ERROR);
		assertThat(stored.getError()).contains("instantiates-canonical").doesNotContain(task.getIdPart());
	}

	private ErrorIsolatingSubscriptionMatcher matcher() {
		return new ErrorIsolatingSubscriptionMatcher(hapiMatcher, () -> new SubscriptionErrorStatus(myDaoRegistry));
	}

	/** Like a Subscription stored before HAPI validated its criteria: bypasses that validation on purpose. */
	private IIdType storeLegacySubscription(String criteria) {
		myInterceptorRegistry.unregisterInterceptor(hapiSubscriptionValidator);
		try {
			return storeSubscription(criteria);
		} finally {
			myInterceptorRegistry.registerInterceptor(hapiSubscriptionValidator);
		}
	}

	private IIdType storeSubscription(String criteria) {
		Subscription subscription = new Subscription().setStatus(Subscription.SubscriptionStatus.ACTIVE).setReason("test")
				.setCriteria(criteria);
		subscription.getChannel().setType(Subscription.SubscriptionChannelType.RESTHOOK).setEndpoint("https://localhost:1/hook");
		return mySubscriptionDao.create(subscription, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
	}

	private IIdType storeRequestedTask() {
		return myTaskDao.create(new Task().setStatus(Task.TaskStatus.REQUESTED).setIntent(Task.TaskIntent.ORDER),
				new SystemRequestDetails()).getId().toUnqualifiedVersionless();
	}

	private ResourceModifiedMessage changeOf(IIdType task) {
		return new ResourceModifiedMessage(myFhirContext, myTaskDao.read(task, new SystemRequestDetails()),
				BaseResourceMessage.OperationTypeEnum.CREATE);
	}

	private Subscription read(IIdType id) {
		return mySubscriptionDao.read(id, new SystemRequestDetails());
	}

	private static CanonicalSubscription canonical(IIdType id, String criteria) {
		CanonicalSubscription subscription = new CanonicalSubscription();
		subscription.setIdElement(id);
		subscription.setCriteriaString(criteria);
		return subscription;
	}
}
