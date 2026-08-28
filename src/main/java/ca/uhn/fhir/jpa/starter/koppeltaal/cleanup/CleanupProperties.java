package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the AVG cleanup runs. The transition rule (no recent Task
 * activity) applies until two years after the configured transition date; with
 * no date configured the rule is always active, which is the safe default.
 */
@Configuration
@ConfigurationProperties(prefix = "cleanup")
public class CleanupProperties {

	private int graceDays = 30;
	private int minPatientAgeDays = 730;
	private LocalDate transitionDate;

	public boolean isTransitionRuleActive(Clock clock) {
		if (transitionDate == null) {
			return true;
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
		return today.isBefore(transitionDate.plusYears(2));
	}

	public int getGraceDays() {
		return graceDays;
	}

	public void setGraceDays(int graceDays) {
		this.graceDays = graceDays;
	}

	public int getMinPatientAgeDays() {
		return minPatientAgeDays;
	}

	public void setMinPatientAgeDays(int minPatientAgeDays) {
		this.minPatientAgeDays = minPatientAgeDays;
	}

	public LocalDate getTransitionDate() {
		return transitionDate;
	}

	public void setTransitionDate(LocalDate transitionDate) {
		this.transitionDate = transitionDate;
	}
}
