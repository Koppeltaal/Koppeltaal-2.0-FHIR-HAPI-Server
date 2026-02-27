package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.server.subscription.retry")
public class SubscriptionRetryProperties {

	/**
	 * Maximum number of delivery attempts. 1 means no retries (try once and give up on failure).
	 * Must be at least 1.
	 */
	private int maxAttempts = 1;

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		this.maxAttempts = maxAttempts;
	}
}
