package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CleanupPropertiesTest {

	private static Clock fixed(String isoInstant) {
		return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
	}

	@Test
	void defaultsMatchSpec() {
		CleanupProperties properties = new CleanupProperties();
		assertEquals(30, properties.getGraceDays());
		assertEquals(730, properties.getMinPatientAgeDays());
		assertNull(properties.getTransitionDate());
	}

	@Test
	void transitionRuleAlwaysActiveWithoutConfiguredDate() {
		assertTrue(new CleanupProperties().isTransitionRuleActive(fixed("2030-01-01T00:00:00Z")));
	}

	@Test
	void transitionRuleActiveUntilTwoYearsAfterTransitionDate() {
		CleanupProperties properties = new CleanupProperties();
		properties.setTransitionDate(LocalDate.parse("2026-01-01"));
		assertTrue(properties.isTransitionRuleActive(fixed("2027-12-31T00:00:00Z")));
		assertFalse(properties.isTransitionRuleActive(fixed("2028-01-01T00:00:00Z")));
	}
}
