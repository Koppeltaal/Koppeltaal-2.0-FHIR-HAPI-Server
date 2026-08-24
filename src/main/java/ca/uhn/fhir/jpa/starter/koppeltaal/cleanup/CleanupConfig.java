package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "cleanup.enabled", havingValue = "true")
public class CleanupConfig {

	@Bean
	public CleanupService cleanupService(DaoRegistry daoRegistry, CleanupProperties cleanupProperties,
			FhirServerAuditLogConfiguration auditLogConfiguration) {
		Clock clock = Clock.systemUTC();
		ParticipatingApps participatingApps = new ParticipatingApps(daoRegistry,
				auditLogConfiguration.getObserver().getIdentifier().getSystem(),
				auditLogConfiguration.getObserver().getIdentifier().getValue());
		EligibilityService eligibilityService = new EligibilityService(daoRegistry, cleanupProperties, clock);
		AnnouncementFactory announcementFactory = new AnnouncementFactory(cleanupProperties, clock);
		return new AnnouncementCleanupService(daoRegistry, participatingApps, eligibilityService,
				announcementFactory, auditLogConfiguration.getSite());
	}
}
