package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a detection to domain-admin with {@code POST {domain-admin}/deactivations}. Calls run on one daemon thread, so
 * a slow or unreachable domain-admin never holds up a FHIR request. Redirects are not followed; only a 2xx confirms.
 */
public class DomainAdminDeactivationClient implements AutoCloseable {

	public enum Mode {
		ENFORCE, REPORT
	}

	private static final Logger LOG = LoggerFactory.getLogger(DomainAdminDeactivationClient.class);
	private static final ObjectMapper JSON = new ObjectMapper();

	private final URI endpoint;
	private final String token;
	private final Duration retryInterval;
	private final int maxAttempts;
	private final Duration timeout;
	private final HttpClient httpClient;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "domain-admin-deactivation");
		thread.setDaemon(true);
		return thread;
	});

	public DomainAdminDeactivationClient(String domainAdminUrl, String token, Duration retryInterval, int maxAttempts) {
		this(domainAdminUrl, token, retryInterval, maxAttempts, Duration.ofSeconds(10));
	}

	DomainAdminDeactivationClient(String domainAdminUrl, String token, Duration retryInterval, int maxAttempts,
			Duration timeout) {
		this.endpoint = StringUtils.isBlank(domainAdminUrl) ? null
				: URI.create(StringUtils.removeEnd(domainAdminUrl.trim(), "/") + "/deactivations");
		this.token = token;
		this.retryInterval = retryInterval;
		this.maxAttempts = maxAttempts;
		this.timeout = timeout;
		this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	/**
	 * Sends the report in the background. An ENFORCE report is retried, with the same detection id, until domain-admin
	 * confirms it, after which {@code onConfirmed} runs; a REPORT is sent once.
	 *
	 * @return false when no domain-admin URL or token is configured, so nothing is sent
	 */
	public boolean report(String clientId, Mode mode, String detectionId, String reason, Runnable onConfirmed) {
		if (endpoint == null || StringUtils.isBlank(token)) {
			LOG.warn("Not reporting client [{}] to domain-admin: no domain-admin URL or token configured", clientId);
			return false;
		}
		executor.execute(() -> attempt(clientId, mode, detectionId, reason, onConfirmed, 1));
		return true;
	}

	private void attempt(String clientId, Mode mode, String detectionId, String reason, Runnable onConfirmed, int attempt) {
		if (send(clientId, mode, detectionId, reason)) {
			onConfirmed.run();
		} else if (mode == Mode.ENFORCE && attempt < maxAttempts && !executor.isShutdown()) {
			executor.schedule(() -> attempt(clientId, mode, detectionId, reason, onConfirmed, attempt + 1),
					retryInterval.toMillis(), TimeUnit.MILLISECONDS);
		} else if (mode == Mode.ENFORCE) {
			LOG.error("Domain-admin did not confirm the deactivation of client [{}] after {} attempts; this server keeps "
					+ "refusing the client until it restarts", clientId, attempt);
		}
	}

	/**
	 * In JDK 17 the request timeout ends at the response headers, so a stalled body would hold the only worker thread;
	 * the bounded get covers the whole exchange.
	 */
	private boolean send(String clientId, Mode mode, String detectionId, String reason) {
		String body = JSON.createObjectNode()
				.put("clientId", clientId)
				.put("mode", mode.name())
				.put("detectionId", detectionId)
				.put("reason", reason)
				.toString();
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("X-Deactivation-Token", token)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		CompletableFuture<HttpResponse<Void>> exchange = httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
		try {
			int status = exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS).statusCode();
			if (status >= 200 && status < 300) {
				return true;
			}
			LOG.warn("Domain-admin answered {} to the {} report of client [{}]", status, mode, clientId);
		} catch (TimeoutException e) {
			exchange.cancel(true);
			LOG.warn("Domain-admin did not answer the {} report of client [{}] within {}", mode, clientId, timeout);
		} catch (ExecutionException e) {
			LOG.warn(String.format("Failed to send the %s report of client [%s] to domain-admin", mode, clientId), e.getCause());
		} catch (InterruptedException e) {
			exchange.cancel(true);
			Thread.currentThread().interrupt();
		}
		return false;
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}
}
