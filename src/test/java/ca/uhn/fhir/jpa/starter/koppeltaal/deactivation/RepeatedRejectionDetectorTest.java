package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.starter.koppeltaal.deactivation.RepeatedRejectionDetector.Detection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RepeatedRejectionDetectorTest {

	private static final Instant START = Instant.parse("2026-11-11T10:00:00Z");

	private final MutableClock clock = new MutableClock(START);
	// at least 10 rejections, each request 5 times on average, at most 4 distinct requests tracked
	private final RepeatedRejectionDetector detector = new RepeatedRejectionDetector(Duration.ofMinutes(10), 10, 5, 4, clock);

	private void reject(int times, String clientId, String fingerprint) {
		for (int i = 0; i < times; i++) {
			assertThat(detector.record(clientId, fingerprint)).isEmpty();
		}
	}

	@Test
	void detectsTheSameRequestComingBack() {
		reject(9, "client-a", "same");

		assertThat(detector.record("client-a", "same")).hasValueSatisfying(detection -> {
			assertThat(detection.id()).isNotBlank();
			assertThat(detection.clientId()).isEqualTo("client-a");
			assertThat(detection.rejections()).isEqualTo(10);
			assertThat(detection.distinctRequests()).isEqualTo(1);
			assertThat(detection.windowStart()).isEqualTo(START);
		});
	}

	@Test
	void needsEachRequestToComeBackOftenEnough() {
		// 14 rejections over 3 requests: below 5 times each
		for (int i = 0; i < 14; i++) {
			assertThat(detector.record("client-a", "request-" + (i % 3))).isEmpty();
		}

		// the 15th makes it 5 times each
		assertThat(detector.record("client-a", "request-2")).isPresent();
	}

	@Test
	void manyDifferentRequestsNeverQualify() {
		for (int i = 0; i < 100; i++) {
			assertThat(detector.record("client-a", "request-" + i)).isEmpty();
		}
	}

	@Test
	void aVariedWindowStaysVariedUntilItEnds() {
		// the fifth distinct request exceeds the 4 tracked ones
		for (int i = 0; i < 5; i++) {
			assertThat(detector.record("client-a", "request-" + i)).isEmpty();
		}
		reject(50, "client-a", "same");

		clock.advance(Duration.ofMinutes(10));
		reject(9, "client-a", "same");
		assertThat(detector.record("client-a", "same")).isPresent();
	}

	@Test
	void aNewWindowStartsWhenTheWindowEnds() {
		reject(9, "client-a", "same");
		clock.advance(Duration.ofMinutes(10));

		assertThat(detector.record("client-a", "same")).isEmpty();
	}

	@Test
	void startsOverAfterADetectionWithANewId() {
		reject(9, "client-a", "same");
		Detection first = detector.record("client-a", "same").orElseThrow();

		reject(9, "client-a", "same");
		Detection second = detector.record("client-a", "same").orElseThrow();

		assertThat(second.id()).isNotEqualTo(first.id());
	}

	@Test
	void countsClientsSeparately() {
		reject(9, "client-a", "same");
		reject(9, "client-b", "same");

		assertThat(detector.record("client-a", "same")).hasValueSatisfying(d -> assertThat(d.clientId()).isEqualTo("client-a"));
		assertThat(detector.record("client-b", "same")).hasValueSatisfying(d -> assertThat(d.clientId()).isEqualTo("client-b"));
	}

	@Test
	void theReasonHoldsCountsAndDutchTimesOnly() {
		reject(9, "client-a", "same");
		clock.advance(Duration.ofSeconds(90));

		Detection detection = detector.record("client-a", "same").orElseThrow();

		// 10:00Z is 11:00 in Amsterdam in November
		assertThat(detection.reason()).isEqualTo("10 geweigerde schrijfverzoeken tussen 11-11-2026 11:00:00 en "
				+ "11-11-2026 11:01:30 (Nederlandse tijd): steeds hetzelfde verzoek");
	}

	@Test
	void theReasonCountsDistinctRequestsInSummerTime() {
		MutableClock summer = new MutableClock(Instant.parse("2026-07-01T10:00:00Z"));
		RepeatedRejectionDetector summerDetector = new RepeatedRejectionDetector(Duration.ofMinutes(10), 10, 5, 4, summer);
		for (int i = 0; i < 14; i++) {
			summerDetector.record("client-a", "request-" + (i % 3));
		}

		Detection detection = summerDetector.record("client-a", "request-2").orElseThrow();

		// 10:00Z is 12:00 in Amsterdam in July
		assertThat(detection.reason()).isEqualTo("15 geweigerde schrijfverzoeken tussen 01-07-2026 12:00:00 en "
				+ "01-07-2026 12:00:00 (Nederlandse tijd): 3 verschillende verzoeken, elk gemiddeld 5 keer verstuurd");
	}

	@Test
	void detectsExactlyOncePerTenRejectionsUnderConcurrency() throws Exception {
		ExecutorService threads = Executors.newFixedThreadPool(8);
		AtomicInteger detections = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				futures.add(threads.submit(() -> {
					start.await();
					for (int i = 0; i < 125; i++) {
						detector.record("client-a", "same").ifPresent(detection -> detections.incrementAndGet());
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			start.countDown();
			threads.shutdownNow();
		}

		assertThat(detections).hasValue(100);
	}
}
