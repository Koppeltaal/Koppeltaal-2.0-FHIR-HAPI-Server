package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.uhn.fhir.jpa.starter.koppeltaal.deactivation.DomainAdminDeactivationClient.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DomainAdminDeactivationClientTest {

	private static final String DETECTION = "6c3b9b8e-1d1f-4c55-9d43-1f0c2f3a9b10";

	private record ReceivedRequest(String method, String path, String token, String contentType, String body) {
	}

	private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
	private final Queue<Integer> answers = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean stallNextAnswer = new AtomicBoolean();
	private final CountDownLatch releaseStalledAnswer = new CountDownLatch(1);
	private final ExecutorService serverThreads = Executors.newCachedThreadPool();
	private HttpServer domainAdmin;
	private DomainAdminDeactivationClient client;

	@BeforeEach
	void startDomainAdmin() throws IOException {
		domainAdmin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		domainAdmin.setExecutor(serverThreads); // a stalled answer must not block the next request
		domainAdmin.createContext("/", exchange -> {
			received.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("X-Deactivation-Token"),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			if (stallNextAnswer.getAndSet(false)) {
				exchange.sendResponseHeaders(200, 1); // promises a body that never comes
				try {
					releaseStalledAnswer.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				exchange.close();
				return;
			}
			Integer answer = answers.poll();
			exchange.sendResponseHeaders(answer != null ? answer : 204, -1);
			exchange.close();
		});
		domainAdmin.start();
	}

	@AfterEach
	void stop() {
		if (client != null) {
			client.close();
		}
		releaseStalledAnswer.countDown();
		domainAdmin.stop(0);
		serverThreads.shutdownNow();
	}

	private String url() {
		return "http://127.0.0.1:" + domainAdmin.getAddress().getPort() + "/domain-admin/";
	}

	private DomainAdminDeactivationClient client(String url, String token, int maxAttempts) {
		client = new DomainAdminDeactivationClient(url, token, Duration.ofMillis(20), maxAttempts, Duration.ofMillis(500));
		return client;
	}

	/** Waits for the expected requests, then a little longer to see that no more follow. */
	private void awaitRequests(int expected) throws InterruptedException {
		await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= expected);
		Thread.sleep(200);
		assertThat(received).hasSize(expected);
	}

	@Test
	void postsTheReportWithTheSecretAndConfirmsOnSuccess() throws Exception {
		CountDownLatch confirmed = new CountDownLatch(1);

		boolean sent = client(url(), "deactivation-secret", 3)
				.report("client-a", Mode.ENFORCE, DETECTION, "Rejected 300 write requests", confirmed::countDown);

		assertThat(sent).isTrue();
		assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).singleElement().satisfies(request -> {
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.path()).isEqualTo("/domain-admin/deactivations");
			assertThat(request.token()).isEqualTo("deactivation-secret");
			assertThat(request.contentType()).isEqualTo("application/json");
			assertThat(new ObjectMapper().readTree(request.body())).isEqualTo(new ObjectMapper().readTree(
					"{\"clientId\":\"client-a\",\"mode\":\"ENFORCE\",\"detectionId\":\"" + DETECTION
							+ "\",\"reason\":\"Rejected 300 write requests\"}"));
		});
	}

	@Test
	void retriesTheSameDetectionUntilDomainAdminConfirms() throws Exception {
		answers.addAll(List.of(503, 401));
		CountDownLatch confirmed = new CountDownLatch(1);

		client(url(), "deactivation-secret", 5).report("client-a", Mode.ENFORCE, DETECTION, "reason", confirmed::countDown);

		assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).hasSize(3);
		assertThat(received).allSatisfy(request -> assertThat(request.body()).contains(DETECTION));
	}

	@Test
	void givesUpAfterTheMaximumAttempts() throws Exception {
		answers.addAll(List.of(503, 503, 503, 503, 503));
		AtomicBoolean confirmed = new AtomicBoolean();

		client(url(), "deactivation-secret", 3).report("client-a", Mode.ENFORCE, DETECTION, "reason", () -> confirmed.set(true));

		awaitRequests(3);
		assertThat(confirmed).isFalse();
	}

	@Test
	void sendsAReportOnceEvenWhenItFails() throws Exception {
		answers.add(500);
		AtomicBoolean confirmed = new AtomicBoolean();

		client(url(), "deactivation-secret", 3).report("client-a", Mode.REPORT, DETECTION, "reason", () -> confirmed.set(true));

		awaitRequests(1);
		assertThat(confirmed).isFalse();
	}

	@Test
	void aRedirectIsNoConfirmation() throws Exception {
		answers.add(302);
		AtomicBoolean confirmed = new AtomicBoolean();

		client(url(), "deactivation-secret", 1).report("client-a", Mode.ENFORCE, DETECTION, "reason", () -> confirmed.set(true));

		awaitRequests(1);
		assertThat(confirmed).isFalse();
	}

	@Test
	void aStalledAnswerTimesOutAndTheNextAttemptGoesAhead() throws Exception {
		stallNextAnswer.set(true);
		CountDownLatch confirmed = new CountDownLatch(1);

		client(url(), "deactivation-secret", 3).report("client-a", Mode.ENFORCE, DETECTION, "reason", confirmed::countDown);

		assertThat(confirmed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).hasSize(2);
	}

	@Test
	void sendsNothingWithoutAUrlOrToken() throws Exception {
		assertThat(client(" ", "deactivation-secret", 3).report("client-a", Mode.ENFORCE, DETECTION, "reason", () -> { })).isFalse();
		client.close();
		assertThat(client(url(), "", 3).report("client-a", Mode.ENFORCE, DETECTION, "reason", () -> { })).isFalse();

		Thread.sleep(200);
		assertThat(received).isEmpty();
	}
}
