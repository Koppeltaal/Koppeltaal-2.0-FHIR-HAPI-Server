package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.server.subscription.error")
public class SubscriptionErrorProperties {

	/**
	 * Failed notifications in a row after which a Subscription goes to status error. 0 or lower disables this.
	 */
	private int maxFailures = 5;

	/**
	 * How long ago the first of those failures must at least be, so a short outage of an endpoint switches nothing off.
	 */
	private Duration minFailureDuration = Duration.ofHours(24);

	public int getMaxFailures() {
		return maxFailures;
	}

	public void setMaxFailures(int maxFailures) {
		this.maxFailures = maxFailures;
	}

	public Duration getMinFailureDuration() {
		return minFailureDuration;
	}

	public void setMinFailureDuration(Duration minFailureDuration) {
		this.minFailureDuration = minFailureDuration;
	}
}
