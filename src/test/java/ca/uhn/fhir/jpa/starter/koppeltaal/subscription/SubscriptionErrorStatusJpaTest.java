package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.starter.koppeltaal.subscription.SubscriptionErrorStatus.Outcome;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.Test;

class SubscriptionErrorStatusJpaTest extends BaseJpaR4Test {

	private static final String RESOURCE_ORIGIN = "http://koppeltaal.nl/fhir/StructureDefinition/resource-origin";
	private static final String ENDPOINT = "https://localhost:1/hook";

	@Test
	void anActiveSubscriptionGoesToErrorWithTheReason() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);

		assertThat(markError(id, "Delivery failed: test")).isEqualTo(Outcome.SET_TO_ERROR);

		Subscription stored = read(id);
		assertThat(stored.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ERROR);
		assertThat(stored.getError()).isEqualTo("Delivery failed: test");
		assertThat(stored.getExtensionByUrl(RESOURCE_ORIGIN).getValue()).isInstanceOfSatisfying(Reference.class,
				origin -> assertThat(origin.getReference()).isEqualTo("Device/vendor"));
	}

	@Test
	void aSubscriptionInErrorGetsNoNewVersion() {
		IIdType id = store(Subscription.SubscriptionStatus.ERROR);

		assertThat(markError(id, "again")).isEqualTo(Outcome.NOT_APPLICABLE);
		assertThat(read(id).getMeta().getVersionId()).isEqualTo("1");
	}

	@Test
	void aSubscriptionTheOwnerSwitchedOffStaysOff() {
		IIdType id = store(Subscription.SubscriptionStatus.OFF);

		assertThat(markError(id, "late failure")).isEqualTo(Outcome.NOT_APPLICABLE);
		assertThat(read(id).getStatus()).isEqualTo(Subscription.SubscriptionStatus.OFF);
	}

	@Test
	void aDeletedSubscriptionIsLeftAlone() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);
		mySubscriptionDao.delete(id, new SystemRequestDetails());

		assertThat(markError(id, "late failure")).isEqualTo(Outcome.NOT_APPLICABLE);
	}

	@Test
	void aSubscriptionTheOwnerCorrectedStaysActive() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);

		Outcome outcome = errorStatus().markError(id.getIdPart(),
				stored -> "https://old.example/hook".equals(stored.getChannel().getEndpoint()), "late failure");

		assertThat(outcome).isEqualTo(Outcome.NOT_APPLICABLE);
		assertThat(read(id).getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
	}

	@Test
	void aFailureWhileSettingTheStatusAsksToRetryLater() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);

		Outcome outcome = errorStatus().markError(id.getIdPart(), stored -> {
			throw new IllegalStateException("database unavailable");
		}, "late failure");

		assertThat(outcome).isEqualTo(Outcome.RETRY_LATER);
		assertThat(read(id).getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
	}

	@Test
	void aSecondAttemptWhileTheFirstRunsIsAskedToRetryLater() throws Exception {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);
		SubscriptionErrorStatus errorStatus = errorStatus();
		CountDownLatch firstIsRunning = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Outcome> first = executor.submit(() -> errorStatus.markError(id.getIdPart(), stored -> {
				firstIsRunning.countDown();
				await(releaseFirst);
				return true;
			}, "first"));
			assertThat(firstIsRunning.await(30, TimeUnit.SECONDS)).isTrue();

			assertThat(errorStatus.markError(id.getIdPart(), stored -> true, "second")).isEqualTo(Outcome.RETRY_LATER);

			releaseFirst.countDown();
			assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.SET_TO_ERROR);
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
		}
		assertThat(read(id).getError()).isEqualTo("first");
		// released again: the Subscription is in error now, so nothing is left to do
		assertThat(errorStatus.markError(id.getIdPart(), stored -> true, "third")).isEqualTo(Outcome.NOT_APPLICABLE);
	}

	@Test
	void theGuardIsReleasedAfterAFailedAttempt() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);
		SubscriptionErrorStatus errorStatus = errorStatus();

		assertThat(errorStatus.markError(id.getIdPart(), stored -> {
			throw new IllegalStateException("database unavailable");
		}, "first")).isEqualTo(Outcome.RETRY_LATER);

		assertThat(errorStatus.markError(id.getIdPart(), stored -> true, "second")).isEqualTo(Outcome.SET_TO_ERROR);
	}

	@Test
	void anOwnerUpdateBetweenReadAndWriteWins() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);

		Outcome outcome = errorStatus().markError(id.getIdPart(), stored -> {
			mySubscriptionDao.update(read(id).setReason("changed by the owner"), new SystemRequestDetails());
			return true;
		}, "late failure");

		assertThat(outcome).isEqualTo(Outcome.RETRY_LATER);
		Subscription stored = read(id);
		assertThat(stored.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
		assertThat(stored.getReason()).isEqualTo("changed by the owner");
	}

	@Test
	void aLongReasonIsTruncated() {
		IIdType id = store(Subscription.SubscriptionStatus.ACTIVE);

		assertThat(markError(id, "x".repeat(5000))).isEqualTo(Outcome.SET_TO_ERROR);
		assertThat(read(id).getError()).hasSize(1000);
	}

	private Outcome markError(IIdType id, String reason) {
		return errorStatus().markError(id.getIdPart(), stored -> ENDPOINT.equals(stored.getChannel().getEndpoint()), reason);
	}

	private SubscriptionErrorStatus errorStatus() {
		return new SubscriptionErrorStatus(myDaoRegistry);
	}

	private IIdType store(Subscription.SubscriptionStatus status) {
		Subscription subscription = new Subscription().setStatus(status).setReason("test").setCriteria("Task?status=requested");
		subscription.getChannel().setType(Subscription.SubscriptionChannelType.RESTHOOK).setEndpoint(ENDPOINT);
		subscription.addExtension(RESOURCE_ORIGIN, new Reference("Device/vendor"));
		return mySubscriptionDao.create(subscription, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
	}

	private Subscription read(IIdType id) {
		return mySubscriptionDao.read(id, new SystemRequestDetails());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
