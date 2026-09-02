package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.DeleteFlowLifecycleAuditInterceptor;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "cleanup.enabled", havingValue = "true")
public class CleanupConfig {

	private static final Logger LOG = LoggerFactory.getLogger(CleanupConfig.class);

	@Bean
	public ParticipatingApps participatingApps(DaoRegistry daoRegistry,
			FhirServerAuditLogConfiguration auditLogConfiguration) {

		return new ParticipatingApps(daoRegistry,
				auditLogConfiguration.getObserver().getIdentifier().getSystem(),
				auditLogConfiguration.getObserver().getIdentifier().getValue());
	}

	@Bean
	public AnnouncementFactory announcementFactory(CleanupProperties cleanupProperties) {
		return new AnnouncementFactory(cleanupProperties, Clock.systemUTC());
	}

	@Bean
	public CleanupService cleanupService(DaoRegistry daoRegistry, CleanupProperties cleanupProperties,
			FhirServerAuditLogConfiguration auditLogConfiguration, ParticipatingApps participatingApps,
			AnnouncementFactory announcementFactory) {

		EligibilityService eligibilityService = new EligibilityService(daoRegistry, cleanupProperties,
				Clock.systemUTC());
		return new AnnouncementCleanupService(daoRegistry, participatingApps, eligibilityService,
				announcementFactory, auditLogConfiguration.getSite());
	}

	/**
	 * Spring exposes an EntityManagerFactory, not an EntityManager, so the
	 * transaction-bound one is derived from it.
	 */
	@Bean
	public DeleteFlowAggregateLock deleteFlowAggregateLock(ObjectProvider<EntityManagerFactory> entityManagerFactory) {

		EntityManagerFactory availableFactory = entityManagerFactory.getIfAvailable();

		if (availableFactory == null) {
			LOG.warn("No EntityManagerFactory available, so the aggregated cleanup lifecycle events are not "
					+ "serialised per patient; concurrent status writes may miss an unhold");
			return DeleteFlowAggregateLock.NONE;
		}
		return new AdvisoryDeleteFlowAggregateLock(SharedEntityManagerCreator.createSharedEntityManager(availableFactory));
	}

	@Bean
	public DeleteFlowLifecycleAuditInterceptor deleteFlowLifecycleAuditInterceptor(DaoRegistry daoRegistry,
			AnnouncementFactory announcementFactory, ParticipatingApps participatingApps,
			DeleteFlowAggregateLock deleteFlowAggregateLock, PlatformTransactionManager transactionManager,
			FhirServerAuditLogConfiguration auditLogConfiguration) {

		return new DeleteFlowLifecycleAuditInterceptor(daoRegistry, announcementFactory, participatingApps,
				deleteFlowAggregateLock, transactionManager, auditLogConfiguration.getSite());
	}
}
