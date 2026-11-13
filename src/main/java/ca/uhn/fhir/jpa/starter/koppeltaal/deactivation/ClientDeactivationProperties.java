package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "fhir.server.client-deactivation")
public class ClientDeactivationProperties {

	public enum Mode {
		/** Neither counts nor refuses. */
		OFF,
		/** Reports detections to domain-admin, which mails beheer, and refuses clients whose Device is inactive. */
		REPORT,
		/** Like REPORT, but a detection deactivates the client. */
		ENFORCE
	}

	private Mode mode = Mode.REPORT;

	/** Base URL of domain-admin. Blank skips every call. */
	private String domainAdminUrl;

	/** Secret for domain-admin's deactivation endpoint (X-Deactivation-Token). Blank skips every call. */
	private String token;

	/** How long a client's counting window lasts, from its first rejected write. */
	private Duration window = Duration.ofMinutes(10);

	/** Rejected writes in one window before a client can be detected. */
	private int minRejections = 300;

	/** How often each distinct request must come back on average. */
	private int minRepeatFactor = 10;

	/** Distinct requests tracked per window; beyond this the window counts as varied and never qualifies. */
	private int maxFingerprints = 1000;

	/** In report mode, the minimum time between two reports of one client. */
	private Duration reportInterval = Duration.ofHours(1);

	/** In enforce mode, the time between attempts to reach domain-admin. */
	private Duration retryInterval = Duration.ofMinutes(1);

	/** In enforce mode, the attempts to reach domain-admin before giving up. */
	private int maxAttempts = 60;

	/** How long a client's Device status is cached. */
	private Duration deviceStatusCacheTtl = Duration.ofSeconds(30);

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public String getDomainAdminUrl() {
		return domainAdminUrl;
	}

	public void setDomainAdminUrl(String domainAdminUrl) {
		this.domainAdminUrl = domainAdminUrl;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}

	public int getMinRejections() {
		return minRejections;
	}

	public void setMinRejections(int minRejections) {
		this.minRejections = minRejections;
	}

	public int getMinRepeatFactor() {
		return minRepeatFactor;
	}

	public void setMinRepeatFactor(int minRepeatFactor) {
		this.minRepeatFactor = minRepeatFactor;
	}

	public int getMaxFingerprints() {
		return maxFingerprints;
	}

	public void setMaxFingerprints(int maxFingerprints) {
		this.maxFingerprints = maxFingerprints;
	}

	public Duration getReportInterval() {
		return reportInterval;
	}

	public void setReportInterval(Duration reportInterval) {
		this.reportInterval = reportInterval;
	}

	public Duration getRetryInterval() {
		return retryInterval;
	}

	public void setRetryInterval(Duration retryInterval) {
		this.retryInterval = retryInterval;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public Duration getDeviceStatusCacheTtl() {
		return deviceStatusCacheTtl;
	}

	public void setDeviceStatusCacheTtl(Duration deviceStatusCacheTtl) {
		this.deviceStatusCacheTtl = deviceStatusCacheTtl;
	}
}
